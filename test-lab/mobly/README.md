# Mobly lane

The production-grade multi-device lane is host-driven and requires Android
devices with ADB. The required scenarios are:

- `relay_multihop_test`: create a report on device A, forward through B, and
  verify the delivery ledger on C;
- `gateway_sync_test`: exercise Android → PC Gateway public sync;
- `ble_submit_prestage_test`: prepare Android Courier → Windows Bridge input.

Keep the device Binder/Snippet API debug-only. Run the deterministic host
oracle first with `test-lab/run-host-checks.ps1`; run Mobly only when the lab
has the declared device matrix.
