# Relay Nearby Connections実装

更新日: 2026-07-15

## 現在の状態

Debugビルドでは、`RelayApplication`が実機用の`NearbyConnectionsTransport`を生成し、`SyncCoordinator`へ渡す。単体テストとFake統合テストでは引き続き`FakeOfflineTransport`を使用でき、Google Play servicesへ依存しない。

本実装はコードとローカルテストで確認した段階であり、`adb devices -l`で接続された端末は0台だった。インストール、権限要求、Foreground Service、端末発見、認証、2台同期は実機未検証である。

QR、署名付きファイル、ローカルWeb、USB、SMS、インターネット補助同期、コミュニティPKI、自動接続は実装していない。

## 通信戦略

AdvertisingとDiscoveryにはNearby Connectionsの`Strategy.P2P_CLUSTER`を使用する。

Relayは一対一の高速転送より、複数端末が出会ったときに小さなJSONパケットを交換するStore–Carry–Forwardを優先する。`P2P_CLUSTER`は複数端末間の接続を扱えるため、この用途に適している。転送対象は`Payload.Type.BYTES`だけであり、上限は`ConnectionsClient.MAX_BYTES_DATA_SIZE`で防御的に制限する。大容量ファイルやストリームは扱わない。

service IDにはアプリのpackage nameを使用する。Advertisingのendpoint nameには、永続的なハードウェアIDではなく、アプリが生成してSharedPreferencesへ保存したランダムUUIDを使用する。Nearbyの一時的な`endpointId`はTransport内部だけで保持し、ドメイン側のPeer IDとの対応表を管理する。

## 構成

通信経路は次の責務に分離している。

```text
Compose UI / RelayViewModel
  -> RelayCommunicationService
  -> RelayCommunicationRuntime
  -> SyncCoordinator
  -> OfflineTransport
  -> NearbyConnectionsTransport
  -> NearbyPlatform
  -> GoogleNearbyPlatform
  -> Google Nearby Connections API
```

- `OfflineTransport`: Fakeと実機実装に共通する抽象。Peer、接続イベント、受信Payload、Transport状態を公開する。
- `NearbyConnectionsTransport`: endpoint対応、手動認証待ち、複数Peer状態、Payload上限、転送完了待ち、停止時のクリーンアップを担当する。
- `NearbyPlatform`: Google Play services固有APIを薄く隔離するテスト境界。
- `GoogleNearbyPlatform`: Advertising、Discovery、接続、BYTES送受信を`ConnectionsClient`へ変換する。
- `SyncCoordinator`: HELLO、MANIFEST、MESSAGE_REQUEST、MESSAGE_DATA、ACK、Receiptを処理する。受信データの検証、TTL、hop limit、重複排除、Room保存はこの既存経路を通る。
- `RelayApplication`: Debug APKで使う実機Transportの差し替え箇所。一般利用者向け実行経路がFake通信を成功として扱う構成にはしていない。

Nearbyから受信したByteArrayをTransportが直接Roomへ保存することは禁止している。`ReceivedPayload`として`SyncCoordinator`へ渡し、サイズ・受信件数・JSON・protocol version・packet type・MessagePolicy・TTL・hop limit・messageId重複排除を通過した後だけRepositoryへ保存する。

## 接続ライフサイクル

1. ユーザーが`DRILL`または`RELAY`モードを選び、通信開始を操作する。
2. 権限と端末前提条件を確認し、Foreground Serviceを開始する。
3. TransportがAdvertisingとDiscoveryを開始する。
4. Discoveryで得たendpoint nameをドメインPeer IDへ変換し、Nearbyのendpoint IDとの対応を内部保存する。
5. ユーザーがPeerへ接続要求を送る。または相手端末から接続要求を受ける。
6. `onConnectionInitiated`で確認コードを取得し、承認待ち状態としてUIへ通知する。
7. ユーザーが双方のコード一致を確認して、承認または拒否する。
8. `onConnectionResult`が成功した後だけPeerを接続済みにする。
9. 接続イベントを受けた`SyncCoordinator`がHELLOと差分Manifestの交換を開始する。
10. 切断、通知の停止Action、Service終了時には接続状態を解除し、Advertising、Discovery、全endpointおよび転送待ちを停止する。

開始処理は多重実行を抑止する。AdvertisingまたはDiscoveryの開始に失敗した場合は開始済み処理を停止し、Transportを停止状態へ戻す。`SyncCoordinator`はTransportの`started`状態を確認してから開始成功として扱う。

## 接続認証

一般利用者の災害時操作を増やさないため、P2P_CLUSTERでは認証コードを表示だけし、利用者の承認操作を要求せず接続を成立させる。受信情報は未検証として扱い、既存の形式検証・サイズ制限・重複排除を必ず通過させる。将来、信頼済み拠点だけを自動接続する場合は別の認証境界を追加する。

Peer画面には一時Peer ID、確認コード、承認、拒否、接続状態、失敗理由を表示する。DeviceRole、community ID、Peerの自己申告は承認根拠に使用しない。

確認コードは接続時の中間者攻撃を検知するための照合情報であり、端末や発信元を長期的に証明するPKIではない。endpoint nameとして使うアプリ内UUIDも自己申告値である。重複したPeer IDを異なるendpointから受けた場合は拒否するが、MVPでは暗号学的な発信元認証を提供しない。

## 権限と端末前提条件

Manifestでは次を宣言する。

- Android 11以下: `BLUETOOTH`、`BLUETOOTH_ADMIN`、OS版に応じた`ACCESS_COARSE_LOCATION`または`ACCESS_FINE_LOCATION`
- Android 12以上: `BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`、`BLUETOOTH_ADVERTISE`
- Android 13以上: `NEARBY_WIFI_DEVICES`、`POST_NOTIFICATIONS`
- 全対象版: Wi-Fi/Network状態変更関連権限、`FOREGROUND_SERVICE`
- Android 14以上のconnected-device用途: `FOREGROUND_SERVICE_CONNECTED_DEVICE`

`NearbyPermissionPolicy`がOS版別のランタイム権限一覧を返し、UIは要求前にBluetooth、周辺Wi-Fi、通知を使う理由と、正確なGPS位置を取得しないことを説明する。拒否時は開始せず、永久拒否と判断した場合はアプリ設定への導線を表示する。

`NearbyPrerequisiteChecker`はNearby API呼び出し前に、権限、Google Play services、Bluetoothの利用可否・有効状態、およびAndroid 11以下の位置情報サービスを確認する。条件を満たさない場合はServiceと通信を停止し、理由をUI状態へ反映する。権限ゲートはTransport側にも置き、権限不足時にはAdvertisingやDiscoveryを呼ばない。

Android 13以上では通知権限も開始条件に含めている。このMVPでは通知を表示できない状態でバックグラウンド通信を継続しない。

## Foreground Service

`RelayCommunicationService`は`foregroundServiceType="connectedDevice"`で宣言し、`DRILL`または`RELAY`モードでユーザーが明示的に通信を開始したときだけ使う。`NORMAL`状態で常時スキャンは行わない。

Serviceは低重要度のNotification Channelを作成し、常時通知に「Relay オフライン通信中」、接続Peer数、通信停止Actionを表示する。停止Action、Service破棄、開始条件不成立ではRuntimeを停止し、RuntimeからSyncCoordinatorとTransportを停止する。二重開始はRuntimeとTransportの双方で抑止する。Serviceは`START_NOT_STICKY`であり、OS停止後に無条件で通信を自動再開しない。

Androidのバックグラウンド制限、端末メーカーの省電力設定、Bluetooth/Wi-Fi状態、Google Play servicesの状態により通信が停止する可能性がある。「常にバックグラウンドで確実に通信できる」とは保証しない。

## ACKとReceiptの意味

Relayでは次の4段階を別の状態として扱う。

| 段階 | 意味 | 実装上の通知・記録 |
|---|---|---|
| 1. 送信要求受付 | Nearby APIが`sendPayload`要求を受理した | `PayloadSendRequested`。相手への到達を意味しない |
| 2. Nearby転送完了 | `PayloadTransferUpdate.Status.SUCCESS`を受けた | `PayloadTransferCompleted` / `SendResult.PayloadTransferCompleted`。相手アプリの保存を意味しない |
| 3. Peer ACK | 相手Relayアプリが検証後、DBへREPORTを保存した | `AckBody`と`PEER_RECEIVED` Receipt。Gateway到達や最終配信ではない |
| 4. Gateway Receipt | GATEWAYロールの端末がREPORT保存に成功した | `GATEWAY_RECEIVED` Receipt。Peer ACKとは別に保存・中継する |

送信側は、送信したMESSAGE_DATAのpacket IDとACK内のdata packet IDが一致した場合だけPeer ACKを受理し、Peerごとの送達台帳を更新する。Payload転送完了だけではACK済みにしない。

Gateway Receiptの自動生成経路は、受信側が実際にGATEWAYロールで動作し、REPORTのRepository挿入結果が`Inserted`または既存同一データの`Duplicate`だった場合に限る。`Collision`または保存上限による`Rejected`ではPeer ACKもGateway Receiptも生成しない。ReceiptはManifestによる差分同期の対象であり、後続接続を通じて発信元へ戻る。

## ログとプライバシー

デバッグイベントは、Peer IDとmessage IDを短縮し、Payload本文、正確な位置、メモなどを出力しない。端末発見、接続、認証待ち、Payload送信要求、転送完了・失敗、受信、検証拒否、DB保存、Peer ACK、Gateway Receipt、切断を区別する。

ログはメモリ内の直近イベント表示が中心であり、プロセス終了後の完全な監査証跡を保証しない。

## 既知の制約

- Nearby ConnectionsとGoogle Play servicesを利用できるAndroid端末が必要である。
- Relayをインストールしていないスマートフォンは、自動中継端末として機能しない。ネットワークの到達性は、固定Relay、Gateway、Courierの配置および導入済み端末の移動に依存する。
- 通信はBYTES Payloadに限定され、SDK上限を超えるパケットの分割送信は未実装である。
- 接続確認コードはPKIやメッセージ署名の代替ではなく、発信元偽装を暗号学的に防止しない。
- Transportと接続状態はプロセス内状態である。プロセス終了後はNearbyセッションを復元せず、ユーザーが再度通信を開始する。RoomのREPORTとReceiptは保持され、次回接続時にManifestから再同期する。
- `P2P_CLUSTER`の実効接続数、発見時間、転送速度、安定性、消費電力は端末、OS、電波環境に依存する。
- Notification拒否、Bluetooth無効、必要権限不足、Google Play services異常では通信を開始しない。
- QR、ファイル搬送、ローカルWeb、USB、SMS、インターネット補助同期は利用できない。
- 実機コールバックの高頻度発生、停止直後の遅延コールバック、複数Peer同時操作は単体テストだけでは完全に再現できないため、実機試験で確認する。

## 実機で未検証の項目

ADB接続端末が0台のため、以下はPASSではなく未検証である。

- APKの実機インストールと起動
- OS版別の権限ダイアログ、拒否、永久拒否、設定画面復帰
- Bluetooth無効、位置情報サービス無効、Google Play services利用不能時の表示
- Foreground Serviceの起動、通知、接続Peer数更新、通知からの停止
- 画面をバックグラウンドへ移動した場合とOSがServiceを停止した場合の状態整合性
- 2台間のAdvertising、Discovery、Peer発見と消失
- 双方での認証コード一致、承認、拒否、再接続
- REPORT同期、Peer ACK、messageId重複排除、再起動後のRoom復元
- Payload送信要求、Nearby転送完了、Peer ACKの実機ログ上の分離
- GATEWAY端末でのReceipt生成と別接続を介した逆伝播
- 3台によるA→B→C多段中継、hop count、TTL、hop limit
- 複数Peer接続時の競合、切断、30秒転送タイムアウト、停止時クリーンアップ
- 端末別の電池消費と長時間稼働

実機テストでは、まず1台でインストール、Room保存復元、権限、通信開始停止、Foreground Serviceを確認する。その後に同一APKを2台へ入れて直接同期を確認し、最後に3台の多段中継へ進む。

## 背景リレー（ARMED/EMERGENCY）と単一Transport所有

常時待機（ARMED）と災害通信（EMERGENCY_ACTIVE）の詳細は `BACKGROUND_RELAY_MODE.md` を参照。Nearbyに関する要点のみ以下へ記す。

- **Transportは常に1つ**。以前は `RescueDeliveryService` が救助配送用に別の Foreground Service（`RelayCommunicationService`）を起動し、Nearby Transport / Advertising / Discovery / Gateway Sync が二重に動く可能性があった。
- 修正として `CommunicationLeaseManager`（owner/lease方式）を導入し、`USER_COMMUNICATION` / `RESCUE_DELIVERY` / `EMERGENCY_MODE` の各ownerが同一の共有Runtimeを参照カウントで起動・停止する。
  - `acquire` は冪等（既に起動中なら `ALREADY_ACTIVE`）。
  - `release` は最後のownerが解放したときだけRuntimeを停止（それ以外は `STILL_ACTIVE`）。1つのownerの停止で他ownerの通信は止まらない。
  - 二重stopは安全（`NOT_HELD`）。起動失敗時はleaseを保持しない（`START_FAILED`）。
- 既存の `SyncCoordinator`、サイズ制限、TTL、hop limit、重複排除、暗号・署名検証、ACK、Receipt、Store–Carry–Forward、Gateway/Broker/BLE配送は迂回せずそのまま利用する。受信データを直接Roomへ保存する新経路は追加していない。
- ARMEDでは Nearby を一切起動しない（Advertising/Discoveryなし、無期限FGSなし、短周期ポーリングなし、無期限WakeLockなし、定期BTスキャンなし）。EMERGENCY_ACTIVE のときだけ `connectedDevice` 型 Foreground Service で Nearby を継続する。
