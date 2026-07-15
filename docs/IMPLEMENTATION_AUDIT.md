# Relay 実装監査

> **HISTORICAL (2026-07-14)**  
> この文書は Nearby / FGS / PC Gateway 実装前の監査記録です。  
> **現行の運用・実装の正本は [OPERATION_MODEL.md](OPERATION_MODEL.md) とリポジトリ現状です。**  
> ここに書かれた「Nearby 未実装」等は現行では誤りです。実機検証状況は [DEVICE_VALIDATION_REPORT.md](DEVICE_VALIDATION_REPORT.md) を参照。

監査日: 2026-07-14

## 結論

Debug APK、JVM単体テスト、Lintは成功した。しかし、実機でのNearby通信を試験する実装は存在しない。Nearby transport、権限、Foreground Service、画面からの通信開始配線が未実装であるため、現時点での**実機Nearbyテスト開始判定はNO**である。

## 実行結果

| コマンド | 結果 | 根拠 |
|---|---|---|
| `./gradlew clean` | PASS | Gradle clean成功 |
| `./gradlew testDebugUnitTest` | PASS | 23 tests、0 failures |
| `./gradlew lintDebug` | PASS | 0 errors、22 warnings |
| `./gradlew assembleDebug` | PASS | `app/build/outputs/apk/debug/app-debug.apk`生成 |
| 静的解析 | 未設定 | Gradle tasksにdetekt/ktlint/spotless/checkstyle/pmdなし |
| `apksigner verify --verbose artifacts/relay-debug.apk` | PASS | v1/v2署名検証成功 |
| `aapt dump badging artifacts/relay-debug.apk` | PASS | applicationId、SDK、Launcherを確認 |

Lintの警告は、依存更新通知、`allowBackup`の新しい設定方式、アプリアイコン未設定、KTX書式提案である。エラーはない。

## 要件対応

| 項目 | 状態 | 関連ファイル / 主要クラス | 根拠・検証 |
|---|---|---|---|
| REPORTの作成 | 実装済み | `domain/MessageUseCases.kt` `CreateSafetyMessageUseCase` / `CreateSupplyMessageUseCase` | Fake結合テストでAのUseCaseから作成 |
| ローカルDBへの保存 | 実装済み | `data/local/RelayDatabase.kt`, `data/repository/RoomMessageRepository.kt` | Room Entity/DAOあり。JVMではInMemory repoを主に検証 |
| アプリ再起動後の復元 | 実装されているが未検証 | `RelayApplication.kt`, `RoomMessageRepository.kt`, `MessagePolicy.kt` | Roomとdevice IDは永続化。実機/AndroidTest未実施 |
| messageIdによる重複排除 | 実装済み | `MessageRepository.kt`, `RoomMessageRepository.kt` | `InsertResult.Duplicate`テスト |
| TTL/メッセージ寿命 | 実装済み | `RelayMessage.kt`, `MessagePolicy.kt` | accumulated ageと再起動保守判定の単体テストあり |
| hopCount/hopLimit | 実装済み | `MessagePolicy.kt`, `SyncPlanner.kt` | 受信時increment・上限除外テストあり |
| 端末間差分同期 | 実装済み（Fakeのみ） | `SyncCoordinator.kt`, `SyncPlanner.kt` | MANIFEST/REQUEST/DATA経路をFakeで検証 |
| ACK処理 | 実装済み（Fakeのみ） | `SyncCoordinator.kt`, `MessageRepository.kt` | DB挿入後のPeer ACK、Peer Receipt保存を検証 |
| A→B→C多段中継 | 実装済み（Fakeのみ） | `MultiHopSyncTest.kt` | SyncCoordinator/FakeNetworkを実際に通るテスト |
| FakeOfflineTransport | 実装済み | `transport/FakeOfflineTransport.kt` | 複数端末、接続、切断、ByteArrayコピーを実装 |
| Nearby Connections | 未実装 | `transport/` | 依存のみ。`ConnectionsClient`利用なし |
| Nearby権限 | 未実装 | `AndroidManifest.xml` | uses-permission宣言・runtime requestなし |
| Foreground Service | 未実装 | `AndroidManifest.xml` | Service/通知/FGS typeなし |
| 通信開始/停止 | 部分実装 | `SyncCoordinator.kt`, `RelayViewModel.kt` | CoordinatorにDRILL/RELAY gateとstopあり。UI/実transport未配線 |
| 不正Payload拒否 | 実装済み | `PacketCodec.kt`, `SyncCoordinator.kt` | malformed JSON/未知typeをFailureへ変換。JVMテストあり |
| Payloadサイズ制限 | 実装済み | `ResourcePolicy`, `PacketCodec.kt`, `SyncCoordinator.kt` | 受信前/送信前に制限。JVMテストあり |
| デバッグ画面 | 部分実装 | `ui/RelayApp.kt` | ID・状態・メッセージ表示のみ。実通信ログ/Build情報なし |
| エラーログ | 部分実装 | `SyncCoordinator.kt` `SyncDebugEvent` | メモリ内SharedFlowのみ。永続ログ/Android Logなし |
| README | 実装済み | `README.md` | 設計・ビルド・制限記載あり。実機機能は未実装と読替が必要 |

## ACKの意味

| 段階 | 現実装 | 意味 |
|---|---|---|
| Nearby API送信要求の受理 | 未実装 | Nearby adapterがないため判定不能 |
| Payload転送完了 | `OfflineTransport.SendResult.PayloadTransferCompleted` | Fake transportでは相手への配送後に返る。SyncCoordinatorではメモリ上の`PayloadTransfer`として記録 |
| 相手Relayの検証・DB保存完了 | `AckBody` | 受信側が`MessagePolicy.receive`とRepository insertでInserted/Duplicateを得た後に送る |

Peer ACKはGatewayまたは最終宛先への到達として表示されない。`GATEWAY_RECEIVED` Receiptだけが「Gateway到達済み」を導出する。ただしReceiptに署名・認可はなく、将来の実transportで悪意あるPeerを信頼してはならない。

## 寿命監査

- `createdAt`はUseCaseで`Clock.nowMillis()`から生成する。
- `expiresAt`は作成時のレガシー表示値であり、転送可否は`lifetimeMs`と`accumulatedAgeMs`で判定する。
- `MessagePolicy.prepareForTransfer`は送信端末の保持経過を`accumulatedAgeMs`へ加える。
- `MessagePolicy.receive`は`expiresAt`を書き換えず、ageを保持して受信端末の単調時計基準だけを付け替える。
- 単調時計セッション不一致時は、非負の壁時計差分で復元し、時計後退・不明値では寿命満了として扱う。
- `RelayDesignCorrectionTest`が再起動セッション変更時の保守的失効を検証する。

無署名の相手は`lifetimeMs`や`accumulatedAgeMs`を偽装できる。これはMajorの既知制限であり、実運用前には署名/認可設計が必要である。

## 同期エッジケース

| ケース | コード上の挙動 | 検証 |
|---|---|---|
| 同一packetを2回受信 | peerId+packetIdのreplay cacheで2回目を無視 | 実装あり、専用結合未検証 |
| 同じmessageIdを別Peerから受信 | canonical同一ならDuplicate、差異ならCollision | 単体テストあり |
| ACK前に切断 | DBは保持、送信側のvolatile packet相関は失われ得る | Major、再起動復元未検証 |
| 同期中にアプリ終了 | Room保存済み情報は残るが同期session状態は失われる | 未検証 |
| A/Bが同時送信 | Mutex/Room transactionで重複排除する設計 | 同時実行結合未検証 |
| hopLimit到達済み | 転送不可。受信側も上限を拒否 | 単体テストあり |
| 寿命切れ | Manifest/送信候補から除外 | Fake結合テストあり |
| 未知packetType | codec Failure、Coordinatorは無視 | PacketCodec単体テストあり |
| 不正JSON | codec Failure、クラッシュしない | PacketCodec単体テストあり |
| 最大サイズ超過 | decode前に拒否 | PacketCodec単体テストあり |

## Fake結合テストの確認

`MultiHopSyncTest`は、Repositoryへの直接コピーではなく、A/B/Cの`SyncCoordinator.start`、`FakeOfflineTransport.connect`、`FakeNetwork.deliver`、Flow受信、MANIFEST/REQUEST/DATA/ACKを経由する。

- AのCreateSafetyMessageUseCaseでREPORTを作成
- A→B、A切断、B→Cを実行
- Cで同一REPORTが1件、hopCount=2を確認（既存多段中継テスト）
- Gateway ReceiptのC→B→A戻りと重複Receiptなしを確認
- 期限切れREPORTがBへ届かないことを確認

## 問題一覧

### Critical

1. Nearby Connectionsの実装がない。端末発見、認証コード、接続、実Payload通信は実機で試験できない。
2. Nearby/Bluetooth/通知の権限、Foreground Service、通知、停止制御がない。バックグラウンド通信を実機で試験できない。
3. UIからSyncCoordinator/transportが配線されていない。DRILL/RELAYを選択して実通信を開始する経路がない。

### Major

1. MessageSigner/VerifierはNoOpかつ未使用。origin、Gateway Receipt、寿命フィールドを悪意あるPeerが偽装できる。
2. ACK前の切断またはプロセス終了時、packet相関と送信済み状態はvolatileで復元されない。
3. `SendResult.Failed`でもデバッグイベントが完了として出る経路がある。
4. Reject理由はメモリ内イベントのみ。永続・端末ログに残らない。
5. 実機でのRoom migration、再起動、回転、プロセス終了は未検証。

### Minor

1. InMemory repositoryとRoom repositoryでcanonical比較項目が完全一致していない。
2. Lint警告22件（依存更新通知、バックアップ設定、アイコン等）。
3. デバッグ画面にversion/build情報、実transportイベント、Build hashがない。

## 実機テスト前に必ず直す問題

1. NearbyOfflineTransportを実装し、手動認証コード確認後だけ接続を承認する。
2. OS別権限、拒否時UI、Google Play services非対応時UIを実装する。
3. connectedDevice Foreground Service、通知、停止操作、UI/SyncCoordinator配線を実装する。
4. 実transportのPayload transfer callbackを`SendResult`へ正しく接続する。
5. 実機2～3台で権限・再接続・バックグラウンド・再起動を検証する。

## 実機テスト後でもよい問題

- PKI/署名、Receipt真正性、origin偽装対策
- ACK相関と送信状態の永続化
- 永続デバッグログ、詳細なバッテリー最適化
- Lintの非ブロック警告、アプリアイコン、依存更新

## 現時点の制限

- APKはインストール可能なDebug buildだが、Nearby通信機能を持たない。
- 実機でのForeground Service通知や権限画面は表示されない。
- Release APKは正式署名鍵がないため生成していない。
