from bp7_export import RelayEnvelope, export_bundle, import_fixture


def test_golden_round_trip():
    envelope = RelayEnvelope("msg-1", 3600, "a" * 64, "HIGH")
    assert import_fixture(export_bundle(envelope)) == envelope
