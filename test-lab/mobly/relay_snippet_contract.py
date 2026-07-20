"""Shared contract and deterministic fallback for Relay Mobly scenarios.

The real Mobly adapter expects a snippet bridge exposing ``call(method, args)``.
The fallback intentionally mirrors the debug Binder response shape so the same
scenario assertions run without Android devices or a Mobly installation.
"""

import json
import unittest
from typing import Any, Dict, List, Optional

try:
    from mobly import base_test as _mobly_base_test

    MoblyTestBase = _mobly_base_test.BaseTestClass
    HAS_MOBLY = True
except ImportError:  # pragma: no cover - exercised by the host-only lane
    MoblyTestBase = unittest.TestCase
    HAS_MOBLY = False


METHODS = (
    "contract",
    "createReport",
    "findMessage",
    "deliveryLedger",
    "state",
    "nearbyStart",
    "nearbyStop",
    "gatewayStart",
    "gatewayStop",
    "bleStart",
    "bleStop",
)


class RelaySnippetError(RuntimeError):
    """Raised when the debug endpoint returns an unsuccessful response."""


class MockRelaySnippet:
    """Small deterministic model of the debug Binder contract."""

    def __init__(self, device_id: str) -> None:
        self.device_id = device_id
        self.messages: Dict[str, Dict[str, Any]] = {}
        self.started = {"nearby": False, "gateway": False, "ble": False}
        self.ble_state = "Idle"
        self._counter = 0

    def call(self, method: str, arguments: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        args = arguments or {}
        if method == "contract":
            return {"ok": True, "protocolVersion": 1, "methods": ",".join(METHODS)}
        if method == "createReport":
            self._counter += 1
            message_id = f"{self.device_id}-report-{self._counter:04d}"
            message = {
                "messageId": message_id,
                "messageType": "SAFETY",
                "recordType": "REPORT",
                "originDeviceId": self.device_id,
                "hopCount": 0,
                "maxHopCount": int(args.get("maxHopCount", 8)),
                "payload": {
                    "state": args.get("state", "SAFE"),
                    "companionCount": int(args.get("companionCount", 0)),
                    "approximateLocation": args.get("approximateLocation", "test-location"),
                    "note": args.get("note", "Mobly debug report"),
                },
            }
            self.messages[message_id] = message
            return {"ok": True, "messageId": message_id, "messageJson": json.dumps(message, sort_keys=True)}
        if method == "findMessage":
            message_id = str(args.get("messageId", ""))
            message = self.messages.get(message_id)
            if message is None:
                return {"ok": False, "error": f"message not found: {message_id}"}
            return {"ok": True, "messageJson": json.dumps(message, sort_keys=True)}
        if method == "deliveryLedger":
            return {
                "ok": True,
                "messagesJson": json.dumps(list(self.messages.values()), sort_keys=True),
                "deliveriesJson": "[]",
                "receiptsJson": "[]",
                "pendingGatewayIdsJson": json.dumps(sorted(self.messages)),
            }
        if method in ("nearbyStart", "gatewayStart", "bleStart"):
            transport = method[:-5].lower()
            self.started[transport] = True
            if transport == "ble":
                self.ble_state = "Scanning"
            return {"ok": True, "started": True, "state": self.ble_state if transport == "ble" else ""}
        if method in ("nearbyStop", "gatewayStop", "bleStop"):
            transport = method[:-4].lower()
            self.started[transport] = False
            if transport == "ble":
                self.ble_state = "Idle"
            return {"ok": True, "stopped": True, "state": self.ble_state if transport == "ble" else ""}
        if method == "state":
            return {
                "ok": True,
                "nearbyRunning": self.started["nearby"],
                "gatewayEnabled": self.started["gateway"],
                "bleState": self.ble_state,
            }
        return {"ok": False, "error": f"unknown method: {method}"}

    def inject_message(self, message_json: str) -> None:
        """Test-only forwarding primitive; production API uses Nearby transport."""
        message = json.loads(message_json)
        self.messages[message["messageId"]] = dict(message)


class AndroidRelaySnippetClient:
    """Adapter for a Mobly-loaded snippet bridge.

    The bridge is intentionally injected by the device lab. It must expose a
    ``call(method, arguments)`` method that binds to the debug service's Binder
    contract. Keeping this adapter narrow prevents scenario code from depending
    on a particular snippet-uiautomator release.
    """

    def __init__(self, android_device: Any) -> None:
        self.android_device = android_device
        self._snippet = None

    def _load(self) -> Any:
        if self._snippet is not None:
            return self._snippet
        loader = getattr(self.android_device, "load_snippet", None)
        if loader is None:
            raise RelaySnippetError("Mobly AndroidDevice.load_snippet is unavailable")
        self._snippet = loader("relay_test")
        return self._snippet

    def call(self, method: str, arguments: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        snippet = self._load()
        call = getattr(snippet, "call", None)
        if call is None:
            raise RelaySnippetError("relay_test snippet must expose call(method, arguments)")
        result = call(method, arguments or {})
        if not isinstance(result, dict):
            raise RelaySnippetError("snippet result must be a dict")
        return result


class RelaySnippetContract:
    """Typed-ish convenience wrapper shared by all scenario tests."""

    def __init__(self, client: Any) -> None:
        self.client = client

    def call(self, method: str, **arguments: Any) -> Dict[str, Any]:
        result = self.client.call(method, arguments)
        if not result.get("ok", False):
            raise RelaySnippetError(result.get("error", "debug endpoint failed"))
        return result

    def create_report(self, **arguments: Any) -> Dict[str, Any]:
        return self.call("createReport", **arguments)

    def find_message(self, message_id: str) -> Dict[str, Any]:
        return self.call("findMessage", messageId=message_id)

    def delivery_ledger(self) -> Dict[str, Any]:
        return self.call("deliveryLedger")

    def start(self, transport: str) -> Dict[str, Any]:
        return self.call(transport + "Start")

    def stop(self, transport: str) -> Dict[str, Any]:
        return self.call(transport + "Stop")


class RelayScenarioTest(MoblyTestBase):
    """Base class that runs with real Mobly devices or deterministic mocks."""

    def clients(self, count: int) -> List[RelaySnippetContract]:
        devices = list(getattr(self, "android_devices", []) or [])
        if devices:
            if len(devices) < count:
                raise RelaySnippetError(f"config needs {count} Android devices")
            return [RelaySnippetContract(AndroidRelaySnippetClient(device)) for device in devices[:count]]
        return [RelaySnippetContract(MockRelaySnippet(f"mock-{index}")) for index in range(count)]

    @staticmethod
    def decode_json(result: Dict[str, Any], key: str) -> Any:
        return json.loads(result[key])

