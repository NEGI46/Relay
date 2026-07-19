# Current validation report

Audit date: 2026-07-19
Validation rule: only a command that completed successfully at the current code state is `PASS`. A skipped optional dependency, unavailable scanner, unhealthy emulator, or pre-test host failure is `BLOCKED`; it is never folded into PASS.

## Results

| Category | Command / evidence | Result | Notes |
|---|---|---|---|
| Baseline aggregate Gradle | `:app:testDebugUnitTest :shared:jvmTest :relay-protocol:test :pc-gateway:test :composeApp:desktopTest :app:lintDebug :app:assembleDebug :pc-gateway:build` | FAIL | 152 tests ran; `UdpGatewayDiscoveryTest.receives announcement on bound discovery port` intermittently returned null. The isolated pre-fix test later passed, identifying timing sensitivity. `ddee261` repeats beacons; post-fix re-run is blocked by the Gradle loopback issue below. |
| Focused Gradle post-fix | `:app:testDebugUnitTest --tests com.example.relay.gateway.UdpGatewayDiscoveryTest` | BLOCKED | Gradle failed before test execution with `java.io.IOException: Unable to establish loopback connection`, even after `gradle --stop`. |
| Android DB test compilation | `:app:compileDebugAndroidTestKotlin` | PASS | Completed after adding the SQLCipher concurrency and migration-rollback tests. |
| Android APK/test APK assembly | `:app:assembleDebug :app:assembleDebugAndroidTest` | PASS | Completed after the DB changes. |
| Android instrumentation (new DB tests) | Headless `medium_phone` AVD; `SqlCipherPassphraseStoreTest`, `PlaintextDatabaseMigrationTest` | BLOCKED | AVD reported Bluetooth SIGABRT/system ANRs and instrumentation ended `Process crashed`; no Relay Java exception was established. Earlier full instrumentation evidence predates this audit state and is not reused as current PASS. |
| Implementation contracts | `scripts/verify-implementation-contracts.ps1` | PASS | Completed after CI/script changes. |
| Accessibility contract | `scripts/check-accessibility.ps1` | PASS | 71 Kotlin files checked in baseline. |
| Host contracts | `test-lab/run-host-checks.ps1` | PASS | Completed after CI/script changes. |
| Gateway recovery | bundled Python `unittest discover tests/gateway-recovery` | BLOCKED | 2 tests passed; age binary was absent, so the age round-trip test skipped. |
| BPv7 export | bundled Python tests in `gateway-bp7-export` | PASS | 3 tests passed. Export contract only; no external DTN node was exercised. |
| Meshtastic adapter | bundled Python tests in `gateway-meshtastic-adapter/tests` | PASS | 2 tests passed. Adapter contract only; no radio hardware was exercised. |
| TUF metadata chain | `test-lab/tuf_metadata_test.py -v` | PASS | 5 tests passed. This does not verify a production release key. |
| Virtual BLE | `tools/ble-sim/run_tests.py` | BLOCKED | 12 simulator tests passed; the optional Bumble dependency was not available. |
| Decoder / virtual BLE regression | `scripts/run-jazzer.ps1 -RequirePython` | PASS | 4 deterministic decoder tests and 12 virtual BLE tests passed; the machine-readable result names its Jazzer target `NOT_RUN`. |
| Jazzer fuzzing | JVM Jazzer target | NOT_RUN | No JVM target exists. `JAZZER_FUZZ=1` now fails instead of calling regression coverage Jazzer. |
| SBOM / OSV / Grype | local scan scripts | BLOCKED | Syft, OSV-Scanner, and Grype are not installed. Scripts emitted `BLOCKED`; required mode also failed as intended. |
| MobSF | `scripts/run-mobsf.ps1` | BLOCKED | `MOBSF_URL` and `MOBSF_API_KEY` are not configured. Required mode emitted `BLOCKED` and failed as intended. |
| Distribution signature verification | `scripts/verify-distribution-signatures.ps1 -RequireVerification` | BLOCKED | No distribution/signing evidence or cosign verification setup is available. Required mode emitted `BLOCKED` and failed as intended. |

## Reproduction prerequisites and next steps

1. Restore the host loopback path used by Gradle, then run the aggregate Gradle command again; do not call the earlier aggregate result green.
2. Reset/repair the headless AVD and re-run all instrumentation tests, including the new database tests. Keep the emulator screenless.
3. Provision pinned Syft, OSV-Scanner, Grype, and cosign; configure an isolated MobSF service and CI secrets. Then retain their artifacts from a successful CI run.
4. Install `age` and optional Bumble only when their integration tests are intended to become mandatory.
5. Perform physical-device multi-peer, service-restart, Bluetooth state-change, gateway LAN, and radio-adapter validation before any emergency-use claim.
