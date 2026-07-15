# Relay

> 到達性と端末ロールの設計は [docs/REACHABILITY_AND_ALTERNATIVE_TRANSPORTS.md](docs/REACHABILITY_AND_ALTERNATIVE_TRANSPORTS.md) を参照してください。Relayをインストールしていないスマートフォンは自動中継端末にはならず、固定Relay、Gateway、Courierの配置と導入済み端末の移動が到達性を左右します。

Relay は、災害時にインターネット接続がなくても、近くの Android 端末同士で安否情報と物資不足情報を交換する Store–Carry–Forward 型の実証用アプリです。受信した情報を端末内に保存し、利用者が移動して別の端末と接続した際に再転送します。リアルタイムチャットではありません。

このプロジェクトは AI 機能を使用しません。電話番号、メールアドレス、正確な GPS 位置、端末の永続的なハードウェア ID も必須にしません。

## フェーズと現在の範囲

| フェーズ | 内容 | 状態 |
|---|---|---|
| 1 | 要件、責務境界、データモデル、同期プロトコル、技術リスクの設計 | 設計済み |
| 2 | Android 基盤、Compose UI、Room、Repository、UseCase、Fake transport、同期ロジックと単体テスト | 実装・自動テスト済み |
| 3 | Nearby Connections 実通信、OS 権限、Foreground Service | 実装済み。Android 2台実機同期は未検証 |
| 4 | 防御的処理の強化、デバッグ機能、ログ、電池消費、アクセシビリティ改善 | 未実装・次フェーズ |

フェーズ2では、実機の Nearby Connections に依存せず、`FakeOfflineTransport` を使って同一テストプロセス内で複数端末の同期を検証します。インターネット復旧後のサーバー同期、電子署名、暗号方式の追加点はインターフェースとして用意し、MVPでは NoOp 実装とします。

詳細設計は [docs/architecture.md](docs/architecture.md) を参照してください。

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

## PC Gateway

Android BridgeからローカルLAN経由でPC GatewayへREPORTを同期できます。PC Gatewayは`.\gradlew.bat :pc-gateway:installDist`後に生成される`pc-gateway/build/install/pc-gateway/bin/pc-gateway.bat`から起動し、管理画面は`http://127.0.0.1:8080/`、Healthは`/api/health`です。ペアリング、SQLite保存、重複排除、Gateway Receiptを実装しています。詳細は[PC_GATEWAY_SETUP.md](docs/PC_GATEWAY_SETUP.md)、[PC_GATEWAY_ARCHITECTURE.md](docs/PC_GATEWAY_ARCHITECTURE.md)、[PC_GATEWAY_SECURITY.md](docs/PC_GATEWAY_SECURITY.md)を参照してください。

Android実機からPCへの同期は未検証です。PCへの送信完了だけではGateway到達済みと表示せず、PC保存成功応答から返った`GATEWAY_RECEIVED`だけを既存Receipt処理へ取り込みます。

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

検証済み結果（2026-07-15）: ルート`test`、`lint`、`assembleDebug`、`:pc-gateway:test`、`:pc-gateway:build`が成功。Android 47件、共有DTO 2件、PC Gateway 6件の計55件がPASS。APKは`app/build/outputs/apk/debug/app-debug.apk`に生成されます。

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
- Payload転送完了、Peerアプリ保存（`PEER_RECEIVED`）、Gateway保存（`GATEWAY_RECEIVED`）は別の状態である。画面上の「Gateway到達済み」はGateway Receiptからのみ導出する。
- 業務メッセージは`REPORT`と`STATUS_CHANGE`だけを扱う。STATUS_CHANGEは常にREPORTより先に中継する。
- 通信は`DRILL`または`RELAY`モードで明示開始した場合だけ起動する。端末ロール（MEMBER/GATEWAY/ADMIN）はモードとは別に保持する。
- 一般利用者へ接続承認を要求しない。Nearby接続は自動成立し、受信データは未検証として扱う。信頼済み拠点向けの認証境界は将来追加する。

## フェーズ3: 実機通信TODO

- 現行のGoogle Play services公式資料に基づいてNearby依存版を固定する
- 複数Peer用途のStrategyを選定し、2〜3台の実機で広告・発見・接続を確認する
- 接続開始時の認証コードを両端末へ表示し、利用者の承認後に接続を受理する
- Android OS版ごとのBluetooth、Nearby Wi-Fi、位置情報、通知権限を整理する
- 権限拒否時に理由と設定導線を表示する
- Foreground Serviceと常時通知を実装し、接続数と停止操作を表示する
- 画面消灯、省電力、アプリ復帰、切断、再接続を実機で検証する
- 64 KiB境界、Manifest分割、連続送信、ACK直前切断を検証する
- `P2P_CLUSTER`等のStrategy選択と端末メーカー差を記録する
- 実機2台の確認手順と既知制限を本READMEへ追記する

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
