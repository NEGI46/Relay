# PC Gateway アーキテクチャ

Android BridgeはNearby Connectionsで受信・保存したREPORTをローカルLAN経由でPC Gatewayへ転送する。PC GatewayはNearbyを実装せず、検証、重複排除、SQLite保存、Gateway Receipt生成を担当する。

```text
Android Nearby → Android Bridge Room → HTTP/JSON → PC Gateway SQLite
                                                       ↓
                                      GATEWAY_RECEIVED Receipt
                                                       ↓
                                      Android Bridge Room → Nearby逆伝播
```

- `app`: Android Compose、Room、Nearby、Bridge同期
- `relay-protocol`: Android/PC間で共有する純Kotlin DTOとProtocol Version
- `pc-gateway`: Kotlin/JVM、Ktor/Netty、SQLite JDBC、管理画面

既存Nearbyの`OfflineTransport`と`SyncCoordinator`は変更せず、Bridge同期は独立した`GatewaySyncEngine`で実行する。PCから返ったReceiptは既存`MessageRepository.insertReceipt()`へ投入し、NearbyのReceipt同期で逆伝播できる。

HTTP 2xx、Android側送信完了、Peer ACKはGateway到達を意味しない。PC側で入力検証が通り、SQLite保存または同一canonical messageの重複確認が完了した場合だけ`GATEWAY_RECEIVED`を生成する。collision、TTL切れ、hopLimit超過、サイズ超過、DB障害ではReceiptを返さない。

既定bindは`127.0.0.1`。LAN公開時はWindows FirewallをPrivate network・必要サブネットへ限定する。MVPのHTTPはTLSなしであり、信頼できないWi-Fiへ公開しない。SQLiteはローカルディスクへ置き、SMB共有上では運用しない。
