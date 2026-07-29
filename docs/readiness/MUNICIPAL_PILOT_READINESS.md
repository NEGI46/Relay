# Municipal pilot readiness

Status date: 2026-07-22. This is a code/configuration readiness record for limited-area training and joint demonstration. It is not an emergency-service certification, a 119 alternative, or a claim of reliable real-disaster rescue.

## Implemented in this repository

| Area | Implemented boundary | Why it matters |
|---|---|---|
| Gateway profiles | Explicit `development` / `lab` / `production`; omitted profile defaults to production loopback, no anonymous ingress, no UDP discovery, no remote management | Prevents an omitted environment variable from becoming a LAN-wide anonymous service |
| LAN/remote management | Non-loopback use requires explicit closed-network or TLS-reverse-proxy mode; remote browser management requires TLS-proxy mode and secure session cookies | Moves exposure from accidental default to documented topology choice |
| Android transport | Main/release/pilotRelease deny cleartext; HTTP Gateway paths are debug/localDev-only and runtime-gated | Prevents release builds from silently sending data or credentials over HTTP |
| Gateway staff access | Named local accounts, `ADMIN`/`OPERATOR`/`VIEWER`, bootstrap-only first admin, PBKDF2 password hashes, hashed expiring sessions, HttpOnly/SameSite cookies | Replaces a shared browser/admin secret with accountable least privilege |
| Audit | Durable minimal audit metadata and ADMIN-only search/CSV export | Supports an operator review trail without placing rescue contents, GPS, or secrets in the audit log |
| Broker isolation | Per-Gateway/per-shelter credential hash, expiry, revocation, scope checks for pull and receipt upload, one-time issuance CLI | Limits a credential leak to its assigned shelter/Gateway instead of all shelters |
| Key-file boundary | Owner-only permission/ACL verification before local rescue private key import; production/lab refuse an unprovisioned file; expiry health warning | Fails closed on a readable or silently replaced key file without falsely claiming DPAPI/HSM/KMS protection |
| Release workflow | No debug APK publication; formal release requires Android signing, Authenticode, SBOM, vulnerability evidence, cosign/TUF verification, checksum and source commit | Prevents an unsigned/local/debug artifact from being labelled a formal release |
| Evidence policy | BLOCKED/NOT_RUN evidence remains non-PASS; hardware-independent contracts cover profile, authorization, session, audit, scope, and workflow rules | Keeps missing tooling or hardware visible instead of converting it to green evidence |

## Implemented but not yet physical-device validated

The code has safety boundaries, but the following are **NOT_RUN** unless a dated field record says otherwise:

- Android model/OS matrix, notification denial, foreground-service restoration, reboot, low-battery, and OEM power-saving behavior
- Nearby/BLE delivery, two-device and three-device multi-hop behavior, discovery reliability, and radio coexistence
- Mobile network → HTTPS Broker → Gateway delivery, receipt return, timeout/retry, and TLS proxy behavior
- Closed-network LAN outage/recovery, Broker outage/recovery, Gateway power loss/restart, and database/key recovery
- High load, deliberate fake SOS, malformed input, rate-limit effectiveness under actual network conditions
- Windows installer and Android release signature installation on representative endpoints

Passing a JVM, emulator, simulator, or source-contract test does not substitute for those checks.

## Compatibility migration notes

- Existing PC Gateway SQLite files are retained. Starting the updated Gateway adds the access-schema tables; the first named ADMIN must be bootstrapped because an old shared `admin.key` cannot be attributed to an individual.
- `development` retains explicit HTTP/anonymous/legacy-key compatibility. `production` and `lab` reject that path rather than silently preserving it.
- Android debug/localDev retains a missing legacy manual-Gateway scheme as HTTP for local testing. pilotRelease/release intentionally treats a missing scheme as HTTPS and blocks cleartext; use approved HTTPS provisioning instead of editing a production preference.
- A former shared Broker key must be replaced by a one-time issued scoped credential. It cannot be automatically migrated because it has no Gateway/shelter scope.

## External decisions and provisioning still required

See the detailed list in [BLOCKED_BY_EXTERNAL_DECISIONS.md](BLOCKED_BY_EXTERNAL_DECISIONS.md). The current blockers include:

- municipality/fire-service/shelter operational ownership, escalation, stop rules, and communication plan;
- TLS/DNS/reverse proxy/WAF/hosting and a network boundary approved for the demonstration;
- Android organization signing key, Windows Authenticode certificate, cosign/TUF keys, scanner tools, and release approval;
- regional root/public-key directory issuance, private-key rotation/revocation/re-provisioning policy, and protected key storage;
- privacy, retention/deletion, telecom-law, insurance, ongoing copyright/third-party-notice governance;
- actual Android devices, Windows deployment target, optional macOS/Xcode/iOS prerequisites, and field test personnel.

## Why real-disaster use cannot yet be claimed

1. No agreement makes Relay an authorized emergency dispatch or fire-service intake channel.
2. RF behavior, background execution, endpoint availability, and staff response are not proven under field conditions.
3. The Broker is a single-instance implementation; it is not HA, load-tested, or disaster-recovered.
4. Real production certificates, signed release artifacts, HSM/DPAPI/KMS controls, and external monitoring/incident response are absent from this environment.
5. Anonymous/unauthenticated reports remain deliberately distinguishable but are not identity- or truth-verified.
6. Legal/privacy/insurance decisions and continuing copyright/third-party-notice governance remain external responsibilities.

## Gate for a limited-area joint demonstration

Proceed only after a responsible organization signs off on the runbook, configures an explicit profile/topology, initializes named staff accounts, provisions scoped Broker credentials if used, completes the relevant rows in [FIELD_ACCEPTANCE_TEST.md](../runbooks/FIELD_ACCEPTANCE_TEST.md), and records any skipped/failed case as a stop condition or exception. Do not repurpose a demonstration result as a real-disaster service claim.