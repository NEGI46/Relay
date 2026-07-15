# Build / verify session 2026-07-16

## Git

- Branch: `agent/zero-operation-relay`
- Commits created this session:
  - `e69d184` Fix Android zero-op gateway path: discovery bind, auto-sync, cleartext.
  - `fed3e7c` Harden PC Gateway store, admin key, and operator APIs.
  - `3b2021c` Add local PC Gateway operator console UI.
  - `6cfa131` Align docs and ops scripts with zero-operation model.
- Preservation: `artifacts/relay-before-device-test.patch`, `artifacts/relay-untracked-list.txt`

## Automated tests

| Suite | Count | Failures |
|-------|------:|---------:|
| Android testDebugUnitTest | 54 | 0 |
| Android testReleaseUnitTest | 54 | 0 |
| relay-protocol | 6 | 0 |
| pc-gateway | 18 | 0 |
| Unique product tests (debug+protocol+pc) | **78** | **0** |

`gradlew clean test :app:assembleDebug :app:lintDebug :pc-gateway:installDist` → **BUILD SUCCESSFUL**

## Lint

- Errors: **0**
- Warnings: **51** (SDK XML version warning + known project warnings)

## Artifacts

| File | Size | SHA-256 | Notes |
|------|-----:|---------|-------|
| `artifacts/relay-debug.apk` | 12520064 | `EF855BE13CAFF589DA97174F711D3EDE653B4922117B58F378AE71FCB4722CAF` | P0 + latest code |
| `artifacts/pc-gateway-app-image/RelayPcGateway/RelayPcGateway.exe` | (app-image tree) | launcher `535FE4C97E1C5E577548D189E84301FD92DC01C1BF02569968B7710B62354632` | jpackage **app-image** 0.1.1 with latest UI |
| `artifacts/relay-pc-gateway.exe` | (previous formal) | WiX **not installed** → full `.exe` installer rebuild **BLOCKED** | Use app-image or installDist |

## PC distribution verification (installDist / java -cp)

| Check | Result |
|-------|--------|
| Listen `0.0.0.0:8080` | PASS |
| `/api/health` 200 | PASS |
| New dashboard UI + tabs + CSV button | PASS |
| `/api/dashboard` | PASS |
| Public POST → `GATEWAY_RECEIVED_UNVERIFIED` + trust header | PASS |
| Pair code with admin key | PASS |
| CSV export admin | PASS |
| Admin key persisted `~/.relay/admin.key` | PASS |
| Admin key not printed in console | PASS |
| SQLite under `~/.relay/relay-gateway.db` | PASS |
| Restart keeps admin key + can restart server | PASS |
| UDP 42888 **send** beacon (client binds 42888) | Configured; PC does not LISTEN on 42888 (send-only) |
| Firewall script | **FAIL PermissionDenied** (needs elevation) |
| Network profile | **Public** (should be Private for lab) |
| Autostart script | **FAIL PermissionDenied** (needs elevation) |

## Device

| Check | Result |
|-------|--------|
| adb | Found at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` |
| devices | **0 attached** |
| Android single device | **NOT_RUN** |
| Phone↔PC public E2E | **NOT_RUN** |
| Nearby 2-device / A→B→PC | **NOT_RUN** |

## Stage

`ZERO_OP_CODE_COMPLETE_PC_SMOKE_PASS_DEVICE_NOT_VERIFIED`
