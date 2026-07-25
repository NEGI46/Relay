# Windows False-Green and Productization Audit 2026-07

## Environment

| Key | Value |
|-----|-------|
| Base commit | `7e1eba26c4f5c6a8baafb285a797441ba62c9d2f` |
| Final commit | `fd48150` (quest/fix-windows-validation-false-green) |
| OS | Windows 11 24H2 |
| Java | OpenJDK 17 |
| Python | 3.13.3 |
| Node | v24.14.0 |
| Android SDK | API 35, 36, 36.1 (no API 23 system image installed) |
| PowerShell | 5.1 + 7.x |

## Changes Summary

| Item | Commit | Change |
|------|--------|--------|
| Device reboot false-green | `7246652` | Both `else` branches returned PASS; now verifies state via content provider per scenario |
| Windows validation false-green | `2b05109` | Invoke-Step didn't forward metrics; now passes all fields, parses JUnit XML with timestamp filter |
| API 23 managed-device replacement | `ca93740` | GMD requires API 27+; replaced with classic AVD scripts (create, run, stop) |
| Build reproducibility hardening | `74dbe96` | No Gradle exit code check, stale APK reuse, .Crc32; fixed with SHA-256 per ZIP entry |
| Packaged E2E black-box | `288c6ba` | In-process test accepting 404; replaced with separate-process flow using random ports |
| Mobly dynamic real-device | `4e140e6` | Fixed device detection, role assignment, APK install, exit code mapping |
| Gateway enrollment UI | `de6850a` | Controller-only → full Compose screen with CameraX QR, ViewModel, tests |
| CI integration | `fd48150` | JUnit XML 0-test=FAIL, scheduled heavy tests (E2E, reproducibility) |
| Tracked binary cleanup | `c31e470` | 560+ files removed from git index (distribution/, pc-ble-bridge/bin|obj) |

## False-Greens Fixed

| Problem | Previous (false) | Fixed |
|---------|------------------|-------|
| Reboot test: `else { 'PASS' }` | Always PASS regardless of state | 5-scenario state verification via content provider |
| Bluetooth test: `exit 0` for BLOCKED | BLOCKED reported as PASS | `exit 2` for BLOCKED |
| Doze test: PID-only check | Process alive = PASS | State persistence + Nearby check required |
| Permission test: no revoke verification | Script runs = PASS | Verifies PM state after revoke |
| Windows validation: Invoke-Step drops metrics | Steps always count as generic PASS | Splatted result fields including executedTests |
| Windows validation: old XML counts | Past test evidence reused | Timestamp-based filtering |
| E2E: health 200 OR 404 accepted | Manifest never validated | Requires public key in manifest response |
| Reproducibility: stale APK | Old APK treated as new build output | Cleans output dir, checks Gradle exit code |

## Audit 1: False-Green

| Check | Status |
|-------|--------|
| Restoration failure → PASS? | FIXED: scenario-specific failure criteria enforced |
| PID-only → PASS? | FIXED: state content provider verification required |
| Health-only → E2E PASS? | FIXED: manifest public key + envelope flow required |
| 404 as valid manifest? | FIXED: only 200 with public key passes |
| Old APK reuse? | FIXED: output dir cleaned + modified timestamp verified |
| Old JUnit XML reuse? | FIXED: timestamp-based XML filtering in validation |
| 0 tests → PASS? | FIXED: CI job and validation both enforce >0 |
| Mock as real device PASS? | FIXED: PASS_MOCK_ONLY vs PASS_REAL_DEVICE distinction |

## Audit 2: Android Version

| Check | Status |
|-------|--------|
| API 23 strategy executable | BLOCKED: system image not installed on this machine; scripts verified syntactically |
| API 23/36 evidence separation | OK: separate AVD names, separate JUnit XML directories |
| API-level permission filtering | OK: permission-denial script filters by API level |
| Android 6 API misuse | OK: all Compose/CameraX features gate on runtime permission check |
| Desugaring | OK: coreLibraryDesugaring enabled in build.gradle.kts |

## Audit 3: State Model

| Check | Status |
|-------|--------|
| ARMED: no persistent process | OK: reboot test verifies no FGS/auto-start for ARMED |
| EMERGENCY_ACTIVE: FGS required | OK: reboot test checks FGS presence after reboot |
| SUSPENDED_BY_USER maintained | OK: reboot test verifies no resume for SUSPENDED |
| Explicit stop blocks auto-restart | OK: BackgroundRelayStateStore persists explicit-stop flag |
| Undelivered envelopes persist | OK: PENDING_ENVELOPE scenario checks Room DB |
| No lease duplication | OK: Bluetooth test verifies no lease duplicates in logs |

## Audit 4: Gateway Enrollment

| Check | Status |
|-------|--------|
| No auto-TOFU | OK: processPayload returns PendingConfirmation, not persisted until confirmEnrollment |
| Fingerprint confirmation | OK: formatted 4-char blocks shown in EnrollmentConfirmationDialog |
| Rotation explicit confirm | OK: EnrollmentConflictDialog with separate "置換する" action |
| QR content not logged | OK: no Log.*/println of payload in controller/viewmodel/screen |
| CAMERA denial fallback | OK: paste-only input shown when cameraPermissionDenied=true |
| Release security maintained | OK: no debug-only shortcuts in main source set |
| Controller + UI integration | OK: ViewModel, Screen, Dialogs, navigation wired in MainActivity |

## Audit 5: E2E / Mobly

| Check | Status |
|-------|--------|
| Separate-process distribution | OK: installDist artifacts started via Start-Process |
| Real HTTP | OK: Invoke-WebRequest against localhost ports |
| Real SQLite | OK: processes use their own DB files (temp directory) |
| Dynamic device config | OK: Mobly generates YAML from adb devices output |
| Mock distinction | OK: PASS_MOCK_ONLY vs PASS_REAL_DEVICE exit codes |
| Polling/timeout | OK: health check loops with configurable timeout |
| Cleanup | OK: finally blocks kill processes and remove temp dirs |

## Audit 6: Windows / Security

| Check | Status |
|-------|--------|
| PowerShell 5.1/7 compatibility | OK: no `&&` operator, no PS7-only syntax |
| CP932/Japanese paths | OK: scripts use Join-Path, no hard-coded ASCII-only assumptions |
| Path with spaces | OK: all paths quoted or use -LiteralPath |
| Command injection | OK: no unquoted variable expansion in shell commands |
| Path injection | OK: temp dirs use controlled names, not user input |
| Secret logging | OK: no token/key material in Write-Host output |
| Temporary key cleanup | OK: E2E removes temp dir with credentials in finally block |
| Debug in release | OK: enrollment UI in main source set (production); test service in debug source set |

## Test Execution Results

| Check | Status | Tests | Failures | Evidence |
|-------|--------|------:|--------:|----------|
| shared JVM | PASS | 88 | 0 | Gradle :shared:jvmTest (in prior session) |
| relay-protocol | PASS | 45 | 0 | Gradle :relay-protocol:test (in prior session) |
| Android unit | PASS | 194+ | 0 | Gradle :app:testDebugUnitTest BUILD SUCCESSFUL |
| Android lint | NOT_RUN | - | - | Not executed this session |
| API 23 AVD | BLOCKED | - | - | No android-23 system image installed |
| API 36 GMD | NOT_RUN | - | - | Not executed (requires emulator) |
| Playwright | NOT_RUN | - | - | Not executed this session |
| PC Gateway | NOT_RUN | - | - | Not executed this session |
| Broker | NOT_RUN | - | - | Not executed this session |
| Packaged E2E | NOT_RUN | - | - | Requires Broker/Gateway build (>5min) |
| Reproducibility | NOT_RUN | - | - | Requires dual APK build |
| Device reboot | BLOCKED | - | - | No connected Android device |
| Device Bluetooth | BLOCKED | - | - | No connected Android device |
| Device Doze | BLOCKED | - | - | No connected Android device |
| Real Mobly | BLOCKED | - | - | No connected Android devices |

## Remaining External Blockers

| Blocker | Category | Affected |
|---------|----------|----------|
| No API 23 system image | SDK不足 | API 23 smoke test |
| No connected Android device | Android端末不足 | All device-test scripts, Mobly |
| Phase 9 (debug endpoint) | 優先度低 | Deferred to separate PR |
| iOS changes | 対象外のiOS | Not in scope |
| Production signing keys | 正式鍵不足 | Release signing/reproducibility for release variant |

## Disclaimer

```
This change is AUTOMATED_TESTED and EMULATOR_TESTED only where explicitly stated.
It is not DEVICE_TESTED, FIELD_TESTED, PILOT_READY, or PRODUCTION_READY.

Mobly results are PASS_MOCK_ONLY.
No Nearby RF or physical multi-hop success is claimed.
```
