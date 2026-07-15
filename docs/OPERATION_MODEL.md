# Relay 災害時の運用モデル

Relayは、一般利用者が中継地点を登録したり、端末同士の接続を毎回承認したりする運用を前提にしません。

## 一般利用者

一般利用者の操作は、初回のAndroid権限許可、安否または物資情報の登録、地域情報の閲覧だけです。権限が許可済みなら、アプリ起動時にForeground ServiceとNearby通信を自動開始します。周辺Relay端末は自動発見・自動接続されます。

受信情報は検証、重複排除、TTL、hopLimit、サイズ、件数制限を通ります。端末署名が将来有効になっても、署名だけで公式情報や本人確認済み情報とは表示しません。

## 固定中継地点

固定地点には、常時給電されたAndroid BridgeとPC Gatewayを配置します。BridgeはNearbyで受信した情報をLAN上のGatewayへ自動同期します。PC GatewayはUDPビーコンで自身を広告し、Bridgeは`/api/public/sync/messages`へtokenなしでREPORTを送信できます。

PCでの保存が成功した場合だけ`GATEWAY_RECEIVED_UNVERIFIED`を生成します。これは「中継拠点に保存済み（未認証）」を意味し、公式Gatewayや最終宛先への到達を意味しません。

PC Gatewayは電源投入後に自動起動するWindowsタスクとして登録できます（`scripts/register-pc-gateway-autostart.ps1`）。管理者キーは `%USERPROFILE%\.relay\admin.key` に永続化され、コンソールへ平文出力しません。Firewall は Private プロファイルで TCP API と UDP 発見ポートのみ許可します（`scripts/configure-pc-gateway-firewall.ps1`）。個々のスマートフォンをPCへ登録する作業はありません。

## 到達性の前提

Relayをインストールしていないスマートフォンは、自動中継端末として機能しません。ネットワークの到達性は、固定Relay、Gateway、Courierの配置および導入済み端末の移動に依存します。

PC Gatewayが停止していても、Android端末はStore–Carry–Forwardを続けます。同じREPORTを複数回送ってもPC側はmessageIdで重複排除します。

## セキュリティ上の表示

事前登録、コミュニティPKI、外部インターネットを使わずに自動受信するため、未知のBridgeやGatewayの身元は保証されません。公開同期経路は未検証情報として扱い、レート制限、不正形式拒否、容量上限、DB上限、STATUS_CHANGE拒否を適用します。
