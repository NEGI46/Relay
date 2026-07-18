# 実機試験計画（zero-operation）

**正本:** [OPERATION_MODEL.md](OPERATION_MODEL.md)  
**チェックリスト:** [DEVICE_TEST_CHECKLIST.md](DEVICE_TEST_CHECKLIST.md)

## 前提

Nearby Transport・権限・FGS・公開 PC 同期は **コード実装済み**。  
本計画は **デバイス上でそれが動くか** を確認する。コード未実装を理由に「実行不可」とはしない。

## フェーズ

### 1. Lab 準備

- adb PATH、`adb devices -l`
- 最新 debug APK、最新 PC EXE
- Private LAN、Firewall（TCP 8080 / UDP 42888）

### 2. 単体 Android

権限 → 自動通信開始 → REPORT 作成・復元 → 停止

### 3. Nearby 2 台

承認 UI なし接続 → REPORT 同期 → ラベル確認 → 再接続

### 4. Phone ↔ PC public

[runbooks/PHONE_TO_PC_PUBLIC_SYNC_E2E.md](runbooks/PHONE_TO_PC_PUBLIC_SYNC_E2E.md)

### 5. （任意）Bridge経路のペアリング（内容検証とは別）

運用者経路のみ。一般 UX の合否条件に含めない。

## 合否の書き方

| 記号 | 意味 |
|------|------|
| PASS | 実機で確認した |
| FAIL | 実機で失敗した |
| NOT_RUN | 未実施 |
| N/A | 対象外 |

実施していない項目を PASS にしない。
