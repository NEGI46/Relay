<!-- GENERATED FILE - DO NOT EDIT.
     Source of truth: docs/readiness/status.yml
     Regenerate: python tools/readiness/readiness_tool.py generate -->

# Relay open readiness items

Status date: **2026-07-29** / commit `23bd1da`

## 未実装・実装不能（外部判断待ち）

- `continuous-gps-tracking` 継続GPS追跡（background location） — NOT_IMPLEMENTED（外部判断: BLOCKED_EXTERNAL: municipality/privacy owner approval for background location collection (Play policy: ACCESS_BACKGROUND_LOCATION requires a declared, approved use case); BLOCKED_EXTERNAL: physical Android devices and a field trial are required; background-location behavior cannot be verified on this workstation）
- `auto-disaster-detection` 自動災害検知（FCM・気象・Activation Manifest） — NOT_IMPLEMENTED（外部判断: BLOCKED_EXTERNAL: FCM project/credentials and a signing authority for the Activation Manifest must be provisioned by the operating municipality; BLOCKED_EXTERNAL: real devices and live JMA feed access are required to verify detection triggers end-to-end）
- `broker-high-availability` Broker高可用性・監視・災害復旧 — NOT_IMPLEMENTED（外部判断: Infrastructure/SRE owner for HA, RTO/RPO, and monitoring design; BLOCKED_EXTERNAL: multi-node infrastructure, a managed database or replication target, and an on-call/monitoring stack must be provisioned before any HA work can be real; a single-workstation SQLite deployment cannot honestly claim HA）
- `branch-protection` 必須セキュリティチェック付きブランチ保護 — BLOCKED_EXTERNAL（外部判断: Repository owner must apply the settings documented in docs/security/BRANCH_PROTECTION.md）

## 実装済みだが実機未検証（DEVICE_TESTEDなし）

- `sos-rescue-request` SOS・救助依頼の作成・更新・取消 — 実機: NOT_RUN
- `encrypted-storage` Room + SQLCipher暗号化保存（Keystore保護passphrase） — 実機: NOT_RUN
- `location-update` 明示同意時のみの位置更新 — 実機: NOT_RUN
- `armed-emergency-state` ARMED / EMERGENCY背景中継状態管理 — 実機: NOT_RUN
- `nearby-relay` Nearby暗号化Envelope中継（Store-Carry-Forward） — 実機: BLOCKED_EXTERNAL
- `nearby-connection-policy` Nearby接続ポリシー（OPEN / TRUSTED） — 実機: NOT_RUN
- `gateway-enrollment-core` LAN Gateway登録（token検証・永続化・rotation） — 実機: NOT_RUN
- `gateway-enrollment-ui` Gateway登録画面（CameraX QRスキャナ含むCompose UI） — 実機: NOT_RUN
- `broker-manifest-enrollment` Broker Manifest登録（debug/localDev限定） — 実機: NOT_RUN
- `ble-gateway-trust-chain` BLE Gateway信頼chain（Root→Directory→Manifest→fingerprint） — 実機: BLOCKED_EXTERNAL
- `android6-compat` Android 6.0互換基盤（minSdk 23） — 実機: NOT_RUN
- `device-test-harness` 実機テスト基盤（ADB・Mobly script） — 実機: BLOCKED_EXTERNAL
- `formal-release` 正式Release（組織署名・Authenticode・TUF/cosign） — 実機: BLOCKED_EXTERNAL
- `ios-preview` iOS simulatorプレビュービルド — 実機: BLOCKED_EXTERNAL
- `meshtastic-adapter` Meshtastic adapter（契約境界） — 実機: BLOCKED_EXTERNAL

## 外部判断が必要な項目（機能別）

| ID | 機能 | 外部判断 |
|---|---|---|
| `continuous-gps-tracking` | 継続GPS追跡（background location） | BLOCKED_EXTERNAL: municipality/privacy owner approval for background location collection (Play policy: ACCESS_BACKGROUND_LOCATION requires a declared, approved use case); BLOCKED_EXTERNAL: physical Android devices and a field trial are required; background-location behavior cannot be verified on this workstation |
| `auto-disaster-detection` | 自動災害検知（FCM・気象・Activation Manifest） | BLOCKED_EXTERNAL: FCM project/credentials and a signing authority for the Activation Manifest must be provisioned by the operating municipality; BLOCKED_EXTERNAL: real devices and live JMA feed access are required to verify detection triggers end-to-end |
| `nearby-relay` | Nearby暗号化Envelope中継（Store-Carry-Forward） | Two/three physical Android devices for RF multi-hop validation |
| `nearby-connection-policy` | Nearby接続ポリシー（OPEN / TRUSTED） | Allow-list distribution and update operation design |
| `ble-gateway-trust-chain` | BLE Gateway信頼chain（Root→Directory→Manifest→fingerprint） | Formal regional Root and signed shelter Directory issuance by trust authority |
| `https-broker` | HTTPS Broker（暗号文保存・重複排除・TTL・scoped credential） | Production TLS/DNS/reverse proxy/WAF/hosting |
| `broker-high-availability` | Broker高可用性・監視・災害復旧 | Infrastructure/SRE owner for HA, RTO/RPO, and monitoring design; BLOCKED_EXTERNAL: multi-node infrastructure, a managed database or replication target, and an on-call/monitoring stack must be provisioned before any HA work can be real; a single-workstation SQLite deployment cannot honestly claim HA |
| `device-test-harness` | 実機テスト基盤（ADB・Mobly script） | Physical Android devices and approved test network |
| `formal-release` | 正式Release（組織署名・Authenticode・TUF/cosign） | Organization Android signing key; Windows Authenticode certificate and timestamp policy; cosign/TUF key governance; Release approval by responsible organization |
| `ios-preview` | iOS simulatorプレビュービルド | Apple Developer signing and iPhone hardware |
| `meshtastic-adapter` | Meshtastic adapter（契約境界） | Physical Meshtastic hardware |
| `data-retention` | 個人・救助情報のretention管理 | Privacy/legal owner approval of retention periods |
| `branch-protection` | 必須セキュリティチェック付きブランチ保護 | Repository owner must apply the settings documented in docs/security/BRANCH_PROTECTION.md |

詳細な背景は [BLOCKED_BY_EXTERNAL_DECISIONS](BLOCKED_BY_EXTERNAL_DECISIONS.md) を参照。
