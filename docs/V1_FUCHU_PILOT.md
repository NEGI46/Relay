# Relay v1 — 広島県安芸郡府中町パイロット（歴史的な機能説明）

> この文書の旧来の「v1」フローは実証候補の機能説明です。現在の安全な配備条件は [Municipal pilot readiness](readiness/MUNICIPAL_PILOT_READINESS.md) と [production Gateway 配備 runbook](runbooks/PRODUCTION_GATEWAY_DEPLOYMENT.md) が正本です。ここに書かれた到達表現は 119 の代替、実災害での救助保証、または自治体との運用合意を意味しません。

> [!IMPORTANT]
> **Current implementation status (2026-07-22):** durable sender-session restoration,
> update, cancellation, and verified receipt display are implemented, but periodic/background
> location tracking is **not** implemented in this branch. The UI reports that location updates
> are stopped. BLE Gateway trust code exists, but the official Regional Root and Root-signed
> Directory have not been supplied, so Phase 0B is blocked and real BLE trust is unverified.
> This notice overrides any older wording below that implies continuous GPS updates or a completed
> official BLE trust rollout. See the [durability audit](audits/RESCUE_DURABILITY_INITIAL_AUDIT.md).

Relay v1 は、通信障害時の救助依頼をスマートフォンから暗号化して自動中継し、府中町の救助拠点PCで対応するパイロット版です。

## 利用者の流れ

1. 初回にGPSと近距離通信の権限を許可する。
2. 命の危険がある場合は、赤いSOSを2秒長押しする。
3. それ以外は人数と「命の危険・けが/体調不良・移動困難・支援が必要」から状態を選ぶ。
4. GPS位置、取得時刻、位置精度を含む本文が暗号化される。
5. Relay端末がStore–Carry–Forwardで府中町の救助拠点へ自動中継する。利用者は避難所や中継先を選ばない。
6. 依頼後は状況・人数の更新または取消ができる。活動中は新しいGPS位置を暗号化した更新版として送る。

## PCスタッフの流れ

1. local operator は `http://127.0.0.1:8080/`、remote staff は承認済み TLS proxy の `https://` URL を開き、個人の username/password でログインする。初回の ADMIN は一回限りの bootstrap secret から作る。共有 PIN / `admin.key` / `X-Admin-Key` は production と lab で使わない。
2. 未確認の「命の危険」SOSを最上位に表示し、全画面警告と警告音を確認する。
3. 最初に「担当開始」を押したスタッフ端末が担当になる。
4. 正確なGPS、位置の古さ、人数、状態、タグ、自由記述を確認し、確認済み→準備中→対応中→完了へ進める。
5. 完了・取消になった依頼の全バージョンは30日後に自動削除される。

同一 Gateway の運用画面を LAN から開くのは、明示的に TLS reverse proxy または閉域網を構成した限定区域だけです。担当確定は Gateway の SQLite トランザクションで先着 1 台に固定されます。独立した複数 Gateway 間の担当同期は v1 対象外です。

## 府中町の地図と公式情報

- PCは国土地理院の標準地図タイルを府中町周辺・ズーム13〜15に限定して保存する。
- 初期設定の「地図をダウンロード」はバックグラウンドで進み、保存済み範囲は回線断後も表示できる。
- 出典を常時表示し、国土地理院コンテンツ利用規約に従う。
- 気象庁の広島県警報JSONから府中町コード`3430200`を抽出し、失敗時は最後のキャッシュを表示する。
- 詳細リンクは府中町、広島県防災Web、気象庁の公式サイトだけを掲載する。
- Androidの「地域情報」もユーザー投稿を表示せず、同じ公式情報への導線だけを掲載する。

## v1で実装済み / 次の境界

| 項目 | v1状態 |
|---|---|
| 2秒長押しSOS、人数不明、命危険、GPS | 実装済み |
| 通常依頼4状態、任意タグ・自由記述 | 実装済み |
| 避難所選択なし・暗号化・自動中継 | 実装済み |
| 依頼更新・取消・活動中GPS更新 | 実装済み（アプリプロセス生存中） |
| PC救助一覧、担当確定、状態遷移、30日削除 | 実装済み（単一Gateway共有） |
| 府中町オフライン地図、公式警報キャッシュ | 実装済み（PC） |
| 日本語 / English切替 | 実装済み（Android救助フロー） |
| PCの対応中・完了状態をスマホへ逆配送 | 実装済み。署名ReceiptをBLE再接触またはNearby端末経由で返送 |
| モバイル通信経由のBroker配送 | 実装済み。HTTPS Broker経由でNearby/BLE/LANと並行配送。設定時に有効化 |
| Androidの公式情報限定画面 | 実装済み（府中町・広島県・気象庁） |
| 独立した複数Gateway間の担当同期 | 次版。v1は1 Gatewayを複数スタッフPCで共有 |
| プロセス終了後も継続するGPS追跡 | 次版。暗号文の自動中継自体はForeground Serviceで継続 |

Relayは消防・警察・自治体の緊急連絡や認証済み人命安全システムを置き換えません。実災害への導入前に、府中町・消防・避難所運営者との運用設計、地図利用手続、物理端末での無線試験が必要です。

## 主な実装場所

```text
shared/.../rescue/RescueModels.kt              暗号化するv1救助契約
app/.../ui/rescue/                              Android救助UIと状態管理
app/.../location/                               GPS取得と時刻
app/.../rescue/BrokerRescueDelivery.kt          Broker HTTPS配送
app/.../rescue/BrokerRetryWorker.kt             Broker再送WorkManager
app/.../rescue/BrokerReceiptPoller.kt           Broker Receipt取得
pc-gateway/.../rescue/                          復号、担当、状態、保持期間
pc-gateway/.../rescue/BrokerPullAgent.kt        BrokerからEnvelope取得
pc-gateway/.../rescue/ReceiptOutbox.kt          署名ReceiptのBroker再送
pc-gateway/.../GsiTileCache.kt                  府中町の地図保存
pc-gateway/.../OfficialInformationService.kt    気象庁・公式情報
pc-gateway/src/main/resources/web/              スタッフ画面
broker/                                         HTTPS Brokerサーバー
```
