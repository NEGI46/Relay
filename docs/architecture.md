# Relay アーキテクチャ

到達性、端末ロール、および将来の非Nearby入出力の設計境界は [REACHABILITY_AND_ALTERNATIVE_TRANSPORTS.md](REACHABILITY_AND_ALTERNATIVE_TRANSPORTS.md) を正とする。

PC Gatewayの追加設計は[PC_GATEWAY_ARCHITECTURE.md](PC_GATEWAY_ARCHITECTURE.md)を正とする。Android BridgeはNearbyとは独立したHTTP同期経路を持ち、PC側保存成功後だけ`GATEWAY_RECEIVED`を生成する。

## 1. 設計目標

Relayは、インターネットのない環境でメッセージを端末に保存し、近くの端末との一時的な接続を使って差分交換し、その端末が次の場所へ運ぶStore–Carry–Forwardシステムである。

設計上、次の変更を局所化する。

- Nearby ConnectionsからWi-Fi AwareまたはLANへの通信方式変更
- JSONからCBORまたはProtocol Buffersへのデータ形式変更
- 単純なID ManifestからBloom Filter等への在庫交換方式変更
- NoOp署名から電子署名・鍵管理への移行
- NoOpクラウド同期から復旧後のサーバー同期への移行

フェーズ2では単一 `app` モジュールを採用する。MVP規模ではモジュール間Gradle設定を増やさず、パッケージとKotlin interfaceで依存方向を保つ方が実装・テストのフィードバックを短くできるためである。境界が安定してから必要に応じてGradleモジュールへ分割する。

## 2. 依存方向と責務

```text
Compose UI
  ↓ intent / StateFlow
ViewModel
  ↓
UseCase
  ↓
Domain interface / pure policy
  ↑
Room repository / Packet codec / Fake or Nearby transport
```

- UIは画面状態の表示と利用者操作の通知だけを担当する
- ViewModelはUseCaseを呼び、画面用の`StateFlow`を公開する
- UseCaseは1つの利用者操作または業務操作を表す
- Domainは転送可否、差分、優先順位、状態遷移をAndroid非依存で表す
- DataはRoom、端末ID永続化、Repository実装を担当する
- Protocolはwire形式、codec、入力検証を担当する
- TransportはPeer発見、接続、ByteArray送受信を担当し、DBや同期手順を知らない
- Serviceはフェーズ3のForeground Serviceと通知を担当する

## 3. パッケージ構成

```text
...relay/
  core/
    time/          Clock
    id/            IdGenerator
    logging/       SafeLogger
    result/        AppError
  domain/
    model/         RelayMessage, typed payload, Peer, enums
    repository/    MessageRepository, DeviceIdentityRepository
    transport/     OfflineTransport, events and transport state
    sync/          SyncPlanner, SyncCoordinator, session state
    security/      MessageSigner, MessageVerifier, PayloadRateLimiter
    cloud/         CloudSyncGateway
    usecase/       create, observe, start/stop, cleanup
  data/
    local/         Room database, entities, DAOs, converters
    mapper/        domain/entity mapping
    repository/    Room and preferences implementations
    cloud/         NoOpCloudSyncGateway
  protocol/
    model/         Envelope and packet bodies
    codec/         PacketCodec, JsonPacketCodec
    validation/    PacketValidator, ValidationLimits
  transport/
    fake/          FakeOfflineNetwork, FakeOfflineTransport
    nearby/        phase 3 adapter
  service/         phase 3 foreground service and notification
  ui/
    navigation/
    home/
    safety/
    supply/
    regional/
    peers/
    debug/
    permissions/
    components/
    theme/
```

## 4. ドメインモデル

### RelayMessage

```text
messageId: UUID v4
messageType: SAFETY | SUPPLY
createdAt: Instant
expiresAt: Instant
priority: LOW | NORMAL | HIGH | CRITICAL
originDeviceId: random application ID
payload: SafetyPayload | SupplyPayload
hopCount: Int
maxHopCount: Int
status: ACTIVE | RELAYED | EXPIRED
receivedAt: Instant
```

生成元端末では`hopCount = 0`とする。他端末から新規受信して保存するときに1増やす。転送できるのは`expiresAt > now`かつ`hopCount < maxHopCount`のメッセージだけである。

`messageId`には端末時刻に依存しないUUID v4を使う。Roomの一意制約と`INSERT IGNORE`相当の操作で、同じIDを何度受信しても1件だけ保存する。

### 型付きPayload

`SafetyPayload`:

- 状態: `SAFE`, `INJURED`, `EVACUATING`, `AT_SHELTER`
- 同行者数: 0〜99
- おおまかな場所: 最大100文字
- メモ: 最大280文字

`SupplyPayload`:

- 種別: `WATER`, `FOOD`, `MEDICINE`, `BLANKET`, `POWER`, `HYGIENE`, `OTHER`
- 必要数: 1〜9999
- おおまかな場所: 最大100文字
- メモ: 最大280文字

UIとドメインでは型付きモデルを使用し、Roomでは専用mapper/codecを通してJSON文字列として保存する。任意JSONをUIから直接Repositoryへ渡さない。

### Peer別送達状態

```text
PeerTransmission
  messageId
  peerId
  state: PENDING | SENT | ACKED | FAILED
  lastAttemptAt
  ackedAt
  attemptCount
```

主キーは`(messageId, peerId)`とする。メッセージ全体に単一の「送信済み」を置くと別Peerへの中継が止まるため、送達状態はPeer別に保持する。`ACKED`のPeerには再送しない。`SENT`のまま切断した場合は再送を許し、受信側の重複排除で冪等に処理する。

## 5. 主要interface

```kotlin
interface OfflineTransport {
    val state: StateFlow<TransportState>
    val discoveredPeers: Flow<List<Peer>>
    val connectionEvents: Flow<ConnectionEvent>
    val receivedPayloads: Flow<ReceivedPayload>

    suspend fun start()
    suspend fun stop()
    suspend fun connect(peerId: String)
    suspend fun acceptConnection(peerId: String)
    suspend fun rejectConnection(peerId: String)
    suspend fun disconnect(peerId: String)
    suspend fun send(peerId: String, payload: ByteArray)
}
```

手動の認証コード確認を支えるため、接続開始とは別に承認と拒否を持たせる。`ConnectionEvent.AuthenticationRequired`はPeer IDと表示用確認コードを公開し、Nearby固有CallbackをDomain/UIへ漏らさない。

ほかの交換点:

```text
PacketCodec              Envelopeのencode/decode
InventoryExchangeStrategy Manifest作成と未保有ID計算
MessageRepository        保存、取得、Peer送達状態、期限切れ削除
MessageSigner            将来の署名、MVPはNoOp
MessageVerifier          将来の検証、MVPはNoOp
PayloadRateLimiter       Peer単位の流量制限
CloudSyncGateway         復旧後同期、MVPはNoOp
Clock                    時刻判定をテスト可能にする
```

## 6. 状態遷移

Transport:

```text
STOPPED → STARTING → RUNNING → STOPPING → STOPPED
                    ↘ ERROR（再開始可能）
```

Peer:

```text
DISCOVERED → CONNECTING → AUTHENTICATION_REQUIRED
           → CONNECTED → DISCONNECTED
                       ↘ CONNECTION_FAILED
```

Sync session:

```text
CONNECTED
 → AWAITING_HELLO
 → EXCHANGING_MANIFEST
 → REQUESTING_MESSAGES
 → TRANSFERRING
 → AWAITING_ACK
 → SYNCED
```

`SyncCoordinator`はPeerごとに`Mutex`を持ち、同一Peerとの同期を直列化する。切断時はセッションを破棄するが、Roomへ完了済みの保存とACK状態は残す。

## 7. Wire protocol

Envelope:

```text
protocolVersion
packetType
packetId
senderDeviceId
sentAt
body
```

packet type:

- `HELLO`: 対応version、session ID、capabilities
- `MANIFEST`: 有効で転送可能なメッセージのメタデータ一覧
- `MESSAGE_REQUEST`: 受信側が保有していないmessage ID一覧
- `MESSAGE_DATA`: 完全な`RelayMessage`
- `ACK`: `messageId`, `dataPacketId`, `ACCEPTED | DUPLICATE | REJECTED`
- `ERROR`: 機械判定可能なcodeと個人情報を含まない説明

同期手順:

1. 接続後、双方が`HELLO`を送る
2. 共通versionがなければ安全に`UNSUPPORTED_VERSION`として終了する
3. TTL、hop上限、当該PeerへのACK済みを除外して`MANIFEST`を送る
4. 受信側がローカルIDとの差分を取り、`MESSAGE_REQUEST`を返す
5. 送信側がTTLとhopを再検査する
6. `priority DESC, createdAt DESC, expiresAt ASC`で送る
7. 受信側が検証し、message IDを一意キーとして原子的に保存する
8. 新規保存なら`ACCEPTED`、既存なら`DUPLICATE`、拒否なら`REJECTED`を返す
9. 送信側は`ACCEPTED`と`DUPLICATE`をPeer別`ACKED`として記録する

Manifestとrequestは最大128件ごとに分割する。将来は`InventoryExchangeStrategy`の実装だけをBloom Filter等へ交換する。

## 8. 入力検証と障害耐性

`PacketCodec.decode`は例外を外へ投げず、成功または構造化された失敗を返す。enumへ直接deserializeせず、まずJSON objectからversionとpacket type文字列を読む。これにより未知値を通常の拒否結果として扱う。

検査順:

1. ByteArrayが空でない
2. Envelopeが64 KiB以下
3. UTF-8 JSONとして解釈可能
4. 必須ヘッダの存在、型、長さ
5. protocol versionが対応範囲内
6. packet typeが既知
7. packet固有body schema
8. ID形式、件数、文字数、数値範囲
9. `createdAt <= expiresAt`
10. `0 <= hopCount < maxHopCount <= 32`
11. `messageType`とpayload型が一致

過大PayloadはJSON解析前に拒否する。不正JSON、未知version/type、重複packetはクラッシュ理由にしない。ERRORを返す場合も受信本文や個人情報を含めない。

## 9. セキュリティとプライバシー

- 電話番号、メール、正確なGPSを必須にしない
- ANDROID_ID、MAC address等の永続ハードウェア識別子を使わない
- ランダムなアプリ内端末IDをアプリ専用領域へ保存する
- 本文、場所、メモを通常ログへ出さない
- 受信データを信用せず、全境界で長さ・形式・範囲を検査する
- Peer単位のレート制限を差し込める構造にする
- 署名と検証をinterface越しにし、NoOp利用中であることを明示する

Nearby Connectionsの接続が暗号化されていても、Relayのメッセージが複数端末へ保存・再送される性質は変わらない。端末内暗号化、メッセージ署名、信頼モデルはMVP後の重要課題である。

## 10. Fake transportとテスト戦略

`FakeOfflineNetwork`は同一プロセス内の共有Hubであり、複数の`FakeOfflineTransport(deviceId)`を登録する。発見、接続、ByteArray配送、切断を再現し、必要に応じて重複、破損、遅延を注入できるようにする。配送時はByteArrayをcopyし、共有可変データによるテストの誤判定を防ぐ。

JVM単体テスト:

- message IDの重複排除方針
- TTL切れ除外
- hop上限判定
- 優先度順
- Manifest差分
- ACK後のPeer送達状態
- 不正JSON
- 最大Payload超過
- 未知protocol version/type
- Fake transportによるA作成→A/B同期→切断→B/C同期→C到達

Room実DBの一意制約とtransactionは`androidTest`で確認する。Nearby実通信、権限、Foreground Serviceはフェーズ3の実機テスト対象である。

## 11. フェーズ2実装順

1. Gradleと最小Android/Composeプロジェクト
2. Domain model、`Clock`、UUID生成、純粋な転送判定
3. Envelope、JSON codec、validator
4. Room entity、DAO、database、mapper
5. `RoomMessageRepository`
6. Fake network / transport
7. `SyncPlanner`
8. `SyncCoordinator`
9. A→B→C統合テスト
10. Home、定型フォーム、地域一覧、Peer、Debugの基本画面
11. 全単体テストとdebug APK build

各まとまりの後に以下を実行し、失敗を解消してから次へ進む。

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

## 12. 技術リスクと制限

- AndroidとGoogle Play servicesの版によって権限、API、Foreground Service要件が変わる
- Nearbyの発見・広告・同時接続は端末メーカーと省電力設定に左右される
- 認証コード確認中に接続がタイムアウトする可能性がある
- 単純なID Manifestはメッセージ増加時に分割や別方式が必要
- JSONはサイズ効率が低い
- 絶対時刻TTLは大幅な端末時計ずれに弱い
- ACK直前切断では再送されるため、全処理を冪等にする必要がある
- NoOp署名では偽情報や改ざんを防げない
- アプリデータ削除で端末IDが変わる
- JVMテスト成功だけではNearby、権限、バックグラウンド動作を保証できない

## 13. フェーズ3の実機TODO

- Google Play services Nearbyの依存版と`compileSdk`/`targetSdk`を固定する
- OS版別のBluetooth、Nearby Wi-Fi、位置、通知権限を実装する
- 権限拒否理由と設定画面への案内を表示する
- 認証コードを両側に表示し、利用者操作でaccept/rejectする
- Foreground Serviceを実装し、通知に動作中表示、接続数、停止操作を含める
- 2台で発見、接続、差分同期、切断、再接続を確認する
- 3台でA→B→Cの実中継を確認する
- 画面消灯、省電力、移動、アプリ再起動時の挙動を確認する
- 端末メーカー差、Strategy選択、バッテリー消費を記録する
- 実機手順と既知制限をREADMEへ反映する
