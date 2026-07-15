# Relay 実機テストチェックリスト

このチェックリストは、Nearby Connections、権限処理、Foreground Serviceを含むDebug APKの手動検証用である。2026-07-15時点ではADB接続端末が0台のため、以下の実機項目はすべて**未実施**である。コード実装または単体テストの成功を実機PASSへ読み替えず、実際に確認した項目だけへチェックを付ける。

QR、Relayファイル、ローカルWeb、USB・外部ストレージ、SMS、インターネット補助同期、コミュニティPKI、自動接続は未実装であり、今回の試験対象外である。未実装機能がホーム、Peer、デバッグ画面で利用可能に見えないことを確認する。

## 共通準備と記録

- [ ] `artifacts/relay-debug.apk`が存在し、0バイトではない
- [ ] `Get-FileHash artifacts\relay-debug.apk -Algorithm SHA256`が`artifacts/relay-debug.apk.sha256`の値と一致
- [ ] 端末A: 機種 / serial / Android版 / Google Play services版を記録
- [ ] 端末Bを使う場合: 機種 / serial / Android版 / Google Play services版を記録
- [ ] 個人情報、正確な位置、実在人物の情報を含まないテストREPORTを準備
- [ ] 各端末でlogcatを消去し、端末別ファイルへ保存開始

```powershell
adb devices -l
adb -s <SERIAL> logcat -c
adb -s <SERIAL> logcat -v time > artifacts\logcat-<SERIAL>.txt
```

## 1台スモークテスト

### インストールと起動

- [ ] `adb -s <SERIAL> install -r artifacts\relay-debug.apk`が成功
- [ ] 署名不一致時はアンインストールせず停止して報告
- [ ] `adb -s <SERIAL> shell am start -n com.example.relay/.MainActivity`で起動
- [ ] 起動直後にクラッシュしない
- [ ] Relayホーム画面が表示される
- [ ] 初期モードがNORMALで、Advertising・Discoveryとも停止中
- [ ] QR等の未実装機能が利用可能に見えない

### 保存と復元

- [ ] 個人情報を含まない安否REPORTを作成できる
- [ ] 地域情報一覧にREPORTが1件表示される
- [ ] DeviceRoleを変更し、選択したロールを記録する
- [ ] `adb -s <SERIAL> shell am force-stop com.example.relay`を実行
- [ ] Launcher Activityを再起動するとREPORTが残っている
- [ ] DeviceRoleが再起動後も維持される
- [ ] デバッグ画面を開ける

### 通信モード、権限、Foreground Service

- [ ] 通信開始前にBluetooth・周辺Wi-Fi・通知権限の理由説明が表示される
- [ ] 権限ダイアログをキャンセルしてもクラッシュせず、Nearby APIを開始しない
- [ ] DRILLまたはRELAYを選択し、権限許可後に通信を開始できる
- [ ] AdvertisingとDiscoveryが開始状態になる
- [ ] `RelayCommunicationService`の常時通知が表示される
- [ ] 通知に接続中Peer数と通信停止Actionが表示される
- [ ] 通知の停止ActionでAdvertising・Discovery・Serviceが停止する
- [ ] UIも通信停止状態へ戻る
- [ ] 二重に開始操作してもAdvertising・Discoveryが重複開始しない

### 拒否・異常系

- [ ] Bluetooth権限拒否時に理由と再試行または設定導線が表示される
- [ ] 永久拒否時にアプリ設定画面への導線が表示される
- [ ] 通知権限拒否時に通信を開始せず、説明が表示される
- [ ] Bluetooth無効時に通信を開始せず、設定導線が表示される
- [ ] Android 11以前では、必要な位置情報権限または位置情報サービス無効時に案内が表示される
- [ ] Google Play servicesが無効・古い・利用不能の場合、クラッシュせずエラーを表示する
- [ ] OS設定からForeground Serviceを停止した後、UIが通信中のままにならない

## 2台Nearby直接同期

端末A・Bの両方で同一ハッシュのAPKを使用する。接続は自動成立し、双方には確認コードが診断情報として表示される。受信情報は未検証として表示されることを確認する。

- [ ] A/Bへ同一APKをインストール
- [ ] A/Bで必要な権限を許可
- [ ] A/BでDRILLまたはRELAYモードを明示開始
- [ ] A/Bが互いを発見し、Peer画面へ表示される
- [ ] 接続要求後、双方に一時Peer名またはIDと認証コードが表示される
- [ ] 片側だけ承認した段階ではPayload同期が始まらない
- [ ] 認証コード一致を確認し、双方で承認する
- [ ] 接続中Peerとして表示される
- [ ] AでREPORTを作成
- [ ] Bへ同じmessageIdのREPORTが1件だけ保存される
- [ ] BのhopCountが期待どおり1増える
- [ ] ログ上で「Nearby送信要求」「Payload転送完了」「Peer ACK」を区別できる
- [ ] Peer ACKをGateway到達または最終配信完了として表示しない
- [ ] A/Bを切断して再接続する
- [ ] 再同期後も同じmessageIdが重複保存されない
- [ ] A/Bをforce-stop・再起動してREPORTとReceiptの保存状態を確認
- [ ] 認証画面で拒否した場合、接続状態が残らずPayloadを送信しない

## 3台多段中継（端末を用意できる場合）

- [ ] AとBだけを接続してA→B同期
- [ ] A/Bを完全切断し、Aを通信停止または圏外にする
- [ ] BとCだけを接続してB→C同期
- [ ] Cに同じmessageIdが1件だけ存在
- [ ] CのhopCountが期待値
- [ ] 期限切れREPORTが転送されない
- [ ] hopLimit到達REPORTが転送されない
- [ ] CをGATEWAYへ設定して保存成功時だけGateway Receiptが生成される
- [ ] Gateway ReceiptがC→B→Aへ戻る

Gateway Receiptのドメイン処理とFake統合テストは存在するが、2026-07-15時点で実機逆伝播は未検証である。

## ログ確認

- [ ] 端末発見・消失、接続要求、認証、接続成否、切断
- [ ] Payload送信要求、転送完了・失敗、受信
- [ ] 検証結果、DB保存結果、Peer ACK、Gateway Receipt、エラー
- [ ] Peer ACKとGateway Receiptが異なるイベントとして記録される
- [ ] REPORT本文、正確な位置、個人識別情報がログにない
- [ ] `artifacts/logcat-<SERIAL>.txt`の実パスを検証レポートへ記載
