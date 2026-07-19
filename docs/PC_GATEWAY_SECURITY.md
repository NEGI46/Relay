# PC Gateway セキュリティ

## 信頼モデル（zero-operation）

| 経路 | 経路認証 | 内容検証 | Receipt |
|------|----------|----------|---------|
| 公開 Ingress | `ANONYMOUS_LAN`（同一 LAN + レート制限） | `UNVERIFIED` | `GATEWAY_RECEIVED_UNVERIFIED` |
| ペアリング同期 | `AUTHENTICATED_BRIDGE`（管理者承認後のBearer token） | `UNVERIFIED` | `GATEWAY_RECEIVED` |

- ビーコンと公開同期は **発見・中継拠点保存**であり、身元保証や公式情報ではない
- ペアリング済みBridgeも、REPORT本文・claimed origin・本人を保証しない
- 管理画面は「経路認証」と「内容検証」を別々に表示する
- `GATEWAY_RECEIVED` は認証済み経路からPC保存が完了した証跡であり、公式情報、内容検証、最終宛先への配信ではない
- 未導入スマホは中継に参加しない

## 実装済み制御

- 公開経路: リクエスト/メッセージ/バイトの分単位レート制限（ソケット peer 単位）
- 公開経路: `STATUS_CHANGE` は未検証イベントとして保存するが、対象REPORTの表示状態には適用しない。形式・TTL・hop・payload サイズを検証
- `messageId` 重複排除・collision 検出
- DB 件数上限
- ペアリング: 期限付きコード、管理者キー、token は SHA-256 ハッシュ保存
- Android token（任意経路）: Keystore AES/GCM
- 管理者キー: env または永続ファイル（起動ごとに乱数で捨てない）
- `GET /api/sync/receipts` は **当該 Bridge が提出した message の Receipt のみ**
- コンソールに admin key の平文を出さない

## 残存リスク

- LAN HTTP は **TLS なし**。信頼できない無線 LAN では盗聴・なりすまし可能
- 公開経路では origin / payload を誰でも送りうる（未検証表示が前提）
- ペアリング済みBridgeもNearbyで運んだ第三者REPORTを提出するため、Bridge認証を発信元認証として扱えない
- ビーコンは誰でも偽装できる（discovery ≠ auth）
- メッセージ署名（protocol v2 ECDSA）はライブラリ実装済みだが **既定パスでは未配線**
- 複数 Gateway の信頼ランキングは未実装
- EXE は未コード署名（SmartScreen）
- NAT 配下では複数端末が同一 peer IP としてレート制限を共有しうる

## 運用上の必須

1. Windows ネットワークを **Private**
2. Firewall で TCP API ポートと UDP 42888 のみ、Private に限定  
   `scripts/configure-pc-gateway-firewall.ps1`
3. Public / ゲスト Wi‑Fi に Gateway を出さない
4. 管理画面と admin key を一般利用者に渡さない

## v1救助オペレーター画面

- 救助APIは`X-Admin-Key`が一致するスタッフだけに、復号済み本文と正確なGPSを返す
- ブラウザは共通PINを`sessionStorage`だけに保持し、タブを閉じると破棄する
- HTTPはTLSなしのため、救助画面は信頼済みPrivate LANとFirewall内に限定する
- 担当確定は単一GatewayのSQLiteトランザクションで先着スタッフ端末に固定する
- 完了・取消・対応不可・重複になったrequestIdの全バージョンを30日後に削除する
- 独立した複数Gateway間の担当同期はv1では行わない
