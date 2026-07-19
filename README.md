<div align="center">

<img src="docs/assets/relay-logo.svg" alt="Relay — local-first disaster information relay" width="820">

### 災害時の「届かない」を、端末と人の移動でつなぐ。
### Keep critical information moving when networks cannot.

[![Status](https://img.shields.io/badge/status-active%20development-F59E0B?style=for-the-badge)](#開発状況)
[![Android](https://img.shields.io/badge/Android-6.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#クイックスタート)
[![Kotlin](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](#技術構成)
[![Design](https://img.shields.io/badge/design-local--first-0284C7?style=for-the-badge)](#relayの仕組み)
[![Core](https://img.shields.io/badge/core-AI%20not%20required-475569?style=for-the-badge)](#プライバシーとセキュリティ)

## 🌐 Choose your language / 言語を選択

### [🇯🇵 日本語で読む](#日本語版)　　|　　[🇬🇧 Read in English](#english-version)

[Repository guide](docs/REPOSITORY_GUIDE.md) · [Operation model](docs/OPERATION_MODEL.md) · [Architecture](docs/architecture.md) · [Device validation](docs/DEVICE_VALIDATION_REPORT.md)

</div>

> [!CAUTION]
> **Relay is under active development and is not a replacement for emergency services, official alerts, or certified life-safety systems.** Source code and automated checks do not prove real-world radio reachability, deployment safety, or delivery in an actual disaster.
>
> **Relayは開発途中であり、消防・警察・自治体の緊急連絡、公式警報、認証済み人命安全システムを置き換えるものではありません。** コードや自動テストが存在しても、実際の電波到達性、設置安全性、災害時の配送成功を証明するものではありません。

---

# 日本語版

<p align="right"><a href="#english-version">🇬🇧 Switch to English →</a></p>

## Relayを30秒で

Relayは、災害や大規模通信障害によりインターネットが利用不能・不安定・混雑した状況を想定した、**ローカル優先の情報中継プロジェクト**です。

常時接続を前提にせず、端末が情報を保存し、人の移動と端末同士の短い接触を利用して次の端末や固定拠点へ渡す **Store–Carry–Forward（保存・運搬・再転送）** を採用しています。

```text
┌────────┐     ┌────────┐     ┌────────┐     ┌────────────┐
│  作成  │ ──► │  保存  │ ──► │  運ぶ  │ ──► │ 次の接点へ │
└────────┘     └────────┘     └────────┘     └────────────┘
   CREATE          STORE          CARRY            FORWARD
```

- Android端末同士がNearby Connectionsで自動発見・接続し、不足データだけを同期
- 安否、避難状況、物資不足、状態変更を暗号化されたローカルDBへ保存
- 救助要請は避難所公開鍵で暗号化してから保存・中継
- 固定地点ではAndroid BridgeからPC GatewayへLAN同期
- インターネット復旧時は、設定されたHTTPSフィードからHIGH / CRITICAL情報を制限付きで補完
- 電話番号、メールアドレス、永続的ハードウェアIDを必須にしない
- 中核のオフライン配送にAIを必要としない

## 目標と非目標

### 目標

Relayが目指すのは、必ず即時に届く通信ではありません。**接続機会が断続的でも、重要情報が少しずつ前へ進み、端末や固定拠点に安全に保存される仕組み**を作ることです。

### Relayではないもの

- リアルタイムチャットや常時オンラインのメッセンジャー
- 配送時間、最終到達、救助実施を保証するサービス
- 受信情報を自動的に「公式」「本人確認済み」にする仕組み
- 緊急通報、行政防災無線、Jアラートなどの代替
- Relay未導入端末を自動的に中継ノードへ変える仕組み
- 現時点で一般配布・本番運用が完了した製品

## Relayの仕組み

<img src="docs/assets/relay-system-overview.svg" alt="Relayの通常レポート経路と暗号化救助要請経路" width="100%">

Relayには、性質の異なる2つのデータ経路があります。

### ① 通常レポート経路

安否・避難状況・物資不足・状態変更などの情報を扱います。

```text
Androidで作成
  ↓
Room + SQLCipherへ保存
  ↓
NearbyでManifest交換
  ↓
不足IDを要求し、差分だけ送信
  ↓
受信側で形式・サイズ・TTL・hop・重複を検査
  ↓
端末保存 / PC Gateway保存 / Receipt逆伝播
```

主な特徴:

- `messageId`による重複排除とcollision検出
- TTL、累積経過時間、hop上限による転送制御
- Manifest / Request / Data / ACKによる差分同期
- 1ページ最大128件のページ分割
- Peer別ACKとGateway Receiptの保存
- 通常REPORTのcanonical ECDSA P-256署名・検証コード
- 受信量、送信バイト、Payloadサイズ、replay cacheの上限
- Nearby切断時の指数バックオフ再接続

> [!IMPORTANT]
> REPORT署名は改ざん検知に役立ちますが、署名鍵の持ち主が実在人物・自治体・公式組織であることまでは証明しません。またPC Gatewayの既定保存経路では、REPORT内容を自動的に「検証済み」へ昇格させません。

### ② 暗号化救助要請経路

救助要請は、通常レポートとは別のモデル・保存領域・プロトコルで扱います。

```text
救助要請の平文を作成
  ↓ 作成境界でのみ存在
AES-256-GCMで本文を暗号化
  +
RSA-OAEP-256で共通鍵を避難所公開鍵へラップ
  ↓
暗号化EnvelopeだけをRoomへ保存
  ↓
Nearby Courierが暗号文のまま中継
  ↓
信頼済み避難所BLE Bridgeへ提出
  ↓
避難所ReceiptをECDSA P-256で署名
  ↓
Android側でReceiptを検証
```

主な特徴:

- 平文は作成境界からRepositoryへ渡さない
- Courier端末は避難所秘密鍵を持たず、内容を復号できない
- immutableなルーティングヘッダーをAES-GCMのAADへ結合
- ciphertextのSHA-256とサイズを検証
- Nearby用に独立した`RSQ\x01`識別子とInventory / Request / Envelope / ACKを使用
- 同じrequest versionでhashが違うデータをcollision候補として再要求しない
- BLE提出は署名済み地域台帳と避難所identityを照合し、未信頼広告をfail-closedで拒否
- 再接続・プロセス再起動後も同じidempotency keyで再試行
- 有効な署名付き避難所Receiptだけが提出状態を変更

## 主な機能

| 分野 | 現在のコードにあるもの |
|---|---|
| 安否・避難状況 | 無事、負傷、避難中、避難所到着、同行者数、任意の場所・メモ |
| 物資不足 | 水、食料、薬、毛布、電源、衛生用品、その他、必要数、場所、メモ |
| 地域情報 | 保存済みREPORT、発信元区分、hop数、保存・受領状態の表示 |
| 救助要請 | 緊急度、人数、負傷・移動困難・閉じ込め等、支援種別、位置、自由記述 |
| Nearby中継 | 自動広告・探索・決定的接続開始・自動承認・再接続・差分同期 |
| バックグラウンド | connected-device Foreground Service、起動状態保存、救助運搬専用Service |
| ローカル保存 | Room、SQLCipher、Android Keystore保護passphrase、旧平文DB移行 |
| PC Gateway | UDPビーコン、公開/認証Ingress、Ktor、SQLite、管理画面、CSV、Receipt |
| 復旧後同期 | HTTPS限定、port 443、redirect禁止、件数・容量・timeout・version制限 |
| Apple基盤 | Kotlin/Compose Multiplatform framework、Swift Package、GATT・Receipt検証契約 |
| BLE Bridge | Windows .NET 8 / MSIX前提、暗号文をopaqueなままloopback Gatewayへ提出 |
| Meshtastic | Relay coreから分離したJSONL adapter、220-byte予算、15分TTL |
| BPv7 | DTN sidecar向けexport-only JSON境界 |
| 防御的処理 | 不正JSON、未知version、過大データ、期限切れ、過剰hop、replay、path escape拒否 |

## 自動運用モデル

一般利用者が毎回Peerを選択したり、中継地点を登録したりする前提ではありません。

1. 初回に必要なAndroid権限を許可
2. Relay通信を開始
3. 周辺Relay端末を自動探索・接続
4. 保存済み情報を差分同期
5. 同一LANにPC Gatewayがあれば自動発見・同期
6. インターネットが戻れば設定済み優先フィードを限定的に取得
7. 暗号化救助要請の自動運搬が有効なら、信頼済み避難所BLEを探索して提出

GPSは、安否・物資フォームの場所欄が空で、位置権限が許可されている場合だけワンショットで補完します。拒否・測位失敗でも登録でき、常時追跡は行いません。

## 保存・受領表示の意味

Relayは、次の事象を意図的に分離します。

```text
転送要求が完了した
      ≠
相手端末に保存された
      ≠
PC Gatewayに保存された
      ≠
提出経路が認証された
      ≠
内容が真正である
      ≠
最終宛先へ届いた
```

| 状態・事象 | 意味 | 証明しないこと |
|---|---|---|
| Nearby Payload完了 | Google NearbyのPayload転送が完了 | 相手DB保存、内容検証、最終到達 |
| `PEER_RECEIVED` | 別のRelayアプリがDBへ保存 | 本人確認、内容の正しさ、Gateway到達 |
| HTTP 2xx | Gateway HTTP要求が処理された | Receipt保存、公式到達、内容真正性 |
| `GATEWAY_RECEIVED_UNVERIFIED` | 匿名LAN経路でPC GatewayのSQLiteへ保存 | 公式Gateway、内容検証、最終配信 |
| `GATEWAY_RECEIVED` | 認証済みBridge経路でPC Gatewayへ保存 | REPORT発信者本人、本文真正性、最終配信 |
| 署名済みREPORT | canonical REPORTが署名後に変更されていない | 署名鍵の社会的身元・権限・公式性 |
| 署名済みShelter Receipt | 信頼済み避難所鍵でReceiptが署名された | 救助隊の出動・救助完了 |

Nearby経由で受け取った`GATEWAY_RECEIVED`は、経路情報を過信しないため`GATEWAY_RECEIVED_UNVERIFIED`へ保守的にdowngradeして保存します。

## プライバシーとセキュリティ

### 端末内データ

- Room DBはSQLCipherで暗号化
- 32-byte SQLCipher passphraseをランダム生成
- passphrase自体はAndroid KeystoreのAES-GCM鍵で暗号化して保存
- `android:allowBackup="false"`
- 旧バージョンの平文Room DBをread-onlyで開き、暗号化DBへのコピー成功後だけ置換
- 失敗時は元DBへrollbackする設計
- 2026年7月19日に、リセット済みAVDで平文→暗号化移行とpassphrase storeのinstrumentation testがPASS

### 通信・入力

- JSON decode前にPayloadサイズを制限
- Peer単位の受信件数・送信バイト・時間窓制限
- packet ID replay cache
- 不正identifier、enum、TTL、hop、payload、hash、signatureを拒否
- HTTPS優先フィードはHTTPS・port 443のみで、user info、query、fragment、redirectを拒否
- PC Gateway公開Ingressはレート制限、DB上限、形式検証、collision検出を適用

### 既知の境界・残存リスク

- PC GatewayのLAN HTTPはTLSなし。Public / guest Wi-Fiへ公開しない
- UDPビーコンは発見手段であり認証ではない
- 認証済みBridgeも、Nearbyで運んだ第三者REPORTの発信者本人を証明しない
- REPORT署名鍵の社会的な本人確認・組織認証・失効基盤は完成していない
- 複数Gatewayの信頼ランキングは未実装
- Windows EXE / MSIXの本番コード署名と組織Publisherはリポジトリに含まれない
- 本番TUF / cosign運用には、信頼されたオフライン鍵と正式なリリース手順が必要

## 開発状況

この表は、**現在のソースを読むためのスナップショット**です。製品完成・災害運用準備完了を宣言するものではありません。

| コンポーネント | 実装 | 自動・仮想検証 | 物理・運用検証 |
|---|---:|---:|---:|
| Android UI / Domain / Room | ✅ | ✅ Unit・UI契約・AVD | 🚧 多機種・長時間・実運用 |
| SQLCipher / 旧DB移行 | ✅ | ✅ 2026-07-19 AVD PASS | 🚧 多OS・大容量・実端末更新 |
| Nearby Store–Carry–Forward | ✅ | ✅ Fake多段・host契約 | 🚧 2台以上の実機RF |
| 自動接続・再接続 | ✅ | ✅ Unit / deterministic tests | 🚧 メーカー別・省電力・画面消灯 |
| Foreground Service | ✅ | ✅ 実装契約・unit | 🚧 Androidバージョン別長時間運用 |
| PC Gateway | ✅ | ✅ build・HTTP・recovery fixture・PC smoke記録 | 🚧 Phone→PC実機E2E |
| 通常REPORT署名 | ✅ | ✅ JVM crypto tests | 🚧 鍵配布・失効・公式identity運用 |
| 暗号化救助要請 | ✅ | ✅ crypto・repository・Nearby契約 | 🚧 複数実機と避難所運用 |
| BLE避難所提出 | ✅ Android / Windows基盤 | ✅ 仮想GATT・contract | 🚧 物理BLE advertisement/write/indicate |
| HTTPS優先フィード | ✅ | ✅ URL・容量・version・scheduler tests | 🚧 実配信元・署名・運用合意 |
| Apple / iOS基盤 | 🧪 Source / framework | ✅ Swift/KMP build手順 | 🚧 IPA・CoreBluetooth実機 |
| Meshtastic adapter | 🧪 分離adapter | ✅ Python contract | 🚧 実無線機・地域運用 |
| BPv7 export | 🧪 export-only | ✅ Mapping contract | 🚧 実DTN sidecar |
| 配布保護 | 🧪 scripts / metadata | ✅ SBOM・scan・TUF chain | 🚧 本番鍵・署名済み正式配布 |

## 検証スナップショット

### 確認済みとして記録されているもの

- 2026年7月16日: product tests 78件、0 failure
- 2026年7月16日: Debug APK rebuild PASS
- 2026年7月16日: PC Gateway health、UI、公開Ingress、CSV、pair、admin key smoke PASS
- 2026年7月19日: headless Android 16 emulatorで現行APK起動・再起動後DB再open PASS
- 2026年7月19日: DB headerが平文SQLite headerではないことを確認
- 2026年7月19日: `PlaintextDatabaseMigrationTest` 1件 PASS
- 2026年7月19日: `SqlCipherPassphraseStoreTest` 2件 PASS

### 現在も未検証として扱うもの

- 実Android端末2台以上でのNearby同期
- A → B → Cの物理RF多段中継
- 実Android Phone → PC Gateway公開同期
- 切断・再接続・画面消灯・省電力下の長時間挙動
- Android Courier → Windows BLE Bridgeの物理GATT E2E
- iPhone CoreBluetooth / IPA
- Meshtastic実無線、BPv7実DTN
- 本番署名鍵を使った正式配布

日付付きの正本は[`docs/DEVICE_VALIDATION_REPORT.md`](docs/DEVICE_VALIDATION_REPORT.md)です。コード変更後は、古いPASSを現在のHEADへ自動的に引き継がないでください。

## クイックスタート

### 必要環境

- JDK 17
- Android Studio
- Android SDK 36
- Android Build Tools 36.0.0（`app`）
- 初回依存取得時のインターネット接続
- Python 3.12相当（host / adapter tests）
- PowerShell 7推奨（検証・配布スクリプト）

### AndroidとGatewayをビルド

Windows PowerShell:

```powershell
.\gradlew.bat :app:testDebugUnitTest :shared:jvmTest :relay-protocol:test :pc-gateway:test
.\gradlew.bat :composeApp:desktopTest
.\gradlew.bat :app:assembleDebug :pc-gateway:build
```

macOS / Linux:

```bash
./gradlew :app:testDebugUnitTest :shared:jvmTest :relay-protocol:test :pc-gateway:test
./gradlew :composeApp:desktopTest
./gradlew :app:assembleDebug :pc-gateway:build
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

> [!NOTE]
> APKのビルド成功は、Nearby電波、runtime permission、Foreground Service、画面消灯、再接続が物理端末で成功することを意味しません。

### PC Gatewayを起動

Windows:

```powershell
.\Start-PC-Gateway.cmd
```

macOS / Linux:

```bash
chmod +x scripts/run-pc-gateway.sh
./scripts/run-pc-gateway.sh
```

既定値:

| 項目 | 値 |
|---|---|
| Operator console | `http://127.0.0.1:8080/` |
| Health | `http://127.0.0.1:8080/api/health` |
| Public ingress | `POST /api/public/sync/messages` |
| Authenticated ingress | `POST /api/sync/messages` |
| LAN discovery | UDP `42888` |
| LAN HTTP bind | `0.0.0.0:8080` |

固定地点へ導入する前に、[`PC_GATEWAY_SETUP.md`](docs/PC_GATEWAY_SETUP.md)と[`PC_GATEWAY_SECURITY.md`](docs/PC_GATEWAY_SECURITY.md)を確認してください。

### Host contracts

```powershell
.\test-lab\run-host-checks.ps1 -IncludeGradle
```

このrunnerはfault matrix、Meshtastic mock / tests、必要に応じてshared JVMとCompose desktop testsを実行します。

### Apple基盤

macOS:

```bash
cd apple/RelayAppleKit
swift test
cd ../..
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```

Simulator frameworkはIPAではありません。実機にはXcode Archive、Apple Developer署名、provisioning、CoreBluetooth権限が必要です。

## CI・品質・配布検証

`.github/workflows/relay-ci.yml`には次のjobがあります。

| 分類 | 内容 |
|---|---|
| Build | Android Debug APK、PC Gateway JVM artifact |
| Unit | shared JVM、Android unit tests |
| UI | Compose Multiplatform desktop smoke |
| Implementation contracts | SQLCipher、Keystore、FGS、権限、backup、署名検証境界の存在確認 |
| Accessibility | UI semanticsと安全表示契約 |
| Fuzz | Jazzer parser regression |
| APK scan | MobSF（設定時） |
| Dependency scan | Syft SBOM、OSV-Scanner、Grype |
| Distribution | cosign bundle / artifact verification scripts |
| Gateway recovery | backup / restoreとtransport fixtures |
| Hardware-independent | Mobly host、virtual BLE、decoder regression、TUF metadata chain |

現在の`SECURITY_MODE`は`report-only`です。検査jobが存在することは、本番リリースが認証・署名済みであることを意味しません。

## 技術構成

- Kotlin / JVM 17
- Kotlin Multiplatform
- Jetpack Compose / Material 3
- Compose Multiplatform
- Coroutines / Flow / StateFlow
- Room
- SQLCipher for Android
- Android Keystore / AES-GCM
- kotlinx.serialization
- Google Nearby Connections
- Ktor / Netty
- SQLite JDBC
- AES-256-GCM
- RSA-OAEP-256（3072-bit RSA key生成）
- ECDSA P-256 / SHA-256
- Swift Package / CoreBluetooth contracts
- .NET 8 / Windows GATT peripheral / MSIX model
- Python host tests and edge adapters
- GitHub Actions / Syft / OSV / Grype / MobSF / Jazzer / cosign / TUF

## リポジトリ構成

### Gradleモジュール

| モジュール | 役割 |
|---|---|
| `:app` | Android本体、Compose、Room、SQLCipher、Nearby、Service、Gateway同期 |
| `:shared` | KMP共有モデル、救助暗号、REPORT署名、QR・信頼文書関連 |
| `:relay-protocol` | PC Gatewayと共有するDTO・wire contract |
| `:pc-gateway` | Ktor / Netty / SQLite / LAN beacon / operator console |
| `:composeApp` | Compose Multiplatform UI、desktop、iOS framework |

### リポジトリ全体

```text
Relay/
├─ app/                         Android app
├─ shared/                      KMP models, crypto and shared logic
├─ relay-protocol/              Gateway DTO and wire contracts
├─ composeApp/                  Compose Multiplatform UI / iOS framework
├─ pc-gateway/                  JVM Gateway
├─ pc-ble-bridge/               Windows BLE peripheral boundary
├─ gateway-meshtastic-adapter/  Isolated Meshtastic adapter
├─ gateway-bp7-export/          BPv7 export-only boundary
├─ apple/                       Swift package and Apple contracts
├─ test-lab/                    Mobly, fuzz and fault injection
├─ tools/ble-sim/               Deterministic virtual GATT harness
├─ maestro/                     UI flows
├─ distribution/                Release inputs and TUF metadata
├─ scripts/                     Build, startup, backup, scan and verification
├─ docs/                        Canonical design, security and runbooks
└─ artifacts/                   Dated/local evidence; mostly untracked
```

## 推奨ドキュメント

1. [`docs/REPOSITORY_GUIDE.md`](docs/REPOSITORY_GUIDE.md) — 変更場所と責務境界
2. [`docs/OPERATION_MODEL.md`](docs/OPERATION_MODEL.md) — 一般利用者と固定中継地点の運用
3. [`docs/architecture.md`](docs/architecture.md) — 通常レポート同期アーキテクチャ
4. [`docs/PC_GATEWAY_ARCHITECTURE.md`](docs/PC_GATEWAY_ARCHITECTURE.md) — Gateway経路・保存・Receipt
5. [`docs/PC_GATEWAY_SECURITY.md`](docs/PC_GATEWAY_SECURITY.md) — 脅威、制御、残存リスク
6. [`docs/DEVICE_TEST_PLAN.md`](docs/DEVICE_TEST_PLAN.md) — 物理端末試験計画
7. [`docs/DEVICE_VALIDATION_REPORT.md`](docs/DEVICE_VALIDATION_REPORT.md) — 日付付き検証記録
8. [`docs/APPLE_TARGETS.md`](docs/APPLE_TARGETS.md) — Apple成果物と未完了範囲
9. [`pc-ble-bridge/README.md`](pc-ble-bridge/README.md) — Windows BLE境界
10. [`gateway-meshtastic-adapter/README.md`](gateway-meshtastic-adapter/README.md) — Meshtastic adapter
11. [`gateway-bp7-export/README.md`](gateway-bp7-export/README.md) — BPv7 export boundary

## コントリビューション方針

このリポジトリは設計・実装ともに更新中です。

- 大きな変更の前に運用モデルとリポジトリ案内を読む
- 通常REPORTと暗号化救助Envelopeの境界を崩さない
- 外部transportをRelay coreへ直接結合せず、明示的なadapter / export境界に置く
- 「保存」「経路認証」「内容検証」「最終到達」を混同しない
- まず最小の決定的unit / host testを追加する
- 実機が必要な検証をhost testのPASSで代用しない
- 秘密鍵、keystore、PFX、API key、実機ログ、SQLite DB、生成APKをコミットしない
- 仕様変更時は日本語と英語を同じ内容で更新する

## README更新チェック

- [ ] 利用者から見える機能が現在のUIと一致している
- [ ] 通常REPORTと救助要請を別々に説明している
- [ ] 実装、自動検証、物理検証を分離している
- [ ] Receiptの意味を過大に表現していない
- [ ] 暗号・署名が身元や公式性を自動証明すると書いていない
- [ ] ビルドコマンドと成果物パスが現行コードに合っている
- [ ] 日本語版と英語版の章・表・注意点が一致している
- [ ] 詳細仕様は`docs/`の正本へリンクしている

## ライセンス

ライセンスは未選定です。ライセンスが追加されるまでは、このコードを再配布・改変・商用利用する許可があるものとみなさないでください。

## 安全上の注意

公式警報、行政情報、消防、警察、医療、緊急通報が利用できる場合は必ずそちらを優先してください。Relayは実験的な耐障害プロジェクトであり、認証済み人命安全システムではありません。

<p align="right"><a href="#choose-your-language--言語を選択">↑ 言語選択へ戻る</a> · <a href="#english-version">🇬🇧 English version →</a></p>

---

# English version

<p align="right"><a href="#日本語版">← 🇯🇵 日本語版へ</a></p>

## Relay in 30 seconds

Relay is a **local-first information relay project** designed for disasters and large-scale communication failures where internet connectivity is unavailable, intermittent, congested, or unreliable.

Instead of assuming continuous connectivity, Relay uses **Store–Carry–Forward**: a device stores information, a person physically carries the device, and the information is forwarded during a later brief encounter with another device or fixed site.

```text
┌────────┐     ┌────────┐     ┌────────┐     ┌──────────────┐
│ CREATE │ ──► │ STORE  │ ──► │ CARRY  │ ──► │ NEXT CONTACT │
└────────┘     └────────┘     └────────┘     └──────────────┘
```

- Android devices automatically discover and connect with Nearby Connections, then exchange only missing data
- Safety, evacuation, supply, and status-change records are stored in an encrypted local database
- Rescue requests are encrypted to a shelter public key before storage or forwarding
- At fixed sites, an Android bridge synchronizes to a PC Gateway over the LAN
- After internet recovery, an optional configured HTTPS feed can supplement HIGH / CRITICAL information within strict limits
- Phone numbers, email addresses, and persistent hardware identifiers are not mandatory
- Core offline delivery does not require AI

## Goals and non-goals

### Goal

Relay does not aim to guarantee immediate communication. Its goal is to create a system where **important information can continue making gradual progress and be stored safely even when contact opportunities are brief and intermittent**.

### Relay is not

- A real-time chat or continuously online messenger
- A service that guarantees delivery time, final arrival, or rescue action
- A mechanism that automatically makes received information official or identity-verified
- A replacement for emergency calls, government alerts, or public warning systems
- A mechanism that turns phones without Relay installed into automatic relay nodes
- A finished, generally distributed production product

## How Relay works

<img src="docs/assets/relay-system-overview.svg" alt="Relay ordinary report lane and encrypted rescue-request lane" width="100%">

Relay contains two data lanes with different security and delivery properties.

### ① Ordinary report lane

This lane carries safety, evacuation, supply-shortage, and status-change information.

```text
Create on Android
  ↓
Store in Room + SQLCipher
  ↓
Exchange manifests over Nearby
  ↓
Request missing IDs and transfer only differences
  ↓
Validate format, size, TTL, hop count, and duplicates
  ↓
Store on a peer / PC Gateway / propagate receipts back
```

Key properties:

- Deduplication and collision detection by `messageId`
- Forwarding policy based on TTL, accumulated age, and hop limits
- Manifest / Request / Data / ACK differential synchronization
- Pagination of up to 128 entries per page
- Peer-specific ACK state and Gateway receipt storage
- Canonical ECDSA P-256 signing and verification code for ordinary REPORT records
- Limits for received items, sent bytes, payload size, and replay-cache entries
- Exponential-backoff reconnection after Nearby disconnection

> [!IMPORTANT]
> A REPORT signature can detect modification after signing, but it does not prove that the key belongs to a real person, government body, or authorized organization. The default PC Gateway storage path also does not automatically promote REPORT content to “verified.”

### ② Encrypted rescue-request lane

Rescue requests use a separate model, storage area, and protocol from ordinary reports.

```text
Create rescue plaintext
  ↓ plaintext exists only at the creation boundary
Encrypt payload with AES-256-GCM
  +
Wrap the content key to the shelter public key with RSA-OAEP-256
  ↓
Store only the encrypted envelope in Room
  ↓
Nearby couriers forward ciphertext without decrypting it
  ↓
Submit to a trusted shelter BLE bridge
  ↓
Shelter signs a receipt with ECDSA P-256
  ↓
Android verifies the receipt
```

Key properties:

- Plaintext is never passed from the creation boundary to the repository
- Courier devices do not hold shelter private keys and cannot decrypt the request
- Immutable routing headers are bound as AES-GCM authenticated additional data
- Ciphertext SHA-256 and byte length are verified
- A dedicated `RSQ\x01` discriminator and Inventory / Request / Envelope / ACK protocol are used over Nearby
- A same-version, different-hash item is treated as a collision candidate and is not repeatedly requested
- BLE delivery checks a signed regional directory and shelter identity; untrusted advertisements fail closed
- Retries reuse a durable idempotency key after disconnects or process restarts
- Only a valid signed shelter receipt changes submission state

## Main capabilities

| Area | Present in the current code |
|---|---|
| Safety and evacuation | Safe, injured, evacuating, at-shelter, companion count, optional location and note |
| Supply shortages | Water, food, medicine, blankets, power, hygiene, other items, count, location, note |
| Regional information | Stored reports, origin category, hop count, storage and receipt state |
| Rescue requests | Urgency, people count, injuries, mobility, trapped/collapse risk, support needs, location, free text |
| Nearby relay | Automatic advertising, discovery, deterministic connection initiation, auto-accept, reconnect, differential sync |
| Background operation | Connected-device foreground service, persisted activation, dedicated rescue-carry service |
| Local storage | Room, SQLCipher, Android-Keystore-protected passphrase, legacy plaintext DB migration |
| PC Gateway | UDP beacon, public/authenticated ingress, Ktor, SQLite, console, CSV, receipts |
| Recovery sync | HTTPS only, port 443, no redirects, bounded items, bytes, timeout, and version |
| Apple foundation | Kotlin/Compose Multiplatform framework, Swift Package, GATT and receipt-verification contracts |
| BLE bridge | Windows .NET 8 / MSIX model, opaque encrypted handoff to loopback Gateway ingress |
| Meshtastic | Out-of-process JSONL adapter, 220-byte budget, 15-minute TTL |
| BPv7 | Export-only JSON boundary for a DTN sidecar |
| Defensive processing | Reject malformed JSON, unknown versions, oversized data, expiry, hop abuse, replay, and path escape |

## Automatic operating model

Ordinary users are not expected to manually select peers or register each relay site.

1. Grant required Android permissions once
2. Start Relay communication
3. Automatically discover and connect to nearby Relay devices
4. Differentially synchronize stored information
5. Automatically discover and synchronize to a PC Gateway on the same LAN
6. When internet returns, fetch the configured bounded priority feed
7. When rescue auto-carry is enabled, scan for a trusted shelter BLE endpoint and submit encrypted requests

GPS is used only as an optional one-shot fill when a safety or supply form has an empty location field and permission is available. Saving still succeeds when permission is denied or a fix is unavailable. Relay does not continuously track location.

## Meaning of storage and receipt states

Relay deliberately separates the following events:

```text
Transfer request completed
      ≠
Stored by another device
      ≠
Stored by a PC Gateway
      ≠
Submission route authenticated
      ≠
Content authentic
      ≠
Delivered to final destination
```

| State or event | Means | Does not prove |
|---|---|---|
| Nearby payload complete | Google Nearby reported transfer completion | Peer DB storage, content verification, final arrival |
| `PEER_RECEIVED` | Another Relay application stored the record | Identity, content truth, Gateway arrival |
| HTTP 2xx | The Gateway HTTP request was processed | Receipt storage, official arrival, authenticity |
| `GATEWAY_RECEIVED_UNVERIFIED` | A PC Gateway stored it through anonymous LAN ingress | Official Gateway, verified content, final delivery |
| `GATEWAY_RECEIVED` | A PC Gateway stored it through an authenticated bridge route | Original reporter identity, body authenticity, final delivery |
| Signed REPORT | Canonical REPORT content was not changed after signing | Social identity, authority, or official status of the key owner |
| Signed shelter receipt | A receipt was signed by a trusted shelter key | Rescue-team dispatch or completed rescue |

A `GATEWAY_RECEIVED` receipt received through Nearby is conservatively downgraded to `GATEWAY_RECEIVED_UNVERIFIED` before local storage, because the receiving peer cannot safely inherit the authenticated route context.

## Privacy and security

### Data at rest

- The Room database is encrypted with SQLCipher
- A random 32-byte SQLCipher passphrase is generated
- The passphrase is encrypted using an Android Keystore AES-GCM key
- `android:allowBackup="false"`
- A legacy plaintext Room database is opened read-only and replaced only after a successful encrypted copy
- Migration failure rolls back to the original database
- On July 19, 2026, plaintext-to-encrypted migration and passphrase-store instrumentation tests passed on a reset AVD

### Communication and input handling

- Payload size is checked before JSON decoding
- Per-peer received-item, sent-byte, and time-window limits
- Packet-ID replay cache
- Reject invalid identifiers, enums, TTL, hops, payloads, hashes, and signatures
- The priority feed requires HTTPS on port 443 and rejects user info, query, fragment, and redirects
- Public PC Gateway ingress applies rate limits, DB limits, format validation, and collision detection

### Known boundaries and residual risks

- PC Gateway LAN HTTP has no TLS; never expose it to public or guest Wi-Fi
- UDP beacons provide discovery, not authentication
- An authenticated bridge may submit third-party reports carried through Nearby and therefore does not prove the original reporter
- Social identity verification, organizational authorization, revocation, and PKI for REPORT signing keys are incomplete
- Trust ranking across multiple Gateways is not implemented
- Production code signing and organizational publisher identity for Windows EXE / MSIX are not included
- Production TUF / cosign operation requires trusted offline keys and a formal release process

## Development status

This table is a **source-oriented snapshot**, not a production-readiness or disaster-deployment declaration.

| Component | Implementation | Automated / virtual verification | Physical / operational verification |
|---|---:|---:|---:|
| Android UI / Domain / Room | ✅ | ✅ Unit, UI contracts, AVD | 🚧 Device diversity, long-running operation |
| SQLCipher / legacy migration | ✅ | ✅ 2026-07-19 AVD PASS | 🚧 Multiple OS versions, large real DB upgrades |
| Nearby Store–Carry–Forward | ✅ | ✅ Fake multi-hop, host contracts | 🚧 Two-or-more physical-device RF |
| Auto-connect and reconnect | ✅ | ✅ Unit / deterministic tests | 🚧 Vendor power management and screen-off |
| Foreground services | ✅ | ✅ Implementation contracts and unit tests | 🚧 Long-running behavior across Android versions |
| PC Gateway | ✅ | ✅ Build, HTTP, recovery fixtures, dated PC smoke | 🚧 Physical Phone-to-PC E2E |
| Ordinary REPORT signing | ✅ | ✅ JVM crypto tests | 🚧 Key distribution, revocation, official identity |
| Encrypted rescue requests | ✅ | ✅ Crypto, repository, Nearby contracts | 🚧 Multi-device and shelter operations |
| Shelter BLE delivery | ✅ Android / Windows foundation | ✅ Virtual GATT and contracts | 🚧 Physical BLE advertise/write/indicate |
| HTTPS priority feed | ✅ | ✅ URL, byte, version, scheduler tests | 🚧 Real publisher, signing, operating agreement |
| Apple / iOS foundation | 🧪 Source / framework | ✅ Swift/KMP build procedures | 🚧 IPA and physical CoreBluetooth |
| Meshtastic adapter | 🧪 Isolated adapter | ✅ Python contracts | 🚧 Real radios and field operation |
| BPv7 export | 🧪 Export-only | ✅ Mapping contract | 🚧 Real DTN sidecar |
| Distribution protection | 🧪 Scripts / metadata | ✅ SBOM, scans, TUF chain | 🚧 Production keys and signed release process |

## Validation snapshot

### Dated checks recorded as successful

- July 16, 2026: 78 product tests, 0 failures
- July 16, 2026: Debug APK rebuild passed
- July 16, 2026: PC Gateway health, UI, public ingress, CSV, pairing, and admin-key smoke passed
- July 19, 2026: Current APK launched and reopened its DB on a headless Android 16 emulator
- July 19, 2026: Database header was confirmed not to contain the plaintext SQLite header
- July 19, 2026: `PlaintextDatabaseMigrationTest` — 1 test passed
- July 19, 2026: `SqlCipherPassphraseStoreTest` — 2 tests passed

### Still treated as unverified

- Nearby sync between two or more physical Android devices
- Physical RF A → B → C multi-hop relay
- Physical Android Phone → PC Gateway public sync
- Long-running disconnect, reconnect, screen-off, and power-management behavior
- Physical Android courier → Windows BLE Bridge GATT E2E
- iPhone CoreBluetooth / IPA
- Real Meshtastic radio and real BPv7 DTN integration
- Formal distribution with production signing keys

The dated source of truth is [`docs/DEVICE_VALIDATION_REPORT.md`](docs/DEVICE_VALIDATION_REPORT.md). Do not automatically carry an old PASS forward to a changed HEAD revision.

## Quick start

### Requirements

- JDK 17
- Android Studio
- Android SDK 36
- Android Build Tools 36.0.0 for `app`
- Internet access for initial dependency download
- Python 3.12-class runtime for host and adapter tests
- PowerShell 7 recommended for verification and distribution scripts

### Build Android and Gateway

Windows PowerShell:

```powershell
.\gradlew.bat :app:testDebugUnitTest :shared:jvmTest :relay-protocol:test :pc-gateway:test
.\gradlew.bat :composeApp:desktopTest
.\gradlew.bat :app:assembleDebug :pc-gateway:build
```

macOS / Linux:

```bash
./gradlew :app:testDebugUnitTest :shared:jvmTest :relay-protocol:test :pc-gateway:test
./gradlew :composeApp:desktopTest
./gradlew :app:assembleDebug :pc-gateway:build
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

> [!NOTE]
> A successful APK build does not prove Nearby RF behavior, runtime permissions, foreground-service behavior, screen-off operation, or reconnection on physical devices.

### Start PC Gateway

Windows:

```powershell
.\Start-PC-Gateway.cmd
```

macOS / Linux:

```bash
chmod +x scripts/run-pc-gateway.sh
./scripts/run-pc-gateway.sh
```

Defaults:

| Item | Value |
|---|---|
| Operator console | `http://127.0.0.1:8080/` |
| Health | `http://127.0.0.1:8080/api/health` |
| Public ingress | `POST /api/public/sync/messages` |
| Authenticated ingress | `POST /api/sync/messages` |
| LAN discovery | UDP `42888` |
| LAN HTTP bind | `0.0.0.0:8080` |

Read [`PC_GATEWAY_SETUP.md`](docs/PC_GATEWAY_SETUP.md) and [`PC_GATEWAY_SECURITY.md`](docs/PC_GATEWAY_SECURITY.md) before fixed-site deployment.

### Host contracts

```powershell
.\test-lab\run-host-checks.ps1 -IncludeGradle
```

The runner executes the fault matrix, Meshtastic mock and tests, and optionally shared JVM and Compose desktop tests.

### Apple foundation

On macOS:

```bash
cd apple/RelayAppleKit
swift test
cd ../..
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```

A simulator framework is not an IPA. Physical deployment requires an Xcode archive, Apple Developer signing, provisioning, and CoreBluetooth permissions.

## CI, quality, and distribution verification

`.github/workflows/relay-ci.yml` defines the following jobs.

| Category | Coverage |
|---|---|
| Build | Android Debug APK and PC Gateway JVM artifact |
| Unit | Shared JVM and Android unit tests |
| UI | Compose Multiplatform desktop smoke |
| Implementation contracts | Presence of SQLCipher, Keystore, FGS, permissions, backup, and signature-verification boundaries |
| Accessibility | UI semantics and safety-wording contracts |
| Fuzz | Jazzer parser regression |
| APK scan | MobSF when configured |
| Dependency scan | Syft SBOM, OSV-Scanner, Grype |
| Distribution | cosign-bundle and artifact-verification scripts |
| Gateway recovery | Backup / restore and transport fixtures |
| Hardware-independent | Mobly host, virtual BLE, decoder regression, TUF metadata chain |

The current `SECURITY_MODE` is `report-only`. The presence of scanning jobs does not mean that a production release is certified or formally signed.

## Technology

- Kotlin / JVM 17
- Kotlin Multiplatform
- Jetpack Compose / Material 3
- Compose Multiplatform
- Coroutines / Flow / StateFlow
- Room
- SQLCipher for Android
- Android Keystore / AES-GCM
- kotlinx.serialization
- Google Nearby Connections
- Ktor / Netty
- SQLite JDBC
- AES-256-GCM
- RSA-OAEP-256 with 3072-bit RSA key generation
- ECDSA P-256 / SHA-256
- Swift Package / CoreBluetooth contracts
- .NET 8 / Windows GATT peripheral / MSIX model
- Python host tests and edge adapters
- GitHub Actions / Syft / OSV / Grype / MobSF / Jazzer / cosign / TUF

## Repository structure

### Gradle modules

| Module | Responsibility |
|---|---|
| `:app` | Android application, Compose, Room, SQLCipher, Nearby, services, Gateway sync |
| `:shared` | KMP models, rescue cryptography, REPORT signing, QR and trust-document logic |
| `:relay-protocol` | DTOs and wire contracts shared with PC Gateway |
| `:pc-gateway` | Ktor / Netty / SQLite / LAN beacon / operator console |
| `:composeApp` | Compose Multiplatform UI, desktop, iOS framework |

### Entire repository

```text
Relay/
├─ app/                         Android app
├─ shared/                      KMP models, crypto and shared logic
├─ relay-protocol/              Gateway DTO and wire contracts
├─ composeApp/                  Compose Multiplatform UI / iOS framework
├─ pc-gateway/                  JVM Gateway
├─ pc-ble-bridge/               Windows BLE peripheral boundary
├─ gateway-meshtastic-adapter/  Isolated Meshtastic adapter
├─ gateway-bp7-export/          BPv7 export-only boundary
├─ apple/                       Swift package and Apple contracts
├─ test-lab/                    Mobly, fuzz and fault injection
├─ tools/ble-sim/               Deterministic virtual GATT harness
├─ maestro/                     UI flows
├─ distribution/                Release inputs and TUF metadata
├─ scripts/                     Build, startup, backup, scan and verification
├─ docs/                        Canonical design, security and runbooks
└─ artifacts/                   Dated/local evidence; mostly untracked
```

## Recommended documentation

1. [`docs/REPOSITORY_GUIDE.md`](docs/REPOSITORY_GUIDE.md) — change locations and responsibility boundaries
2. [`docs/OPERATION_MODEL.md`](docs/OPERATION_MODEL.md) — ordinary-user and fixed-site operation
3. [`docs/architecture.md`](docs/architecture.md) — ordinary-report synchronization architecture
4. [`docs/PC_GATEWAY_ARCHITECTURE.md`](docs/PC_GATEWAY_ARCHITECTURE.md) — Gateway routes, storage, and receipts
5. [`docs/PC_GATEWAY_SECURITY.md`](docs/PC_GATEWAY_SECURITY.md) — threats, controls, and residual risks
6. [`docs/DEVICE_TEST_PLAN.md`](docs/DEVICE_TEST_PLAN.md) — physical-device test plan
7. [`docs/DEVICE_VALIDATION_REPORT.md`](docs/DEVICE_VALIDATION_REPORT.md) — dated validation evidence
8. [`docs/APPLE_TARGETS.md`](docs/APPLE_TARGETS.md) — Apple artifacts and unfinished scope
9. [`pc-ble-bridge/README.md`](pc-ble-bridge/README.md) — Windows BLE boundary
10. [`gateway-meshtastic-adapter/README.md`](gateway-meshtastic-adapter/README.md) — Meshtastic adapter
11. [`gateway-bp7-export/README.md`](gateway-bp7-export/README.md) — BPv7 export boundary

## Contribution principles

This repository is still evolving in both design and implementation.

- Read the operation model and repository guide before major changes
- Preserve the boundary between ordinary REPORTs and encrypted rescue envelopes
- Keep external transports behind explicit adapter or export boundaries instead of coupling them directly into Relay core
- Never conflate storage, route authentication, content verification, and final delivery
- Add the smallest deterministic unit or host test first
- Do not substitute a host-test PASS for a validation that requires physical hardware
- Do not commit private keys, keystores, PFX files, API keys, device logs, SQLite databases, or generated APKs
- Update Japanese and English with the same content whenever specifications change

## README maintenance checklist

- [ ] User-visible capabilities match the current UI
- [ ] Ordinary REPORTs and rescue requests are explained separately
- [ ] Implementation, automated verification, and physical verification are separated
- [ ] Receipt semantics are not overstated
- [ ] Encryption or signatures are not described as automatic proof of social identity or official authority
- [ ] Build commands and artifact paths match current code
- [ ] Japanese and English contain the same sections, tables, and warnings
- [ ] Detailed specifications link to canonical documents under `docs/`

## License

No license has been selected. Until a license is added, do not assume permission to redistribute, modify, or commercially use this code.

## Safety note

Always prioritize official alerts, government information, fire, police, medical services, and emergency calls when they are available. Relay is an experimental resilience project, not a certified life-safety system.

<p align="right"><a href="#choose-your-language--言語を選択">↑ Back to language selection</a> · <a href="#日本語版">🇯🇵 日本語版へ</a></p>
