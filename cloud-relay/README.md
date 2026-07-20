# Cloud Relay

Cloud Relay stores only EncryptedRescueEnvelope JSON and signed receipts. It never receives shelter private keys and never decrypts rescue text or GPS.

Production deployment must terminate HTTPS/WSS, require gateway device-key signatures or mTLS, apply rate limiting, size limits, replay protection, and PostgreSQL TTL cleanup. The PC Gateway uses an outbound pull; no inbound Internet connection to a shelter PC is required.
