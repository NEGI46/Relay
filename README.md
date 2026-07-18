# Relay

> 到達性と端末ロールの設計は [docs/REACHABILITY_AND_ALTERNATIVE_TRANSPORTS.md](docs/REACHABILITY_AND_ALTERNATIVE_TRANSPORTS.md) を参照してください。Relayをインストールしていないスマートフォンは自動中継端末にはならず、固定Relay、Gateway、Courierの配置と導入済み端末の移動が到達性を左右します。

Relay は、災害時にインターネット接続がなくても、近くの Android 端末同士で安否情報と物資不足情報を交換する Store–Carry–Forward 型の実証用アプリです。受信した情報を端末内に保存し、利用者が移動して別の端末と接続した際に再転送します。リアルタイムチャットではありません。

このプロジェクトは AI 機能を使用しません。電話番号・メール・ハードウェア ID は必須にしません。安否・物資登録時は許可されていれば GPS で場所を補完します（拒否・未取得でも登録可能）。

## フェーズと現在の範囲

| フェーズ | 内容 | 状態 |
|---|---|---|
| 1 | 要件、責務境界、データモデル、同期プロトコル、技術リスクの設計 | 設計済み |
| 2 | Android 基盤、Compose UI、Room、Repository、UseCase、Fake transport、同期ロジックと単体テスト | 実装・自動テスト済み |
| 3 | Nearby Connections 実通信、OS 権限、Foreground Service | 実装済み。Android 2台実機同期は未検証 |
| 4 | 防御的観測性、信頼表示、オペレータ向けデバッグ、基本 a11y、PC Gateway zero-op | **アプリ完成（ソフトウェア + PC Gateway ゲート）** |
| 5 | 実機 RF 検証（Phone public sync、2台 Nearby） | residual（物理端末/エミュレータ RF 環境依存） |

フェーズ2では、実機の Nearby Connections に依存せず、`FakeOfflineTransport` を使って同一テストプロセス内で複数端末の同期を検証します。インターネット復旧後のサーバー同期、電子署名、暗号方式の追加点はインターフェースとして用意し、現行では NoOp 実装とします。

詳細設計は [docs/architecture.md](docs/architecture.md) を参照してください。

## 完成判定（このアプリの完成）

**アプリ完成** とは、災害時 Store–Carry–Forward と zero-operation PC Gateway の **製品パスがコード上閉じている** ことです。

ゲート:

1. `testDebugUnitTest` / `:relay-protocol:test` / `:pc-gateway:test` / `assembleDebug` が 0 failures
2. 実 `installDist` 起動で `GET /api/health` + 公開 `POST` が `GATEWAY_RECEIVED_UNVERIFIED`（連続 2 回）
3. 多段 SCF・送信失敗観測・HTTP 公開クライアント・信頼ラベルが shipped コードの自動テストで証明済み

**残差（製品バグではなく環境限界のみ）**

- 物理 Android / Nearby RF の multi-device 成功は端末が無いと証明できない（ソフトウェア側は Fake 多段 + Nearby 実装で完了）
- Firewall / 自動起動スクリプトは管理者権限が必要
- メッセージ署名は NoOp（spoofable は設計上の非ゴール）
- Play Store 署名・本番 PKI は非ゴール

現状: [artifacts/relay_status.json](artifacts/relay_status.json)

## ローカル優先とインターネット復帰

- **既定はすべてローカル**: Nearby 自動中継 + 同一LANの PC Gateway 公開同期（登録・ペアリング不要）
- **GPS**: 作成時に場所が空ならワンショットで補完（連続追跡しない）
- **インターネット復帰**: 接続検知時に重要度 HIGH/CRITICAL の優先情報をローカルDBへ追加取り込み（`InternetPrioritySync`）。オフライン SCF は継続

## MVP の主要機能

- 定型フォーム中心の安否情報登録
- 定型フォーム中心の物資不足情報登録
- Room による作成・受信メッセージの永続化
- `messageId` の一意制約による重複排除
- TTL、最大中継回数、Peer別ACKを考慮した多段中継
- ManifestによるID一覧交換後の差分同期
- 高優先度メッセージからの送信
- ホーム、地域情報、近隣Peer、開発用デバッグ画面の責務分離
- 同一プロセス内でA→B→Cを再現できるFake transport

## 技術構成

- Kotlin
- Jetpack Compose / Material 3
- Kotlin Coroutines / Flow / StateFlow
- Room
- kotlinx.serialization JSON
- MVVMを入口とする簡略化Clean Architecture
- Google Nearby Connections API
- Kotlin/JVM PC Gateway（Ktor/Netty、SQLite JDBC、ローカル管理画面）

Android `app`、共有DTO `relay-protocol`、PC Gateway `pc-gateway`の3モジュールです。Android通信処理は`OfflineTransport`、PC同期は`GatewaySyncEngine`、HTTP契約は`relay-protocol`で分離しています。

## Apple / マルチプラットフォーム

SwiftUI の別アプリではなく:

| 端末 | 対応 |
|------|------|
| **iPhone** | Kotlin Multiplatform + Compose Multiplatform（`shared` + `composeApp`）と、`apple/RelayAppleKit`のSwift配送基盤。Android版と同じドメイン・公開Gatewayクライアント・Compose UI系統、およびWindows BLE bridge互換の不透明配送フレーム |
| **Mac（PC Gateway）** | 既存 `:pc-gateway` JVM をそのまま。`scripts/run-pc-gateway.sh` / `scripts/setup-pc-gateway-macos.sh` |
| **Android 本番** | 従来どおり `:app`（Room / Nearby / FGS） |

詳細: [docs/APPLE_TARGETS.md](docs/APPLE_TARGETS.md)



## PC Gateway

固定中継の主経路は **zero-operation** です。PC は UDP ビーコン（42888）で自らを広告し、Android は token なしで `POST /api/public/sync/messages` に REPORT を送れます。保存成功時は `GATEWAY_RECEIVED_UNVERIFIED`（匿名LAN経路でPC保存）です。ペアリング + Bearer は任意の認証済みBridge経路で、保存成功時は `GATEWAY_RECEIVED` です。どちらもREPORT本文・発信元の真正性は検証せず、現アプリの内容検証状態は `UNVERIFIED` です。Gateway ReceiptはPC保存の証跡であり、公式情報や最終宛先への配信完了ではありません。

**起動（推奨）:** Windows はリポジトリ直下の **`Start-PC-Gateway.cmd`** をダブルクリック（中身は `scripts/run-pc-gateway.ps1`）。macOS/Linux は `./scripts/run-pc-gateway.sh`。初回のみ installDist を自動構築。管理画面 `http://127.0.0.1:8080/`、Health `/api/health`。固定地点の EXE インストーラは `artifacts/relay-pc-gateway.exe`（任意）。上級の手動手順は [PC_GATEWAY_SETUP.md](docs/PC_GATEWAY_SETUP.md)。

詳細: [OPERATION_MODEL.md](docs/OPERATION_MODEL.md)、[PC_GATEWAY_SETUP.md](docs/PC_GATEWAY_SETUP.md)、[PC_GATEWAY_ARCHITECTURE.md](docs/PC_GATEWAY_ARCHITECTURE.md)、[PC_GATEWAY_SECURITY.md](docs/PC_GATEWAY_SECURITY.md)。

Android 実機から PC への同期は **端末接続時に runbook で検証**（未接続時は residual）。ソフトウェア経路（公開 HTTP クライアント + PC Gateway）は自動テスト済みです。HTTP 送信成功だけでは公式到達と表示しません。

## 前提とMVP既定値

- `minSdk`: 23（Room 2.8系の下限に合わせる）
- `compileSdk` / `targetSdk`: 36（この開発環境に導入済みのSDKへ固定）
- Build Tools: 36.0.0
- 最新Compose系のSDK 37移行は、ローカルSDK更新後の別作業とする
- メッセージID: UUID v4
- 端末ID: アプリ内でランダム生成し、アプリ専用領域へ保存
- 通常TTL: 24時間（初期案）
- 高優先度TTL: 72時間（初期案）
- `maxHopCount`: 8（既定値、検証上限は16）
- Envelope上限: 64 KiB
- Manifest / request: 1パケット最大128件。複数パケットへ分割する
- おおまかな場所: 最大100文字
- メモ: 最大280文字
- 同行者数: 0〜99
- 必要数: 1〜9999

これらはMVPの安全な初期値であり、災害運用上の実証結果を受けて設定可能にする予定です。端末時刻が数分ずれるだけでパケットを拒否しない一方、TTLの最終判定には端末の現在時刻を用います。大幅な時計ずれへの完全な対策はMVPの対象外です。

## ビルドとテスト

前提:

- Android Studioでプロジェクトを開けること
- プロジェクトが指定するJDKとAndroid SDKがインストール済みであること
- 初回のみGradle依存関係の取得にインターネット接続が必要であること

Windows PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

検証済み結果（コードベース）: `testDebugUnitTest`、`:relay-protocol:test`、`:pc-gateway:test`、`assembleDebug` が成功。件数はおおよそ Android 50+ / protocol 6 / pc-gateway 15 前後（追加に応じて変動）。APK は `app/build/outputs/apk/debug/app-debug.apk` および `artifacts/relay-debug.apk`。

macOS / Linux:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Roomの実DBテストを実行できる端末またはエミュレータがある場合:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

最低限の自動テスト対象は、重複排除、TTL除外、hop上限、優先度順、未保有IDの差分、ACK後のPeer送達状態、不正JSON、過大Payload、未知protocol version、およびFake transportによるA→B→C多段中継です。

## セキュリティとプライバシー

- 受信データは信頼せず、Byte数をJSON解析前に確認する
- 不正JSON、未知version、未知packet typeは例外をUIまで伝播させず拒否する
- ID、文字数、件数、日時、hop、payload種別を検証する
- ログにはメッセージ本文、場所、自由記述を出力しない
- 同一Peerからの大量送信を制限できる `PayloadRateLimiter` 境界を設ける
- 署名・検証は `MessageSigner` / `MessageVerifier` の背後へ置く
- 端末IDはアプリデータ削除で変わる。端末追跡目的には使用しない

Nearby Connections自体の接続特性に加えて、アプリ層の入力検証、重複排除、転送制御が必要です。MVPのNoOp署名では、悪意ある端末による偽情報や改ざんを防げません。

## 設計修正: 寿命・受領・運用モード

- REPORTの有効期間は端末ごとの受信時刻でリセットしない。`lifetimeMs`と`accumulatedAgeMs`を転送し、送信前にその端末での保持時間を累積する。
- 再起動後は単調時計を直接比較しない。保存時の壁時計差分を非負の場合に限り利用し、復元不能または時計が戻った場合は安全側で期限切れにする。
- Payload転送完了、Peer保存（`PEER_RECEIVED`）、匿名LAN経路でのPC保存（`GATEWAY_RECEIVED_UNVERIFIED`）、認証済みBridge経路でのPC保存（`GATEWAY_RECEIVED`）は別状態。いずれのReceiptもREPORT内容の検証済み・公式情報・最終配信を意味しない。
- 業務メッセージは`REPORT`と`STATUS_CHANGE`。STATUS_CHANGEはREPORTより先に中継する。
- 権限許可済みならアプリ起動時に通信（RELAY）を自動開始する。一般利用者は接続承認・Gateway 登録をしない。

## フェーズ3: 実機検証TODO（実装は zero-op 済み）

コード上は Nearby 自動接続、権限、FGS、公開 PC 同期がある。**実機での PASS は未実施。**

- 1 台: 起動、権限、REPORT 保存復元、通信自動開始
- 2 台: 承認 UI なしで接続、REPORT 同期、Peer ACK の表示意味
- Phone↔PC: ビーコン発見 → public sync → UNVERIFIED 表示（[runbook](docs/runbooks/PHONE_TO_PC_PUBLIC_SYNC_E2E.md)）
- 画面消灯、省電力、切断・再接続、メーカー差
- 64 KiB 境界、連続送信、ACK 直前切断

## 既知の技術リスク

- Nearbyの発見・広告・同時接続はOS、端末メーカー、省電力設定の影響を受ける
- Androidの権限とForeground Service要件はtargetSdkにより変化する
- 単純なID Manifestは件数増加時に分割またはBloom Filter等への移行が必要
- JSONは可読性と引き換えに通信サイズが大きい
- 絶対時刻TTLは大幅な時計ずれに弱い
- ACK直前の切断では再送が発生するため、受信側の冪等性が必須
- 実機Nearby、OS権限、バックグラウンド動作はJVM単体テストだけでは保証できない
## 災害時の操作方針

一般利用者は中継地点やGatewayを事前登録しません。Androidの必須権限が許可済みなら、アプリ起動時に通信を開始し、周辺のRelay端末へ自動接続します。

固定中継地点は常時給電Android BridgeとPC Gatewayで構成します。PC GatewayはLANビーコンで自動発見され、登録済みtokenがなくてもREPORTを検証・制限付きで受信します。保存成功のReceiptは「中継拠点に保存済み（未認証）」として表示します。

詳細は[docs/OPERATION_MODEL.md](docs/OPERATION_MODEL.md)を参照してください。
