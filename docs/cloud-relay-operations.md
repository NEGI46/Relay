# Cloud Relay運用

Cloud Relayは暗号化済みEncryptedRescueEnvelopeと、避難所PCが返す署名済みReceiptだけを保持します。救助本文・GPS・避難所秘密鍵は配置しません。PC Gatewayは外向きmTLS接続で取得し、インターネットからGatewayへ着信させません。

必須環境変数:
- RELAY_CLOUD_JDBC_URL
- RELAY_CLOUD_DB_USER / RELAY_CLOUD_DB_PASSWORD
- RELAY_CLOUD_KEYSTORE / RELAY_CLOUD_KEYSTORE_PASSWORD / RELAY_CLOUD_KEY_ALIAS
- RELAY_CLOUD_PORT

Androidのcloud_relay_endpointは必ずhttps://で始めます。AndroidはNET_CAPABILITY_VALIDATEDが無い場合はクラウド送信せず、ローカルStoreに残してWorkManagerへ再試行を委譲します。

CLOUD_STOREDは「クラウドに暗号文が保存された」だけで、避難所確認を意味しません。避難所確認は既存の署名済みShelter Receiptでのみ表示します。

試験:
1. Wi-Fiを切り、検証済みLTE/5GでPOSTが成功することを確認する。
2. 機内モードでEnvelopeが失われず、WorkManagerが再試行することを確認する。
3. Nearby/Bluetooth権限を拒否してもHTTPS送信が動くことを確認する。
4. Cloud Relay DB・ログ・HTTP監視に本文/GPS/秘密鍵が出ないことを確認する。
5. Gateway PCを外部から接続できない状態で、Gatewayの外向き取得と署名Receipt返却を確認する。
