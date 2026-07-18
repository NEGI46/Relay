"""Host-driven three-device REPORT forwarding contract."""

import unittest

from relay_snippet_contract import METHODS, RelayScenarioTest, MockRelaySnippet


class RelayMultihopTest(RelayScenarioTest):
    def test_contract_and_report_reaches_three_hops(self) -> None:
        origin, relay, destination = self.clients(3)
        self.assertTrue(set(METHODS).issubset(set(origin.call("contract")["methods"].split(","))))

        created = origin.create_report(
            state="EVACUATING",
            companionCount=2,
            approximateLocation="north-sector",
            note="multihop contract",
        )
        message_id = created["messageId"]
        message_json = created["messageJson"]

        for node in (relay, destination):
            self.assertTrue(node.start("nearby")["started"])
            if isinstance(node.client, MockRelaySnippet):
                node.client.inject_message(message_json)
            found = node.find_message(message_id)
            self.assertEqual(self.decode_json(found, "messageJson")["messageId"], message_id)

        ledger = destination.delivery_ledger()
        self.assertIn(message_id, self.decode_json(ledger, "pendingGatewayIdsJson"))

    def test_nearby_start_stop_contract(self) -> None:
        nodes = self.clients(3)
        for node in nodes:
            self.assertTrue(node.start("nearby")["started"])
            self.assertTrue(node.stop("nearby")["stopped"])


if __name__ == "__main__":
    unittest.main()
