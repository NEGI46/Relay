# Device / distribution validation report

**Date (JST):** 2026-07-16  
**Branch:** `agent/zero-operation-relay`  
**Commits:** `e69d184` … `6cfa131` on top of `c832ff2`  
**Detail log:** [artifacts/build-verify-20260716.md](../artifacts/build-verify-20260716.md)

## Summary

| Area | Result |
|------|--------|
| Preserve uncommitted work | **PASS** (patch + untracked list) |
| Logical commits | **PASS** (4 commits) |
| Clean test ≥78 | **PASS** (78 product tests, 0 fail) |
| Lint errors | **PASS** (0 errors, 51 warnings) |
| Debug APK rebuild | **PASS** |
| Formal WiX EXE rebuild | **BLOCKED** (WiX not installed) |
| jpackage app-image + installDist UI | **PASS** |
| PC smoke (health, UI, public UNVERIFIED, CSV, pair, admin key) | **PASS** |
| Firewall script | **BLOCKED** (admin required) |
| Autostart script | **BLOCKED** (admin required) |
| Android device | **NOT_RUN** (adb OK, 0 devices) |
| Phone↔PC public E2E | **NOT_RUN** |
| Nearby 2-device / multihop | **NOT_RUN** |

**Stage:** `ZERO_OP_CODE_COMPLETE_PC_SMOKE_PASS_DEVICE_NOT_VERIFIED`  
**Not claimed:** `PC_GATEWAY_PUBLIC_DEVICE_VERIFIED`

## PC smoke evidence (not a phone)

- Public POST accepted `smoke-msg-3`
- Receipt type `GATEWAY_RECEIVED_UNVERIFIED`
- Header `X-Relay-Receipt-Trust: unverified`
- Dashboard `unverifiedMessages: 1`
- CSV row includes `UNVERIFIED`
- Console UI serves tabs + CSV control

## Explicit non-PASS

Real Android installation, automatic discovery from a phone, Nearby pairing-free sync, and multihop A→B→PC were **not** executed in this session.


## 2026-07-16 session note (MVP auto+PC complete)

- Send-fail observability and trust labels improved in app code; unit tests cover shipped SyncCoordinator/Runtime paths.
- Stage remains **device residual**: adb device count 0 → Phone↔PC and Nearby multi-device still NOT_RUN.
- Do not claim `PC_GATEWAY_PUBLIC_DEVICE_VERIFIED` until real device runbook passes.

## 2026-07-19 headless emulator verification

- **PASS**: Gradle debug APK and Android test APK build after the SQLCipher migration changes.
- **PASS**: headless Android 16 emulator launched the current APK and kept the Relay process alive.
- **PASS**: the Room database header was encrypted (not `SQLite format 3`) and remained identical after force-stop/relaunch.
- **NOT_RUN / environment-limited**: instrumentation execution was not accepted as a test pass because the low-memory Play Store AVD killed the instrumentation process while Android/GMS services were also being reclaimed.
- **Policy**: future routine device checks use the screenless, low-load emulator first. A physical device is reserved for RF/Bluetooth, multi-device, or hardware-specific behavior that an emulator cannot reproduce.

The emulator result verifies application startup and encrypted-database reopen behavior; it does not claim Nearby or physical-radio E2E verification.
