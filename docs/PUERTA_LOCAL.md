# PUERTA Local

Open `http://127.0.0.1:8080/local-pilot` after `./scripts/Start-Relay-Local-Pilot.ps1`. The page is separate from the authenticated staff console. It displays that it is training-only, not a formal rescue intake, and that anonymous reports are not identity-verified. `-AllowLan` is explicit; without it the Gateway binds to loopback. Do not change Windows Firewall automatically—restrict any drill LAN manually.

The page encrypts a validated `RescuePayload` using the current shelter public key and sends it through `RescueDeliveryIngress`; it never INSERTs a rescue record itself. A completion page says only that this PC stored the request and issued an ID. It explicitly says that staff receipt remains unconfirmed; a successful HTTP response is not a dispatch or staff-acknowledgement claim.
