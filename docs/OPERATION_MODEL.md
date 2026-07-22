# Relay 災害時の運用モデル

Relayは、一般利用者が中継地点を登録したり、端末同士の接続を毎回承認したりする運用を前提にしません。

## 一般利用者

v1の一般利用者画面は救助を最優先にし、安否・物資登録は主要導線から外します。初回にGPSと近距離通信を許可し、SOSまたは人数・4状態の通常依頼を作成します。権限が許可済みなら、Foreground ServiceとNearby通信を自動開始し、周辺Relay端末を自動発見・自動接続します。

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

> **Current rescue-session status (2026-07-22) / 現行の救助セッション状態**
>
> The continuous GPS update description below is historical and is **not** enabled for the
> current rescue flow. Phase 5 (explicit location-tracking consent, a location foreground
> service, and periodic position updates) is `NOT_STARTED`. The current implementation captures
> location only while creating a request when a location fix is available, persists that rescue
> state in an encrypted recovery payload, and reports that location updates are stopped. It does
> not start a location foreground service from boot, background work, or request restoration.
>
> 以下の継続GPS更新の記述は現行の救助フローには適用しません。Phase 5（明示同意、位置情報
> Foreground Service、継続位置更新）は `NOT_STARTED` です。現在は依頼作成時に取得できた位置
> のみを暗号化した回復データへ保持し、位置更新は停止中と表示します。起動時・バックグラウンド
> 作業・依頼復元から位置情報FGSを開始しません。


## 位置情報（GPS）

救助依頼ではGPS権限と測位を必須にします。取得時刻を含む最新位置を添付し、古いlast-known位置へフォールバックした場合もPCで古さを判断できます。依頼が活動中でアプリプロセスが生存している間は定期的に再測位し、位置が変化した場合に新しい暗号化版を送ります。

通常の安否・物資レポートに残る従来経路は、場所が空ならワンショットGPSで補完し、権限拒否・測位不可でも保存できます。これはv1救助経路とは異なる互換機能です。

## インターネット復帰時の優先取り込み

端末がインターネット到達可能になると `InternetPrioritySync` が重要メッセージ（HIGH/CRITICAL）をローカル倉庫へ追加します。これは peer SCF や PC Gateway を置き換えず、復旧後の公式・広域情報の穴埋め用です。
