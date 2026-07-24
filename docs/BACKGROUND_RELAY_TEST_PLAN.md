# Background Relay Test Plan

This plan covers the background relay ARMED/EMERGENCY feature. It separates what is proven by
deterministic JVM unit tests (CI-safe) from what **must** be validated on a real device and is
therefore explicitly **not** claimed as passing here.

## Automated unit tests (JVM, CI-safe)

Run with:

```
./gradlew :app:testDebugUnitTest
./gradlew test
```

### `BackgroundRelayStateMachineTest` (pure transitions)

| Requirement | Test |
| --- | --- |
| DISABLED → ARMED | `DISABLED to ARMED on explicit opt-in` |
| ARMED → EMERGENCY_ACTIVE | `ARMED to EMERGENCY_ACTIVE on user activation` |
| Auto-start by rescue creation | `rescue creation auto-activates disaster mode` |
| Auto-start by rescue reception | `rescue reception auto-activates disaster mode` |
| Bluetooth OFF → DEGRADED | `bluetooth off degrades a running emergency but keeps it wanted` |
| Bluetooth ON → restore | `bluetooth on recovers from degrade back to emergency` |
| ARMED does not degrade (not running) | `armed does not degrade because it is not running` |
| No restart after user stop | `user stop is sticky and non-user sources cannot reactivate` |
| Explicit user re-activation allowed | `explicit user action can re-activate after a user stop` |
| Reboot restore (emergency persisted) | `restore decision restores when emergency was persisted` |
| Reboot restore (undelivered rescue) | `restore decision restores when undelivered rescue exists` |
| Restore refused after user stop | `restore decision refuses after an explicit user stop` |
| ARMED-only does not restore | `armed alone does not restore after reboot` |
| Start-failure backoff + reset | `start failure backs off exponentially and healthy start resets it` |
| Deactivate → ARMED | `deactivate falls back to armed when still opted in` |

### `CommunicationLeaseManagerTest` (owner/lease guarantees)

| Requirement | Test |
| --- | --- |
| Single Nearby transport start | `first acquire starts the runtime exactly once` |
| Multi-start idempotent | `duplicate acquire by same owner is idempotent and does not restart` |
| Second owner joins, no 2nd start | `second owner joins existing session without a second start` |
| Multi-owner stop keeps runtime | `releasing one owner keeps the runtime for the remaining owner` |
| Safe release of non-holder | `releasing an owner that never acquired is a safe no-op` |
| Multi-stop safe | `double release is safe` |
| Failed start holds no lease | `failed start holds no lease` |
| releaseAll stops once | `releaseAll drops every owner and stops once` |

### `ApplicationExitInterpreterTest` (exit-info parsing)

| Requirement | Test |
| --- | --- |
| Null record → no interpretation | `null record yields no interpretation` |
| USER_REQUESTED labelled | `user requested is labelled and flagged user-initiated but not abnormal` |
| Crash is abnormal | `crash is abnormal` |
| USER_REQUESTED alone ≠ permanent stop | `user requested exit alone does NOT force a permanent user stop` |
| Persisted flag is authoritative | `explicit persisted stop flag is authoritative` |
| Unknown code → "unknown" | `unknown reason code degrades to unknown label` |

### Existing regression suites (unchanged, still run)

The existing ACK / Receipt / rescue-delivery / gateway / sync tests are **not** deleted, disabled,
or skipped. `./gradlew :app:testDebugUnitTest` runs them alongside the new tests and the full run
is `BUILD SUCCESSFUL`.

## Instrumented / edge behaviors covered indirectly

- `ForegroundServiceStartNotAllowedException` and `SecurityException`: `RescueDeliveryService`
  catches these on `startForeground`, records a diagnostic + `markStartFailure`, and returns
  `START_NOT_STICKY` (no crash, no infinite retry — degrades to a user-actionable notification).
- Null-Intent recreation: `onStartCommand(null, …)` consults the persisted automation flag and
  stops itself when not enabled.
- Notification denied: treated as `DEGRADED_NOTIFICATION_VISIBILITY`, not a hard failure.
- Permission missing / Play services unavailable: mapped through `NearbyPrerequisiteChecker` →
  `DegradeReason`, surfaced in the UI.

## NOT verified (requires real hardware) — explicitly not PASS

The following are **not** claimed as passing because no physical device / multi-device rig / power
monitor was available in this environment:

- Actual background Nearby advertising/discovery/connect/transfer between two physical phones.
- Real reboot (`BOOT_COMPLETED`) and `MY_PACKAGE_REPLACED` restoration end-to-end.
- Real Bluetooth toggle → DEGRADED → recovery on device.
- Task Manager kill → `ApplicationExitInfo` on the next real launch.
- Battery consumption / thermal behavior (see `BATTERY_VALIDATION.md`).
- `ForegroundServiceStartNotAllowedException` under a real Android 12+ background start.

These are listed in `DEVICE_TEST_CHECKLIST.md` for on-device execution.

## Commands executed in this change

- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL (all unit tests pass).
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (debug APK produced).
- `./gradlew :app:lintDebug` → see CI / lint report.
- API 36 Managed Device tests: not run (no managed device / emulator image available here).
