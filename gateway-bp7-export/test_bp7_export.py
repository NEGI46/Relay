from pathlib import Path
from bp7_export import RelayEnvelope, export_bundle, import_fixture


def test_golden_round_trip():
    envelope = RelayEnvelope("msg-1", 3600, "a" * 64, "HIGH")
    golden = Path(__file__).parent / "fixtures" / "golden-relay-envelope.json"
    assert export_bundle(envelope) == golden.read_bytes()
    assert import_fixture(golden.read_bytes()) == envelope
