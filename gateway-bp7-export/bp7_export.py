"""Export-only Relay-to-BPv7 boundary with deterministic golden fixtures."""

from dataclasses import dataclass, asdict
import json


@dataclass(frozen=True)
class RelayEnvelope:
    message_id: str
    ttl_seconds: int
    payload_hash: str
    priority: str


def export_bundle(envelope: RelayEnvelope) -> bytes:
    if not envelope.message_id or envelope.ttl_seconds <= 0 or len(envelope.payload_hash) != 64:
        raise ValueError("invalid Relay envelope")
    bundle = {
        "bpVersion": 7,
        "primaryBlock": {"source": "relay:gateway", "destination": "dtn:external"},
        "extensionBlocks": [{"type": "relay-envelope", "data": asdict(envelope)}],
    }
    return json.dumps(bundle, sort_keys=True, separators=(",", ":")).encode()


def import_fixture(data: bytes) -> RelayEnvelope:
    raw = json.loads(data)
    if raw.get("bpVersion") != 7:
        raise ValueError("unsupported bundle version")
    extension = next(block for block in raw["extensionBlocks"] if block["type"] == "relay-envelope")
    return RelayEnvelope(**extension["data"])
