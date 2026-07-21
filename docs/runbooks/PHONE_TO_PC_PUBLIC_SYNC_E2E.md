# Runbook: Phone → PC public sync（development 専用互換試験）

実機があるときに実施する。未実施項目を PASS と書かない。

> これは旧 anonymous/cleartext LAN 経路の開発互換試験です。限定区域の共同実証や production/lab では使用しません。release/pilotRelease は HTTP Gateway を拒否します。[FIELD_ACCEPTANCE_TEST.md](FIELD_ACCEPTANCE_TEST.md) の HTTPS / profile 条件を代わりに使ってください。

## 事前条件

1. debug または localDev APK を Android にインストール（正式成果物ではない）
2. `RELAY_PROFILE=development` を明示して PC Gateway を起動（development のみ `0.0.0.0:8080` / anonymous ingress / discovery の互換既定）
3. PC と Android が **同一 Private LAN**
4. Firewall: TCP 8080 + UDP 42888（`scripts/configure-pc-gateway-firewall.ps1`）
5. ネットワークプロファイルが **Private**

## 手順

1. PC で `http://127.0.0.1:8080/api/health` → 200
2. PC コンソールに `LAN discovery beacon: UDP 42888` と出ることを確認
3. Android: 権限許可 → 災害通信が自動開始
4. HOME の「中継拠点」表示が `gateway_not_found` から変化するまで待つ（最大約 30s）
5. 安否または物資 REPORT を登録
6. 数秒〜数十秒後、地域情報で **「中継拠点へ保存済み（未認証）」**
7. PC ダッシュボードに UNVERIFIED 行が増える

## 合格条件

- [ ] IP / token / ペアリング入力なしで同期できる
- [ ] UI が UNVERIFIED を公式到達と表示しない
- [ ] 同一 messageId を再送しても PC は 1 件
- [ ] PC 停止中に作った REPORT が復旧後に載る（任意・強く推奨）

## 失敗時の切り分け

1. 同一 SSID / Private か
2. `Test-NetConnection <PC-IPv4> -Port 8080`（Android からではない PC 側疎通の参考）
3. UDP 42888 が FW で塞がれていないか
4. Android が debug/localDev build であり、production/lab の HTTP 拒否を回避しようとしていないか
5. PC ログ / HTTP 429（レート制限）/ 422（形式）
