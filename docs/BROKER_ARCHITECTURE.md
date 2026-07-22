# Broker architecture — scoped encrypted-envelope relay

The Broker is an optional delivery-assistance component for a limited-area Relay pilot. It never decrypts rescue envelopes and does not prove shelter receipt, responder dispatch, or rescue completion.

```text
Android ── HTTPS upload ──► TLS proxy ──► Broker (loopback HTTP + SQLite)
Android ◄─ HTTPS receipt ── TLS proxy ◄── Gateway receipt outbox
                                      ▲
                        scoped credential pull / receipt upload
                                      │
                               PC Gateway
```

## Runtime profiles and network boundary

`RELAY_BROKER_PROFILE` (or shared `RELAY_PROFILE`) selects `development`, `lab`, or `production`; the default is `production`.

Core invariants: the Broker never decrypts; it does not increment relay hop count; `BROKER_STORED` is separate from shelter receipt state; routes remain independent; and deduplication plus outbox/retry behavior is at-least-once and idempotent. Android registration proves possession of the P-256 key, rather than treating an upload signature as identity verification.

- `production` and `lab` must bind the Broker to loopback. A non-loopback listener stops startup.
- TLS is not faked in this process. An externally operated reverse proxy, certificate, DNS, firewall/WAF, and hosting decision are required before any external use.
- Android and PC Gateway reject non-HTTPS Broker URLs. Broker health exposes counts and profile only—no credential, rescue content, or personal data.
- `RELAY_BROKER_GATEWAY_API_KEY` is a deprecated shared key. It is accepted only in `development`; its presence in `lab`/`production` stops startup.

## Gateway credentials and shelter isolation

The former shared Gateway Bearer key is replaced by a high-entropy credential scoped to exactly one `gatewayId` and `shelterId`.

```text
broker_gateway_credentials
  credential_id | token_hash | gateway_id | shelter_id | issued_at | expires_at | revoked_at
```

- Raw token entropy is 256 bits. SQLite stores only SHA-256 of the token, never a reversible secret.
- `GET /v1/gateways/{shelterId}/pull` requires `Authorization: Bearer …` and `X-Gateway-Id` that both match the stored scope.
- `POST /v1/gateways/{shelterId}/receipts` additionally requires the payload’s `gatewayId` to match that scope.
- Expired, revoked, unknown, malformed, wrong-Gateway, and wrong-shelter credentials are rejected. A credential leaked from one shelter cannot pull or upload for another.
- The local Broker operator command prints a raw credential only at issuance:

```text
broker issue-gateway-credential --gateway-id <gateway> --shelter-id <shelter> --expires-at <epoch-millis>
broker revoke-gateway-credential --credential-id <credential-id>
```

Capture the issuance output in the approved secret-delivery channel, not a shell history, ticket, log, or repository. Set it on the matching Gateway as `RELAY_BROKER_CREDENTIAL`; it is not written to its outbox/cursor database or health/audit output.

## API and data behavior

| Method | Path | Boundary |
|---|---|---|
| `POST` | `/v1/devices/register` | Android device public-key registration and capability token issue |
| `POST` | `/v1/rescue/upload` | HTTPS encrypted-envelope upload, structural/signature validation, dedupe/collision isolation |
| `GET` | `/v1/gateways/{shelterId}/pull` | scoped Gateway/shelter credential only |
| `POST` | `/v1/gateways/{shelterId}/receipts` | scoped Gateway/shelter credential only |
| `GET` | `/v1/receipts?token=&sinceSeq=` | device capability token only |
| `GET` | `/v1/health` | profile and aggregate non-sensitive queue counts |

The Broker enforces body-size checks, per-device/Gateway rate limits, TTL purge, ciphertext collision quarantine, a composite pull cursor, and receipt sequence cursors. Those controls reduce accidental or cross-shelter exposure; they do not establish sender identity or operational rescue validity.

## Still external / not claimed

- Reverse-proxy TLS configuration and certificate lifecycle
- HA, horizontal scaling, backup/restore testing, load testing, and incident drills
- Regional directory/root-key issuance and Gateway credential governance
- Legal retention/deletion policy, personal-data processing agreement, and incident response ownership

See [production Gateway deployment](runbooks/PRODUCTION_GATEWAY_DEPLOYMENT.md) and [external decision blockers](readiness/BLOCKED_BY_EXTERNAL_DECISIONS.md).
