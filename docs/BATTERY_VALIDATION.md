# Battery Validation

Battery/thermal behavior of disaster mode has **not** been measured on real hardware in this
change. This document records that honestly and specifies the metrics collected plus the
configuration boundary reserved for a future, measurement-driven power-saving policy.

## Guiding rule

- In `EMERGENCY_ACTIVE`, **reachability is prioritized over battery**. We do **not** introduce any
  algorithm that stops Nearby discovery on an arbitrary interval without real measurements.
- Any future throttling must be justified by on-device measurement first.

## Metrics collected (log-safe, no PII)

The following are recorded as diagnostics / persisted state so a device log can explain behavior.
They **never** include payload bodies, precise location, secret keys, or personal information.

- Emergency start time (`lastHealthyStartAtEpochMillis`).
- Advertising / discovery start (via the communication runtime / supervisor state).
- Connected peer count (surfaced in the notification and the settings UI).
- Last transfer time (`lastTransferAtEpochMillis`).
- Reconnect count (runtime-level).
- Bluetooth errors (`DegradeReason`, diagnostics breadcrumbs).
- Battery level and charging state (settings UI reads `ACTION_BATTERY_CHANGED`).
- Thermal state (to be read via `PowerManager.getCurrentThermalStatus()` on API 29+ when the
  metrics collector is extended).
- OS termination reason (`ApplicationExitInfo` → `ApplicationExitInterpreter`).

## Configuration boundary (reserved, not active)

`consecutiveStartFailures` + `nextRetryAllowedAtEpochMillis` already provide a start-failure
backoff (bounded, no infinite retry). A future power policy can hang off the same state store:
e.g. a discovery duty-cycle field would be added to `BackgroundRelayState` and gated behind a
measured threshold. It is intentionally absent today.

## What must be measured on a real device

- Idle ARMED battery cost — expected to be ~0 because nothing runs, but confirm no stray wakelocks
  or receivers fire.
- `EMERGENCY_ACTIVE` drain over 1h / 6h with N connected peers.
- Thermal escalation under sustained advertising+discovery.
- Behavior under OEM battery managers (Doze, App Standby, manufacturer "deep sleep").

Until these are measured, no battery figure is claimed as PASS.
