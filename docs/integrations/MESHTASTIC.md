# Meshtastic Gateway boundary

Relay does not embed Meshtastic GPL code in the Android APK. The independent
`gateway-meshtastic-adapter` process accepts only the minimum metadata needed
for a radio notice: `requestId`, `shelterId`, `urgency`, coarse location, and a
SHA-256 `payloadHash`. It applies a bounded payload size, TTL, validation, and
`requestId + payloadHash` deduplication before calling the optional Python
Meshtastic `SerialInterface`/`TCPInterface` backend.

```powershell
$env:PYTHONPATH = "$pwd/gateway-meshtastic-adapter"
Get-Content .\message.json | python -m gateway_meshtastic_adapter.main --mock
```

`--mock` is the deterministic host test backend. A real radio requires the
separately installed `meshtastic` package and a serial/TCP port. Android AIDL
is deliberately not a dependency. GPL licensing, device model, regional
frequency, and radio interoperability remain deployment acceptance gates.
