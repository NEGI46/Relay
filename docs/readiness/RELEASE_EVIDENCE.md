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

- **source**: `pc-gateway/src/main/kotlin/com/example/relay/pcgateway/GatewayConfig.kt` — RELAY_TRAINING_MODE moves database, rescue keys, official-info cache, legacy admin key, and BLE bridge secret under ~/.relay/training/; fail-closed init guard rejects env path overrides lacking a 'training' segment; warning code and audit target record the mode
- **source**: `broker/src/main/kotlin/com/example/relay/broker/BrokerConfig.kt` — RELAY_BROKER_TRAINING_MODE ?: RELAY_TRAINING_MODE isolates the Broker store to ./data/training/broker.db with the same fail-closed path guard
- **test**: `pc-gateway/src/test/kotlin/com/example/relay/pcgateway/GatewayConfigTrainingModeTest.kt` — 8 tests: isolated defaults, production defaults unchanged, 3 fail-closed override rejections, segment matching, /api/health exposes trainingMode for the mandatory console banner; negative test: disabling the guard made the 3 rejection tests FAIL, reverted exactly
- **test**: `broker/src/test/kotlin/com/example/relay/broker/BrokerConfigTrainingModeTest.kt` — 4 tests incl. production-path rejection; negative test: disabling the Broker guard made the rejection test FAIL, reverted exactly; full :pc-gateway:test and :broker:test suites green

## `official-info-provenance` — Official information provenance model (JMA XML / CAP)

- **source**: `pc-gateway/src/main/kotlin/com/example/relay/pcgateway/official/OfficialInfoProvenance.kt` — Provenance record with an honest verification ceiling: unsigned JMA content is at most TRANSPORT_TLS_ONLY on live fetch, CACHED_UNVERIFIED on cache replay, UNVERIFIED when nothing is available; SHA-256 of the exact raw document
- **source**: `pc-gateway/src/main/kotlin/com/example/relay/pcgateway/official/CapAlertParser.kt` — Fail-closed OASIS CAP 1.2 and JMA Atom feed parsers over XXE-hardened XML (DOCTYPE banned, external entities/DTD/XInclude off); CAP status Exercise/Test preserved so drills are never shown as real alerts; XML-DSig only recorded as present, never claimed verified
- **test**: `pc-gateway/src/test/kotlin/com/example/relay/pcgateway/official/OfficialXmlParsersTest.kt` — 13 fixture/fail-closed tests incl. 3 XXE gates that assert the DOCTYPE ban specifically; negative test: planted disallow-doctype-decl=false made all 3 XXE gates FAIL, reverted exactly, suite green
- **test**: `pc-gateway/src/test/kotlin/com/example/relay/pcgateway/OfficialInformationServiceProvenanceTest.kt` — 5 provenance-state tests: live fetch, cache replay keeping original fetch time via digest-checked sidecar, tampered cache losing its fetch time, nothing available never presented as verified, throttle stability; full :pc-gateway:test suite green

## `dpapi-key-protection` — Windows DPAPI protection for Gateway private keys

- **source**: `pc-gateway/src/main/kotlin/com/example/relay/pcgateway/rescue/WindowsDpapi.kt` — Direct JNA mapping of crypt32 CryptProtectData/CryptUnprotectData (per-user scope, CRYPTPROTECT_UI_FORBIDDEN); lazy native load so non-Windows platforms never touch it
- **source**: `pc-gateway/src/main/kotlin/com/example/relay/pcgateway/rescue/RescueKeyStore.kt` — RELAY_KEY_PROTECTION=dpapi wraps the key file in a DPAPI blob with one-way migration from plaintext; fail-closed both directions (dpapi mode refuses plaintext fallback, protected files refuse non-dpapi mode); owner-only permission checks still run in both modes
- **test**: `pc-gateway/src/test/kotlin/com/example/relay/pcgateway/rescue/RescueKeyStoreDpapiTest.kt` — 7/7 on Windows (roundtrip with no plaintext key material on disk, migration, mode mismatch, tampered blob, wrong entropy, fail-closed guards, env parsing); negative proof: disabling both fail-closed guards made 2 tests FAIL, then reverted; Windows-only tests skip via Assume on Linux CI

## `data-retention` — Retention policy engine for personal/rescue data

- **source**: `pc-gateway/src/main/kotlin/com/example/relay/pcgateway/GatewayConfig.kt` — RELAY_RESCUE_RETENTION_DAYS (default 30, bounds 1..365) and RELAY_RETENTION_SWEEP_INTERVAL_MINUTES (default 60, bounds 5..1440); strict fail-closed parsing rejects malformed or out-of-range values at startup instead of silently defaulting
- **source**: `pc-gateway/src/main/kotlin/com/example/relay/pcgateway/Main.kt` — Startup purge plus a daemon retention sweeper enforce the policy even when no operator opens the console; only the purged count is logged
- **test**: `pc-gateway/src/test/kotlin/com/example/relay/pcgateway/GatewayRetentionPolicyTest.kt` — 6 tests: pilot defaults, 0/366-day and sweep-interval rejections naming the env var, configured period drives the purge where the default keeps the record, operator list reports the configured retentionDays; negative test: disabling both init guards made 3 tests FAIL, reverted exactly, full :pc-gateway:test suite green

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

## `gradle-dependency-verification` — Gradle dependency verification (sha256, fail-closed)

- **source**: `gradle/verification-metadata.xml` — 935 components / 1632 sha256 hashes; auto-activates by file presence
- **doc**: `docs/security/DEPENDENCY_VERIFICATION.md` — Local negative test: all-checksum tamper fails build (exit 1); positive build passes (exit 0)

## `release-provenance-attestation` — Build provenance attestation for formal release artifacts

- **workflow**: `.github/workflows/publish-release.yml` — attest-build-provenance step blocks publication on failure; linted locally (actionlint/zizmor)

## `detekt-static-analysis` — detekt static analysis with Relay sensitive-logging rules

- **source**: `config/detekt/detekt.yml` — ForbiddenImport android.util.Log / java.util.Random; baseline gates only new findings
- **workflow**: `.github/workflows/security-baseline.yml` — Local negative test: planted android.util.Log import detected (exit 2); positive run exit 0

## `property-based-testing` — Property-based tests for Gateway signing protocol (kotest-property)

- **test**: `relay-protocol/src/test/kotlin/com/example/relay/gateway/protocol/GatewayIntegrityPropertyTest.kt` — 6 properties (1250 randomized cases/run): round-trip, per-field tamper, unknown key, malformed signature crash-freedom, canonical boundary collisions, JSON key-order independence
- **test**: `relay-protocol/src/main/kotlin/com/example/relay/gateway/GatewayIntegrity.kt` — Mutation check: removing length prefix from CanonicalFields.add caused property failure (no false green), then reverted

## `mutation-testing` — PIT mutation testing gate for relay-protocol (threshold 95)

- **source**: `relay-protocol/build.gradle.kts` — Fail-closed mutationThreshold 95; measured 113/114 killed (99%), 0 NO_COVERAGE; sole survivor is an equivalent mutant (NoOpGatewayMessageSigner.keyId already returns empty string)
- **test**: `relay-protocol/src/test/kotlin/com/example/relay/gateway/protocol/GatewayProtocolEdgeCaseTest.kt` — 10 deterministic survivor-killing tests; negative evidence: gate failed closed at 29% and 75% before test-strength fixes
- **workflow**: `.github/workflows/relay-ci.yml` — mutation job runs :relay-protocol:pitest with SHA-pinned actions and uploads the PIT report artifact; job itself NOT_RUN on GitHub until pushed

## `architecture-tests` — ArchUnit architecture rules for JVM security boundaries

- **test**: `pc-gateway/src/test/kotlin/com/example/relay/pcgateway/ArchitectureTest.kt` — Six bytecode-level rules: relay-protocol purity, Broker never touches javax.crypto, Cipher confined to the rescue crypto boundary, java.util.Random/kotlin.random banned, java.sql confined to the persistence layer, module dependency direction; negative test: planting kotlin.random back into GatewayStore.createPairingCode failed noWeakRandomnessAnywhere naming the exact method, then was reverted
- **source**: `pc-gateway/src/main/kotlin/com/example/relay/pcgateway/GatewayStore.kt` — Real finding fixed: bridge pairing codes were generated with kotlin.random; now SecureRandom (same 6-digit space)

## `broker-api-contract` — Broker OpenAPI 3.1 contract with Schemathesis conformance gate

- **source**: `docs/api/broker-openapi.yaml` — OpenAPI 3.1 contract for all 8 Broker endpoints written from BrokerServer.kt: every documented status code, kotlinx-accurate schemas (additionalProperties:false matches ignoreUnknownKeys=false), scoped Gateway credential + X-Gateway-Id and device capability token security schemes
- **test**: `tools/api-contract/run_schemathesis.py` — Fail-closed gate boots the real :broker:installDist output (DEVELOPMENT profile, throwaway SQLite, per-run random legacy key never logged) and runs schemathesis conformance checks; local run 745/745 test cases passed across 8/8 operations; negative test: a planted false spec claim (health status const healthy vs actual ok) failed response_schema_conformance with exit 1, then was reverted and re-run green
- **workflow**: `.github/workflows/relay-ci.yml` — api-contract job with SHA-pinned actions and schemathesis==4.24.3 uploads the JUnit evidence artifact; job itself NOT_RUN on GitHub until pushed; actionlint and zizmor exit 0

## `broker-resilience-load` — Broker network-fault resilience (Toxiproxy) and concurrent load gates

- **test**: `broker/src/test/kotlin/com/example/relay/broker/BrokerToxiproxyResilienceTest.kt` — Real Netty Broker (PRODUCTION profile) behind a real Toxiproxy container (ghcr.io/shopify/toxiproxy:2.12.0): 800ms latency toxic with elapsed>=700ms proof, resetPeer then retry stores exactly once, timeout-0 black-hole then retry stores exactly once; 3/3 passed locally against Docker 29.4.3; negative test: latency toxic set to 0ms failed with 'latency toxic must actually delay the response (took 47ms)', then was reverted and re-run green
- **test**: `broker/src/test/kotlin/com/example/relay/broker/BrokerConcurrentLoadTest.kt` — 144 signed uploads over 16 threads with the production 30/min per-device rate limiter ACTIVE (429 = failure): 12 devices x 8 unique + 4 retry-storm devices x 12 identical racers; zero errors, exactly one 201 per contended envelope, store count 100; measured requests=144 wall=5257ms throughput=27.4req/s p50=205ms p95=3457ms; negative test: planted idempotency-key regeneration bug failed with 'exactly one racer may win Stored expected:<1> but was:<12>', then was reverted
- **workflow**: `.github/workflows/relay-ci.yml` — RELAY_REQUIRE_DOCKER='true' on the build job makes the Toxiproxy tests FAIL (never silently skip) if the runner loses Docker; locally without Docker they report skipped, never passed; actionlint and zizmor exit 0

