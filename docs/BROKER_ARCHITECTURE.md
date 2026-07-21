# Broker Architecture — モバイル通信経由の救助要請配送

## 概要

Brokerは、インターネットが利用可能な場合に、Android端末からPC Gatewayへ救助EnvelopeをHTTPS経由で配送する**任意の補助経路**です。既存のNearby/BLE/LAN経路を置き換えず、並行して動作します。

```text
Android ──HTTPS POST──> Broker (Ktor+SQLite) <──HTTPS GET (poll)── PC Gateway
Android <──HTTPS GET─── Broker (receipt)     <──HTTPS POST──────── PC Gateway (Receipt Outbox)
```

## 設計原則

1. **Brokerは復号しない** — 暗号文の一時保存・重複排除・期限管理・避難所別キューのみ
2. **hopCount不変** — Broker経路はStore-Carry-Forwardのhopではない
3. **別Ledger管理** — `BROKER_STORED`は避難所受領(`SHELTER_*`)と混同しない
4. **1 shelterId = 1 active Gateway** — v1は複数Gatewayの自動フェイルオーバー禁止
5. **アップロード署名 ≠ 本人確認** — 端末鍵はアップロード元を証明するが、本人確認済みとは表示しない
6. **at-least-once + 冪等** — Brokerは重複排除、Gateway Outboxは再送、AndroidはWorkManagerで再試行
7. **独立経路** — Broker障害はNearby/BLE/LANに影響しない
8. **複数運搬端末へ返送** — 同じEnvelopeをアップロードした各端末を関連付け、Receiptを元端末と中継端末へ返す

## API

| Method | Path | 説明 |
|---|---|---|
| POST | `/v1/devices/register` | Androidが端末公開鍵と秘密鍵の所持証明を登録し、Receipt取得用capability tokenを受け取る |
| POST | `/v1/rescue/upload` | AndroidがEnvelopeをアップロード |
| GET | `/v1/gateways/{shelterId}/pull?cursor=&limit=` | Gatewayが未配送Envelopeを取得 |
| POST | `/v1/gateways/{shelterId}/receipts` | Gatewayが署名Receiptをアップロード |
| GET | `/v1/receipts?sinceSeq=` | AndroidがBearer capability tokenで自身の署名Receiptを取得 |
| GET | `/v1/health` | ヘルスチェック |

### セキュリティ

- Android / Gatewayの外部接続はHTTPS必須。Broker本体はTLS終端リバースプロキシの内側でHTTP動作
- 登録4 KiB、Envelope/Receipt 64 KiBの`Content-Length`を本文読込前に必須化・検査
- AndroidはAndroid KeystoreのECDSA P-256鍵を端末ごとのUUIDに登録し、登録時の秘密鍵所持証明とアップロード署名をBrokerで検証
- 同じdevice_key_idを異なる公開鍵で再登録できず、既存tokenを第三者の鍵へ返さない
- Receipt取得は公開のdevice_key_idではなく、Authorization Bearerで登録時に発行する推測困難なcapability tokenを使用
- Gateway APIは`RELAY_BROKER_GATEWAY_API_KEY`未設定なら起動を拒否（明示的な開発用overrideを除く）
- device_key_id / capability token / gatewayあたりのスライディングウィンドウレート制限
- 衝突隔離: 同一(requestId, requestVersion)で異なるciphertext_hash → quarantine + 409

## Android側

| コンポーネント | 役割 |
|---|---|
| `BrokerRescueDelivery` | HTTPS POSTでEnvelopeをBrokerへ送信（hopCount不変） |
| `BrokerRetryWorker` | 失敗時にOneTime WorkManagerで再送（指数backoff 30s〜15min） |
| `BrokerReceiptPoller` | 30s間隔でBrokerから署名Receiptを取得し`applyReceipt()`で検証。token失効時は再登録して回復 |
| `UploadSigningKeyStore` | Android Keystore ECDSA P-256でアップロード署名 |
| `BrokerLedgerEntity` / `BrokerLedgerDao` | Room DB v6の別テーブルでBroker状態を管理 |

### 設定

SharedPreferences `relay_broker_config` の `broker_endpoint` が空の場合はBroker配送無効。

## PC Gateway側

| コンポーネント | 役割 |
|---|---|
| `BrokerPullAgent` | BrokerからEnvelopeをpollingし`RescueIntakeService.ingest()`へ渡す |
| `ReceiptOutbox` | 署名ReceiptをSQLite Outboxに保存し、Brokerへat-least-once配送 |

### 設定

| 環境変数 | デフォルト | 説明 |
|---|---|---|
| `RELAY_BROKER_URL` | (無効) | BrokerのHTTPS URL |
| `RELAY_BROKER_API_KEY` | (空) | Broker側のGateway Bearer API key |
| `RELAY_BROKER_POLL_INTERVAL_MS` | 10000 | Pull間隔 |

## Brokerサーバー

| 環境変数 | デフォルト | 説明 |
|---|---|---|
| `RELAY_BROKER_PORT` | 8443 | Broker HTTPリッスンポート（本番はTLS終端リバースプロキシの内側） |
| `RELAY_BROKER_DB_PATH` | ./data/broker.db | SQLite DBパス |
| `RELAY_BROKER_GATEWAY_API_KEY` | (空、開発時のみ) | Gateway Pull/Receipt APIのBearer key |
| `RELAY_BROKER_ALLOW_INSECURE_GATEWAY` | false | `true`の場合だけAPI keyなし起動を許可。ローカル開発専用 |

Envelopeの最大TTLは7日。期限切れは定期的にpurgeされる。

Broker 1.2では端末登録の所持証明が必須になり、AndroidクライアントとBrokerの協調更新が必要です。旧クライアントは登録APIで拒否されるため、段階的ロールアウト時はBroker更新前に対応アプリを配布してください。

## Room Migration 5→6

```sql
CREATE TABLE IF NOT EXISTS broker_ledger (
    requestId TEXT NOT NULL,
    requestVersion INTEGER NOT NULL,
    brokerReceiptId TEXT,
    brokerStatus TEXT NOT NULL DEFAULT 'PENDING',
    uploadedAtEpochMillis INTEGER,
    retryCount INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(requestId, requestVersion)
)
```

`RescueEntity`は不変。Broker状態は別テーブルで管理し、`SHELTER_*`状態は署名Receipt経由の`applyReceipt()`でのみ遷移する。

## 残課題（v2以降）

- 実TLS証明書（Let's Encryptまたは自治体CA）
- Android Keystore key attestation（任意の強化）
- 複数Gatewayフェイルオーバー
- Broker水平スケーリング / HA
