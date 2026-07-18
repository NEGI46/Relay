# BLE virtual transport harness

This directory contains a deterministic, hardware-free fake GATT central and
peripheral for the Relay courier-to-PC bridge boundary.  The codec is
byte-for-byte compatible with `pc-ble-bridge/Protocol/GattFrame.cs`:

- ATT value: 20 bytes maximum
- header: kind, payload length, sequence (u16 big-endian), total (u16 big-endian), session ID (4 bytes)
- payload: 0..10 bytes
- START metadata, CHUNK ciphertext bytes, and COMMIT SHA-256 digest are each fragmented with the same codec

Run the host harness and write JUnit XML inside this directory:

```powershell
$py="$env:USERPROFILE\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
& $py tools\ble-sim\ble_sim.py --junit-xml tools\ble-sim\test-results\ble-sim.xml
```

The command requires only the Python standard library.  It runs the unit
tests and produces `tools/ble-sim/test-results/ble-sim.xml`, even when the
optional `unittest-xml-reporting` package is unavailable.

The deterministic scenarios cover normal delivery, missing and reordered
frames, duplicate frames, an in-order delayed frame, disconnect/reconnect
with a partial session discarded, SHA-256 mismatch, virtual timeout, replay,
malformed frames, and plaintext rejection at the sender boundary.  The
simulator does not decrypt or cryptographically validate an envelope: the
`encrypted=True` flag models the Android sender contract, while the bridge
continues to treat received envelope bytes as opaque.

The optional Bumble test is intentionally skipped when Bumble is not
installed.  Even when the package is installed, a radio-backed integration is
not claimed without an explicit hardware/lab adapter.  Physical advertisement,
MTU negotiation, notification/indication, and Windows WinRT behavior remain
device-lab acceptance tests.
