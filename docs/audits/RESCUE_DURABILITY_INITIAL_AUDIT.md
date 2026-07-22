# Rescue durability and BLE trust audit

Audit date: 2026-07-22
Baseline reviewed: `origin/agent/zero-operation-relay` at `e46a9bd`
Working branch: `codex/rescue-session-durability`

This is evidence-oriented: **IMPLEMENTED is not AUTOMATED_TESTED**, and neither is a
physical-device result.

## CONFIRMED

| Audit check | Baseline finding | Current disposition |
| --- | --- | --- |
| 1. Resolver construction | Android constructed `RegionalShelterDirectoryResolver(emptyList())`. | Replaced by root-loader + persisted-directory runtime wiring. |
| 2. Regional Root distribution | Android had no Regional Root; PC Gateway accepted an externally supplied public root file. | No official Root has been added. Android production/pilot configuration remains empty. |
| 3. Signed Directory flow | Shared had signing/verification models, but Android had no receive/store/reload path. | Room/SQLCipher-backed verified store and import seam added. |
| 4. BLE fingerprint | Bridge advertised the first 9 bytes of signed-manifest SHA-256; Android re-read identity after GATT. | Preserved; resolver still requires an accepted signed Directory. |
| 5. ViewModel state | `activeDraft`, own request state, version, and a location Job were process-memory state. | Moved sender-critical state/versioning to the coordinator and encrypted session store. |
| 6. Room boundary | Envelope store was transactional only for a single envelope. | Session + envelope commits now use one Room transaction and optimistic version CAS. |
| 7. Delivery lifecycle | Connected-device delivery was sticky and restarted from app/boot/package/Bluetooth events. | Preserved for encrypted-envelope delivery; active session restores independently. |
| 8. BOOT_COMPLETED | Existing receiver starts the connected-device service when enabled. | No location FGS has been added or started at boot. |
| 9. Notification denial | No explicit notification-denied recovery UI was found. | Still not addressed; Phase 5/device work remains required. |
| 10. Android 14+ location FGS | No user consent flow, location FGS type, or visible-Activity start discipline existed. | Phase 5 is deliberately NOT_STARTED; old ViewModel periodic tracking was removed. |
| 11. Existing tests | No tests covered persisted Directory or sender-session recovery/CAS. | New contract tests were added; execution evidence is pending. |
| 12. README/code alignment | README implied trusted BLE and restart update/cancel coverage not established by code. | README now distinguishes Phase 0A implementation from blocked Phase 0B and marks device evidence as required. |

## IMPLEMENTED

### Phase 0A — generic trust foundation

- `RegionalRootBundleParser` and `AssetRegionalRootLoader` load public-only root bundles per
  build variant. Missing, duplicate, malformed, private-key-shaped, or unparseable inputs produce
  an empty root set without logging key material.
- `VerifiedRegionalDirectoryStore` validates Root signature, Directory structure/region/current
  validity, generation, every signed Shelter Manifest, and recipient/receipt public-key identity
  before persistence. The resolver binds the accepted signed manifest to the advertised BLE
  fingerprint before BLE use.
- `regional_shelter_directories` persists `(regionId, generation, stable signed digest, JSON,
  acceptedAt)` in the existing SQLCipher Room database. Writes are a Room transaction: failed
  replacement preserves the prior record.
- Older generation is rejected; exact same-generation/digest retry is idempotent; a different
  same-generation signed directory is rejected. An expired persisted directory is retained for
  audit but never trusted; a valid higher generation may replace it.
- `RegionalTrustRuntime` parses the public Root asset at startup, then replays/revalidates accepted
  directories on the application I/O scope before accepting them into
  `RegionalShelterDirectoryResolver`. This avoids Room access from `Application.onCreate()` on the
  main thread. Until replay completes, the resolver is empty and BLE delivery fails closed.
- `ShelterDeliveryCoordinator` compares the scan-advertised fingerprint with the GATT identity
  itself before resolving a Directory entry. The Android BLE client performs the same comparison;
  this duplicate check prevents a replacement/fake client from weakening the invariant.
- Removed startup use of the unsigned bundled Fuchu manifest and removed automatic debug TOFU
  enrollment. Existing explicit enrollment remains the only legacy public-key path.
- Test roots are generated at runtime in test source. No fixed test private key, Root bundle, or
  Directory artifact is added to `main`, `release`, or `pilotRelease` source sets.
- The PC offline operator CLI supplies `generate-regional-root`,
  `export-regional-root-bundle`, `sign-regional-directory`,
  `verify-regional-directory`, and `print-public-fingerprints`. It rejects private root output
  under a Git worktree, uses owner-only secret-file writes, refuses overwrites, and does not print
  secret material. It does **not** establish an official municipal Root.

### Phases 1–4 — sender-session durability

- Added `active_rescue_sessions` (Room schema v8). It stores request/version/status/timestamps
  plus AES-GCM ciphertext and nonce only; rescue body, count, condition, free text, sender ID,
  and location are inside `RescueSessionRecoveryPayload`.
- `AesGcmRecoveryPayloadCipher` uses a distinct Android Keystore alias
  `relay_rescue_session_recovery_v1`, AES-GCM, provider-generated random 12-byte nonces, and
  authenticated decryption. Failures retain the row and surface a recovery failure; they do not
  silently create a new request.
- `ActiveRescueSessionCoordinator` owns initial creation, restoration, update, cancellation,
  expiry marking, version allocation, envelope creation, recovery-payload refresh, and transport
  notification. `RescueViewModel` now supplies UI input/state and has no `activeDraft`, tracking
  Job, or direct `current.requestVersion + 1` write.
- `ActiveRescueSessionCoordinator` encrypts/builds before the short Room transaction, and the
  session store uses a per-request Mutex only to reduce local work while relying on durable
  `latestVersion` CAS for
  process-safe correctness. It commits envelope and session together; conflict/rejection commits
  neither. Active session envelopes are protected from ordinary courier capacity pruning.
- Signed receipt application from BLE, Nearby, local Gateway, and Broker now uses the session
  store transaction, so a verified receipt updates both envelope state and sender session state.
  Transport success/Broker ledger state alone does not advance a shelter state.
- Activity recreation, process restart, and user-launched restart restore the newest active (or
  retained terminal) session. Updates/cancellations rebuild from encrypted recovery state and
  allocate the next version in the coordinator.
- A terminal result stays visible until the sender chooses **I have reviewed this result**. That
  acknowledgement deletes only the encrypted `active_rescue_sessions` row; it never converts the
  result into a new request or silently deletes corrupt recovery material.

### Phase 5 — intentionally excluded

- Continuous background location tracking, location FGS declaration, background location
  permission, and WorkManager GPS scheduling are **not** implemented in this change.
- The prior ViewModel minute loop was removed. The restored UI explicitly reports that location
  updates are stopped. A separate Phase 5 review must implement explicit consent, foreground
  start while visible, Android 14+ restrictions, distance/time/accuracy policy, and terminal stop
  conditions.

## AUTOMATED_TESTED

Test code added or extended:

- Shared trust tests: valid root/directory, old generation, same-generation equivocation,
  invalid manifest signature, recipient/receipt key-ID mismatch, and signed-manifest fingerprint
  mismatch. An Android JVM coordinator contract additionally rejects a mismatch between the BLE
  advertisement identity and the GATT identity before submission.
- Android JVM trust tests: root parsing, absence/malformed/unknown root failure, current-time
  validation, rollback, idempotence, corrupt persistence, failed replacement retention, no-root
  startup, and no test/private material in main/release/pilot assets.
- Session tests: AES-GCM round trip, distinct nonce, tamper rejection, wrong alias rejection,
  create/restore/update/cancel, concurrent coordinator updates, terminal/expiry refusal, corrupt
  recovery retention, failed commit atomicity, and terminal acknowledgement/removal.
- Existing ViewModel tests were moved onto the coordinator seam. SQLCipher instrumentation test
  now also checks that broker-ledger and active-session tables survive a close/reopen.
- `verifyNoTestTrustArtifactsInReleaseApks` builds both release-derived APKs and rejects any
  packaged test asset, `TEST ONLY` trust content, or private-Root JSON field.

Executed from `C:\Users\matubayasi\Documents\Relay` on 2026-07-22 (the client time limit
expired for some commands, but the Gradle daemon recorded the successful result):

```text
.\gradlew.bat --offline :shared:jvmTest :pc-gateway:test --stacktrace
BUILD SUCCESSFUL in 38s

.\gradlew.bat --offline :pc-gateway:test --stacktrace
BUILD SUCCESSFUL in 1m 8s

.\gradlew.bat --offline :shared:jvmTest :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --stacktrace
BUILD SUCCESSFUL in 2m 32s

.\gradlew.bat --offline :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --stacktrace
BUILD SUCCESSFUL in 2m 2s

.\gradlew.bat --offline :app:assembleRelease :app:assemblePilotRelease --stacktrace
BUILD SUCCESSFUL in 11m 49s

.\gradlew.bat --offline :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --stacktrace
BUILD SUCCESSFUL in 1m 55s

.\gradlew.bat --offline :app:verifyNoTestTrustArtifactsInReleaseApks --stacktrace
BUILD SUCCESSFUL in 2m 38s

.\gradlew.bat --offline :broker:test --stacktrace
BUILD SUCCESSFUL in 32s
```

Observed XML results: shared JVM **20/20**, Android JVM **201/201**, PC Gateway **57/57**, and
Broker JVM **28/28** tests passed; failures and errors were zero. Android instrumentation source, including
the Room migration contracts, compiled successfully, but was not executed on an emulator or
physical device. The generated `release` and `pilotRelease` APKs contained no regional/root/test/
private asset entry.

## DEVICE_TESTED

**None.** No physical Android device, Windows PC, BLE sidecar, mobile-network path, OEM power
policy, or Android 14+ location-FGS trial was available in this workspace. Do not mark any of the
following as PASS yet:

- Android→PC LAN, Android→PC BLE, Android→Android Nearby, A→B→PC multi-hop;
- mobile network→Broker→Gateway, reverse receipt delivery, screen-off/battery optimization;
- normal process death versus `adb shell am force-stop`, device reboot, Wi-Fi/Bluetooth/Broker
  loss/recovery; or
- official public Root/Directory acceptance against an actual Gateway.

## BLOCKED

### Phase 0B — `BLOCKED_BY_EXTERNAL_TRUST_ARTIFACTS`

The following public artifacts are not in the repository and must be supplied by the authorized
offline operator:

1. official `RegionalRootBundle` JSON;
2. Root-signed `SignedRegionalShelterDirectory` JSON;
3. actual Gateway recipient public key;
4. actual Gateway receipt-signing public key;
5. reviewed `regionId`, `generation`, `issuedAt`, and `validUntil`; and
6. operator-confirmed public-key and signed-manifest fingerprints.

The Regional Root private key is explicitly **not** requested, copied, generated for production,
or committed. After public artifacts arrive, import them into the intended build variant, run the
Directory acceptance tests, validate the actual BLE advertisement/GATT identity, and execute the
device matrix above.

## NOT_RUN / remaining work

- Android instrumentation execution, including Room migration, SQLCipher reopen, process-death,
  notification-denial, permission-denial, GPS-disabled, and log-inspection cases, requires an
  installed/emulated Android target. Only its source compilation ran here.
- PC Gateway/Broker/BLE simulator integration and all physical-device tests remain unrun; this
  does not negate the separate PC Gateway and Broker JVM test results above.
- Existing notification-denial behavior needs an explicit test and user recovery path.
- Migration test from a schema-v6 encrypted database through v7/v8 must be run with real schema
  snapshots, including preservation of `rescue_envelopes` and `broker_ledger`.
- Phase 5 needs its own review/commit/PR; do not add location FGS from BOOT_COMPLETED or use
  WorkManager for short-period GPS.
- The offline CLI needs operator-host validation on the target Windows/offline filesystem ACL
  model before use.

## Self-audit record

### Design audit

- ViewModel no longer owns active request/version/tracking state: **implemented**.
- Restart update/cancel uses encrypted recovery state: **implemented; Android JVM-tested**.
- Version collisions use DB CAS and session/envelope transaction: **implemented; concurrency
  tested on Android JVM, real Room instrumentation execution pending**.
- Session payload is not a plaintext Room/SharedPreferences column: **implemented; Android JVM
  and source-level instrumentation contracts tested/compiled**.

### Android constraint audit

- No location FGS is started from boot or background in this change: **confirmed**.
- Force-stop behavior is not claimed: **device test required**.
- Notification denial remains unverified: **not complete**.
- WorkManager is not used for short-period tracking: **confirmed for this change**.

### Relay semantics audit

- Peer transfer and Broker storage do not become shelter acceptance: **preserved**.
- Only verified signed receipts advance sender-session shelter state: **implemented**.
- BLE Gateway identity requires Root→Directory→Manifest→fingerprint checks: **implemented,
  blocked from real-world validation by public artifacts**.
- Nearby/LAN/Broker routing paths remain separate from absent BLE Root artifacts: **confirmed by
  wiring review; execution test pending**.

## Status

```text
PHASE_0A_STATUS=PARTIALLY_AUTOMATED_TESTED
PHASE_0B_STATUS=BLOCKED_BY_EXTERNAL_TRUST_ARTIFACTS
PHASE_1_STATUS=PARTIALLY_AUTOMATED_TESTED
PHASE_2_STATUS=PARTIALLY_AUTOMATED_TESTED
PHASE_3_STATUS=PARTIALLY_AUTOMATED_TESTED
PHASE_4_STATUS=PARTIALLY_AUTOMATED_TESTED
PHASE_5_STATUS=NOT_STARTED
OVERALL_STATUS=READY_FOR_DEVICE_TEST_WITH_TRUST_ARTIFACT_BLOCKER
```
