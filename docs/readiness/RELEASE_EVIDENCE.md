<!-- GENERATED FILE - DO NOT EDIT.
     Source of truth: docs/readiness/status.yml
     Regenerate: python tools/readiness/readiness_tool.py generate -->

# Relay release evidence index

Status date: **2026-07-26** / commit `89e7651`

Evidence listed here proves only what its kind states. `source`/`test`/`script`/`workflow`/`doc` entries are automated or written evidence; only dated `external_record` entries can support DEVICE_TESTED / FIELD_TESTED claims.

## `sos-rescue-request` — SOS / rescue request creation, update, cancel

- **source**: `app/src/main/java/com/example/relay/ui/RelayApp.kt`
- **test**: `app/src/test/java/com/example/relay` — :app:testDebugUnitTest in relay-ci.yml unit job
- **workflow**: `.github/workflows/relay-ci.yml`

## `encrypted-storage` — Room + SQLCipher encrypted storage with Keystore passphrase

- **source**: `app/src/main/java/com/example/relay/data/local/SqlCipherPassphraseStore.kt`
- **test**: `app/src/androidTest/java/com/example/relay/data/local/SqlCipherPassphraseStoreTest.kt` — Instrumentation test exists; requires emulator/device lane to execute
- **script**: `scripts/verify-implementation-contracts.ps1` — Contract check enforces SQLCipher + Keystore usage in CI

## `location-update` — Consent-gated location update

- **test**: `app/src/test/java/com/example/relay` — Unit-tested policy; GPS hardware behavior is out of scope

## `continuous-gps-tracking` — Continuous background GPS tracking

- 証跡なし（NOT_IMPLEMENTEDまたは設計のみ）

## `armed-emergency-state` — ARMED / EMERGENCY_ACTIVE background relay state machine

- **source**: `app/src/main/java/com/example/relay/background`
- **doc**: `docs/BACKGROUND_RELAY_MODE.md`

## `auto-disaster-detection` — Automatic disaster detection triggers

- **doc**: `docs/DISASTER_ACTIVATION_TRIGGERS.md` — Design document only

## `nearby-relay` — Nearby store-carry-forward relay of encrypted envelopes

- **doc**: `docs/NEARBY_IMPLEMENTATION.md`
- **script**: `scripts/device-test/run-multihop-test.ps1` — Harness exists; never executed on physical devices

## `nearby-connection-policy` — Nearby OPEN / TRUSTED connection policy

- **test**: `app/src/test/java/com/example/relay`

## `gateway-enrollment-core` — LAN Gateway enrollment token validation, pinning, rotation

- **source**: `app/src/main/java/com/example/relay/gateway/GatewayShelterEnrollment.kt`
- **test**: `app/src/test/java/com/example/relay/gateway/GatewayEnrollmentTrustTest.kt`

## `gateway-enrollment-ui` — Gateway enrollment Compose screen with CameraX QR scanner

- **source**: `app/src/main/java/com/example/relay/ui/enrollment/EnrollmentScreen.kt`
- **source**: `app/src/main/java/com/example/relay/ui/enrollment/CameraQrScanner.kt`
- **test**: `app/src/test/java/com/example/relay/ui/enrollment/EnrollmentViewModelTest.kt`

## `broker-manifest-enrollment` — Broker manifest self-pin for debug/localDev

- **test**: `app/src/test/java/com/example/relay/rescue/ShelterManifestEnrollmentTest.kt`

## `ble-gateway-trust-chain` — BLE Gateway trust chain (Root -> Directory -> Manifest -> fingerprint)

- **doc**: `docs/adr/ADR-002-gateway-discovery-trust.md`
- **source**: `shared/src/commonMain/kotlin`

## `pc-gateway-console` — PC Gateway staff console (accounts, roles, audit, receipts)

- **source**: `pc-gateway/src/main/kotlin/com/example/relay/pcgateway`
- **test**: `pc-gateway/src/test` — :pc-gateway:test plus Playwright staff-console E2E
- **workflow**: `.github/workflows/relay-ci.yml`

## `sqlite-write-coordination` — SQLite write coordinator and lock file

- **test**: `pc-gateway/src/test`

## `csv-export-injection-safe` — CSV export with formula injection neutralization

- **test**: `pc-gateway/src/test` — Shared encoder for messages and audit exports

## `https-broker` — HTTPS Broker (ciphertext store, dedup, TTL, scoped credentials)

- **source**: `broker/src/main/kotlin/com/example/relay/broker`
- **test**: `broker/src/test`

## `broker-observability-minimal` — Broker security-event logging without secrets

- **test**: `broker/src/test`

## `packaged-e2e` — Packaged Broker-Gateway black-box E2E

- **script**: `scripts/e2e/run-packaged-broker-gateway-e2e.ps1`
- **workflow**: `.github/workflows/relay-heavy-tests.yml`

## `broker-high-availability` — Broker high availability, monitoring, disaster recovery

- 証跡なし（NOT_IMPLEMENTEDまたは設計のみ）

## `android6-compat` — Android 6.0 (API 23) compatibility baseline

- **source**: `app/build.gradle.kts` — minSdk 23 + core library desugaring + API 23 managed device lane
- **script**: `scripts/android-test/run-api23-smoke.ps1`

## `windows-validation` — Windows batch validation with PASS/FAIL/BLOCKED/NOT_RUN classification

- **script**: `scripts/validate-windows-development.ps1`
- **workflow**: `.github/workflows/relay-ci.yml` — windows-validation job

## `device-test-harness` — ADB/Mobly physical-device test harness

- **script**: `scripts/device-test/run-device-smoke.ps1`
- **test**: `test-lab/mobly` — Host contract tests run in CI; device execution never performed

## `coverage-kover` — Kover coverage reporting

- **source**: `gradle/libs.versions.toml` — kover plugin applied to JVM modules

## `build-reproducibility` — APK build reproducibility check

- **script**: `scripts/verify-build-reproducibility.ps1`
- **workflow**: `.github/workflows/relay-heavy-tests.yml`

## `dependency-security-scan` — Syft SBOM + OSV + Grype dependency scanning

- **script**: `scripts/run-syft.ps1`
- **script**: `scripts/run-osv.ps1`
- **script**: `scripts/run-grype.ps1`
- **workflow**: `.github/workflows/relay-ci.yml` — deps-scan job with completeness enforcement

## `fuzz-decoders` — Jazzer decoder fuzzing (regression lane)

- **source**: `fuzz-jvm/build.gradle.kts`
- **script**: `scripts/run-jazzer.ps1`

## `formal-release` — Formal signed release (Android signing, Authenticode, TUF/cosign)

- **workflow**: `.github/workflows/publish-release.yml`
- **doc**: `docs/runbooks/VERIFY_FORMAL_RELEASE.md`

## `ios-preview` — iOS simulator preview build

- **workflow**: `.github/workflows/ios-ci.yml` — Unsigned arm64 simulator app built on macOS runner

## `meshtastic-adapter` — Meshtastic adapter contract boundary

- **test**: `gateway-meshtastic-adapter/tests`
- **doc**: `docs/integrations/MESHTASTIC.md`

## `bp7-export` — BPv7 export boundary

- **test**: `gateway-bp7-export`
- **doc**: `docs/integrations/BPV7.md`

## `training-mode` — Training mode with full data separation

- 証跡なし（NOT_IMPLEMENTEDまたは設計のみ）

## `official-info-provenance` — Official information provenance model (JMA XML / CAP)

- 証跡なし（NOT_IMPLEMENTEDまたは設計のみ）

## `dpapi-key-protection` — Windows DPAPI protection for Gateway private keys

- 証跡なし（NOT_IMPLEMENTEDまたは設計のみ）

## `data-retention` — Retention policy engine for personal/rescue data

- 証跡なし（NOT_IMPLEMENTEDまたは設計のみ）

## `codeql-analysis` — CodeQL static analysis (java-kotlin / js-ts / actions)

- **workflow**: `.github/workflows/codeql.yml`

## `secret-scanning-gitleaks` — Secret scanning with Relay-specific gitleaks rules

- **workflow**: `.github/workflows/security-baseline.yml`
- **doc**: `.gitleaks.toml` — Local run: 293 commits clean; negative test with planted fake secrets detected 2 leaks (exit 1)

## `workflow-lint-zizmor` — Workflow lint and hardening audit (actionlint + zizmor)

- **workflow**: `.github/workflows/security-baseline.yml` — Local run: actionlint exit 0; zizmor 0 findings (30 suppressed via persist-credentials:false)

## `action-sha-pinning-gate` — CI gate rejecting non-SHA-pinned GitHub Actions

- **workflow**: `.github/workflows/security-baseline.yml` — Local regex gate run: 0 violations across all workflows

## `dependency-review` — PR dependency review (fail on high severity, license denylist)

- **workflow**: `.github/workflows/dependency-review.yml`

## `scorecard-monitoring` — OSSF Scorecard supply-chain posture monitoring

- **workflow**: `.github/workflows/scorecard.yml`

## `dependabot-updates` — Dependabot update configuration (gradle/actions/npm/pip)

- **doc**: `.github/dependabot.yml`

## `branch-protection` — Branch protection with required security checks

- **doc**: `docs/security/BRANCH_PROTECTION.md`

