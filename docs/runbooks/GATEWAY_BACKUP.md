# PC Gateway backup and restore

The Gateway database is protected at rest by the host filesystem and must be
exported only as a Litestream replica encrypted with `age`. Never copy a raw
SQLite database into `distribution/` or attach it to an issue. A database
backup is **not** a key or credential backup: do not include rescue private-key
files, bootstrap secrets, staff passwords, Broker credentials, age identities,
or reverse-proxy/TLS private keys in the archive.

## Backup

Install `litestream`, `age`, and `age-keygen` from the release toolchain. Create
an age identity offline and record only the recipient public key in the
operator secret store:

```powershell
age-keygen -o gateway-backup.key
age-keygen -y gateway-backup.key
```

Run the repository script with one or more recipients:

```powershell
./scripts/backup-gateway.ps1 -Action Backup `
  -DatabasePath $env:RELAY_GATEWAY_DB `
  -AgeRecipient 'age1...' `
  -BackupRoot 'D:\RelayBackups'
```

The script creates a short-lived Litestream file replica, archives it, encrypts
the archive as `.tar.age`, and writes a sidecar SHA-256 manifest. Temporary
replicas are removed in a `finally` block. `-DryRun` checks the command boundary
without creating an archive.

## Restore

Restore to a stopped Gateway host, verify the sidecar checksum before opening
the database, and keep the original database outside the target path until the
restored instance passes its startup and message-count smoke checks:

```powershell
./scripts/backup-gateway.ps1 -Action Restore `
  -InputArchive 'D:\RelayBackups\relay-gateway-*.tar.age' `
  -AgeIdentity 'D:\Secrets\gateway-backup.key' `
  -DatabasePath $env:RELAY_GATEWAY_DB
```

The restore path is fail-closed when `age`, the identity, the archive, or
Litestream is missing. The automated smoke test is
`tests/gateway-recovery/backup_recovery_smoke.py`; its real age round-trip is
skipped rather than faked when the external binary is unavailable.

## Operational rules

- Keep the age identity offline and separate from the backup destination.
- Use at least two recipients for an operational backup when policy permits.
- Test a restore periodically on an isolated copy.
- Restore only the database. Re-provision rescue keys and Broker credentials
  through their controlled procedures; never recover them from a database
  backup.
- Do not log database paths containing secrets, age identities, or plaintext
  payloads.
