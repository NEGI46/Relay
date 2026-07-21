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

## API

| Method | Path | 説明 |
|---|---|---|
| POST | `/v1/rescue/upload` | AndroidがEnvelopeをアップロード |
| GET | `/v1/gateways/{shelterId}/pull?cursor=&limit=` | Gatewayが未配送Envelopeを取得 |
| POST | `/v1/gateways/{shelterId}/receipts` | Gatewayが署名Receiptをアップロード |
| GET | `/v1/devices/{deviceKeyId}/receipts?since=` | AndroidがReceiptを取得 |
| GET | `/v1/health` | ヘルスチェック |

### セキュリティ

- HTTPS必須（TLS設定は環境変数で指定）
- envelope JSON 64 KiB制限（deserialization前に拒否）
- device_key_id / gateway_id あたりのスライディングウィンドウレート制限
- 衝突隔離: 同一(requestId, requestVersion)で異なるciphertext_hash → quarantine + 409

## Android側

| コンポーネント | 役割 |
|---|---|
| `BrokerRescueDelivery` | HTTPS POSTでEnvelopeをBrokerへ送信（hopCount不変） |
| `BrokerRetryWorker` | 失敗時にOneTime WorkManagerで再送（指数backoff 30s〜15min） |
| `BrokerReceiptPoller` | 30s間隔でBrokerから署名Receiptを取得し`applyReceipt()`で検証 |
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
| `RELAY_BROKER_POLL_INTERVAL_MS` | 10000 | Pull間隔 |

## Brokerサーバー

| 環境変数 | デフォルト | 説明 |
|---|---|---|
| `RELAY_BROKER_PORT` | 8443 | リッスンポート |
| `RELAY_BROKER_DB_PATH` | ./data/broker.db | SQLite DBパス |
| `RELAY_BROKER_TLS_KEYSTORE` | — | TLSキーストアパス |
| `RELAY_BROKER_TLS_PASSWORD` | — | キーストアパスワード |
| `RELAY_BROKER_TLS_ALIAS` | relay-broker | 証明書エイリアス |

Envelopeの最大TTLは7日。期限切れは定期的にpurgeされる。

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
