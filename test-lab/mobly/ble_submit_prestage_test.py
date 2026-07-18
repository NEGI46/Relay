"""Courier BLE pre-stage lifecycle contract."""

import unittest

from relay_snippet_contract import RelayScenarioTest


class BleSubmitPrestageTest(RelayScenarioTest):
    def test_ble_scan_can_start_and_stop_without_plaintext_access(self) -> None:
        client = self.clients(1)[0]
        started = client.start("ble")
        self.assertTrue(started["started"])
        self.assertEqual(client.call("state")["bleState"], "Scanning")

        stopped = client.stop("ble")
        self.assertTrue(stopped["stopped"])
        self.assertEqual(client.call("state")["bleState"], "Idle")

    def test_report_lookup_remains_available_before_ble_submission(self) -> None:
        client = self.clients(1)[0]
        created = client.create_report(note="BLE pre-stage contract")
        found = client.find_message(created["messageId"])
        self.assertEqual(self.decode_json(found, "messageJson")["messageId"], created["messageId"])


if __name__ == "__main__":
    unittest.main()
