# Gateway boundary adapters

This document defines the integration seam for the two optional, GPL-sensitive
sidecars. They are separate processes; no adapter code is linked into Relay
core or `pc-gateway`.

## Meshtastic

Run `python -m gateway_meshtastic_adapter.main --mock` for fixture and CI use,
or `--mesh-port <serial-device-or-host>` for the separately installed
Meshtastic package. The process reads one JSON object per stdin line and emits
one JSON status per stdout line. A valid input has `requestId`, `shelterId`,
`urgency`, `coarseLocation`, `payloadHash` (SHA-256 hex), `createdAt`, and
optional `ttlSeconds`. The adapter enforces the 220-byte encoded limit and
TTL, and suppresses repeats using `requestId + payloadHash` until TTL expiry.
The cache is process-local; durable dedupe remains the PC Gateway's job.

The adapter output is not a new core protocol. A supervising process maps an
`accepted` event to the existing authenticated Gateway HTTP boundary:
`POST /api/sync/messages`, preserving the existing JSON request envelope and
protocol version. Do not write the adapter directly to the SQLite database.
Use `/api/health` for readiness and `/api/sync/receipts` for delivery status.

## BPv7 export

Run `python gateway-bp7-export/bp7_export.py` and send JSONL records with
`messageId`, `ttl`, `payloadHash`, and `priority`. The output is a deterministic
BPv7-shaped export fixture. This tool is export-only: it does not receive BPv7,
claim receipts, perform dedupe, or change Relay delivery semantics.
`gateway-bp7-export/fixtures/golden-relay-envelope.json` is the canonical
golden fixture.

## GPL boundary

Any GPL-licensed Meshtastic/BPv7 implementation must remain behind the
stdin/stdout JSONL process boundary. Distribution may include an adapter
launcher and these contracts, but must not link GPL code into the Kotlin
Gateway, Android app, or shared core. The sidecar may call the Gateway over a
configured authenticated HTTP endpoint; it must not open or mutate the Gateway
SQLite file.
