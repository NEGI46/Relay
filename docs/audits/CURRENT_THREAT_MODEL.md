# Current threat model

Audit date: 2026-07-19. The model describes current implementation boundaries, not an assertion of production safety.

## Assets and trust boundaries

| Asset / boundary | Current protection | Residual risk |
|---|---|---|
| Local messages, receipts, rescue envelopes | Room/SQLCipher; passphrase encrypted by Android Keystore AES-GCM | Rooted/compromised device, backup/restore behavior, keystore invalidation, and migration failure require device evidence. |
| Message authenticity | REPORT messages have canonical ECDSA P-256 verification paths; receipt types carry distinct trust ranks | Payload transfer success alone is not a signed remote receipt. Unsigned/legacy data must remain visibly unverified. |
| Nearby peer exchange | Android Nearby permission gate, size limits, application validation/deduplication | Auto-accept with available authentication digits does not bind a human or long-term peer identity. |
| LAN gateway discovery | Strict JSON shape/version/path/port checks | UDP beacons are unauthenticated and replayable/spoofable on the LAN. |
| Gateway/public ingress | Explicit anonymous ingress path and receipt handling | LAN HTTP and discovery need an operator trust bootstrap; gateway availability is not delivery proof. |
| CI/release inputs | TUF contract checks; scanner/signature scripts now report `BLOCKED` rather than success when unavailable | No current evidence of an actual SBOM, vulnerability scan, MobSF scan, or cosign bundle verification. |

## Attacker and failure matrix

| Actor or event | Plausible action | Current mitigation | Status / next control |
|---|---|---|---|
| Nearby attacker | Advertises a look-alike endpoint or accepts an auto-accepted connection | Required permissions, Nearby authentication-code availability, payload limits, validation, and unverified receipt state | P1: choose OPEN/TRUSTED policy and bind a trusted peer identity; ADR-001. |
| LAN attacker | Sends a valid-shaped UDP beacon pointing to an attacker-controlled endpoint | Field validation only | P1: authenticated/pinned gateway discovery, TOFU/QR onboarding, and explicit trust UI; ADR-002. |
| Replay/duplicate sender | Replays a payload or receipt | Message IDs, dedupe, TTL/age/path validation, receipt ordering | Verify with long-duration and multi-device tests; not a remote-persistence guarantee. |
| Parser-fuzz input | Sends malformed/oversized frames or JSON | Decoder regression, size limits, validation | Deterministic regression PASS; real Jazzer fuzzing is NOT_RUN because no target exists. |
| Local crash during DB migration | Interrupts replacement after legacy plaintext was moved | Backup/restore path and encrypted `integrity_check`/checkpoint | New injected-failure test compiled but latest AVD execution is BLOCKED. |
| CI supply-chain attacker | Moves an action tag or compromises a dependency/release input | Limited token permissions, report artifacts, TUF contract tests | P2: pin action SHAs; add dependency verification/locking and a real scanner installation lane. |
| Misconfigured release pipeline | Omits scanner, signing key, bundle, or artifact | CI now fails rather than claiming a successful verification | P0 remediation implemented; evidence remains BLOCKED until provisioning is complete. |
| Android/OEM lifecycle failure | Kills service or suppresses restart | Foreground service, activation store, boot/package/Bluetooth receiver | P2: physical-device/OEM recovery matrix is still required. |

## Non-claims

- A Nearby payload-transfer completion is only transport completion.
- A local pending-state transition is not proof of remote durable persistence.
- Passing virtual BLE, host, or emulator tests is not physical radio, gateway, or disaster-field validation.
- A `BLOCKED`, `NOT_RUN`, or skipped optional dependency is never interpreted as PASS.
