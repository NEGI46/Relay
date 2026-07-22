<div align="center">

<img src="docs/assets/relay-logo.svg" alt="Relay" width="680">

# Relay

### 災害時の「届かない」を、端末・人・避難所PC・安全なオンライン経路でつなぐ。
### Keep critical rescue information moving across offline and approved online paths.

[![Pilot](https://img.shields.io/badge/pilot-Fuchu%20Town-5B4FB2?style=for-the-badge)](docs/readiness/MUNICIPAL_PILOT_READINESS.md)
[![Android](https://img.shields.io/badge/Android-6.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#androidでの流れ)
[![Gateway](https://img.shields.io/badge/PC%20Gateway-production%20fails%20closed-0078D4?style=for-the-badge&logo=windows&logoColor=white)](#pc-gateway)
[![Trust](https://img.shields.io/badge/BLE%20trust-external%20artifacts%20required-F59E0B?style=for-the-badge)](#現在の状態)
[![Release](https://img.shields.io/badge/formal%20release-signing%20required-D946EF?style=for-the-badge)](#配布と正式release)

**[🇯🇵 日本語](#日本語)**　｜　**[🇬🇧 English](#english)**　｜　**[共同実証 readiness](docs/readiness/MUNICIPAL_PILOT_READINESS.md)**

</div>

> [!CAUTION]
> **Relayは119、消防・警察・自治体の緊急連絡、公式警報、認証済み人命安全システムを置き換えません。** 現在は限定区域の訓練・共同実証に向けたコード基盤です。送信、保存、Receiptは、救助実施や最終到達の保証ではありません。

## 30秒でわかるRelay / Relay in 30 seconds

|  | 日本語 | English |
|---|---|---|
| **目的** | 回線が止まっても、暗号化した救助要請を利用可能な経路で避難所PCへ近づける | Move encrypted rescue requests toward a shelter PC even when connectivity is intermittent |
| **利用者** | Androidで赤いSOSを2秒長押し、または人数・状態を入力 | Hold the red SOS for two seconds, or enter people and conditions |
| **経路** | Nearby、承認済みGateway、任意のHTTPS Brokerを独立して再試行 | Retry independently through Nearby, approved Gateway paths, and an optional HTTPS Broker |
| **受信側** | PC Gatewayで担当・確認・対応・完了を管理し、署名Receiptを返す | Staff manage claim, acknowledgement, response, and completion; signed receipts return to devices |
| **現在地** | コードと自動試験は充実。正式な地域Root、実機試験、本番インフラは未完了 | Strong code and automated tests; official regional trust, device testing, and production infrastructure remain |

```mermaid
flowchart LR
    A[📱 Android<br/>SOS / rescue request] --> B[🔐 Encrypt before storage]
    B --> N[📲 Nearby<br/>Store–Carry–Forward]
    B --> G[🌐 Approved HTTPS Gateway path]
    B --> C[☁️ Optional HTTPS Broker]
    N --> P[🖥 PC Gateway]
    G --> P
    C --> P
    P --> R[✍️ Signed shelter receipt]
    R --> A
    R --> N
```

## 現在の状態

**基準日: 2026-07-22 / HEAD: current default branch**

| 区分 | 状態 | 内容 |
|---|---:|---|
| 救助UI・暗号化Envelope | ✅ 実装済み | 2秒SOS、通常依頼、更新、取消、署名Receipt表示 |
| 送信者セッション復元 | ✅ 実装済み | Room + SQLCipherと別Keystore鍵で暗号化復元情報を保持。通常のプロセス終了後も更新・取消を再開 |
| Nearby / LAN / Broker | ✅ 実装済み | 独立経路として再試行。Broker保存やtransport完了を避難所受領へ昇格しない |
| PC Gatewayアクセス制御 | ✅ 実装済み | `ADMIN` / `OPERATOR` / `VIEWER`、個人アカウント、監査、secure session |
| BLE Gateway信頼チェーン | ⚠️ コード実装・実運用BLOCKED | Root → signed Directory → signed Manifest → BLE fingerprint検証。正式な府中町Root/Directoryは未提供 |
| 自動試験 | ✅ 一部PASS | shared 20/20、Android JVM 201/201、PC Gateway 57/57、Broker 28/28。release系APKのtest/private trust artifact混入検査PASS |
| Android instrumentation | ◻ 未実行 | source compilationのみ。emulator・物理端末でRoom migrationや再起動をまだ実行していない |
| 物理RF・現地運用 | ◻ NOT RUN | Nearby/BLE多段、Phone→PC、モバイル回線→Broker、OEM省電力、停電復旧など未検証 |
| 正式Release | ⛔ 外部鍵・証明書待ち | Android組織署名、Authenticode、cosign/TUF、TLS、正式scanner evidenceが必要 |
| 継続バックグラウンドGPS | ❌ 未実装 | 旧ViewModel周期追跡は削除。Android 14+制約と明示同意を含む別設計が必要 |

> [!IMPORTANT]
> **IMPLEMENTED ≠ AUTOMATED_TESTED ≠ DEVICE_TESTED ≠ DEPLOYMENT-READY** です。詳しい証拠区分は [救助耐久性・BLE信頼監査](docs/audits/RESCUE_DURABILITY_INITIAL_AUDIT.md) を参照してください。

## 画面イメージ

| Androidホーム | 救助ホーム | 公式情報 |
|---|---|---|
| <img src="docs/assets/relay-android-home.png" alt="Relay Android home" width="260"> | <img src="docs/assets/relay-android-rescue.png" alt="Relay rescue home" width="260"> | <img src="docs/assets/relay-android-official.png" alt="Relay official information" width="260"> |

---

# 日本語

## Relayとは

Relayは、災害や大規模通信障害を想定した**ローカル優先の救助情報中継システム**です。受信先鍵がある救助要請は避難所公開鍵で暗号化して保存・中継し、中継端末やBrokerは本文、正確な位置、人数を復号しません。受信先鍵が未解決のSOSは、まず送信元端末だけが復元できる暗号化保留状態として保存します。

現在の対象は、広島県安芸郡府中町を想定した限定区域パイロットです。ただし、このリポジトリに正式な府中町Regional RootやRoot署名済みDirectoryは含まれていません。正式な受信先公開鍵が承認済みEnrollment経路で提供されるまで、信頼済みBLE配送や本番運用は成立しません。

## Androidでの流れ

1. 位置情報と近距離通信の権限を許可する。
2. 命の危険がある場合は、赤いSOSを2秒長押しする。
3. 通常依頼では人数と「命の危険・けが/体調不良・移動困難・支援が必要」を入力する。
4. GPS位置、取得時刻、精度を含む本文を暗号化し、SQLCipher DBへ保存する。
5. Nearby、承認済みGateway経路、設定済みBrokerを独立して再試行する。
6. 依頼後は暗号化復元情報から更新・取消を継続できる。
7. 避難所の署名済みReceiptだけを確認・対応中・完了として表示する。
8. 終了結果は利用者が「確認しました」を押すまで保持し、その後に暗号化セッション復元行を削除する。

SOS作成と端末内保存にWi-Fiやモバイル通信は必要ありません。受信先公開鍵がまだ無い場合も、SOSは送信元端末のAES-GCM回復領域へ安全に保留し、Nearby通信を開始します。ただし、受信先を復号できない端末へ本文を渡すことはできないため、**信頼済みの受信先鍵が解決されるまで暗号化Envelopeの中継は開始しません**。PC GatewayやBrokerへ接続できない場合も、作成済みの暗号化依頼は利用可能なNearby/LAN/Broker経路で再試行されます。BLE Gateway配送は承認済みの信頼情報がある場合だけ行われます。

debug/localDev のみ、同一Private LANで発見した `development` profile のPC Gatewayが生成した公開manifestを自動登録できます。この便宜経路は正式な信頼根・署名済みDirectoryの代わりではなく、release/pilotReleaseでは無効です。

## 配送経路

<details open>
<summary><strong>📲 Nearby Store–Carry–Forward</strong></summary>

- 端末同士が遭遇したとき、不足している暗号化Envelopeだけを交換します。
- 中継端末は避難所秘密鍵を持たず、救助内容を復号できません。
- TTL、hop、サイズ、hash、version、重複・衝突を検査します。
- transport転送完了は相手の永続保存や避難所到達を証明しません。
- Broker経由の署名Receiptは、元端末だけでなく同じ暗号文を運んだ中継端末にも返送できます。

</details>

<details>
<summary><strong>📡 BLE Gateway配送と地域信頼</strong></summary>

BLE Gateway配送は次の全検証が成功した場合だけ有効です。

```text
承認済みRegional Root
        ↓ signature
Root署名済みRegional Shelter Directory
        ↓ manifest / key binding
署名済みShelter Manifest
        ↓ advertised + GATT identity match
BLE Gatewayへ暗号文を提出
```

- Androidはbuild variantごとの**公開Root bundleだけ**を読み込みます。
- DirectoryはRoot署名、region、generation、有効期限、Shelter Manifest、recipient key、receipt keyを検証してSQLCipherへ保存します。
- 同一generationで異なる内容、古いgeneration、期限切れ、fingerprint不一致は拒否します。
- 起動時に保存済みDirectoryを再検証し、完了するまでresolverは空です。
- `release` / `pilotRelease` はunsigned manifestと自動Enrollmentを拒否します。`debug` / `localDev`だけは、明示的なdevelopment profileの同一LAN Gatewayが提示する公開manifestをdevelopment専用として登録できます。
- 正式Root/Directoryがない現在はBLE配送がfail-closedになります。Nearby、承認済みLAN、Brokerはそれだけを理由に停止しません。
- Regional Root秘密鍵はリポジトリ、APK、実行中Gatewayへ渡してはいけません。

オフライン運用者CLI:

```powershell
.\gradlew.bat :pc-gateway:regionalTrustProvisioning --args="generate-regional-root ..."
```

対応コマンド: `generate-regional-root`、`export-regional-root-bundle`、`sign-regional-directory`、`verify-regional-directory`、`print-public-fingerprints`。

CLIはprivate Root materialのGit worktree内出力、既存ファイル上書き、秘密鍵の標準出力を拒否します。CLIで生成したRootが自治体の正式Rootになるわけではありません。

</details>

<details>
<summary><strong>🌐 承認済みGateway / LAN経路</strong></summary>

Androidの`release` / `pilotRelease`はcleartext Gateway通信を拒否します。HTTPは`debug` / `localDev`だけです。

PC Gatewayの既定`production` profile:

- `127.0.0.1` bind
- anonymous ingress無効
- UDP discovery無効
- remote management無効
- legacy `X-Admin-Key`拒否

LAN利用には明示的な構成が必要です。

| モード | 用途 |
|---|---|
| `disabled` | loopbackのローカル操作だけ |
| `closed-network` | 管理者が承認した閉域網。Android release通信には別途承認済みHTTPS終端が必要 |
| `tls-reverse-proxy` | Gatewayはloopbackのまま、外部TLS proxyだけをremote browser経路にする |

UDP discoveryは`development`互換経路であり、認証ではありません。未検証経路だけで救助判断を自動化しないでください。

</details>

<details>
<summary><strong>☁️ 任意のHTTPS Broker</strong></summary>

```text
Android ── HTTPS ──► TLS proxy ──► Broker (loopback HTTP + SQLite)
Android ◄─ Receipt ─ TLS proxy ◄── PC Gateway Receipt Outbox
                                  ▲
                     scoped Gateway credential
```

Brokerは復号せず、暗号文の保存、重複排除、衝突隔離、TTL purge、避難所別queueを担当します。`hopCount`は増やしません。

主な境界:

- Android登録時に端末固有P-256鍵の所持を証明
- upload署名を登録済み公開鍵で検証
- Receipt取得は推測困難なBearer capability token
- Gateway資格情報は256-bitで、1つの`gatewayId`と`shelterId`へ固定
- DBには資格情報のSHA-256 hashだけを保存
- 期限切れ・失効・別Gateway・別避難所を拒否
- pullは複合cursor、Receiptは単調増加seq cursor
- Gateway Receipt Outboxは救助状態と同じSQLite transaction境界
- Brokerは`production` / `lab`でloopback bind必須。外部TLS proxy、DNS、証明書、WAFは運用者の責任
- 単一BrokerはHAではありません

```text
broker issue-gateway-credential --gateway-id <gateway> --shelter-id <shelter> --expires-at <epoch-ms>
broker revoke-gateway-credential --credential-id <id>
```

raw credentialは発行時に一度だけ表示されます。repository、shell history、ticket、audit logへ保存しないでください。

</details>

## 救助セッションの耐久性

`active_rescue_sessions`（Room schema v8）は、request/version/status/timestampと暗号化されたrecovery payloadだけを保存します。

- 本文、人数、状態、自由記述、sender ID、位置は平文列へ置きません。
- SQLCipher DBとは別のAndroid Keystore alias `relay_rescue_session_recovery_v1`を使用します。
- AES-GCM、provider生成nonce、authenticated decryptionを使用します。
- sessionと新しいEnvelopeを1つのRoom transactionでcommitします。
- version更新はdurable CASで競合を検出します。
- 復号失敗時は行を残して明示的な復元失敗にし、新規依頼へすり替えません。
- BLE、Nearby、Gateway、Brokerの署名Receipt適用は、Envelope状態とsender session状態を同一transactionで更新します。
- 通常のprocess deathや利用者による再起動から復元しますが、OSのforce-stopやOEM挙動を保証するものではありません。

### 位置情報について

継続バックグラウンドGPS追跡、location foreground service、background location permissionは現在未実装です。以前のViewModel周期追跡は削除され、復元後のUIは位置更新が停止中であることを表示します。Android 14+制約、利用者の明示同意、開始・停止条件を含む別設計が必要です。

## PC Gateway

### Staff認証・権限

- 初回ADMINは一回限りの`bootstrap-admin`で作成します。既定passwordはありません。
- 個人アカウントと`ADMIN` / `OPERATOR` / `VIEWER`を使用します。
- passwordはPBKDF2-HMAC-SHA-256、random salt、210,000 iterationsです。
- session tokenはrandom 256-bitで、DBにはhashだけを保存します。
- browser cookieは`HttpOnly`、`SameSite=Strict`、TLS proxy時は`Secure`です。
- 無効化したアカウントのsessionは失効します。最後の有効ADMINは無効化・降格できません。

スタッフ状態遷移:

```text
未確認 → 確認済み → 準備中 → 対応中 → 完了
```

最初に「担当開始」を成功させたスタッフが担当になります。独立した複数Gateway間の担当同期はv1対象外です。

### 監査ログ

時刻、認証済みoperator、target ID、action、result、最小限のpeer情報を記録します。救助本文、GPS、暗号文、password、session token、Broker credential、private key、exception textは記録しません。監査検索・CSV exportはADMIN限定で、CSV formula injectionも無効化します。

### 秘密鍵境界

Gatewayの救助秘密鍵は現在もローカルfileです。DPAPI、HSM、KMS保護済みとは主張しません。owner-only POSIX permissionまたはWindows ACLを検査し、`production` / `lab`では未provisioned・安全でないfileをfail-closedにします。rotation、revocation、escrow、hardware-backed storageは外部方針が必要です。

配備手順: [Production Gateway deployment](docs/runbooks/PRODUCTION_GATEWAY_DEPLOYMENT.md)

## 公式情報と地図

- Androidは府中町、広島県、気象庁の公式情報への導線だけを表示します。
- PC Gatewayは府中町周辺の国土地理院標準地図をズーム13–15でcacheできます。
- 気象庁の広島県警報JSONから府中町コード`3430200`を抽出し、取得失敗時は最後のcacheを表示します。
- 地図利用時は [国土地理院コンテンツ利用規約](https://maps.gsi.go.jp/help/termsofuse.html) に従ってください。

## 配布と正式Release

`debug` APK、ローカル署名APK、未署名APK、未署名Windows installerは正式な実証配布物ではありません。

`Publish Relay formal release` workflowは、次が揃わない限り公開前に失敗します。

- 組織管理Android signing materialと`apksigner`検証
- Windows Authenticode証明書、timestamp、`signtool`検証
- immutable source commit
- pinned Syft / OSV-Scanner / GrypeとSBOM・脆弱性証拠
- High/Critical blocking policy
- cosign署名・bundle検証
- TUF trusted root / targets metadata
- SHA-256とsource-commit manifest

通常CIは`report-only`で、不足した外部serviceや署名を`BLOCKED`として残します。正式Releaseは`block-high-critical`でfail-closedです。

- [GitHub Releases](https://github.com/NEGI46/Relay/releases)
- [正式Release検証手順](docs/runbooks/VERIFY_FORMAL_RELEASE.md)
- [外部判断・provisioning blocker](docs/readiness/BLOCKED_BY_EXTERNAL_DECISIONS.md)

## 開発者向け

### 必要環境

- JDK 17
- Android SDK / API 36
- Git
- Windows installer作成時はWiX 3とsigning toolchain

Android: min SDK 23、target/compile SDK 36、version `1.0.0`。

### Build / test

Windows PowerShell:

```powershell
.\gradlew.bat :shared:jvmTest :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :pc-gateway:test :broker:test
.\gradlew.bat :app:assembleDebug :pc-gateway:build :broker:build
.\gradlew.bat :app:verifyNoTestTrustArtifactsInReleaseApks
```

macOS / Linux:

```bash
./gradlew :shared:jvmTest :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :pc-gateway:test :broker:test
./gradlew :app:assembleDebug :pc-gateway:build :broker:build
./gradlew :app:verifyNoTestTrustArtifactsInReleaseApks
```

`compileDebugAndroidTestKotlin`はinstrumentation sourceをcompileするだけで、emulator・実機上のtest実行ではありません。

### CI

Relay CIは次を実行します。

- Android debug APK、PC Gateway、Broker build/test
- shared / Android JVM test、Compose desktop smoke
- implementation・accessibility・municipal security contracts
- deterministic decoder、virtual BLE、Mobly host contracts
- Gateway recovery、Meshtastic、BPv7、TUF metadata tests
- pinned Syft / OSV / Grype evidence
- 任意のMobSFとdistribution verification

GitHub Actionsはcommit SHA pinを使用しています。decoder regressionは実Jazzer fuzzingではなく、JVM Jazzer targetは別途必要です。

## リポジトリ構成

| 場所 | 役割 |
|---|---|
| `app/` | Android UI、暗号化DB、session復元、Nearby/Gateway/Broker/BLE |
| `shared/` | 救助model、暗号、署名Receipt、地域信頼contract |
| `pc-gateway/` | 救助復号、staff管理、監査、地図、公式情報、Broker Pull/Outbox、provisioning CLI |
| `broker/` | scoped encrypted-envelope relay、端末登録、資格情報、Receipt中継 |
| `relay-protocol/` | Gateway wire contract |
| `pc-ble-bridge/` | Windows BLE GATT sidecar |
| `composeApp/` | Compose Multiplatform / desktop / iOS framework |
| `apple/` | Swift PackageとApple向けGATT・Receipt contract |
| `gateway-meshtastic-adapter/` | 分離されたMeshtastic adapter |
| `gateway-bp7-export/` | BPv7 export-only境界 |
| `test-lab/`, `tools/ble-sim/` | host、fault、decoder、virtual BLE test |
| `docs/` | architecture、security、readiness、audit、runbook |

## 共同実証前に必要なこと

- 自治体・消防・避難所の責任者、運用範囲、停止条件、連絡計画の承認
- 正式なpublic Regional Root bundleとRoot署名済みDirectory
- 実Gatewayのrecipient public key・receipt-signing public keyとfingerprint確認
- TLS/DNS/reverse proxy/WAF/hostingと承認済みnetwork boundary
- Android/Windows/cosign/TUFの組織署名・検証material
- privacy、retention、法務、保険、license、OSS noticeの判断
- 実Android端末、Windows配備先、現地スタッフによる [Field acceptance test](docs/runbooks/FIELD_ACCEPTANCE_TEST.md)

**現在の総合状態:** `READY_FOR_DEVICE_TEST_WITH_TRUST_ARTIFACT_BLOCKER`

## 主要ドキュメント

- [Municipal pilot readiness](docs/readiness/MUNICIPAL_PILOT_READINESS.md)
- [救助耐久性・BLE信頼監査](docs/audits/RESCUE_DURABILITY_INITIAL_AUDIT.md)
- [外部判断・provisioning blocker](docs/readiness/BLOCKED_BY_EXTERNAL_DECISIONS.md)
- [Field acceptance test](docs/runbooks/FIELD_ACCEPTANCE_TEST.md)
- [Production Gateway deployment](docs/runbooks/PRODUCTION_GATEWAY_DEPLOYMENT.md)
- [Broker architecture](docs/BROKER_ARCHITECTURE.md)
- [PC Gateway security](docs/PC_GATEWAY_SECURITY.md)
- [Gateway backup](docs/runbooks/GATEWAY_BACKUP.md)
- [Formal release verification](docs/runbooks/VERIFY_FORMAL_RELEASE.md)
- [Security policy](SECURITY.md)
- [Repository guide](docs/REPOSITORY_GUIDE.md)

---

# English

<details open>
<summary><strong>Open the full English documentation</strong></summary>

## What Relay is

Relay is a **local-first rescue-information relay** for outages and intermittent networks. Rescue content is encrypted to a shelter recipient key before local storage. Courier devices and the Broker handle ciphertext and cannot decrypt the rescue body, exact location, or people count.

The current scope is a limited-area Fuchu Town pilot candidate. This repository does not include an official Fuchu Regional Root or a Root-signed Regional Shelter Directory. Trusted BLE Gateway delivery and production operation remain blocked until authorized public trust artifacts and operational approvals are provisioned.

## Android flow

1. Grant location and nearby-device permissions.
2. Hold the red SOS for two seconds for an immediate life-risk request.
3. For a normal request, enter people count and one or more conditions.
4. Encrypt GPS position, timestamp, accuracy, and rescue content before SQLCipher storage.
5. Retry independently through Nearby, approved Gateway paths, and an optional Broker.
6. Restore, update, or cancel the active request from encrypted recovery state after a normal process restart.
7. Show shelter acknowledgement, response, and completion only after a valid signed shelter receipt.
8. Retain a terminal result until the sender explicitly acknowledges it.

Internet access is not required to create and store an SOS, but a new envelope still needs an approved shelter recipient public key. Transport completion, HTTP success, and `BROKER_STORED` are not shelter acceptance.

## Delivery paths

### Nearby Store–Carry–Forward

Nearby exchanges only missing encrypted envelopes. Couriers do not have shelter private keys. TTL, hop, size, hash, version, deduplication, and collision rules are enforced. Transfer completion is not durable remote storage or final delivery.

### BLE Gateway trust

BLE delivery requires the complete chain:

```text
Approved Regional Root
  → Root-signed Regional Shelter Directory
  → signed Shelter Manifest and key bindings
  → matching advertised and GATT identity
  → encrypted BLE submission
```

Android loads public-only Root bundles, validates and persists signed directories in SQLCipher, rejects rollback/equivocation/expiry/fingerprint mismatch, and re-verifies persisted data at startup. Unsigned bundled manifests and automatic debug TOFU enrollment were removed. Without approved public artifacts, BLE delivery fails closed while other independent routes remain available. The Regional Root private key must never enter this repository, an APK, or a running Gateway.

### Approved Gateway path

`release` and `pilotRelease` reject cleartext Gateway traffic. The default `production` profile binds to loopback with anonymous ingress, UDP discovery, remote management, and legacy `X-Admin-Key` disabled. LAN or remote access requires an explicit approved closed-network or TLS-reverse-proxy topology.

### Optional HTTPS Broker

The Broker never decrypts. It stores and deduplicates encrypted envelopes, isolates collisions, purges by TTL, and relays signed receipts. Production/lab Broker instances must bind to loopback behind an externally operated TLS reverse proxy.

Gateway credentials contain 256 bits of entropy, are scoped to exactly one Gateway and shelter, and are stored only as SHA-256 hashes. Expired, revoked, malformed, cross-Gateway, and cross-shelter use is rejected. Device registration proves possession of a per-device P-256 key; upload signatures are not human identity verification.

## Durable rescue sessions

Room schema v8 stores request/version/status metadata plus only an AES-GCM encrypted recovery payload. Rescue body, people, conditions, sender ID, and location are not plaintext session columns. A separate Android Keystore alias protects recovery data. Envelope and session changes commit together with durable version CAS. Decryption failures retain evidence and surface recovery failure rather than silently creating a replacement request.

Continuous background GPS tracking is not implemented. The previous ViewModel loop was removed. A separate design is required for explicit consent, Android 14+ location-FGS constraints, and lifecycle stop conditions.

## PC Gateway

The Gateway uses named `ADMIN`, `OPERATOR`, and `VIEWER` accounts. The first ADMIN is created with a one-time bootstrap command; there is no default password. Passwords use PBKDF2-HMAC-SHA-256 with random salts and 210,000 iterations. Random session tokens are stored only as hashes, and browser cookies are HttpOnly/SameSite with Secure required behind TLS.

The audit log records minimal operator/action/result metadata and must not contain rescue text, GPS, ciphertext, passwords, tokens, Broker credentials, private keys, or exception text. Gateway rescue private keys remain an owner-only local-file boundary; this repository does not claim DPAPI/HSM/KMS protection.

## Formal distribution

Debug, locally signed, or unsigned artifacts are not formal pilot releases. The formal workflow fails closed unless organization Android signing, Windows Authenticode, pinned SBOM/vulnerability scanners, High/Critical blocking, cosign/TUF verification, checksums, and immutable source-commit evidence are all available.

## Validation boundary

Automated evidence dated 2026-07-22 records shared JVM 20/20, Android JVM 201/201, PC Gateway 57/57, and Broker 28/28 passing tests, plus successful inspection of release-derived APKs for test/private trust material. Android instrumentation source compiled but did not run on an emulator or physical device.

Physical Nearby/BLE, multi-hop, Phone-to-PC, mobile-to-Broker-to-Gateway, OEM background behavior, power-loss recovery, production TLS, formal signing, and field operations remain unvalidated.

**Overall status:** `READY_FOR_DEVICE_TEST_WITH_TRUST_ARTIFACT_BLOCKER`

## Build and test

```bash
./gradlew :shared:jvmTest :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :pc-gateway:test :broker:test
./gradlew :app:assembleDebug :pc-gateway:build :broker:build
./gradlew :app:verifyNoTestTrustArtifactsInReleaseApks
```

## Key documents

- [Municipal pilot readiness](docs/readiness/MUNICIPAL_PILOT_READINESS.md)
- [Rescue durability and BLE trust audit](docs/audits/RESCUE_DURABILITY_INITIAL_AUDIT.md)
- [External decision blockers](docs/readiness/BLOCKED_BY_EXTERNAL_DECISIONS.md)
- [Field acceptance test](docs/runbooks/FIELD_ACCEPTANCE_TEST.md)
- [Production Gateway deployment](docs/runbooks/PRODUCTION_GATEWAY_DEPLOYMENT.md)
- [Broker architecture](docs/BROKER_ARCHITECTURE.md)
- [PC Gateway security](docs/PC_GATEWAY_SECURITY.md)
- [Formal release verification](docs/runbooks/VERIFY_FORMAL_RELEASE.md)
- [Security policy](SECURITY.md)

</details>

## License / ライセンス

No project license has been selected. Do not assume permission to redistribute, modify, or commercially use the code until a license is added.

プロジェクトのライセンスは未選定です。ライセンスが追加されるまでは、再配布・改変・商用利用の許可があるものとみなさないでください。
