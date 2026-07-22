# Blocked by external decisions

These items cannot be completed honestly from this source workspace. They are not hidden TODOs and must not be labelled PASS, production-ready, HA, or field-verified until the named owner supplies evidence.

| Blocker | Why code alone cannot resolve it | Required owner/input | Safe next step |
|---|---|---|---|
| iOS / IPA / CoreBluetooth | iOS build, signing, and actual Bluetooth behavior require macOS, Xcode, Apple Developer signing, and iPhone hardware | Apple account owner and test devices | Keep iOS as `BLOCKED`; run a separate signed-device test plan on macOS |
| Android organization signing | A production Android signing key must not be generated or invented here | Organization release owner; GitHub secret governance | Provide managed keystore/alias/password secrets and approve `assembleRelease` evidence |
| Windows Authenticode | An Authenticode certificate and timestamp policy are organization assets | Certificate owner/PKI team | Provide PFX/certificate access through approved CI secrets; verify on representative Windows endpoints |
| TLS/DNS/reverse proxy/WAF/hosting | Certificates, routing, firewall, WAF, availability, and municipal network segmentation are real infrastructure | Hosting/network/security owner | Build a documented test topology; keep Broker/Gateway loopback until it is approved |
| HSM / DPAPI / KMS | This repository only verifies local file permissions; it does not create OS/cloud key custody | Security architecture/key-custody owner | Choose custody, rotation, recovery, and access-review design; then integrate/test it |
| Regional root, directory, public keys | Trust anchors and shelter directory entries need an authorized issuer and change process | Municipality/region trust authority | Issue signed public-only root/directory material; do not rely on debug TOFU or hand edits in pilot release |
| Gateway/Broker credential governance | Credential identity, expiry, revocation, delivery channel, and incident response need an operator | Broker operator and shelter owner | Use the local scoped credential command only in a controlled training environment; record issuance/revocation |
| Municipality/fire/shelter agreement | Response ownership, escalation, operating hours, stop conditions, and exercises are operational/legal decisions | Municipality, fire service, shelter operator | Execute a written joint demonstration agreement and tabletop exercise |
| Personal-data protection | SOS content/GPS retention, access, disclosure, deletion, and breach response require jurisdiction-specific review | Privacy/legal owner | Approve DPIA/handling rules and configure retention/backup procedures accordingly |
| Telecom-law / emergency routing | Whether any service is regulated or can represent emergency communications needs legal advice | Legal counsel / operator | Explicitly prohibit 119-substitution claims until advice and agreement exist |
| Insurance | Field activities and staff response risks need an insurer’s decision | Sponsor/insurer | Obtain coverage or documented exclusion before field work |
| Commercial license / OSS notice / copyright | This repo intentionally has no chosen project license | Rights holder and legal owner | Select commercial/OSS terms, notice process, and copyright attribution; do not add `LICENSE` speculatively |
| Gradle dependency locks / verification metadata | Adding lock/checksum metadata without a clean, reviewed dependency resolution can pin incomplete or untrusted state; the current workspace cannot download the Gradle distribution | Build/release owner with approved dependency-network access | Resolve dependencies in the approved environment, review the resulting lock/verification files, and then commit them as a separate supply-chain change |
| Broker HA / monitoring | Single SQLite Broker is not redundant, load tested, or monitored | Infrastructure/SRE owner | Design HA, backups, alerts, failure drills, and RTO/RPO before any availability claim |
| Physical devices and network | Radio/OEM/mobile/closed-network behavior cannot be inferred from source tests | Field test lead, Android/Windows devices, approved network | Execute and retain the acceptance record in `FIELD_ACCEPTANCE_TEST.md` |

## Secret and artifact checklist for the human owner

- [ ] Android release keystore, alias, and passwords stored only in the approved secret system
- [ ] Windows Authenticode PFX/certificate and timestamp URL approved for release CI
- [ ] cosign private/public key and TUF trusted root/private signing material governed separately
- [ ] TLS certificate/private key and reverse-proxy configuration owned outside this repository
- [ ] Scoped Broker credential issuance/revocation record with no raw credential in tickets/logs
- [ ] Regional public root/directory/shelter key package signed by the authorized issuer
- [ ] Backup encryption recipients and recovery authorization documented
- [ ] Municipality/fire/shelter contacts, escalation chain, privacy owner, legal owner, and insurer documented
- [ ] Android and Windows physical test devices plus approved test SIM/network available

None of these inputs should be committed to the repository, pasted into an issue, or supplied to an unapproved local script.
