# UI コピー刷新監査 2026-07

現在の Relay 実装に合わせて、利用者・運用者向けの文章・状態名・説明・エラー・通知・
アクセシビリティ表記を再点検した記録。これは UI デザインの作り直しではなく、実装と
一致しない表記、誤解を招く説明、画面ごとに不統一な用語の修正である。

確認は次の優先順位で行った。
1. 実際の状態遷移・永続化・暗号化・配送コード
2. テストで保証されている挙動
3. Android / PC Gateway / Broker / Compose Multiplatform の実装
4. 最新 README・運用ドキュメント
5. 過去の成果物・引き継ぎ文書

## 対象にした画面・領域

- Android: `app/src/main/java/com/example/relay/ui/`（ホーム・設定・診断・公式防災情報）と
  `ui/rescue/`（救助依頼作成/更新/取消、SOS、中継端末画面、安全とプライバシー、状態カード）、
  ViewModel から表示されるメッセージ、Foreground Service 通知。
- PC Gateway: `pc-gateway/src/main/resources/web/index.html` / `app.js` / `app.css`
  （ログイン、救助依頼一覧・詳細、担当・対応状態、地図/オフライン地図、公式情報、初期設定、
  接続状態、エラー、`aria-label`/`role`）。
- Compose Multiplatform: `composeApp/src/commonMain`（ホーム、安否・物資・地域情報、
  オフライン地図、中継拠点同期）と `iosApp/README.md`。

## 現在の状態モデル（実装・テスト準拠）

### 救助依頼の送達状態 `RescueSubmissionStatus`
定義: `app/.../rescue/RescueEnvelopeRepository.kt`。terminal 集合は
`SHELTER_COMPLETED` / `CANCELLED` / `SHELTER_REJECTED`（`RescueDeliveryServiceTest` で保証）。

| 状態 | 内部的な意味 | 利用者向け日本語（短） | 英語（短） | 信頼レベル | 終了 |
|------|--------------|------------------------|------------|------------|------|
| PENDING_DESTINATION | 送信元端末内の復元用暗号データのみ。転送可能なEnvelopeは未生成 | この端末に保存しました | Saved on this device | DEVICE_ONLY | × |
| PENDING | 受信先確認済み。近くの端末を探索し中継準備 | 近くの端末を探しています | Looking for nearby devices | RELAYING | × |
| IN_TRANSIT | 近くの端末へ中継中 | 近くの端末へ中継中です | Relaying via nearby devices | RELAYING | × |
| SHELTER_STORED | 救助拠点PCが署名付き受信確認を返した | 救助拠点に保存（署名確認済み） | Stored at the rescue hub (signed) | SHELTER_STORED | × |
| SHELTER_ACCEPTED | スタッフが受領 | スタッフが受領しました | Accepted by shelter staff | SHELTER_HANDLING | × |
| SHELTER_RESPONDING | 避難所が対応中 | 避難所が対応中です | Shelter is responding | SHELTER_HANDLING | × |
| SHELTER_COMPLETED | 対応完了を記録 | 対応が完了しました | Response complete | RESOLVED | ○ |
| CANCELLED | 取消を救助拠点が確認 | 取り消し済みです | Cancelled | RESOLVED | ○ |
| SHELTER_REJECTED | 救助拠点側で確認が必要 | 確認が必要です | Needs shelter review | RESOLVED | ○ |

### 配送・受信証跡の信頼レベル（新設 `RescueDeliveryTrust`）
- DEVICE_ONLY: 端末内に安全に保存。転送可能なEnvelopeは未生成。
- RELAYING: 近くの端末が搬送中。避難所の確認なし（peer ACK / Nearby payload 完了を含む）。
- SHELTER_STORED: 署名付き避難所Receiptで救助拠点PCの保存を確認。スタッフ未対応。
- SHELTER_HANDLING: 避難所スタッフが受領または対応中。
- RESOLVED: 完了 / 取消 / 要確認の終了状態。

Gateway 側の配送提示は `shared` の `DeliveryPresentation` /
`deliveryPresentationLabel`（既存・trust-safe）で、PEER_RECEIVED と
GATEWAY_RECEIVED_UNVERIFIED を明確に区別している（`OperatorFacingCopyTest`）。

### PC スタッフの対応状態（配送状態とは別軸）
`app.js` の `statusLabel()` に集約:
UNCONFIRMED=未確認 / CONFIRMED=確認済み / PREPARING=対応準備中 /
RESCUE_REQUESTED=対応要請を記録（外部連携未確認）/ RESPONDING=対応中 /
COMPLETED=完了 / UNABLE=対応不可 / DUPLICATE=重複。

## 表記統一ルール

1. 「送信」を曖昧に使わず、端末保存・近くの端末探索/中継・救助拠点保存・スタッフ受領・
   対応中・完了を段階ごとに書き分ける。
2. HTTP 2xx / Gateway 保存 / Broker 保管 / Nearby payload 完了 / peer ACK /
   未検証Receipt を「避難所に届いた」「救助開始」と扱わない。署名付き避難所Receipt が
   ある状態のみ SHELTER_STORED 以上として表示。
3. PENDING_DESTINATION は「この端末に保存」「受信先を確認中」「まだ送っていない」
   「確認前は取消可能」。Nearby 送信中・避難所配送中とは書かない。
4. 継続バックグラウンドGPSは未実装のため、「継続して更新中」「常に追跡」は使わない。
   実装どおり「作成時と更新送信時に取得」「常時追跡はしない」と表示。
5. バックグラウンド通信は断定せず、「通信サービスが動作している間は中継」「強制終了や
   省電力で止まることがある」と限定。
6. 経路は一般画面では「近くの端末」「地域の中継拠点」「利用できる安全なオンライン経路」に
   まとめ、診断・設定画面で具体化。
7. 運用地域の固定表示は「広島県安芸郡府中町」、共通コンポーネントは「地域の救助拠点/中継拠点」。
8. debug/localDev/prerelease/pilot/release と PoC を区別。PC Gateway に運用モードを表示。
9. 一般表示に raw enum / raw error（`gateway_not_found` 等）を出さず、診断表示で併記。
10. 日本語と英語で保証範囲を一致させる。

## 主な旧表記 → 新表記

Android:
- 「救助要請を自動送信中」→「救助要請を中継中」
- 「自動送信中」/「取消情報を送信中」→「近くの端末へ中継中」/「取り消しを中継中」
- 「現在地を継続して更新中です。」→「位置は依頼の作成時と更新の送信時に取得します。常時追跡はしません。」
- 「依頼 → 自動中継 → 避難所受信 → 対応中 → 完了」（固定表示）→ 現在段階の説明文（`statusCopy().description`）
- 「アプリを閉じても自動で中継します。」→「画面を閉じたあとも、通信サービスが動作している間は中継を続けます。強制終了や省電力設定で止まることがあります。」
- 「受信した救助要請は、府中町の避難所PCを見つけると自動で安全に提出されます。」→「地域の救助拠点のPCが見つかると安全に中継します。」
- 「送信先: 府中町の救助拠点」→「送信先: 地域の救助拠点」
- 通知「避難所PCを見つけると自動で提出します」→「地域の救助拠点が見つかると中継します」
- 設定「同じWi‑Fi上のPC Gatewayへ公開同期」→「利用できる安全なオンライン経路（地域の中継拠点など）へ公開同期」

PC Gateway:
- 「このPC」→「サインイン中の運用者」（`nodeId()` はスタッフ username のため）
- 「最初に確認したPCが担当」→「最初に確認した運用者が担当」
- 「別のPCが先に担当したか」→「別の運用者が先に担当したか」
- 初期設定に「運用モード」を追加し、`/api/health` の profile / anonymousIngress から表示。
- ヘッダに開発モードチップ（production では非表示）。

Compose Multiplatform:
- 「iPhone / 共有 Kotlin 版」→「共有Kotlin版（開発プレビュー）」＋機能制限の注記
  （救助依頼(SOS)・自動中継なし）。
- 「中継拠点へ同期（公開LAN）」→「地域の中継拠点へ同期（開発プレビュー）」
- Gateway クライアント識別子「Relay Apple」→「Relay 共有版プレビュー」
- `iosApp/README.md` に開発プレビュー・機能制限の明記。

## 状態表記ロジックの共通化

- Android: 新規 `app/.../rescue/RescueStatusCopy.kt` に `RescueStatusCopy`
  （短名/説明/terminal/trustLevel、日英）と `RescueSubmissionStatus.statusCopy()` を集約。
  従来 `RescueFlow.kt` と `RescueStatusScreens.kt` に分散していた2つのラベル表を、
  それぞれ `statusCopy().shortLabel(language)` / `statusCopy().description(language)` へ委譲。
- PC Gateway: 対応状態は `statusLabel()`、運用モードは `profileLabel()` /
  `renderOperationMode()` に集約（`app.js`）。

## Android / PC Gateway / Compose の機能差

- Android: 救助依頼(SOS)作成・更新・取消、Nearby 中継、中継端末画面、Foreground Service を提供。
- PC Gateway: 避難所スタッフ向け。受信・担当・対応状態管理、地図、公式情報。
- Compose Multiplatform（iPhone/Desktop 共有）: 開発プレビュー。安否・物資・地域情報の共有と
  地図確認、開発用の PC Gateway 公開同期のみ。救助依頼(SOS)・自動中継・Foreground Service は未提供。

## 開発版と正式版の表記差

- PC Gateway は `GatewayProfile`（DEVELOPMENT/LAB/PRODUCTION）を運用モードとして表示。
  非 production では「正式運用には未対応」、anonymous ingress 有効時はその旨を併記。
  production では開発警告を出さない。
- Compose は常に「開発プレビュー」。

## 未実装・運用未確認（表記を控えめにした箇所）

- Phase 5 の同意ベース位置更新を実装: 送信者が明示的にオプトインした場合のみ現在地を1回取得し、暗号化リカバリペイロード（`trackingEnabled`、永続的同意の真実源）と公開 `trackingMode` に反映する。同意前は位置ハードウェアに触れず、`trackingEnabled` は依頼作成・復元時とも false 既定。継続バックグラウンドGPS・Foreground Service 宣言・WorkManager 定期追跡は引き続き未実装。
- 外部消防・救助機関への自動要請は未確認。RESCUE_REQUESTED は「外部連携未確認」と明示。
- BLE 信頼チェーンはコード対応済みだが正式 Root/Directory/Manifest は別途必要。
- Compose のゲートウェイ探索はプラットフォーム依存で未配線。開発時は override/localhost。

## 意図的に残した旧表記

- 公式防災情報リンクの「府中町」（実在の自治体公式情報のため保持）。
- `pc-gateway` タイトル「Relay 府中町 救助拠点」と「STAFF ONLY」（運用地域表示。既存テスト
  `DashboardUiTest` が依存）。
- 設定・診断の生コード併記（`sent=`, `gateway_not_found` 等）は診断目的で保持。
- 過去時点の監査資料（`docs/audits/RESCUE_DURABILITY_INITIAL_AUDIT.md` 等）は当時の記録として保持。

## 実行したテスト

`docs` の最終報告と PR 本文に記載。少なくとも以下を対象とした:
shared JVM、Android JVM unit（新規 `RescueStatusCopyTest` 含む）、PC Gateway
（拡張した `DashboardUiTest` 含む）、Compose 共有コードのコンパイル、Android `localDev` APK。

## 未実施の実機・現地確認

- 実機/エミュレータでの Maestro・スクリーンショットUIテストは未実施（本環境では実行不可）。
- iOS 実機ビルドは Windows 環境のため未実施。
- 府中町での現地運用確認は未実施。
