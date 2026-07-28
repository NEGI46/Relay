# Production-profile Gateway deployment runbook

Use this only for an approved limited-area training/joint demonstration. It does not authorize real-disaster use or public emergency intake.

## 0. Preconditions / stop conditions

Do not proceed if any of these is missing:

- designated shelter/operator owner and documented joint-test scope;
- a unique Gateway ID and shelter ID approved by that owner;
- Windows host, local administrator, backup recipient, and physical access policy;
- either loopback-only operation, an approved closed network, or an approved TLS reverse-proxy topology;
- signed/approved shelter key material and an explicit decision on the local key-file boundary;
- no Public/guest Wi-Fi exposure.

Stop if `/api/health` reports an unexpected profile, anonymous ingress, discovery, remote management, `bootstrap_required` after initialization, or a rescue-key warning. Do not work around a configuration failure by changing code or opening a broader firewall rule.

## 1. Choose profile and topology

| Use case | Required settings | What Relay opens |
|---|---|---|
| local operator console only | `RELAY_PROFILE=production`, `RELAY_GATEWAY_LAN_MODE=disabled`, loopback bind | no inbound LAN port |
| approved closed-network intake | `production`, `closed-network`, explicit non-loopback bind, explicitly selected anonymous/discovery flags | only the approved firewall ports; remote browser management remains off. Android release/pilotRelease does not use plaintext HTTP, so mobile Gateway use still needs an approved HTTPS termination path |
| remote staff console through TLS proxy | `production`, `tls-reverse-proxy`, loopback bind, HTTPS public scheme, `RELAY_GATEWAY_REMOTE_MANAGEMENT=true`, secure cookie | Relay opens no external listener; proxy/network owner opens and protects the external path. Until the explicit switch is true, the TLS-proxy mode serves neither operator UI nor management API, even though the proxy reaches loopback |

`development` is the only profile with compatibility defaults for anonymous ingress, UDP discovery, and legacy `X-Admin-Key`. Never select it for a municipal pilot.

Example loopback-only environment (PowerShell; values are examples, not secrets):

```powershell
$env:RELAY_PROFILE = 'production'
$env:RELAY_GATEWAY_LAN_MODE = 'disabled'
$env:RELAY_GATEWAY_HOST = '127.0.0.1'
$env:RELAY_GATEWAY_PORT = '8080'
$env:RELAY_GATEWAY_ID = 'approved-gateway-id'
$env:RELAY_SHELTER_ID = 'approved-shelter-id'
$env:RELAY_GATEWAY_DB = 'C:\ProgramData\RelayPcGateway\relay-gateway.db'
```

For Windows scheduled startup, use `scripts/setup-pc-gateway.ps1` with its safe defaults. Use `-LanMode closed-network -HostBind <approved-IP>` only after the network owner approves the boundary. `tls-reverse-proxy` is not a substitute for actually configuring the proxy, certificate, DNS, ACLs, and logging outside Relay.

### HTTPS SPKI pin

An HTTPS public endpoint must also set
`RELAY_GATEWAY_TLS_SPKI_SHA256=<64 lowercase hex characters>`. Use the SHA-256
digest of the TLS reverse proxy leaf certificate's SubjectPublicKeyInfo:

```text
openssl x509 -in gateway-cert.pem -pubkey -noout |
  openssl pkey -pubin -outform DER |
  openssl dgst -sha256
```

Copy only the hex value after `SHA2-256(stdin)= `. Verify it with the certificate
owner over an approved out-of-band channel; never learn the initial pin from the
endpoint being enrolled. The Gateway publishes it in the enrollment QR/token, and
Android requires both normal CA/validity/hostname verification and an SPKI match.
Provision the environment variable for the scheduled-task/service account. When a
certificate renewal changes its key, approve and deploy the new pin first, then
regenerate the enrollment QR/token and re-enroll devices.

## 2. Initialize named local staff accounts

Before opening the operator console, create the first `ADMIN` exactly once. Do not put a password in a command-line argument, task definition, source file, or ticket.

```powershell
$env:RELAY_GATEWAY_DB = 'C:\ProgramData\RelayPcGateway\relay-gateway.db'
$secure = Read-Host 'Bootstrap password' -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
  $env:RELAY_GATEWAY_BOOTSTRAP_CLI_SECRET = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
  & 'C:\Program Files\RelayPcGateway\RelayPcGateway.exe' bootstrap-admin --username shelter-admin
} finally {
  [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
  Remove-Item Env:RELAY_GATEWAY_BOOTSTRAP_CLI_SECRET -ErrorAction SilentlyContinue
}
```

If an interactive console is available, omit `RELAY_GATEWAY_BOOTSTRAP_CLI_SECRET` and enter the bootstrap password at the prompt. The `ADMIN` then signs in to the loopback console, creates `OPERATOR`/`VIEWER` accounts, and manages account disablement/role changes. Do not recreate shared admin keys or re-enable `X-Admin-Key`.

The command is intentionally one-time: it fails when any account already exists. Treat a failure as a review event, not a reason to delete the database.

### Migration from an older shared-key Gateway

1. Stop the old process and make an encrypted database backup before changing its profile.
2. Start the updated binary with the same `RELAY_GATEWAY_DB` and `RELAY_PROFILE=production`. The access-schema migration creates staff/session/audit tables without deleting existing message or rescue records.
3. Run the one-time bootstrap command above to create the accountable first ADMIN. Old `admin.key` / `X-Admin-Key` material is intentionally not imported into a person account and is rejected by production.
4. Create named OPERATOR/VIEWER accounts, confirm login/audit/session-expiry behavior, then remove any shared-key environment/file from the production service definition and controlled backup set.
5. Retain the old shared key only in an isolated `development` compatibility setup if an explicit migration test needs it. Do not use it for a pilot.

## 3. Provision rescue keys and verify health

- Place the approved local key file only at the configured path. The Gateway checks owner-only permissions/ACL before import and stops on an unsafe file.
- This repository does not provision DPAPI/HSM/KMS or key rotation. Record the owner, expiry, re-provisioning method, revocation decision, and backup exclusion.
- Start the Gateway and inspect `http://127.0.0.1:<port>/api/health` locally. It must show `production`, the expected LAN mode, expected anonymous/discovery values, `bootstrapRequired=false`, and no unapproved warning.
- Health deliberately omits tokens, password hashes, key paths, rescue content, and GPS.

## 4. Broker credential issuance and revocation

Only use this section after the HTTPS reverse proxy and Broker host are approved. The Broker itself stays loopback-bound in production/lab.

```text
broker issue-gateway-credential --gateway-id approved-gateway-id --shelter-id approved-shelter-id --expires-at 1770000000000
broker revoke-gateway-credential --credential-id <credential-id>
```

Issuance prints `RELAY_BROKER_CREDENTIAL` once. Transfer it through the approved secret channel and set it only on the matching Gateway process together with `RELAY_BROKER_URL=https://…`. Do not save the raw credential in a runbook, shell history, audit log, backup, or support ticket. Revoke immediately on host loss, staff change, suspected leakage, or end of exercise.

## 5. Backup and recovery

- Back up the Gateway SQLite database using [GATEWAY_BACKUP.md](GATEWAY_BACKUP.md) and approved encrypted recipients.
- Do not include rescue private-key files, legacy admin-key files, BLE shared-secret files, raw Broker credentials, environment dumps, or task-runner scripts containing secrets.
- Restore only to an isolated stopped host. Verify checksum, startup, profile, account boundary, and synthetic-data counts before reconnecting it.
- A successful encrypted DB backup is not a private-key backup or a key-recovery design.

## 6. Operational handoff

Record the profile/topology, Gateway/shelter IDs, named staff roles, credential ID (not raw value), key expiry, test date, result, and responsible contacts. Use [FIELD_ACCEPTANCE_TEST.md](FIELD_ACCEPTANCE_TEST.md) for actual test evidence. Any `BLOCKED`, `NOT_RUN`, or failed item remains a deployment constraint.
