# Background Relay Mode (ARMED / EMERGENCY_ACTIVE)

This document describes the opt-in "always-ready standby" feature and the disaster
communication mode it can transition into. It is deliberately **not** a
"keep-the-process-alive-forever" feature.

## What this is (and is not)

- **ARMED is not an always-on process.** In normal time the app does **not** keep its
  process running, does **not** run Nearby advertising/discovery, holds no indefinite
  foreground service, schedules no short-period WorkManager polling, holds no indefinite
  WakeLock, and does not periodically scan Bluetooth. ARMED only means: *a durable opt-in is
  persisted and the OS launch routes are wired.*
- **ARMED alone cannot detect a disaster.** Without an external trigger (see
  `DISASTER_ACTIVATION_TRIGGERS.md`), it can only be activated by the limited routes below.
- **No permanence guarantee.** Android, OEM battery managers, and the user can stop the
  service at any time. Nothing here evades a force-stop or a Task Manager kill.

## State model

`BackgroundRelayMode` (in `com.example.relay.background`):

| Mode | Meaning |
| --- | --- |
| `DISABLED` | Not opted in. Auto-start routes are ignored. |
| `ARMED` | Opted in, standing by. Persisted config + OS launch routes only. Nearby is NOT running. |
| `EMERGENCY_ACTIVE` | Disaster communication running under a `connectedDevice` foreground service. |
| `DEGRADED` | Emergency was requested but a prerequisite (Bluetooth/permission/Play services) is unmet. Still *wanted*. |
| `SUSPENDED_BY_USER` | The user explicitly stopped everything. Receivers/workers must not silently restart it. |

`ActivationSource`: `USER_ACTION`, `RESCUE_CREATED`, `RESCUE_RECEIVED`, `BOOT_RESTORE`,
`PACKAGE_REPLACED`, `BLUETOOTH_RESTORED`, `DEBUG_SIMULATION`.

`DegradeReason`: `NONE`, `BLUETOOTH_DISABLED`, `MISSING_BLUETOOTH_PERMISSION`,
`MISSING_NEARBY_PERMISSION`, `PLAY_SERVICES_UNAVAILABLE`, `DEGRADED_NOTIFICATION_VISIBILITY`,
`OS_BACKGROUND_RESTRICTED`.

### Persisted fields (`BackgroundRelayState`)

opt-in, current mode, `emergencyActive` (disaster enabled/disabled), `activationSource`,
`deviceRole`, `operatingMode`, `lastHealthyStartAtEpochMillis`, `lastTransferAtEpochMillis`,
`lastError`, `explicitlyStoppedByUser`, `consecutiveStartFailures`,
`nextRetryAllowedAtEpochMillis`, `degradeReason`.

Persistence uses a dedicated `SharedPreferences` file (`relay_background_relay_state`) via
`SharedPreferencesBackgroundRelayStateStore`. No DataStore migration was performed; the existing
`CommunicationActivationStore` / `RescueAutomationStore` files are untouched for compatibility.

## Transitions

```
DISABLED --optIn()--> ARMED
ARMED --activate(USER_ACTION|RESCUE_CREATED|RESCUE_RECEIVED|...)--> EMERGENCY_ACTIVE
EMERGENCY_ACTIVE --degrade(reason)--> DEGRADED     (still emergencyActive == true)
DEGRADED --recoverFromDegrade()--> EMERGENCY_ACTIVE
EMERGENCY_ACTIVE|DEGRADED --deactivate()--> ARMED (if opted in) else DISABLED
any --suspendByUser()--> SUSPENDED_BY_USER (explicitlyStoppedByUser = true)
SUSPENDED_BY_USER --activate(USER_ACTION only)--> EMERGENCY_ACTIVE (clears the flag)
```

Non-user sources (`BOOT_RESTORE`, `BLUETOOTH_RESTORED`, `RESCUE_*`, …) **cannot** reactivate a
device that is `SUSPENDED_BY_USER`. Only an explicit `USER_ACTION` can.

## Allowed activation routes (no external trigger yet)

- User action (in-app buttons)
- Notification action
- Rescue information created (`RESCUE_CREATED`)
- Rescue information received (`RESCUE_RECEIVED`)
- Reboot restore **only if** the device was `EMERGENCY_ACTIVE` or has an undelivered rescue
- Bluetooth re-enable restore
- Debug build simulation (`DEBUG_SIMULATION`)

## Foreground service ownership — chosen approach

**Option 2: owner/lease on the communication runtime** (see
`CommunicationLeaseManager`). Rationale:

- The existing `RescueDeliveryService` is already a `connectedDevice` foreground service. Adding
  a third unified service would have been more disruptive than reference-counting the single
  shared runtime.
- Previously `RescueDeliveryService.startNearbyRelayForRescue()` started a *second* foreground
  service (`RelayCommunicationService`), risking **double** Nearby transport / advertising /
  discovery / gateway sync. The lease manager removes that second FGS.

`CommunicationOwner` = `USER_COMMUNICATION`, `RESCUE_DELIVERY`, `EMERGENCY_MODE`.

Guarantees enforced by `CommunicationLeaseManager`:

- Single shared runtime start regardless of owner count (single Nearby transport / advertising /
  discovery / gateway sync).
- `acquire` is idempotent (`ALREADY_ACTIVE` when already running).
- `release` stops the runtime only when the **last** owner releases (`STILL_ACTIVE` otherwise);
  one owner stopping never stops another owner's communication.
- Double release is safe (`NOT_HELD`). A failed start holds no lease (`START_FAILED`).

## Restart & process recreation

- `START_STICKY` is **not** applied unconditionally. `RescueDeliveryService` returns
  `START_STICKY` only for an active emergency and `START_NOT_STICKY` on the explicit-stop path.
- On a null-Intent OS recreation the persisted automation opt-in is the gate; the service stops
  itself if not enabled.
- Restore is driven by `restoreDecision(state, hasUndeliveredRescue, source)`: it restores only
  when emergency was persisted or an undelivered rescue exists, and **refuses** after an explicit
  user stop. ARMED-only never restores after reboot.
- Launch routes handled: `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, Bluetooth `STATE_ON`, OS
  service recreation, user action, rescue create/receive.
- Out of scope (intentionally): `LOCKED_BOOT_COMPLETED` / Direct Boot. The encrypted DB, Keystore
  and SharedPreferences were **not** moved to device-protected storage.

### ApplicationExitInfo

At process start `BackgroundRelayManager.onProcessStart()` reads the newest
`ApplicationExitInfo` (API 30+) via `ApplicationExitInterpreter` and records a log-safe
breadcrumb. **The exit reason alone never marks the feature as permanently stopped** — in
particular `REASON_USER_REQUESTED` is ambiguous across OS versions (app update, Recents swipe,
Task Manager). The persisted `explicitlyStoppedByUser` flag is the authority
(`ApplicationExitInterpreter.shouldTreatAsUserStop`).

## Permissions & notifications

Distinguished states: Bluetooth permission missing, Bluetooth disabled, Nearby Wi-Fi permission
missing, Play services unavailable, notification denied, OS background restricted, app Restricted,
user force-stop.

`POST_NOTIFICATIONS` is **not** an OS requirement for starting a foreground service. A missing
notification permission is treated as `DEGRADED_NOTIFICATION_VISIBILITY` (a warning), not a hard
permission failure. The enable screen strongly recommends granting it so the user can see the
running state.

## UI

Settings screen "常時待機（ARMED）" card surfaces: current mode, activation reason, last transfer,
Bluetooth/Nearby status, notification status, Play services status, connected peer count, battery
status, degrade/last-error, and the Android-constraint disclaimer. Actions: enable/disable
standby, start/end disaster communication.

The ongoing disaster notification shows the connected-peer count, and offers "アプリを開く" and
"災害通信を終了" (stop) actions.
