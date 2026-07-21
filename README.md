<div align="center">

<img src="docs/assets/relay-logo.svg" alt="Relay" width="620">

# Relay

### 災害時の「届かない」を、端末・人・避難所PC・モバイル通信でつなぐ。
### Keep critical information moving across offline and online paths.

[![v1](https://img.shields.io/badge/v1-Fuchu%20Town-5B4FB2?style=for-the-badge)](docs/V1_FUCHU_PILOT.md)
[![Android](https://img.shields.io/badge/Android-6.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](docs/readiness/MUNICIPAL_PILOT_READINESS.md)
[![Windows](https://img.shields.io/badge/Windows-PC%20Gateway-0078D4?style=for-the-badge&logo=windows&logoColor=white)](#pc-gateway)
[![Broker](https://img.shields.io/badge/Broker-optional%20HTTPS-0EA5E9?style=for-the-badge)](#3-任意のbroker経路)

</div>

## まず結論

Relayは、災害時の救助要請を Android で暗号化し、利用可能な経路を並行して使って避難所 PC への**送達を試みる**、ローカル優先の救助情報中継システムです。送達試行・保存・Receipt は救助実施や最終到達の保証ではありません。

v1は **広島県安芸郡府中町の救助要請** に対象を絞っています。

```text
                         ┌─ Nearby / BLE ─ Store–Carry–Forward ─┐
AndroidでSOSを作成・暗号化 ├─ 同一LAN ──────────────── PC Gateway ├─► スタッフ対応
                         └─ HTTPS Broker（任意）──────────────┘
                                                           │
Android ◄──────────── 署名済み受領・対応Receipt ─────────────┘
```

| 利用者 | 現在できること |
|---|---|
| Android | 赤いSOSを2秒長押し、GPS付き救助要請、更新・取消、受領・対応状況の確認 |
| 中継端末 | 救助内容を復号せず、暗号化Envelopeだけを自動中継 |
| PCスタッフ | 正確な位置・人数・状態を確認し、担当開始から完了まで管理 |
| Broker | インターネット利用時だけ暗号文を一時保管し、PC Gatewayとの配送を補助 |
| 公式情報 | 府中町・広島県・気象庁への導線と、PC側の府中町オフライン地図 |

> [!CAUTION]
> Relayは消防・警察・自治体の緊急連絡、公式警報、認証済み人命安全システムを置き換えません。実災害で使う前に、自治体・消防・避難所運営者との運用設計、物理端末での無線試験、本番鍵・TLS・配布署名の整備が必要です。

このブランチの目的は、自治体・消防・避難所運営者との**限定区域・訓練・共同実証**へ持ち込むための安全な基盤です。実災害で確実に救助できる完成品、119の代替、正式リリース済み製品は主張しません。現在の境界は [自治体共同実証 readiness](docs/readiness/MUNICIPAL_PILOT_READINESS.md) を参照してください。

## 画面イメージ

| Androidホーム | 救助ホーム | 公式情報 |
|---|---|---|
| <img src="docs/assets/relay-android-home.png" alt="Relay Android home" width="260"> | <img src="docs/assets/relay-android-rescue.png" alt="Relay rescue home" width="260"> | <img src="docs/assets/relay-android-official.png" alt="Relay official information" width="260"> |

PCスタッフ画面、地図、公式情報、担当フローは [府中町v1パイロット仕様](docs/V1_FUCHU_PILOT.md) にまとめています。

## 配布・正式リリース

`debug` APK、ローカル署名 APK、未署名 APK、未署名 Windows インストーラーは**正式な実証配布物ではありません**。このリポジトリは、組織管理の Android 署名鍵、Windows Authenticode 証明書、cosign/TUF 鍵、SBOM・脆弱性検査ツールが GitHub Secrets / runner に用意されるまで、正式 Release workflow を fail-closed にします。

- `artifacts/` とローカル `assembleDebug` の成果物は開発・検証専用です。
- 正式 release workflow は `assembleRelease`、Authenticode、SBOM、OSV/Grype、cosign/TUF 検証、SHA-256、ビルド commit SHA を必須にします。
- 正式 artifact の受領者は、同梱の `RELEASE_ARTIFACT_VERIFICATION.md` と [formal release 検証手順](docs/runbooks/VERIFY_FORMAL_RELEASE.md) で、組織が別経路で管理する証明書・cosign 公開鍵・TUF trusted root と照合します。
- Broker サーバーは release asset ではありません。実際のホスティング、TLS、運用契約が必要です。
- 必要な外部入力は [BLOCKED_BY_EXTERNAL_DECISIONS.md](docs/readiness/BLOCKED_BY_EXTERNAL_DECISIONS.md) を参照してください。

## Androidの使い方

1. APKをインストールする。
2. 初回起動で位置情報と近距離通信の権限を許可する。
3. 命の危険がある場合は、ホームの赤いSOSを2秒長押しする。
4. それ以外は「状況を入力して救助を依頼」から人数と状態を選ぶ。
5. GPS位置、取得時刻、位置精度を含む本文が暗号化される。
6. Nearby / BLE / LAN / 設定済み Broker の利用可能な経路で自動配送を試みる。
7. 自分の依頼カードから状況・人数の更新、取消、避難所の受領・対応中・完了を確認する。

SOSは人数不明・命の危険として作成されます。通常依頼は人数必須で、「命の危険・けが/体調不良・移動困難・支援が必要」から1つ以上を選びます。自由記述と補足タグは任意です。

SOS作成と端末内保存にWi-Fiやモバイル通信は必要ありません。府中町v1の公開鍵はアプリへ同梱され、オフラインでも暗号化して保存できます。PC GatewayやBrokerへ接続できない場合も、SOS作成とNearby/BLE中継は止まりません。

## 配送経路

### 1. Nearby / BLE — オフライン中継

救助要請は避難所公開鍵で暗号化され、中継端末は本文・正確な位置・人数を復号できません。端末同士の接触と人の移動でStore–Carry–Forwardします。

### 2. 同一LAN — PC Gatewayへ直接配送

PC Gateway の `production` profile は loopback bind・匿名 ingress 無効・UDP discovery 無効です。LAN を使うのは、`RELAY_GATEWAY_LAN_MODE=closed-network` または `tls-reverse-proxy` を明示し、自治体側が閉域網または TLS 終端を確認した場合だけです。Android の release / pilotRelease は HTTPS Gateway のみを許可し、開発用 HTTP は debug / localDev に閉じ込めています。

Androidには、メッセージ本文や暗号文を含まない安全な診断情報として、最後に見つけたGateway IP、探索結果、配送結果が保存・表示されます。

> [!IMPORTANT]
> UDPビーコンは接続先を見つけるヒントであり、Gatewayの本人性を証明しません。未検証経路の情報だけで救助決定を自動化してはいけません。production でリモート管理を有効にするには、loopback Gateway の前段に TLS reverse proxy と Secure/HttpOnly/SameSite cookie を使う構成、および明示的な `RELAY_GATEWAY_REMOTE_MANAGEMENT=true` が必要です。

### 3. 任意のBroker経路

インターネットが使える場合、Androidはオフライン経路と並行してHTTPS Brokerへ暗号化Envelopeを送信できます。

```text
Android ── HTTPS upload ──► Broker ◄── HTTPS pull ── PC Gateway
Android ◄─ HTTPS receipts ─ Broker ◄── HTTPS outbox ─ PC Gateway
```

Brokerは**復号しません**。暗号文の一時保存、重複排除、衝突隔離、期限管理、避難所別キューだけを担当します。Broker経路では`hopCount`を増やしません。

最新実装の主な保護:

- 端末ごとに初回生成したUUID `deviceKeyId`とAndroid Keystore ECDSA P-256鍵を使用
- 端末登録時に公開鍵をBrokerへ登録し、アップロード署名をBroker側で検証
- Receipt取得には公開`deviceKeyId`ではなく、推測困難なcapability tokenを使用
- AndroidはBroker URLをHTTPSに限定し、HTTPとredirectを拒否
- 64 KiBの`Content-Length`を本文読込前に確認し、読込後も再検査
- PC GatewayのPull / Receipt APIはBearer token認証に対応
- Envelope取得は`stored_at + envelope_id`の複合cursorで欠落を防止
- Receipt取得はBroker採番の単調増加`seq`を使い、Android再起動後もcursorを保持
- PC GatewayのPull cursorをファイルへ保存
- Receipt Outboxを救助状態と同じSQLite接続・トランザクション境界で管理
- WorkManager再送はネットワーク接続時だけ実行
- Broker障害はNearby / BLE / LAN経路へ影響しない

`BROKER_STORED`は「Brokerが暗号文を保存した」という別Ledger状態です。避難所が受領・対応・完了したことを意味せず、`SHELTER_*`状態は署名済みReceiptを検証した場合だけ変化します。

詳しくは [Brokerアーキテクチャ](docs/BROKER_ARCHITECTURE.md) を参照してください。

## PC Gateway

Windowsインストーラーを実行すると、固定拠点用PC Gatewayが入ります。起動後、スタッフPCで次を開きます。

```text
http://127.0.0.1:8080/
```

初回は既定パスワードを作りません。ローカルの一回限り `bootstrap-admin` コマンドまたは bootstrap 環境値で**個人の ADMIN アカウント**を作成し、ブラウザは HttpOnly / SameSite session cookie で認証します。`X-Admin-Key` は development profile の互換用途だけで、lab / production では拒否されます。詳細は [production Gateway 配備 runbook](docs/runbooks/PRODUCTION_GATEWAY_DEPLOYMENT.md) を参照してください。

```text
未確認 → 確認済み → 準備中 → 対応中 → 完了
```

最初に「担当開始」を押したスタッフ端末が担当になります。同じ Gateway の複数スタッフ PC 利用は、明示的に TLS reverse proxy または承認済み閉域網を構成した限定区域に限ります。独立した複数 Gateway 間の担当同期は v1 対象外です。

対応状態は避難所鍵で署名したReceiptとしてAndroidへ返ります。完了・取消になった依頼の全バージョンは30日後に削除されます。

## 公式情報と地図

- Androidはv1画面でユーザー投稿を表示せず、府中町・広島県・気象庁の公式情報だけへ導線を出します。
- PC Gatewayは府中町周辺の国土地理院標準地図をズーム13–15で保存できます。
- 気象庁の広島県警報JSONから府中町コード`3430200`を抽出し、取得失敗時は最後のキャッシュを表示します。
- 地図利用時は [国土地理院コンテンツ利用規約](https://maps.gsi.go.jp/help/termsofuse.html) に従ってください。

## Brokerの配置設定

Broker本体はHTTPで待ち受けます。本番ではnginxやCaddyなどの**TLS終端reverse proxyの背後**に置き、AndroidとPC Gatewayからは必ずHTTPS URLで接続します。

| 対象 | 設定 |
|---|---|
| Android | debug/localDev だけが SharedPreferences `relay_broker_config/broker_endpoint` を使える。pilotRelease/release は mutable preference を受け付けず、レビュー済み build-time HTTPS endpoint（将来は署名済み regional provisioning）が必要。空なら無効 |
| PC Gateway | `RELAY_BROKER_URL`（HTTPS）、`RELAY_BROKER_CREDENTIAL`（Gateway+避難所スコープ）、任意で`RELAY_BROKER_POLL_INTERVAL_MS` |
| Broker | `RELAY_BROKER_PORT`、`RELAY_BROKER_DB_PATH`、`RELAY_BROKER_PROFILE` |

Broker は `issue-gateway-credential` で Gateway ID・shelter ID・期限に結びつく 256-bit 資格情報を一度だけ発行します。SQLite にはハッシュだけを保存し、別 shelter への pull / receipt upload は拒否します。旧 `RELAY_BROKER_API_KEY` / `RELAY_BROKER_GATEWAY_API_KEY` 共有キーは development profile 限定で、production / lab は起動失敗にします。

## 開発者向け

### 必要環境

- Windows 10/11
- JDK 17
- Android SDK / API 36
- Git
- Windowsインストーラー作成時のみWiX 3

### ビルドとテスト

Windows PowerShell:

```powershell
.\gradlew.bat :shared:jvmTest :app:testDebugUnitTest :app:compileDebugKotlin :pc-gateway:test :broker:test
.\gradlew.bat :app:assembleDebug :pc-gateway:build :broker:build
```

macOS / Linux:

```bash
./gradlew :shared:jvmTest :app:testDebugUnitTest :app:compileDebugKotlin :pc-gateway:test :broker:test
./gradlew :app:assembleDebug :pc-gateway:build :broker:build
```

APKは`app/build/outputs/apk/debug/app-debug.apk`に生成されます。WindowsインストーラーはReleaseワークフローがWindows runner上で作成します。

### ヘッドレスAVD確認

画面を表示しないAVDでは`Medium_Phone`を使い、`-no-window -no-audio`を指定します。府中町のGPS例:

```powershell
adb emu geo fix 132.504 34.392
```

仮想端末のPASSは、物理Bluetooth、OEM差、画面消灯、実際の電波環境を証明しません。実機2台以上のNearby/BLE、Phone→PC、モバイル回線→Broker→GatewayのE2E試験は別途必要です。

## リポジトリ構成

| 場所 | 役割 |
|---|---|
| `shared/` | 救助契約、暗号化、署名Receipt、共通モデル |
| `app/` | Android UI、GPS、Nearby/BLE/LAN、暗号化Room DB、Broker配送 |
| `pc-gateway/` | 復号、担当、状態遷移、地図、公式情報、Broker Pull / Receipt Outbox |
| `broker/` | Ktor + SQLite Broker、端末登録、署名検証、暗号文保管、Receipt中継 |
| `pc-ble-bridge/` | Windows BLE GATT sidecar |
| `apple/` | Swift Package、Apple向けGATT・Receipt契約 |
| `gateway-meshtastic-adapter/` | 分離されたMeshtastic adapter |
| `gateway-bp7-export/` | BPv7 export-only境界 |
| `docs/` | v1仕様、運用、セキュリティ、ADR、テスト、リリース手順 |
| `.github/workflows/` | CIとGitHub Release作成 |

## 主要ドキュメント

- [府中町v1パイロット仕様](docs/V1_FUCHU_PILOT.md)
- [Brokerアーキテクチャ](docs/BROKER_ARCHITECTURE.md)
- [PC Gatewayセットアップ](docs/PC_GATEWAY_SETUP.md)
- [PC Gatewayアーキテクチャ](docs/PC_GATEWAY_ARCHITECTURE.md)
- [PC Gatewayセキュリティ](docs/PC_GATEWAY_SECURITY.md)
- [オフライン地図設計](docs/design/OFFLINE_MAP.md)
- [デバイス試験チェックリスト](docs/DEVICE_TEST_CHECKLIST.md)
- [署名付き配布手順](docs/release/SIGNED_DISTRIBUTION.md)
- [自治体共同実証 readiness](docs/readiness/MUNICIPAL_PILOT_READINESS.md)
- [production Gateway 配備 runbook](docs/runbooks/PRODUCTION_GATEWAY_DEPLOYMENT.md)
- [現地受入試験](docs/runbooks/FIELD_ACCEPTANCE_TEST.md)
- [正式 release 検証手順](docs/runbooks/VERIFY_FORMAL_RELEASE.md)
- [脆弱性報告ポリシー](SECURITY.md)
- [リポジトリ案内](docs/REPOSITORY_GUIDE.md)

## 現在の検証境界

自動テストは暗号化、Broker端末登録・署名検証・認証、cursor、Receipt Outbox、Gateway API、UI契約などを対象にしています。ただし、次は自動テストだけでは証明できません。

- 実機同士のNearby / BLE到達性
- OEMごとのForeground Service・再起動・省電力制限
- 実際のモバイル回線とTLS reverse proxyを使ったBroker運用
- 府中町の現場ネットワーク・複数スタッフ運用
- 本番鍵、証明書、配布署名、災害時の最終到達

## English summary

Relay v1 is a local-first rescue-request relay for the Fuchu Town pilot in Hiroshima, Japan. Android users can create a two-second SOS or a normal GPS-backed rescue request. The request is encrypted before storage and can travel through Nearby/BLE Store–Carry–Forward, a local PC Gateway path, and an optional HTTPS Broker path in parallel.

The Broker never decrypts rescue content. Devices register per-device ECDSA P-256 public keys, uploads are signature-verified, receipt polling uses an unguessable capability token, Gateway APIs support Bearer authentication, and persistent cursors/outboxes provide restart-safe at-least-once delivery. The Broker itself is intended to run behind a TLS-terminating reverse proxy.

The staff PC Gateway decrypts only requests addressed to its shelter, manages claim and response states, and returns signed receipts to the requester. Relay remains a pilot, not an emergency-service replacement. Physical RF, mobile-network, operational, TLS, key-management, and production-signing validation are required before deployment.

## License

A project license has not been selected. Do not assume permission to redistribute, modify, or commercially use the source code until a license is added.
