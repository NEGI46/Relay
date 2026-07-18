# Relay 災害時の運用モデル

Relayは、一般利用者が中継地点を登録したり、端末同士の接続を毎回承認したりする運用を前提にしません。

## 一般利用者

一般利用者の操作は、初回のAndroid権限許可、安否または物資情報の登録、地域情報の閲覧だけです。権限が許可済みなら、アプリ起動時にForeground ServiceとNearby通信を自動開始します。周辺Relay端末は自動発見・自動接続されます。

受信情報は検証、重複排除、TTL、hopLimit、サイズ、件数制限を通ります。端末署名が将来有効になっても、署名だけで公式情報や本人確認済み情報とは表示しません。

## 固定中継地点

固定地点には、常時給電されたAndroid BridgeとPC Gatewayを配置します。BridgeはNearbyで受信した情報をLAN上のGatewayへ自動同期します。PC GatewayはUDPビーコンで自身を広告し、Bridgeは`/api/public/sync/messages`へtokenなしでREPORTを送信できます。

PCでの保存が成功した場合だけ`GATEWAY_RECEIVED_UNVERIFIED`を生成します。これは「中継拠点に保存済み（未認証）」を意味し、公式Gatewayや最終宛先への到達を意味しません。

任意のペアリング経路では、Bearer tokenで提出Bridgeを認証し、PC保存成功時に
`GATEWAY_RECEIVED`を生成します。ただしBridge認証はREPORT本文、claimed origin、発信者本人を
証明しません。現MVPは内容署名を検証していないため、公開経路・ペアリング経路とも内容は
`UNVERIFIED`です。ReceiptはPC保存の証跡であり、公式情報や最終宛先への配信完了ではありません。

固定地点の運用者は、インストール後に管理者PowerShellから`scripts/setup-pc-gateway.ps1`を1回実行します。これによりPrivate限定Firewall、自動起動、プロセス監視・再起動、起動直後のhealth確認をまとめて設定します。Publicネットワークでは何も開放せず停止し、スクリプトがネットワーク種別を勝手に変更することはありません。管理者キーは `%USERPROFILE%\.relay\admin.key` に永続化し、タスク引数やログへ値を出しません。一般利用者や個々のスマートフォンがPCを登録する作業はありません。

## 到達性の前提

Relayをインストールしていないスマートフォンは、自動中継端末として機能しません。ネットワークの到達性は、固定Relay、Gateway、Courierの配置および導入済み端末の移動に依存します。

PC Gatewayが停止していても、Android端末はStore–Carry–Forwardを続けます。同じREPORTを複数回送ってもPC側はmessageIdで重複排除します。

## セキュリティ上の表示

事前登録、コミュニティPKI、外部インターネットを使わずに自動受信するため、未知のBridgeやGatewayの身元は保証されません。公開同期経路は未検証情報として扱い、レート制限、不正形式拒否、容量上限、DB上限、STATUS_CHANGE拒否を適用します。

## アプリ完成境界（残差の明示）

本モデルの **アプリ完成（ソフトウェア）** は、zero-operation 公開 Ingress・未認証 Receipt・Store–Carry–Forward が自動テストと PC Gateway 起動 smoke で再現できることです。

次は **運用検証** であり、製品コアの未実装ではありません。

| 項目 | 状態 |
|---|---|
| 自動テスト + assembleDebug | ゲート（CI/ローカル） |
| PC Gateway health + public UNVERIFIED | ゲート |
| 実機 Phone→PC public sync | residual（adb デバイス必要） |
| 2台 Nearby 多段 | residual |
| 管理者 Firewall/autostart | residual（昇格権限） |
| 内容署名 / PKI | 非ゴール（MVP NoOp） |

Stage token: `APP_COMPLETE_SOFTWARE_AND_PC` — residual is physical RF/device and admin elevation only.


## 位置情報（GPS）

安否・物資レポート作成時、利用者が場所を空のまま保存すると、端末の位置権限が許可されていればワンショットで GPS 座標を `approximateLocation` に埋めます。権限拒否・測位不可でも保存は成功します。常時追跡は行いません。

## インターネット復帰時の優先取り込み

端末がインターネット到達可能になると `InternetPrioritySync` が重要メッセージ（HIGH/CRITICAL）をローカル倉庫へ追加します。これは peer SCF や PC Gateway を置き換えず、復旧後の公式・広域情報の穴埋め用です。
