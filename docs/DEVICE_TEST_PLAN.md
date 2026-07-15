# Relay 3端末テスト計画

## 前提と判定

この計画は、Nearby transport、権限、Foreground Serviceが実装された後の手順である。現ビルドではそれらが未実装のため、手順4以降の実通信確認は実行不可として記録する。

## 準備

1. 端末A/B/Cに同じDebug APKをインストールする。
2. Androidバージョン、端末機種、Google Play services版、Bluetooth/Wi-Fi状態、バッテリー最適化状態を記録する。
3. 実装後はBluetooth、Nearby Wi-Fi、通知、必要なら位置情報の権限を各端末で許可する。拒否ケースも別途実施する。
4. A/B/Cでアプリ内ID、モード、ロール、APK versionCode/versionNameをデバッグ画面から記録する。

## 通常の多段中継

1. AをDRILLまたはRELAYモードへ明示的に切り替え、通信を開始する。
2. Aで安否または物資REPORTを作成し、messageIdを記録する。
3. A/Bで発見、認証コード一致、手動承認、接続状態を確認する。
4. A/B同期後、BにREPORTが1件保存され、hopCountが1であることを確認する。
5. Aを切断し、B/Cで同じ発見・認証・接続を行う。
6. Cに同一messageIdのREPORTが1件だけ保存され、hopCountが2であることを確認する。
7. 同じ端末を再接続して再同期し、A/B/Cの件数が増えないことを確認する。
8. CをGatewayロールとして受領し、Gateway ReceiptがB、次にAへ戻ることを確認する。
9. Aの表示がPeer到達ではなくGateway到達済みに変化することを確認する。

## 復元・異常系

1. 各端末でREPORT作成後にアプリを終了・再起動し、REPORTとReceiptがRoomから復元されることを確認する。
2. BluetoothをOFFにして発見/通信開始が失敗理由付きで安全に停止することを確認する。
3. Nearby/Bluetooth/通知/位置情報権限を拒否し、クラッシュせず設定導線が出ることを確認する。
4. A→B同期中にBluetoothを切断し、再接続後に重複なく再送されることを確認する。
5. テスト用Fake/開発ビルドで不正JSONと上限超過Payloadを注入し、受信側が保存せず、本文をログへ出さないことを確認する。
6. 期限切れREPORTを用意し、A→B/B→Cのどちらにも送られないことを確認する。

## バッテリーとログ

1. 端末ごとに開始時/終了時のバッテリー率、画面ON/OFF時間、通信モード、接続時間を記録する。
2. Androidのバッテリー使用量画面でRelayの消費を確認し、端末・OSごとに比較する。
3. 各端末で時刻、端末ID、messageId、hopCount、接続イベント、拒否理由のみを記録する。場所・メモ本文・個人情報はログへ残さない。

## 現ビルドで実行できる確認

- APKのインストール、起動、REPORT作成、Room再起動復元、デバッグ画面の表示。
- Nearby発見、権限、Foreground Service、認証コード、実端末間同期は未実装のためFAILではなく未実施/未対応として記録する。
