# Windows next-work audit (2026-07)

Audit date: 2026-07-24
Author: Windows-only engineering + security-audit pass on branch `quest/windows-readiness-hardening` (base `agent/zero-operation-relay`).

> Scope and honesty rule. This audit was produced entirely on a personal Windows workstation
> (PowerShell, JDK 17, Android SDK API 35/36). It re-verifies code and test state against the actual
> source at this HEAD. A command is `PASS` only when it completed successfully at this code state.
> A missing toolchain, absent device, or unavailable external dependency is `BLOCKED` or `NOT_RUN`
> and is never folded into `PASS`. Nothing here is a device-, field-, pilot-, or production-readiness
> claim. Relay is not an official 119 / fire / police / municipal emergency channel.

## 1. Why this audit exists

The branch README and background-relay documentation describe LAN Gateway enrollment (out-of-band
QR / manual trust bootstrap) as an implemented capability. A source re-read found that the
enrollment **codec, in-memory store, and trust decision** existed and were unit-tested in
`shared` and `app`, but the product path did **not** use them: `RelayApplication` constructed
`GatewaySyncEngine` without the `enrollmentStore` argument, so every discovered LAN beacon was
treated as `UNVERIFIED` regardless of any provisioned identity, and there was no durable store to
survive a process death. This audit records that gap, the Windows-implementable fix applied here,
and the items that remain genuinely blocked on hardware / municipality / certificates.

## 2. Fact-check: claim vs. verified source state

| Area | Documented / expected | Verified source state at HEAD | Verdict |
|---|---|---|---|
| Enrollment codec (`relay-gw:1:...`) | Fail-closed decode, checksum, field validation | Present in `shared/.../gateway/GatewayEnrollment.kt`; unit-tested (`GatewayEnrollmentCodecTest`) | Confirmed |
| Trust decision (pin gatewayId/scheme/port/shelterId, exclude host) | Spoofed beacon refused, matching beacon trusted | Present; `GatewayEnrollmentTrustTest` asserts both paths | Confirmed |
| Enrollment wired into delivery | Discovered beacon upgraded/refused via enrolled identity | `GatewaySyncEngine` **accepted** `enrollmentStore` but `RelayApplication` passed none (null) → always `UNVERIFIED` | **Gap (fixed here)** |
| Durable enrollment | Provisioned identity survives relaunch | No persistent store existed (in-memory `HashMap` only) | **Gap (fixed here)** |
| Enrollment UI / import | Operator can provision an identity | No UI and no headless import entry point | Partially addressed (headless import flow added; camera-scan UI still future) |

## 3. Change applied in this branch (Windows-implementable)

- Added `app/.../gateway/PersistentGatewayEnrollmentStore.kt`:
  - Owns the single in-memory `GatewayEnrollmentStore` and mirrors every mutation to durable
    storage as canonical `relay-gw:1:...` payload strings (SharedPreferences-backed on Android).
  - **Fail-closed load**: each persisted payload is decoded through the existing codec; a tampered
    or truncated entry cannot yield a partial token — it is dropped and the durable set is rewritten
    to quarantine the corruption. A short, non-secret diagnostic code (gatewayId + reason) is emitted.
  - **No silent overwrite**: enrolling a *different* identity for an already-enrolled gatewayId is a
    `Conflict`; rotation must be explicit (`allowRotation = true`). Re-enrolling the identical
    identity is idempotent and does not rewrite storage.
  - **Safe import flow**: `importPayload()` is the headless entry point (paste / scan → decode →
    conflict policy → persist) a future QR-scanner UI can call. Rejections are surfaced, not stored.
- Wired the store into `RelayApplication.gatewaySyncEngine` via the existing `enrollmentStore`
  parameter, so a discovered beacon whose advertised identity contradicts an enrolled gateway is
  now refused (`gateway_trust_rejected`) instead of delivered to.

Status ladder for this change: **AUTOMATED_TESTED** (JVM unit tests only). It is not
`EMULATOR_TESTED`, `DEVICE_TESTED`, or higher — no instrumentation or physical-device run of the
enrollment persistence path was performed.

## 4. Phase 1 — Windows validation baseline

Command: `scripts/validate-windows-development.ps1` (non-strict), full log at
`artifacts/baseline-validation-console.log`, per-check logs under
`artifacts/windows-validation-logs/`. Prerequisites reported:
`androidSdk=True python=True docker=True securityScanners=False`.

Aggregate: **20 PASS / 1 BLOCKED / 0 FAIL.**

| Check | Result |
|---|---|
| shared-jvm-test | PASS |
| android-unit-test | PASS |
| android-instrumentation-compile | PASS |
| pc-gateway-test | PASS |
| broker-test | PASS |
| compose-desktop-test | PASS |
| accessibility-check | PASS |
| implementation-contract-check | PASS |
| host-checks | PASS |
| decoder-regression | PASS |
| virtual-ble-test | PASS |
| jvm-jazzer-regression | PASS |
| windows-launcher-test | PASS |
| windows-autostart-test | PASS |
| windows-setup-test | PASS |
| cp932-ascii-regression | PASS |
| pc-gateway-build | PASS |
| android-debug-build | PASS |
| android-localdev-build | PASS |
| gateway-backup-restore | **BLOCKED** — `litestream` ships no Windows binary; `age` / `age-keygen` not installed |

There are **no mandatory FAILs**, so feature work in Section 3 proceeded on a green baseline. The
single BLOCKED item is a Linux-release-toolchain round trip that cannot run on Windows; it is
correctly reported BLOCKED, not FAIL.

Post-change verification: `:app:testDebugUnitTest` filtered to
`PersistentGatewayEnrollmentStoreTest` (10 tests) and `GatewayEnrollmentTrustTest` — **BUILD
SUCCESSFUL**, `tests=10 failures=0 errors=0` for the new suite (JUnit XML in
`app/build/test-results/testDebugUnitTest/`).

## 5. Remaining work and honest blockers

Windows-implementable next (planned on this branch):
- Phase 2.3: connect a persisted enrollment token to `ShelterManifestEnrollment` so the pinned
  `manifestFingerprint` verifies the shelter public-key manifest during enrollment.
- Phase 3: audit the Nearby TRUSTED-mode trust boundary for parity with the gateway fail-closed rule.
- Phase 4: add automated failure/recovery tests (process death, corrupted store, conflicting re-enroll).
- Phase 5: Broker observability audit (liveness/readiness/metrics gaps).
- Phase 8: reconcile README / docs with the now-wired enrollment path.

Genuinely BLOCKED (not achievable on this workstation; must remain BLOCKED, never faked):
- `gateway-backup-restore` end-to-end (litestream + age; no Windows litestream binary).
- Physical multi-device Nearby / BLE / LAN gateway field runs; emulator/device instrumentation for
  the enrollment path.
- Municipal / shelter production keys, certificates, and any 119/official-channel integration.
- Security scanners (Syft / OSV / Grype / MobSF) and signed-distribution verification.
