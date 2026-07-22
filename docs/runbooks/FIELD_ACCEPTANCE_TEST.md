# Field acceptance test — limited-area joint demonstration

This checklist is for a supervised training/joint demonstration. It does not certify real-disaster operation. Record device models, OS builds, app/Gateway commit, network topology, test owner, date, result (`PASS` / `FAIL` / `BLOCKED` / `NOT_RUN`), and sanitized evidence for every row. Never record rescue text, GPS, passwords, tokens, or private keys in the evidence.

## Entry gates

- [ ] Joint scope, stop conditions, escalation contacts, and consent/privacy handling approved by responsible organizations
- [ ] `production` profile and intended `lanMode` recorded; health shows no unintended anonymous/discovery/remote-management setting
- [ ] Named ADMIN/OPERATOR/VIEWER accounts tested; no shared `X-Admin-Key` accepted
- [ ] Test data is synthetic and visibly not an emergency request
- [ ] Network boundary, TLS proxy (if used), Firewall, Broker credential ID, and key-expiry status reviewed

## Android matrix

| Device / OEM | Android version | Install / permissions | Notifications denied | Battery saver | Restart / reboot | Result / evidence |
|---|---:|---|---|---|---|---|
|  |  |  |  |  |  |  |
|  |  |  |  |  |  |  |
|  |  |  |  |  |  |  |

## Nearby / BLE / multi-hop

- [ ] Two-device Nearby transfer of a synthetic report; duplicate/replay is not stored twice
- [ ] Three-device multi-hop with a documented path and no claim that transport completion is final delivery
- [ ] BLE/Nearby under screen-off, permission denial, Bluetooth off/on, battery saver, and app restart
- [ ] Unauthenticated discovery/report remains visibly unverified and cannot automatically cause rescue assignment
- [ ] No rescue body/GPS/credential appears in logcat or test evidence

## Gateway and LAN boundary

- [ ] Loopback-only production Gateway starts with anonymous ingress/discovery/remote management disabled
- [ ] Deliberate invalid production configuration stops or reports fail-closed as designed
- [ ] Closed-network LAN test only after approved boundary; anonymous ingress/discovery enabled only if explicitly planned
- [ ] TLS-reverse-proxy test: Relay listener stays loopback, external HTTPS certificate validates, remote management cookie is Secure/HttpOnly/SameSite
- [ ] LAN isolation: remove access to the approved LAN and verify no automatic unsafe fallback/false success
- [ ] Gateway power loss/restart and database recovery using synthetic data
- [ ] Staff role checks: VIEWER cannot export/status-change, OPERATOR cannot administer/audit export, ADMIN can audit; disabled session is rejected

## Broker / mobile network

- [ ] Android mobile network → HTTPS Broker → scoped Gateway pull → signed receipt return
- [ ] Broker stop/restart, DNS/TLS failure, and recovery; UI does not claim final delivery while pending
- [ ] Credential from shelter A is rejected for shelter B pull and receipt upload
- [ ] Revoke scoped credential and verify immediate rejection; issue replacement through approved secret channel
- [ ] Broker health/logs contain no raw credential, rescue plaintext, or GPS

## Adversarial / load cases

- [ ] Synthetic fake SOS and malformed input rate-limit/rejection behavior is observed without involving real emergency agencies
- [ ] Concurrent operator claim/status update preserves one assigned operator and a durable audit entry
- [ ] High-volume synthetic request load has pre-agreed threshold, resource observation, and stop criterion
- [ ] Gateway outage, Broker outage, Internet outage, and recovery produce no false `completed`/rescue claim

## Acceptance decision

| Area | PASS / FAIL / BLOCKED / NOT_RUN | Responsible reviewer | Notes / constraints |
|---|---|---|---|
| Android/OEM matrix |  |  |  |
| Nearby/BLE/multi-hop |  |  |  |
| LAN/Gateway |  |  |  |
| Broker/mobile/TLS |  |  |  |
| Roles/audit/privacy |  |  |  |
| Release/install evidence |  |  |  |
| Overall limited-area exercise |  |  |  |

An overall PASS applies only to the named exercise scope. It does not authorize public, regional, 119, or real-disaster use.
