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

## 5. Phase 6 — Windows DPAPI at-rest protection (conditional investigation)

The brief asks whether Windows DPAPI (`CryptProtectData` / `CryptUnprotectData`) should wrap the
PC gateway's rescue private-key file. This was investigated against the current source and the
repository's honesty rules; the decision is to **not** implement it in this branch and to record it
as a deferred, externally-gated item rather than ship it silently.

Current at-rest posture (verified at HEAD): `RescueKeyStore` persists Base64 private-key material
in a local JSON file guarded by an **owner-only** boundary — a POSIX `600` permission set or an
owner-only Windows ACL — and **fails closed** if that boundary cannot be verified or enforced
(`pc-gateway/.../rescue/RescueKeyStore.kt`, `verifyOwnerOnly` / `restrictOwnerOnly`). The code and
`docs/PC_GATEWAY_SECURITY.md` already state plainly that it does **not** claim DPAPI, HSM, KMS, or
Credential-Manager protection.

Why DPAPI is deferred, not implemented:

1. **Dependency expansion / portability regression.** The JVM has no standard DPAPI API.
   `CryptProtectData` lives in `crypt32.dll` and would require a new JNA/JNI native binding. The
   project currently has **no** native dependency (verified: no `jna`/`jni`/`crypt32` reference in
   any `*.gradle*`/`*.toml`). Adding one would fork the presently portable `RescueKeyStore` into a
   Windows-only code path and contradicts the minimal-toolchain posture (JDK 17 + Android SDK only).
2. **Threat-model reality.** DPAPI user-scope only guarantees that the *same Windows user account*
   can decrypt the blob. The gateway process already runs as that user, so any code executing in
   that user's session can call `CryptUnprotectData` on the file. DPAPI therefore does **not** remove
   the owner-only ACL requirement; it is defense-in-depth against *offline disk theft or other-user
   reads* — a threat that BitLocker full-disk encryption plus the existing owner-only ACL already
   cover substantially.
3. **False-completion risk.** Shipping a DPAPI wrapper could be misread as hardware-backed / HSM-grade
   protection, which it is not. The honesty rule forbids implying a stronger guarantee than is real.

Windows-implementable mitigation available **today with zero code change** (recommended to operators):
enable **BitLocker** on the volume holding the key file and keep the file under the operator's own
profile so the already-enforced owner-only ACL applies. This closes the offline-theft gap DPAPI would
target, without a native dependency or a false HSM claim.

Status: **BLOCKED_ON_EXTERNAL_DECISION** — adopting DPAPI is an architecture/maintainer decision
(accept a JNA native dependency + a Windows-only key-store fork). It is intentionally *not* silently
implemented. The honest owner-only-ACL + fail-closed posture stands and is unchanged by this audit.

## 6. Remaining work and honest blockers

Windows-implementable work completed on this branch (each a logical commit, JVM-unit-tested only):
- Phase 2.3 (`3a6ce9f`): a persisted enrollment token now pins `ShelterManifestEnrollment` — the
  verified gateway identity's `shelterId` is matched fail-closed before a manifest is saved.
- Phase 3 (`a4b0bd6`): the Nearby TRUSTED-mode allow-list is now enforced on the explicit
  `connect()` outbound path (previously only inbound/auto-initiate were gated), fail-closed, no retry.
- Phase 4 (`5caecb2`): engine-level trust-decision tests — a beacon contradicting an enrolled gateway
  is refused (`gateway_trust_rejected`) and never delivered to; a DHCP-shifted matching beacon is trusted.
- Phase 5 (`aca93c9`): a secret-free `BrokerObservability` seam records coarse security-rejection
  categories (never tokens/keys/ciphertext), giving operators rejection visibility with no log-leak.
- Phase 6 (this doc, Section 5): DPAPI investigated and deferred as `BLOCKED_ON_EXTERNAL_DECISION`.

Still open (planned):
- Phase 8: reconcile README / docs with the now-wired enrollment path and this audit.
- Enrollment camera-scan UI (headless import flow exists; on-device QR capture is future, device-gated).

Genuinely BLOCKED (not achievable on this workstation; must remain BLOCKED, never faked):
- `gateway-backup-restore` end-to-end (litestream + age; no Windows litestream binary).
- Physical multi-device Nearby / BLE / LAN gateway field runs; emulator/device instrumentation for
  the enrollment path.
- Municipal / shelter production keys, certificates, and any 119/official-channel integration.
- Security scanners (Syft / OSV / Grype / MobSF) and signed-distribution verification.

## 7. Final self-audit and verification

Three self-audit passes were run against the branch HEAD before sign-off; all evidence is the
current worktree, not memory of earlier work.

1. **Regression (do the tests pass?).** `:shared:jvmTest :app:testDebugUnitTest :broker:test
   :pc-gateway:test` → **BUILD SUCCESSFUL**. JUnit XML aggregate: **tests=444, failures=0,
   errors=0, skipped=1**. The single skip is `RemoteBrokerTunnelE2ETest` — an external-remote-broker
   E2E gated on infrastructure not present on this workstation; it is an environment gate, not a
   disabled test, and predates this branch.
2. **No weakening (were boundaries or tests degraded?).** Branch diff vs base `3d1a44b`: 16 files,
   +959 / -10. No test file deleted (`--diff-filter=D` empty); no `@Ignore`/`@Disabled` added and no
   `@Test` removed. Every removed main-source line is a signature/interface refactor that *tightens*
   the boundary: `VerifiedManifestStore` seam, the added `expectedShelterId` fail-closed pin, and
   threading `observability` through `authenticateGateway` while its `?: return` fail-closed logic is
   unchanged. Test changes are purely additive.
3. **No secret leakage / honest claims.** A secret-pattern scan over all added lines returns only
   KDoc/comment/test text that *describes* the no-secret guarantee — no key, token, password, or
   ciphertext literal is introduced. Documentation status symbols were reconciled to the
   AUTOMATED_TESTED (JVM-unit-only) reality, with camera-scan UI and device/field runs kept explicitly
   unverified.

Standing scope reminder: everything shipped here is **AUTOMATED_TESTED (JVM unit)** at most. No
EMULATOR_TESTED / DEVICE_TESTED / FIELD_TESTED / PILOT_READY / PRODUCTION_READY claim is made, and
Relay remains not an official 119 / fire / police / municipal emergency channel.
