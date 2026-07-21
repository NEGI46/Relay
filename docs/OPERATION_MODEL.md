# Relay 災害時の運用モデル

Relayは、一般利用者が中継地点を登録したり、端末同士の接続を毎回承認したりする運用を前提にしません。

## 一般利用者

v1の一般利用者画面は救助を最優先にし、安否・物資登録は主要導線から外します。初回にGPSと近距離通信を許可し、SOSまたは人数・4状態の通常依頼を作成します。権限が許可済みなら、Foreground ServiceとNearby通信を自動開始し、周辺Relay端末を自動発見・自動接続します。

受信情報は検証、重複排除、TTL、hopLimit、サイズ、件数制限を通ります。端末署名が将来有効になっても、署名だけで公式情報や本人確認済み情報とは表示しません。

## 固定中継地点

固定地点には、常時給電されたAndroid BridgeとPC Gatewayを配置します。限定区域で LAN を使う場合は、自治体が承認した閉域網または TLS 終端 reverse proxy を明示的に構成します。production profile の Gateway は既定で loopback bind、匿名 ingress 無効、UDP discovery 無効です。匿名 REPORT 経路は development 互換機能であり、未検証情報だけで救助判断を自動化してはいけません。

PCでの保存が成功した場合だけ`GATEWAY_RECEIVED_UNVERIFIED`を生成します。これは「中継拠点に保存済み（未認証）」を意味し、公式Gatewayや最終宛先への到達を意味しません。

任意のペアリング経路では、Bearer tokenで提出Bridgeを認証し、PC保存成功時に
`GATEWAY_RECEIVED`を生成します。ただしBridge認証はREPORT本文、claimed origin、発信者本人を
証明しません。現MVPは内容署名を検証していないため、公開経路・ペアリング経路とも内容は
`UNVERIFIED`です。ReceiptはPC保存の証跡であり、公式情報や最終宛先への配信完了ではありません。

固定地点の運用者は、インストール後に管理者 PowerShell から `scripts/setup-pc-gateway.ps1` を実行し、production profile のまま loopback health を確認します。LAN 公開は、閉域網または TLS reverse proxy を構成した上で明示的に選択します。初回の ADMIN は一回限りの bootstrap secret から作成し、以後は個人の ADMIN / OPERATOR / VIEWER アカウントと短命 session を使います。共有 `admin.key`、共有 PIN、production の `X-Admin-Key` は使用しません。一般利用者や個々のスマートフォンが PC を登録する作業はありません。

## 到達性の前提

Relayをインストールしていないスマートフォンは、自動中継端末として機能しません。ネットワークの到達性は、固定Relay、Gateway、Courierの配置および導入済み端末の移動に依存します。

PC Gatewayが停止していても、Android端末はStore–Carry–Forwardを続けます。同じREPORTを複数回送ってもPC側はmessageIdで重複排除します。

## セキュリティ上の表示

事前登録、コミュニティPKI、外部インターネットを使わずに自動受信するため、未知のBridgeやGatewayの身元は保証されません。公開同期経路は未検証情報として扱い、レート制限、不正形式拒否、容量上限、DB上限、STATUS_CHANGE拒否を適用します。

## アプリ完成境界（残差の明示）

本モデルの **アプリ完成（ソフトウェア）** は、限定的な開発・訓練経路の契約を自動テストと PC Gateway 起動 smoke で再現できることです。production profile は公開 ingress を既定にせず、個人認証・監査・避難所スコープを要求します。

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

救助依頼ではGPS権限と測位を必須にします。取得時刻を含む最新位置を添付し、古いlast-known位置へフォールバックした場合もPCで古さを判断できます。依頼が活動中でアプリプロセスが生存している間は定期的に再測位し、位置が変化した場合に新しい暗号化版を送ります。

通常の安否・物資レポートに残る従来経路は、場所が空ならワンショットGPSで補完し、権限拒否・測位不可でも保存できます。これはv1救助経路とは異なる互換機能です。

## インターネット復帰時の優先取り込み

端末がインターネット到達可能になると `InternetPrioritySync` が重要メッセージ（HIGH/CRITICAL）をローカル倉庫へ追加します。これは peer SCF や PC Gateway を置き換えず、復旧後の公式・広域情報の穴埋め用です。
