# PC Gateway security boundary

Relay PC Gateway is a limited-area pilot component. It is not a public emergency endpoint, a 119 replacement, or evidence that a rescue will occur.

## Profiles and network exposure

| Profile | Default bind | Anonymous ingress | UDP discovery | Legacy `X-Admin-Key` | Intended use |
|---|---|---:|---:|---:|---|
| `production` (default) | `127.0.0.1` | off | off | rejected | approved pilot configuration only |
| `lab` | `127.0.0.1` | off | off | rejected | isolated integration lab |
| `development` | `0.0.0.0` | on | on | compatibility only | local developer testing |

Production/lab LAN use requires explicit `RELAY_GATEWAY_LAN_MODE`:

- `closed-network`: the operator must verify the approved closed network and firewall boundary; remote browser management remains loopback-only.
- `tls-reverse-proxy`: Relay remains bound to loopback; a separately operated TLS reverse proxy provides the only remote browser path. Secure cookies are mandatory.

In `tls-reverse-proxy` mode, `RELAY_GATEWAY_REMOTE_MANAGEMENT=false` blocks the operator UI and management APIs even when the proxy’s backend connection appears as loopback. This prevents a proxy from accidentally turning the default into remote staff access. Use `disabled` mode for a local-only browser console.

Invalid combinations stop startup. The health response exposes only non-secret profile, topology, bootstrap, and warning status. It never emits a key path, token, GPS, or rescue text.

## Local staff authorization

- The Gateway SQLite database contains `staff_accounts`, `staff_sessions`, `gateway_audit_log`, and a schema-migration record.
- Roles are least privilege: `ADMIN` (accounts/configuration/audit), `OPERATOR` (view and rescue status changes), and `VIEWER` (view only).
- Bootstrap needs an explicit one-time secret; there is no default password. Use the local `bootstrap-admin --username <id>` command with an interactive console or a one-command environment secret.
- Passwords use PBKDF2-HMAC-SHA-256 with a unique random salt and 210,000 iterations. Session tokens are random 256-bit values and only their hashes are stored.
- Browser sessions are `HttpOnly`, `SameSite=Strict`, and use `Secure` in TLS reverse-proxy mode.
- Disabled accounts revoke all active sessions. The last enabled `ADMIN` cannot be disabled or demoted.
- The old `X-Admin-Key` path is accepted only in `development`; `lab`/`production` reject it even if an old environment variable is present.

## Audit boundary

The audit log records time, authenticated operator, target ID, action, result, and minimal socket-peer information. It records login success/failure, authorization rejection, views, CSV exports, assignments/status changes, account changes, and a non-secret profile/topology snapshot at startup; a changed snapshot is recorded as `CONFIGURATION_CHANGE`. Audit search/export is `ADMIN` only; exported formula-looking cells are neutralized for spreadsheet safety.

It must not contain rescue body text, GPS, ciphertext, passwords, session tokens, Broker credentials, private keys, or exception text. A target ID is retained only as the minimum record needed to relate an administrative action to an object.

## Trust and rescue decisions

| Route | Route identity | Content trust | Operational meaning |
|---|---|---|---|
| Explicit anonymous ingress | `ANONYMOUS_LAN` | `UNVERIFIED` | storage/relay candidate only; no automatic rescue decision |
| Paired Bridge | `AUTHENTICATED_BRIDGE` | `UNVERIFIED` unless a future issuer registry verifies it | known transport path, not identity or content proof |
| Broker pull | scoped Gateway + shelter credential | encrypted envelope, later local key verification | delivery attempt, not rescue completion |

UDP discovery is not authentication. A beacon can be spoofed. Public/anonymous information must remain visually separated from authenticated operator actions and must never itself assign, close, or trigger a rescue response.

## PC private keys and data protection

The rescue private-key file is still a Base64-encoded local file; this repository does **not** claim DPAPI, HSM, KMS, or Windows Credential Manager protection. Before importing private material, the Gateway verifies an owner-only POSIX permission set or owner-only Windows ACL and fails closed otherwise. `production` / `lab` startup also fails if the key file was not provisioned; it will not silently generate a replacement key. Development-only key creation writes through an owner-only temporary file.

Key expiry is surfaced as a safe health warning. Rotation, revocation, disaster recovery re-provisioning, hardware-backed key storage, and key escrow need external policy and infrastructure; see [BLOCKED_BY_EXTERNAL_DECISIONS.md](readiness/BLOCKED_BY_EXTERNAL_DECISIONS.md).

## Residual risk and required controls

- Anonymous ingress, if explicitly enabled for a closed network, remains unverified and rate-limited—not trusted.
- BLE/Nearby discovery and actual RF behavior need physical-device evidence.
- A reverse proxy, DNS, certificate, WAF, and network ACL are external systems; Relay cannot assert that they are configured correctly.
- A single Gateway/Broker is not HA and must not be described as HA.
- Windows code signing, Android signing, SBOM, scanner evidence, and cosign/TUF evidence are formal-release gates, not current production claims.
