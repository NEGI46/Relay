# Local pilot ingress (PUERTA Core)

This is for personal development and evacuation drills only. It is not a 119 replacement and does not guarantee dispatch or rescue. Every new input is converted to the existing `RescuePayload`, encrypted into an `EncryptedRescueEnvelope`, and accepted through `RescueDeliveryIngress` / `RescueIntakeService`; it never writes a rescue request directly to SQLite. That reuses existing TTL, request/version duplicate handling, durable-before-receipt behavior, and payload validation.

`LOCAL_WEB` provenance is stored outside the encrypted rescue body in `puerta_ingress`. It contains only request ID, source channel, assurance, and timestamp—never a name, address, GPS, text, ciphertext, token, browser identity, or IP address. Existing requests without metadata render as `LEGACY / UNVERIFIED`, never as device-signed. The explicit migration creates the metadata table and records schema version 1. It uses neither destructive migration nor database recreation.

The endpoint requires JSON, a bounded request body, and a same-Origin check whenever the browser supplies `Origin`. It is rate-limited through the existing anonymous-ingress limiter. Invalid enum values, malformed JSON, invalid payloads, expired delivery, and duplicate requests receive safe responses without logging report content.

Production always disables the local-pilot page and API with 404. Development and explicitly selected lab profiles can enable it only with `RELAY_LOCAL_PILOT_INGRESS=true`; Gateway listeners remain loopback by default, and LAN exposure is an explicit launcher option. Migration failures stop startup; Relay never deletes an existing database to migrate it.
