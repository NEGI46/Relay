# Current code review

Audit date: 2026-07-19
Scope: current branch `agent/zero-operation-relay`, source, tests, scripts, CI, and committed documentation. This is a code and configuration audit; it is not a claim that radio, gateway, or production operations were exercised.

## Fixed in this audit

| Severity | Finding | Evidence and disposition |
|---|---|---|
| P0 | CI watched only `main`, while active development occurred on `agent/zero-operation-relay`. | `.github/workflows/relay-ci.yml`; fixed in `ddee261` and subsequently expanded to watch both `main` and `agent/zero-operation-relay`. PRs remain covered. |
| P0 | Missing Syft, OSV-Scanner, Grype, or MobSF produced `skipped` data and exited 0. A green job could therefore mean no scan ran. | `scripts/run-{syft,osv,grype,mobsf}.ps1`; fixed in `ddee261` and `47ec985`. Report-only mode writes explicit `BLOCKED` evidence; strict mode fails. The CI aggregation consumes normalized status sidecars so raw scanner JSON cannot be mistaken for PASS. |
| P0 | Missing packages, cosign, signatures, or bundles could skip distribution verification successfully. | `scripts/verify-distribution-signatures.ps1`; fixed in `e0f9529`. It now writes `PASS`/`FAIL`/`BLOCKED` JSON; strict mode fails absent verification. |
| P1 | The job named "Parser fuzz regression" ran deterministic Python decoder/BLE regressions, not a Jazzer target. | `scripts/run-jazzer.ps1`; CI label changed to "Deterministic decoder regression" in `ddee261`. Requesting `JAZZER_FUZZ=1` now fails until a real target exists. |
| P1 | A concurrent first use of the SQLCipher passphrase store could create inconsistent keys/passphrases across store instances; migration did not validate the encrypted copy before replacement. | `SqlCipherPassphraseStore.kt`, `PlaintextDatabaseMigration.kt`; fixed with concurrent-initialization and injected-failure tests in `97a9970`. Android execution remains blocked by the current AVD health; see validation report. |
| P1 | The UDP LAN beacon is unauthenticated. Any host that can send a syntactically valid beacon can steer discovery to its address. | `GatewayLanDiscovery.kt`; no protocol change made. See `ADR-002-gateway-discovery-trust.md`. |
| P1 | Nearby Connections authentication digits are available but incoming connections are auto-accepted without a human confirmation or a previously established trust binding. | `NearbyConnectionsTransport.kt`; no protocol change made. See `ADR-001-open-relay-trust-model.md`. |

## Open findings

| Severity | Finding | Why it matters | Next action |
|---|---|---|---|
| P1 | Transport payload-transfer completion is not a remote durable-persistence receipt. | A UI/state layer must not translate it into "delivered" or "sent to a remote system". | Preserve the existing receipt model; add user-facing wording tests when delivery UI is expanded. |
| P2 | Actions use movable tags such as `actions/checkout@v4`. | A tag can move; CI source integrity is weaker than a full SHA pin policy. | Add actionlint/zizmor and pin actions in a dedicated supply-chain change. |
| P2 | No committed Gradle dependency-verification metadata or dependency locks were found. | Dependency resolution is not independently pinned by checksum/lock data. | Bootstrap verification and locks in a reviewed change; do not generate them blindly during this audit. |
| P2 | Security scanners are intentionally failing `BLOCKED` until their binaries/secrets are provisioned. | This is correct evidence handling, but it means vulnerability/SBOM/MobSF/production-signature assurance is not yet available. | Install and pin scanners, configure MobSF secrets and signing bundle production, then collect a known-good CI run. |
| P2 | Rescue restart reacts to boot/package/Bluetooth broadcasts, but this audit did not establish an end-to-end recovery contract across process death, notification denial, and OEM background restrictions. | Android background behavior differs by device/vendor. | Add an instrumented recovery matrix and physical-device evidence. |
| P3 | `UdpGatewayDiscoveryTest` used a one-shot packet after a timing delay and flaked in the baseline aggregate run. | It made a valid behavior look failed intermittently. | `ddee261` repeats the test beacon; re-run the Gradle test after the host loopback issue is resolved. |

## Positive evidence, with boundaries

- `NearbyConnectionsTransport` uses suspending `emit` for connection, payload, and transport events, so it does not silently discard events merely because a shared-flow buffer is full.
- Gateway sync persists qualifying receipts before clearing pending work in `GatewaySyncEngine`. This is persistence ordering, not proof that a remote gateway received data.
- SQLCipher passphrases are stored encrypted under an Android Keystore AES-GCM key; the new initialization path persists synchronously before returning the generated passphrase.
- Decoder, virtual BLE simulator, TUF metadata-chain, BPv7 export, Meshtastic adapter, accessibility, implementation-contract, and host checks each have local tests. Their individual status is recorded in the validation report rather than being generalized to deployment readiness.

## Audit limits

- No production key, secret, network endpoint, or physical disaster/radio deployment was used.
- Git object-store cleanup was not performed. `git count-objects -vH` reported roughly 611 MiB packed objects and 23 MiB garbage plus temporary object warnings; cleanup needs explicit repository-maintenance authorization.
- This report deliberately does not label scanner absence, emulator failure, skipped optional dependencies, or device non-coverage as PASS.
