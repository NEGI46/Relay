# Disaster Activation Triggers (Design)

This is a **design document**, not a claim of working infrastructure. The current repository has
**no** Firebase configuration, no signed-event distribution server, and no operational keys. None
were fabricated for this change:

- No `google-services.json` / Firebase config was created.
- No API keys were invented.
- FCM is **not** wired up and is **not** claimed to work.
- No backend is treated as complete or verified.

Automatic disaster triggers (FCM, JMA XML server-side fetch, Companion Device, fixed BLE gateway)
are intentionally **out of scope** for the required implementation. This document specifies how
they would be added later. Today, `EMERGENCY_ACTIVE` is reached only by the manual/local routes in
`BACKGROUND_RELAY_MODE.md`.

## Candidate triggers

### 1. High-priority FCM
A `HIGH` priority data message could wake the app and request activation
(`ActivationSource` would gain e.g. `FCM_PUSH`). Requires: a Firebase project, a server that
authenticates the alert source, and handling of Doze/App-Standby delivery limits. On Android 12+ a
background FCM wake **cannot** freely start a foreground service (see §Android 12+ below).

### 2. Server-side JMA (気象庁) XML fetch
The Japan Meteorological Agency publishes XML feeds. A **server** (not the phone) should poll and
normalize these, then fan out via the signed channel below. Phones must not scrape JMA directly at
scale. Requires operational hosting and a polling identity.

### 3. Signed ActivationManifest
The authoritative trigger payload is a small signed manifest: `{event_id, area, severity,
issued_at, expiry, nonce}` signed by a municipality/operator key. The device verifies it against
the **existing trust root** (the same verification path used for rescue receipts / shelter keys) —
no new bypass. Replay protection via `nonce` + `expiry`.

### 4. Fixed Gateway BLE PendingIntent Scan
A fixed municipal BLE gateway could advertise a **short** beacon. Android's
`BluetoothLeScanner.startScan(..., PendingIntent)` can wake the app on a hardware-offloaded filter
match without a running process. The advertisement must carry only short data:

- Service UUID
- version
- event digest (truncated hash of the ActivationManifest)

The **full** signed `ActivationManifest` is **not** stuffed into the advertisement. It is fetched
from a **GATT characteristic** after the scan match and verified via the existing trust root before
any activation. This keeps the advertisement within the 31-byte legacy / extended-advertising
budget and keeps the signature off the air until an authenticated GATT read.

> PC BLE Bridge note: adding an auto-activation broadcaster to the existing PC BLE Bridge is only
> in scope when the PC side can be implemented **and tested in the same PR** as the Android side.
> That is not the case in this change, so the PC bridge is unchanged here.

### 5. Companion Device Manager (CDM)
`CompanionDeviceManager` associations allow some background start latitude for an associated
device (e.g. a paired municipal beacon). Requires the association UX and a real companion device to
test.

### 6. Manual activation (implemented today)
User action, notification action, rescue create/receive, reboot/BT restore, debug simulation. This
is the only path that currently reaches `EMERGENCY_ACTIVE`.

## Network-loss degradation
If the alert channel (FCM/JMA/manifest fetch) is unreachable, the system must **not** infinite-loop
retries. It degrades to the local routes and, where a trigger was partially received, to a
high-importance user-actionable notification rather than silently starting.

## Android 12+ FGS start restriction
From Android 12 (API 31) a background context generally **cannot** start a foreground service
(`ForegroundServiceStartNotAllowedException`). Therefore an automatic trigger must either:

- arrive through an allowed exemption (e.g. high-priority FCM within its short allowance, a CDM
  association, an exact-alarm/`PendingIntent`-scan path), or
- degrade to a **high-importance notification with a user action** that starts the service in the
  foreground context.

The app must never assume it can start the FGS from an arbitrary background wake, and must never
retry indefinitely.

## Security threats
- **Spoofed activation** → mandatory signature verification against the existing trust root; reject
  unsigned/expired/replayed manifests.
- **Battery-drain / DoS via forced activation** → rate-limit, honor `expiry`, and keep the
  `explicitlyStoppedByUser` override absolute.
- **Location/privacy leakage** → triggers carry area codes, never precise user location; payload,
  keys, and PII are never logged.
- **Downgrade** → digest in the advertisement must match the GATT-fetched signed manifest.

## Municipal infrastructure required
- A signing authority (municipality/operator) and key distribution to devices' trust root.
- A server to fetch/normalize JMA feeds and emit signed ActivationManifests.
- Optionally, FCM project + fixed BLE gateways + CDM-registered beacons.

None of these exist in this repository today; this document is the plan for adding them safely.
