# Repository Size Audit — July 2026

**Commit**: `f53c42c` (agent/zero-operation-relay)  
**Total pack size**: 393.81 MiB  
**Total objects**: 5318  

## Summary

The repository contains ~394 MiB of packed Git objects, primarily due to committed
binary build artifacts. This significantly impacts clone time, CI checkout speed,
and developer experience.

## Largest Objects (Top 20)

| Size (MB) | Path |
|---|---|
| 88.7 | `distribution/Relay-User-Package/Windows/BleBridge-Debug/Relay.PcBleBridge.exe` |
| 88.7 | `distribution/Relay-User-Package/Windows/BleBridge-Release/Relay.PcBleBridge.exe` |
| 81.7 | `artifacts/relay-pc-gateway-updated.exe` |
| 81.6 | `artifacts/relay-pc-gateway.exe` (multiple versions) |
| 76.2 | `artifacts/relay-pc-gateway.exe` (older) |
| 74.5 | `artifacts/relay-pc-gateway.exe` (older) |
| 34.9 | `distribution/Relay-User-Package/Windows/Debug/Relay.PcBleBridge-debug-local-signed.msix` |
| 34.9 | `distribution/Relay-User-Package/Windows/Relay.PcBleBridge.msix` |
| 34.9 | `distribution/Relay-User-Package/Windows/Relay.PcBleBridge-local-signed.msix` |
| 23.7 | `pc-ble-bridge/bin/Debug/net8.0-windows10.0.19041.0/win-x64/Microsoft.Windows.SDK.NET.dll` |
| 22.4 | `artifacts/relay-debug.apk` (multiple versions) |
| 13.7 | `distribution/Relay-User-Package/Windows/RelayPcGateway/lib/sqlite-jdbc-3.50.3.0.jar` |
| 13.5 | `distribution/Relay-User-Package/Android/Relay-debug.apk` |
| 12.6 | `pc-ble-bridge/bin/Debug/.../System.Private.CoreLib.dll` |
| 9.3 | `distribution/Relay-User-Package/Android/Relay-release-signed.apk` |

## Categories

### Build Artifacts in History (~330 MiB)

- **EXE files**: PC Gateway and BLE Bridge native executables
- **APK files**: Debug and release Android packages
- **MSIX files**: Windows distribution packages
- **JAR files**: SQLite JDBC and other dependencies
- **.NET DLLs**: Full .NET runtime assemblies committed with BLE bridge

### Recommendations

1. **Do NOT commit build outputs**: `artifacts/`, `distribution/`, and `pc-ble-bridge/bin/`
   should be produced by CI and uploaded as GitHub Release assets instead.

2. **Improve .gitignore**: Add patterns to prevent accidental recommit:
   ```
   artifacts/*.exe
   artifacts/*.apk
   distribution/Relay-User-Package/
   pc-ble-bridge/bin/
   pc-ble-bridge/obj/
   ```

3. **Future cleanup** (requires team coordination and force-push approval):
   - `git filter-repo` to remove historical binaries
   - Expected reduction: ~370 MiB (to ~24 MiB source-only repo)
   - This is destructive and requires all contributors to re-clone

4. **Use Git LFS** for legitimate large files (test fixtures, images) if needed

## .gitignore Improvements Applied

The following patterns are recommended additions (applied in this PR):

```
# Build artifacts that should not be committed
artifacts/*.exe
artifacts/*.apk
artifacts/*.aab
distribution/Relay-User-Package/Windows/BleBridge-*/
distribution/Relay-User-Package/Windows/*.msix
distribution/Relay-User-Package/Android/*.apk
pc-ble-bridge/bin/
pc-ble-bridge/obj/
```

## NOT Modified

- Git history was NOT rewritten
- No `git filter-repo`, BFG, force-push, or tag deletion was performed
- Existing release assets and tags remain untouched

## Impact Assessment

| Metric | Current | After .gitignore (future commits) | After history cleanup |
|---|---|---|---|
| Clone size | ~394 MiB | ~394 MiB (historical) | ~24 MiB |
| Clone time (100 Mbps) | ~32s | ~32s | ~2s |
| CI checkout | Slow | Same | Fast |
| Shallow clone | Helps | Helps | N/A |
