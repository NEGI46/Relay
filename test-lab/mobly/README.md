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
# Relay Mobly test suite

The three Python modules in this directory are the hardware-independent
contract lane for the Android debug test surface:

- `relay_multihop_test.py` — report creation, ledger, and transport lifecycle
- `gateway_sync_test.py` — idempotent sync and receipt-state separation
- `ble_submit_prestage_test.py` — opaque payload/hash pre-submit checks

`config.sample.yaml` is a Mobly-shaped three-device configuration. The more
explicit JSON-compatible YAML variant is under `configs/relay_emulator.yaml`.
Run the deterministic contract lane without a device using the workspace
Python runtime:

```powershell
$py="$env:USERPROFILE\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
& $py -m unittest discover -s test-lab/mobly -p '*_test.py'
```

The Android `RelayTestSnippetService` and its `RelayTestSnippetBinder` are
compiled only from `app/src/debug`; they are not part of release variants.
The host adapter expects a small Mobly snippet bridge named `relay_test` that
binds the service and exposes `call(method, arguments)`. A real Mobly run
requires three configured ADB devices, an installed debug APK, granted Nearby
permissions, enabled Bluetooth/location services, and the bridge package.
Without those prerequisites the same scenario assertions run against the
deterministic mock and do not claim radio, Gateway, or BLE hardware coverage.
