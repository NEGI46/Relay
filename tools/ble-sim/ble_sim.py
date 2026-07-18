"""Deterministic, hardware-free BLE GATT transport simulator.

The wire codec in this file is intentionally byte-for-byte compatible with
``pc-ble-bridge/Protocol/GattFrame.cs`` and
``app/.../rescue/ble/ShelterBleTransport.kt``.  It models the transport
boundary only; it does not implement a Windows or Android Bluetooth stack and
it does not claim to prove that a payload is cryptographically encrypted.
"""

from __future__ import annotations

import argparse
import hashlib
import struct
import time
from dataclasses import dataclass
from enum import IntEnum
from pathlib import Path
from typing import Iterable, Iterator


ATT_PAYLOAD_BYTES = 20
HEADER_BYTES = 10
CHUNK_BYTES = ATT_PAYLOAD_BYTES - HEADER_BYTES
MAX_ENVELOPE_BYTES = 16 * 1024
SESSION_ID_BYTES = 4
SESSION_TIMEOUT_SECONDS = 120.0


class TransferError(ValueError):
    """Base class for fail-closed transport errors."""


class MalformedFrameError(TransferError):
    pass


class OutOfOrderError(TransferError):
    pass


class MissingFrameError(TransferError):
    pass


class DuplicateFrameError(TransferError):
    pass


class ShaMismatchError(TransferError):
    pass


class TimeoutError(TransferError):
    pass


class ReplayError(TransferError):
    pass


class PlaintextRejected(TransferError):
    pass


class DisconnectedError(TransferError):
    pass


class GattFrameKind(IntEnum):
    START = 1
    CHUNK = 2
    COMMIT = 3
    RESULT = 4
    ABORT = 5


@dataclass(frozen=True)
class GattFrame:
    """The exact 10-byte header + 0..10-byte ATT frame."""

    kind: GattFrameKind
    sequence: int
    total: int
    session_id: bytes
    payload: bytes

    def __post_init__(self) -> None:
        try:
            kind = GattFrameKind(self.kind)
        except (TypeError, ValueError) as exc:
            raise MalformedFrameError("GATT frame kind is invalid") from exc
        object.__setattr__(self, "kind", kind)
        if not 0 <= self.sequence <= 0xFFFF:
            raise MalformedFrameError("GATT frame sequence is out of range")
        if not 0 <= self.total <= 0xFFFF:
            raise MalformedFrameError("GATT frame total is out of range")
        if len(self.session_id) != SESSION_ID_BYTES:
            raise MalformedFrameError("session ID must be four bytes")
        if len(self.payload) > CHUNK_BYTES:
            raise MalformedFrameError("GATT frame payload exceeds MTU-safe limit")

    def encode(self) -> bytes:
        return struct.pack(
            ">BBHH4s",
            int(self.kind),
            len(self.payload),
            self.sequence,
            self.total,
            self.session_id,
        ) + self.payload

    @classmethod
    def decode(cls, raw: bytes) -> "GattFrame":
        if len(raw) < HEADER_BYTES or len(raw) > ATT_PAYLOAD_BYTES:
            raise MalformedFrameError("GATT frame length is invalid")
        payload_length = raw[1]
        if payload_length > CHUNK_BYTES or len(raw) != HEADER_BYTES + payload_length:
            raise MalformedFrameError("GATT frame payload length is invalid")
        try:
            kind = GattFrameKind(raw[0])
        except ValueError as exc:
            raise MalformedFrameError("GATT frame kind is invalid") from exc
        sequence, total = struct.unpack(">HH", raw[2:6])
        return cls(kind, sequence, total, bytes(raw[6:10]), bytes(raw[10:]))

    @classmethod
    def fragment(cls, kind: GattFrameKind, session_id: bytes, payload: bytes) -> list["GattFrame"]:
        if len(payload) > MAX_ENVELOPE_BYTES:
            raise TransferError("envelope exceeds 16 KiB")
        if len(session_id) != SESSION_ID_BYTES:
            raise TransferError("session ID must be four bytes")
        total = max(1, (len(payload) + CHUNK_BYTES - 1) // CHUNK_BYTES)
        if total > 0xFFFF:
            raise TransferError("too many BLE fragments")
        return [
            cls(
                kind,
                sequence,
                total,
                bytes(session_id),
                payload[sequence * CHUNK_BYTES : (sequence + 1) * CHUNK_BYTES],
            )
            for sequence in range(total)
        ]


@dataclass(frozen=True)
class BleIdentity:
    """The 33-byte identity read/advertised by the bridge."""

    protocol_version: int
    shelter_id_hash: bytes
    signed_manifest_fingerprint: bytes
    session_hint: bytes

    def __post_init__(self) -> None:
        if self.protocol_version != 1:
            raise TransferError("unsupported BLE identity protocol version")
        if len(self.shelter_id_hash) != 8:
            raise TransferError("shelter ID hash must be eight bytes")
        if len(self.signed_manifest_fingerprint) != 16:
            raise TransferError("manifest fingerprint must be sixteen bytes")
        if len(self.session_hint) != 8:
            raise TransferError("session hint must be eight bytes")

    @classmethod
    def create(cls, shelter_id: str, manifest_fingerprint: bytes, session_hint: bytes) -> "BleIdentity":
        if not shelter_id or len(manifest_fingerprint) < 16:
            raise TransferError("invalid BLE identity inputs")
        if len(session_hint) != 8:
            raise TransferError("session hint must be eight bytes")
        return cls(
            1,
            hashlib.sha256(shelter_id.encode("utf-8")).digest()[:8],
            bytes(manifest_fingerprint[:16]),
            bytes(session_hint),
        )

    def encode(self) -> bytes:
        return bytes((self.protocol_version,)) + self.shelter_id_hash + self.signed_manifest_fingerprint + self.session_hint

    @classmethod
    def decode(cls, raw: bytes) -> "BleIdentity":
        if len(raw) != 33:
            raise TransferError("BLE identity length is invalid")
        return cls(raw[0], raw[1:9], raw[9:25], raw[25:33])


def _identifier_bytes(value: str, maximum: int, field: str) -> bytes:
    allowed = set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.:")
    if not value or any(character not in allowed for character in value):
        raise TransferError(f"{field} must be an ASCII identifier")
    encoded = value.encode("ascii")
    if len(encoded) > maximum:
        raise TransferError(f"{field} is too long")
    return encoded


def start_frames(courier_delivery_id: str, carrier_id: str, session_id: bytes) -> list[GattFrame]:
    courier = _identifier_bytes(courier_delivery_id, 256, "courierDeliveryId")
    carrier = _identifier_bytes(carrier_id, 128, "carrierId")
    metadata = bytearray(5 + len(courier) + len(carrier))
    metadata[0] = 1
    struct.pack_into(">HH", metadata, 1, len(courier), len(carrier))
    metadata[5 : 5 + len(courier)] = courier
    metadata[5 + len(courier) :] = carrier
    return GattFrame.fragment(GattFrameKind.START, session_id, bytes(metadata))


def decode_start_metadata(frames: Iterable[GattFrame]) -> tuple[str, str]:
    ordered = list(frames)
    if not ordered or any(frame.kind != GattFrameKind.START for frame in ordered):
        raise MalformedFrameError("invalid START metadata frames")
    if any(frame.session_id != ordered[0].session_id for frame in ordered):
        raise MalformedFrameError("START metadata session IDs do not match")
    expected_total = ordered[0].total
    if expected_total == 0 or len(ordered) != expected_total:
        raise MissingFrameError("START metadata is incomplete")
    if any(frame.sequence != index or frame.total != expected_total for index, frame in enumerate(ordered)):
        raise OutOfOrderError("START metadata is out of order")
    raw = b"".join(frame.payload for frame in ordered)
    if len(raw) < 5 or raw[0] != 1:
        raise MalformedFrameError("START metadata version is unsupported")
    courier_length, carrier_length = struct.unpack(">HH", raw[1:5])
    if courier_length == 0 or courier_length > 256 or carrier_length == 0 or carrier_length > 128:
        raise MalformedFrameError("START metadata identifier lengths are invalid")
    if len(raw) != 5 + courier_length + carrier_length:
        raise MalformedFrameError("START metadata length is invalid")
    courier_raw = raw[5 : 5 + courier_length]
    carrier_raw = raw[5 + courier_length :]
    try:
        courier = courier_raw.decode("ascii")
        carrier = carrier_raw.decode("ascii")
    except UnicodeDecodeError as exc:
        raise MalformedFrameError("START metadata is not ASCII") from exc
    _identifier_bytes(courier, 256, "courierDeliveryId")
    _identifier_bytes(carrier, 128, "carrierId")
    return courier, carrier


def session_id_for(courier_delivery_id: str) -> bytes:
    return hashlib.sha256(courier_delivery_id.encode("ascii")).digest()[:SESSION_ID_BYTES]


@dataclass(frozen=True)
class TransferFrames:
    session_id: bytes
    start: tuple[GattFrame, ...]
    chunks: tuple[GattFrame, ...]
    commits: tuple[GattFrame, ...]
    encrypted: bool = True

    @property
    def all(self) -> tuple[GattFrame, ...]:
        return self.start + self.chunks + self.commits


class FrameGenerator:
    """Builds stable START/CHUNK/COMMIT vectors and deterministic fault schedules."""

    def __init__(self, seed: int = 0):
        self.seed = seed

    def transfer(
        self,
        envelope: bytes,
        *,
        courier_delivery_id: str = "delivery-1",
        carrier_id: str = "carrier-1",
        session_id: bytes | None = None,
        encrypted: bool = True,
    ) -> TransferFrames:
        if not envelope or len(envelope) > MAX_ENVELOPE_BYTES:
            raise TransferError("envelope must contain 1..16384 bytes")
        session = bytes(session_id or session_id_for(courier_delivery_id))
        if len(session) != SESSION_ID_BYTES:
            raise TransferError("session ID must be four bytes")
        chunks = GattFrame.fragment(GattFrameKind.CHUNK, session, envelope)
        digest = hashlib.sha256(envelope).digest()
        commits = GattFrame.fragment(GattFrameKind.COMMIT, session, digest)
        return TransferFrames(
            session,
            tuple(start_frames(courier_delivery_id, carrier_id, session)),
            tuple(chunks),
            tuple(commits),
            encrypted,
        )

    def events(self, transfer: TransferFrames, scenario: str) -> list["FrameEvent"]:
        name = scenario.lower().replace("_", "-")
        all_frames = list(transfer.all)
        if name in ("normal", "success"):
            return [FrameEvent.write(frame) for frame in all_frames]
        if name == "reordered":
            if len(transfer.chunks) < 2:
                raise TransferError("reordered scenario requires at least two CHUNK frames")
            return [FrameEvent.write(frame) for frame in transfer.start + tuple(reversed(transfer.chunks)) + transfer.commits]
        if name == "missing":
            missing_index = self.seed % len(transfer.chunks)
            chunks = tuple(frame for index, frame in enumerate(transfer.chunks) if index != missing_index)
            return [FrameEvent.write(frame) for frame in transfer.start + chunks + transfer.commits]
        if name == "duplicate":
            duplicate = transfer.chunks[0]
            return [FrameEvent.write(frame) for frame in transfer.start + (duplicate, duplicate) + transfer.chunks[1:] + transfer.commits]
        if name == "delayed":
            events: list[FrameEvent] = []
            for index, frame in enumerate(all_frames):
                events.append(FrameEvent.write(frame, delay_before=5.0 if index == len(transfer.start) + 1 else 0.0))
            return events
        if name == "disconnect-reconnect":
            partial = list(transfer.start) + [transfer.chunks[0]]
            return (
                [FrameEvent.write(frame) for frame in partial]
                + [FrameEvent.disconnect(), FrameEvent.connect()]
                + [FrameEvent.write(frame) for frame in all_frames]
            )
        if name == "sha-mismatch":
            bad = list(transfer.commits)
            bad[0] = GattFrame(bad[0].kind, bad[0].sequence, bad[0].total, bad[0].session_id, bytes([bad[0].payload[0] ^ 0xFF]) + bad[0].payload[1:])
            return [FrameEvent.write(frame) for frame in transfer.start + transfer.chunks + tuple(bad)]
        if name == "timeout":
            return [
                *[FrameEvent.write(frame) for frame in transfer.start],
                FrameEvent.write(transfer.chunks[0], delay_before=SESSION_TIMEOUT_SECONDS + 1.0),
            ]
        if name == "replay":
            return [FrameEvent.write(frame) for frame in all_frames + all_frames]
        if name == "plaintext":
            return [FrameEvent.write(frame) for frame in all_frames]
        raise ValueError(f"unknown BLE simulation scenario: {scenario}")


@dataclass(frozen=True)
class FrameEvent:
    action: str
    frame: GattFrame | None = None
    delay_before: float = 0.0

    @classmethod
    def write(cls, frame: GattFrame, *, delay_before: float = 0.0) -> "FrameEvent":
        if delay_before < 0:
            raise ValueError("delay_before must not be negative")
        return cls("write", frame, delay_before)

    @classmethod
    def disconnect(cls) -> "FrameEvent":
        return cls("disconnect")

    @classmethod
    def connect(cls) -> "FrameEvent":
        return cls("connect")

    def wire_key(self) -> tuple[str, bytes | None, float]:
        return self.action, self.frame.encode() if self.frame else None, self.delay_before


@dataclass(frozen=True)
class TransferReceipt:
    session_id: bytes
    courier_delivery_id: str
    carrier_id: str
    envelope: bytes
    sha256: bytes


class FakeGattPeripheral:
    """Deterministic GATT server with the bridge's ordered reassembly rules."""

    def __init__(self, *, timeout: float = SESSION_TIMEOUT_SECONDS, max_payload: int = MAX_ENVELOPE_BYTES):
        self.timeout = timeout
        self.max_payload = max_payload
        self.connected = False
        self.now = 0.0
        self.identity: BleIdentity | None = None
        self._reset_session()
        self._replayed: set[bytes] = set()
        self.last_receipt: TransferReceipt | None = None

    def advertise(self, identity: BleIdentity) -> bytes:
        self.identity = identity
        return identity.encode()

    def connect(self) -> None:
        self.connected = True

    def disconnect(self) -> None:
        self.connected = False
        self._reset_session()

    def receive(self, raw: bytes, *, now: float | None = None) -> TransferReceipt | None:
        if not self.connected:
            raise DisconnectedError("GATT peripheral is disconnected")
        return self.receive_frame(GattFrame.decode(raw), now=now)

    def receive_frame(self, frame: GattFrame, *, now: float | None = None) -> TransferReceipt | None:
        current = self.now if now is None else now
        if current < self.now:
            raise TransferError("virtual clock moved backwards")
        self.now = current
        if frame.kind == GattFrameKind.START:
            return self._receive_start(frame)
        if frame.kind == GattFrameKind.RESULT:
            raise PlaintextRejected("RESULT is a downlink-only frame")
        self._ensure_open()
        if frame.session_id != self._session_id:
            raise MalformedFrameError("session ID mismatch")
        if frame.kind == GattFrameKind.CHUNK:
            return self._receive_chunk(frame)
        if frame.kind == GattFrameKind.COMMIT:
            return self._receive_commit(frame)
        if frame.kind == GattFrameKind.ABORT:
            self._reset_session()
            return None
        raise PlaintextRejected("unsupported or plaintext GATT frame kind")

    def finish(self) -> TransferReceipt:
        """Explicitly finish a transfer; useful for tests of missing frames."""
        if self._session_id is None or self._start_metadata is None:
            raise MissingFrameError("no complete BLE session")
        if len(self._chunks) != self._chunk_total:
            raise MissingFrameError("missing CHUNK frame")
        if self._commit_total is None or len(self._commit_frames) != self._commit_total:
            raise MissingFrameError("missing COMMIT frame")
        receipt = self._complete()
        return receipt

    def _receive_start(self, frame: GattFrame) -> None:
        if frame.total == 0:
            raise MalformedFrameError("START frame total is zero")
        if frame.sequence == 0:
            if frame.session_id in self._replayed:
                raise ReplayError("replayed BLE session")
            self._reset_session()
            self._session_id = frame.session_id
            self._started_at = self.now
            self._start_total = frame.total
        self._ensure_session(frame)
        if frame.sequence != self._start_next or frame.total != self._start_total:
            raise OutOfOrderError("START metadata is out of order")
        self._start_frames.append(frame)
        self._start_next += 1
        if self._start_next == self._start_total:
            self._start_metadata = decode_start_metadata(self._start_frames)
            self._chunk_total = None
        return None

    def _receive_chunk(self, frame: GattFrame) -> None:
        if self._start_metadata is None:
            raise OutOfOrderError("CHUNK received before START metadata")
        if frame.total == 0 or (self._chunk_total is not None and frame.total != self._chunk_total):
            raise MalformedFrameError("CHUNK total is invalid")
        if self._chunk_total is None:
            self._chunk_total = frame.total
        if frame.sequence != self._chunk_next:
            if frame.sequence < self._chunk_next:
                raise DuplicateFrameError("duplicate CHUNK frame")
            raise OutOfOrderError("CHUNK frame is out of order")
        if len(self._chunks) + 1 > self._chunk_total:
            raise MalformedFrameError("too many CHUNK frames")
        self._chunks.append(frame.payload)
        self._chunk_next += 1
        if sum(len(chunk) for chunk in self._chunks) > self.max_payload:
            raise TransferError("envelope exceeds configured payload limit")
        return None

    def _receive_commit(self, frame: GattFrame) -> TransferReceipt | None:
        if self._start_metadata is None or self._chunk_total is None or self._chunk_next != self._chunk_total:
            raise MissingFrameError("COMMIT received before all CHUNK frames")
        if frame.total == 0 or (self._commit_total is not None and frame.total != self._commit_total):
            raise MalformedFrameError("COMMIT total is invalid")
        if self._commit_total is None:
            self._commit_total = frame.total
        if frame.sequence != self._commit_next:
            if frame.sequence < self._commit_next:
                raise DuplicateFrameError("duplicate COMMIT frame")
            raise OutOfOrderError("COMMIT frame is out of order")
        self._commit_frames.append(frame.payload)
        self._commit_next += 1
        if self._commit_next == self._commit_total:
            return self._complete()
        return None

    def _complete(self) -> TransferReceipt:
        assert self._session_id is not None
        assert self._start_metadata is not None
        envelope = b"".join(self._chunks)
        expected = hashlib.sha256(envelope).digest()
        received = b"".join(self._commit_frames)
        if received != expected:
            self._reset_session()
            raise ShaMismatchError("COMMIT SHA-256 does not match envelope")
        receipt = TransferReceipt(self._session_id, *self._start_metadata, envelope, expected)
        self._replayed.add(self._session_id)
        self.last_receipt = receipt
        self._reset_session()
        return receipt

    def _ensure_open(self) -> None:
        if self._session_id is None:
            raise MissingFrameError("no active BLE session")
        if self.now - self._started_at > self.timeout:
            self._reset_session()
            raise TimeoutError("BLE session timed out")

    def _ensure_session(self, frame: GattFrame) -> None:
        self._ensure_open()
        if frame.session_id != self._session_id:
            raise MalformedFrameError("session ID mismatch")

    def _reset_session(self) -> None:
        self._session_id: bytes | None = None
        self._started_at = 0.0
        self._start_total: int | None = None
        self._start_next = 0
        self._start_frames: list[GattFrame] = []
        self._start_metadata: tuple[str, str] | None = None
        self._chunk_total: int | None = None
        self._chunk_next = 0
        self._chunks: list[bytes] = []
        self._commit_total: int | None = None
        self._commit_next = 0
        self._commit_frames: list[bytes] = []


class FakeGattCentral:
    """A deterministic central that plays generated writes without radio hardware."""

    def __init__(self, peripheral: FakeGattPeripheral):
        self.peripheral = peripheral
        self.connected = False
        self.now = 0.0
        self.events: list[tuple[str, float, bytes | None]] = []

    def connect(self) -> None:
        self.peripheral.connect()
        self.connected = True
        self.events.append(("connect", self.now, None))

    def disconnect(self) -> None:
        self.peripheral.disconnect()
        self.connected = False
        self.events.append(("disconnect", self.now, None))

    def send(self, frame: GattFrame, *, delay_before: float = 0.0) -> TransferReceipt | None:
        if not self.connected:
            raise DisconnectedError("GATT central is disconnected")
        self.now += delay_before
        self.events.append(("write", self.now, frame.encode()))
        return self.peripheral.receive_frame(frame, now=self.now)

    def play(self, events: Iterable[FrameEvent], *, encrypted: bool = True) -> TransferReceipt | None:
        if not encrypted:
            raise PlaintextRejected("plaintext envelope is prohibited at BLE sender boundary")
        if not self.connected:
            self.connect()
        receipt = None
        for event in events:
            if event.action == "connect":
                self.connect()
            elif event.action == "disconnect":
                self.disconnect()
            elif event.action == "write":
                if event.frame is None:
                    raise MalformedFrameError("write event has no frame")
                receipt = self.send(event.frame, delay_before=event.delay_before) or receipt
            else:
                raise ValueError(f"unknown GATT event: {event.action}")
        return receipt


def deterministic_test_envelope() -> bytes:
    """Opaque ciphertext-shaped bytes used by the host harness, not encryption."""
    return b"ciphertext-v1:" + bytes(range(96))


def run_scenario(name: str) -> TransferReceipt | None:
    generator = FrameGenerator(seed=0)
    transfer = generator.transfer(deterministic_test_envelope(), encrypted=name.lower().replace("_", "-") != "plaintext")
    peripheral = FakeGattPeripheral()
    central = FakeGattCentral(peripheral)
    return central.play(generator.events(transfer, name), encrypted=transfer.encrypted)


def _run_unittest(junit_xml: Path) -> int:
    import unittest

    class XmlResult(unittest.TextTestResult):
        def __init__(self, stream, descriptions, verbosity):
            super().__init__(stream, descriptions, verbosity)
            self.cases: list[tuple[str, str, float, str, str]] = []
            self._started: dict[str, float] = {}

        def startTest(self, test):
            self._started[self._id(test)] = time.perf_counter()
            super().startTest(test)

        def stopTest(self, test):
            key = self._id(test)
            elapsed = time.perf_counter() - self._started.pop(key, time.perf_counter())
            outcome = "passed"
            message = ""
            detail = ""
            for failed, label in ((self.failures, "failure"), (self.errors, "error")):
                for failed_test, traceback in failed:
                    if self._id(failed_test) == key:
                        outcome, message, detail = label, label, traceback
            for skipped_test, reason in self.skipped:
                if self._id(skipped_test) == key:
                    outcome, message, detail = "skipped", reason, ""
            self.cases.append((self._class_name(test), test._testMethodName, elapsed, outcome, detail or message))
            super().stopTest(test)

        @staticmethod
        def _id(test) -> str:
            return test.id()

        @staticmethod
        def _class_name(test) -> str:
            return test.__class__.__module__ + "." + test.__class__.__qualname__

    loader = unittest.TestLoader()
    suite = loader.discover(str(Path(__file__).parent), pattern="test_*.py")
    runner = unittest.TextTestRunner(verbosity=2, resultclass=XmlResult)
    result = runner.run(suite)
    junit_xml.parent.mkdir(parents=True, exist_ok=True)
    from xml.sax.saxutils import escape, quoteattr

    failures = len(result.failures)
    errors = len(result.errors)
    skipped = len(result.skipped)
    total_time = sum(case[2] for case in result.cases)
    parts = [
        f'<testsuite name="ble-sim" tests="{len(result.cases)}" failures="{failures}" errors="{errors}" skipped="{skipped}" time="{total_time:.6f}">'
    ]
    for classname, name, elapsed, outcome, detail in result.cases:
        parts.append(f"  <testcase classname={quoteattr(classname)} name={quoteattr(name)} time=\"{elapsed:.6f}\">")
        if outcome == "skipped":
            parts.append(f"    <skipped message={quoteattr(detail)}/>")
        elif outcome == "failure":
            parts.append(f"    <failure message=\"failure\">{escape(detail)}</failure>")
        elif outcome == "error":
            parts.append(f"    <error message=\"error\">{escape(detail)}</error>")
        parts.append("  </testcase>")
    parts.append("</testsuite>")
    junit_xml.write_text("\n".join(parts) + "\n", encoding="utf-8")
    return 0 if result.wasSuccessful() else 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--junit-xml", type=Path, default=Path(__file__).parent / "test-results" / "ble-sim.xml")
    args = parser.parse_args(argv)
    return _run_unittest(args.junit_xml)


if __name__ == "__main__":
    raise SystemExit(main())
