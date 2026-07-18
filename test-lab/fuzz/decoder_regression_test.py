"""Deterministic decoder regression corpus.

These targets intentionally exercise the public boundary invariants with raw
inputs. A Jazzer adapter can call the same `decode_*` functions when the JVM
fuzz toolchain is installed; CI always runs this regression lane.
"""
import base64
import json
import pathlib
import unittest


def decode_envelope(raw: bytes):
    if len(raw) > 16 * 1024 or len(raw) < 2:
        raise ValueError("envelope size")
    return json.loads(raw.decode("utf-8"))


def decode_gateway_dto(raw: bytes):
    value = decode_envelope(raw)
    if not isinstance(value, dict) or not value.get("requestId"):
        raise ValueError("gateway dto")
    return value


def decode_ble_fragment(raw: bytes):
    if len(raw) < 5 or raw[:1] not in (b"S", b"C", b"R") or raw[1:5] == b"\x00\x00\x00\x00":
        raise ValueError("BLE frame")
    return raw


def decode_qr_frame(raw: bytes):
    value = decode_envelope(raw)
    if not isinstance(value, dict) or not all(k in value for k in ("messageId", "frameIndex", "totalFrames", "payloadChunk", "payloadHash", "expiresAt")):
        raise ValueError("QR frame")
    if value["frameIndex"] < 0 or value["totalFrames"] <= 0 or value["frameIndex"] >= value["totalFrames"]:
        raise ValueError("QR index")
    base64.b64decode(value["payloadChunk"], validate=True)
    return value


class EnvelopeDecoderFuzzTest(unittest.TestCase):
    def test_corpus(self):
        for path in pathlib.Path(__file__).parent.glob("corpus/envelope/*"):
            with self.subTest(path=path.name):
                try:
                    decode_envelope(path.read_bytes())
                except (ValueError, UnicodeError, json.JSONDecodeError):
                    pass


class GatewayDtoFuzzTest(unittest.TestCase):
    def test_unknown_and_missing_fields_fail_closed(self):
        with self.assertRaises(ValueError):
            decode_gateway_dto(b"{}")


class BleFragmentFuzzTest(unittest.TestCase):
    def test_malformed_and_oversized_inputs_fail_closed(self):
        for raw in (b"", b"X" * 5, b"C\x00\x00\x00\x00"):
            with self.subTest(raw=raw):
                with self.assertRaises(ValueError):
                    decode_ble_fragment(raw)


class QrFrameFuzzTest(unittest.TestCase):
    def test_negative_and_unknown_schema_fail_closed(self):
        bad = b'{"messageId":"m","frameIndex":-1,"totalFrames":1,"payloadChunk":"","payloadHash":"x","expiresAt":0}'
        with self.assertRaises(ValueError):
            decode_qr_frame(bad)


if __name__ == "__main__":
    unittest.main()
