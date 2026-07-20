import os
import sys
import unittest
import uuid

sys.path.insert(0, os.path.dirname(__file__))

from ble_sim import (  # noqa: E402
    ATT_PAYLOAD_BYTES,
    BleIdentity,
    CHUNK_BYTES,
    FakeGattCentral,
    FakeGattPeripheral,
    FrameGenerator,
    GattFrame,
    GattFrameKind,
    MalformedFrameError,
    PlaintextRejected,
    ReplayError,
    ShaMismatchError,
    TransferError,
    deterministic_test_envelope,
    decode_start_metadata,
    run_scenario,
    session_id_for,
)


class BleSimulationTests(unittest.TestCase):
    def setUp(self):
        self.generator = FrameGenerator(seed=7)
        self.transfer = self.generator.transfer(
            deterministic_test_envelope(),
            courier_delivery_id="delivery-123",
            carrier_id="carrier-456",
        )

    def test_pc_bridge_wire_layout_and_round_trip(self):
        session = bytes((1, 2, 3, 4))
        frame = GattFrame(GattFrameKind.CHUNK, 0x1234, 2, session, bytes((9, 8)))
        encoded = frame.encode()
        self.assertEqual(bytes((2, 2, 0x12, 0x34, 0, 2, 1, 2, 3, 4, 9, 8)), encoded)
        self.assertEqual(frame, GattFrame.decode(encoded))
        self.assertLessEqual(len(encoded), ATT_PAYLOAD_BYTES)

    def test_v2_identity_fits_legacy_advertising_budget(self):
        identity = BleIdentity.create(bytes(range(32)))
        encoded = identity.encode()
        self.assertEqual(10, len(encoded))
        self.assertEqual(identity, BleIdentity.decode(encoded))
        # Flags(3) + AD header(2) + 128-bit service UUID(16) + identity(10).
        self.assertEqual(31, 3 + 2 + 16 + len(encoded))
        service_uuid = uuid.UUID("1f7f7e90-4e0a-4b0b-8fad-1e3c5e3f4a01")
        service_data = identity.encode_service_data(service_uuid)
        self.assertEqual(service_uuid.bytes[::-1], service_data[:16])
        self.assertEqual(encoded, service_data[16:])

    def test_start_metadata_and_commit_fragmentation_match_wire_contract(self):
        self.assertEqual(("delivery-123", "carrier-456"), decode_start_metadata(self.transfer.start))
        self.assertEqual(list(range(len(self.transfer.chunks))), [frame.sequence for frame in self.transfer.chunks])
        self.assertEqual(4, len(self.transfer.commits))
        self.assertTrue(all(len(frame.payload) <= CHUNK_BYTES for frame in self.transfer.all))
        self.assertEqual(session_id_for("delivery-123"), self.transfer.session_id)

    def test_normal_transfer_completes_and_preserves_opaque_bytes(self):
        receipt = FakeGattCentral(FakeGattPeripheral()).play(
            self.generator.events(self.transfer, "normal"),
            encrypted=True,
        )
        self.assertIsNotNone(receipt)
        self.assertEqual(deterministic_test_envelope(), receipt.envelope)

    def test_delayed_transfer_still_completes_before_timeout(self):
        receipt = FakeGattCentral(FakeGattPeripheral()).play(
            self.generator.events(self.transfer, "delayed"),
            encrypted=True,
        )
        self.assertIsNotNone(receipt)

    def test_missing_reordered_duplicate_and_sha_mismatch_fail_closed(self):
        expected = {
            "missing": TransferError,
            "reordered": TransferError,
            "duplicate": TransferError,
            "sha-mismatch": ShaMismatchError,
        }
        for scenario, error in expected.items():
            with self.subTest(scenario=scenario):
                with self.assertRaises(error):
                    run_scenario(scenario)

    def test_disconnect_reconnect_discards_partial_session_then_allows_retry(self):
        receipt = run_scenario("disconnect-reconnect")
        self.assertIsNotNone(receipt)
        self.assertEqual(deterministic_test_envelope(), receipt.envelope)

    def test_timeout_is_virtual_and_deterministic(self):
        with self.assertRaises(TransferError):
            run_scenario("timeout")

    def test_replay_is_rejected_after_success(self):
        with self.assertRaises(ReplayError):
            run_scenario("replay")

    def test_plaintext_is_rejected_at_sender_boundary(self):
        peripheral = FakeGattPeripheral()
        central = FakeGattCentral(peripheral)
        with self.assertRaises(PlaintextRejected):
            central.play(self.generator.events(self.transfer, "normal"), encrypted=False)
        self.assertIsNone(peripheral.last_receipt)

    def test_unsupported_result_frame_is_not_accepted_as_uplink(self):
        central = FakeGattCentral(FakeGattPeripheral())
        central.connect()
        with self.assertRaises(PlaintextRejected):
            central.send(GattFrame(GattFrameKind.RESULT, 0, 1, self.transfer.session_id, b"x"))

    def test_malformed_frames_fail_closed(self):
        malformed = [b"", bytes(21), bytes((99, 0, 0, 0, 0, 1, 1, 2, 3, 4))]
        for raw in malformed:
            with self.subTest(raw=raw):
                with self.assertRaises(MalformedFrameError):
                    GattFrame.decode(raw)

    def test_fault_schedule_is_byte_for_byte_deterministic(self):
        first = [event.wire_key() for event in self.generator.events(self.transfer, "delayed")]
        second = [event.wire_key() for event in FrameGenerator(seed=7).events(self.transfer, "delayed")]
        self.assertEqual(first, second)


if __name__ == "__main__":
    unittest.main()
