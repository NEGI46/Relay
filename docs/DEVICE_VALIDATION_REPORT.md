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
