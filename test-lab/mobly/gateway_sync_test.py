"""Gateway sync and delivery-ledger contract."""

import unittest

from relay_snippet_contract import RelayScenarioTest


class GatewaySyncTest(RelayScenarioTest):
    def test_report_is_visible_to_gateway_ledger(self) -> None:
        client = self.clients(1)[0]
        message_id = client.create_report(note="gateway sync contract")["messageId"]
        self.assertTrue(client.start("gateway")["started"])

        ledger = client.delivery_ledger()
        messages = self.decode_json(ledger, "messagesJson")
        self.assertIn(message_id, [message["messageId"] for message in messages])
        self.assertIn(message_id, self.decode_json(ledger, "pendingGatewayIdsJson"))

        state = client.call("state")
        self.assertTrue(state["gatewayEnabled"])
        self.assertTrue(client.stop("gateway")["stopped"])

    def test_gateway_start_does_not_mark_delivery_without_receipt(self) -> None:
        client = self.clients(1)[0]
        message_id = client.create_report(note="receipt is explicit")["messageId"]
        client.start("gateway")
        self.assertIn(message_id, self.decode_json(client.delivery_ledger(), "pendingGatewayIdsJson"))


if __name__ == "__main__":
    unittest.main()
