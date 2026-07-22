# PC Gateway アーキテクチャ

**運用の正本:** [OPERATION_MODEL.md](OPERATION_MODEL.md)

PC Gateway は Nearby を実装しない。Android Bridge が Nearby で集めた REPORT を LAN HTTP で受け取り、検証・重複排除・SQLite 保存・Receipt 生成を行う。

## 主経路（zero-operation）

```text
Android Nearby 自動接続
  → Android Room
  → UDP ビーコン発見 (port 42888)
  → POST /api/public/sync/messages  (token なし)
  → SQLite (route_authentication=ANONYMOUS_LAN, content_verification=UNVERIFIED)
  → GATEWAY_RECEIVED_UNVERIFIED
  → Android UI「中継拠点へ保存済み（未認証）」
  → (任意) Nearby で Receipt 逆伝播
```

この anonymous HTTP/UDP 図は development 互換経路です。production/lab では既定で無効であり、限定区域で有効化する場合にも、明示した閉域網または TLS reverse proxy と Android 側 HTTPS 要件を満たす必要があります。

## 副経路（運用者の認証済みBridge経路）

```text
ペアリング + Bearer token
  → POST /api/sync/messages
  → SQLite (route_authentication=AUTHENTICATED_BRIDGE, content_verification=UNVERIFIED)
  → GATEWAY_RECEIVED
  → GET /api/sync/receipts （当該 Bridge が提出した message のみ）
```

同一 canonical message を後から paired Bridge が再送すると、受信経路だけを
`AUTHENTICATED_BRIDGE` に昇格できる。Bearer token はBridgeを認証するだけであり、
REPORT本文、`originDeviceId`、発信者本人を検証しない。現MVPは署名検証を配線していないため、
どちらの経路でも `content_verification=UNVERIFIED` のままである。

## 独立した3つの軸

| 軸 | 値 | 意味 |
|---|---|---|
| Gateway保存 | Receiptあり/なし | PCで検証・重複排除・SQLite保存が完了したか |
| 経路認証 | `AUTHENTICATED_BRIDGE` / `ANONYMOUS_LAN` | Bearer tokenで提出Bridgeを識別できたか |
| 内容検証 | `UNVERIFIED`（MVP） | REPORT本文・発信元の真正性。署名検証未配線のため検証済みにはしない |

旧DBの `ingress_trust=VERIFIED` は内容検証を意味していなかった。起動時の非破壊migrationで
`route_authentication=AUTHENTICATED_BRIDGE` へ保存し直し、`content_verification` と互換APIの
`ingressTrust` は保守的に `UNVERIFIED` へ移行する。

## モジュール

| モジュール | 役割 |
|------------|------|
| `app` | Compose UI、Room、Nearby、`GatewaySyncEngine` |
| `relay-protocol` | 共有 DTO、v1/v2、integrity ライブラリ（v2 配線は任意） |
| `pc-gateway` | Ktor/Netty、SQLite、ビーコン、公開/認証 Ingress、管理画面 |

## 到達意味の分離

| 事象 | 意味 |
|------|------|
| Nearby Payload 完了 | 転送完了のみ |
| `PEER_RECEIVED` | 相手アプリが保存 |
| HTTP 2xx | 送信が通っただけ |
| `GATEWAY_RECEIVED_UNVERIFIED` | 匿名LAN経路でPCのSQLiteへ保存 |
| `GATEWAY_RECEIVED` | 認証済みBridge経路でPCのSQLiteへ保存。内容検証・公式情報・最終配信を意味しない |

## 実行プロファイルと既定バインド

- `production`（既定）: **`127.0.0.1:8080`**。匿名 ingress、UDP discovery、リモート管理、旧共有管理キーは無効。
- `lab`: 同じ安全側の既定。限定区域の閉域網または TLS reverse proxy を明示的に構成した場合だけ LAN 機能を選択できる。
- `development`: 開発互換のため匿名 ingress、UDP discovery、旧 `X-Admin-Key` を明示設定で利用できる。自治体実証・正式配備には使わない。

## 実装メモ

- 既存 Nearby の `OfflineTransport` / `SyncCoordinator` は独立。Bridge 同期は `GatewaySyncEngine`
- 公開経路はレート制限・REPORT のみ・形式検証
- staff account、ロール、失効可能な session、最小化した監査ログは Gateway SQLite に保存する
- 初回 ADMIN は環境変数またはローカル CLI の一回限り bootstrap secret で作成し、共有管理キーは production/lab で拒否する
