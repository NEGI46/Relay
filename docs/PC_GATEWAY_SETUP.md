# PC Gateway セットアップ

**正本の運用モデル:** [OPERATION_MODEL.md](OPERATION_MODEL.md)

固定中継地点では、**個々のスマホ登録なし**で Android Bridge が PC へ REPORT を送れます（zero-operation / public ingress）。  
ペアリング + Bearer token は **運用者向け verified 経路**（任意）です。

## 必要環境

- Java 17（ソースから起動する場合）
- または jpackage 済み EXE（`artifacts/relay-pc-gateway.exe`、ランタイム同梱）
- Windows ではネットワークを **Private** にし、Firewall で必要ポートだけ許可

## 起動（ソース / installDist）

```powershell
.\gradlew.bat :pc-gateway:build
.\gradlew.bat :pc-gateway:installDist

$env:RELAY_GATEWAY_HOST = '0.0.0.0'   # 既定も 0.0.0.0。LAN 公開用
$env:RELAY_GATEWAY_PORT = '8080'
$env:RELAY_GATEWAY_DB = "$env:USERPROFILE\.relay\relay-gateway.db"
$env:RELAY_GATEWAY_ID = 'pc-gateway-local'
# 未設定時は %USERPROFILE%\.relay\admin.key を生成して永続化（コンソールには値を出さない）
# $env:RELAY_GATEWAY_ADMIN_KEY = '<optional-fixed-key>'
.\pc-gateway\build\install\pc-gateway\bin\pc-gateway.bat
```

- **管理コンソール（UI）**: `http://127.0.0.1:8080/`  
  概要カード / メッセージ一覧・詳細 / Bridge 管理 / ペアリング操作 / CSV 出力 / 5 秒自動更新
- Health: `http://127.0.0.1:8080/api/health`
- ダッシュボード JSON: `GET /api/dashboard`（件数・直近一覧、payload なし）
- 公開同期: `POST /api/public/sync/messages`（token 不要）
- LAN 発見ビーコン: **UDP 42888**（5 秒間隔）

コンソールでメッセージ詳細・CSV・ペアリングを行うには、画面上部に **管理者キー**（`%USERPROFILE%\.relay\admin.key` の内容）を入力して「保存」します。キーはブラウザの localStorage にのみ保持し、サーバへは各 API の `X-Admin-Key` として送ります。

## 固定地点（推奨手順）

1. EXE をビルドまたは配置する  
   `.\scripts\build-pc-gateway-exe.ps1`  
   正式成果物: `artifacts\relay-pc-gateway.exe`  
   （実行中ロック時の候補: `artifacts\relay-pc-gateway-updated.exe`）
2. Firewall（管理者 PowerShell）  
   `.\scripts\configure-pc-gateway-firewall.ps1`
3. 自動起動  
   `.\scripts\register-pc-gateway-autostart.ps1 -Executable "C:\Path\To\RelayPcGateway.exe"`
4. 電源投入で Gateway 起動 → 同一 LAN の Android が自動発見・公開同期

保存成功時の Receipt は **`GATEWAY_RECEIVED_UNVERIFIED`**（中継拠点保存・未認証）。公式 Gateway 到達や最終配信完了ではありません。

## 環境変数

| 変数 | 既定 | 意味 |
|------|------|------|
| `RELAY_GATEWAY_HOST` | `0.0.0.0` | HTTP bind |
| `RELAY_GATEWAY_PORT` | `8080` | HTTP port |
| `RELAY_GATEWAY_DB` | `%USERPROFILE%\.relay\relay-gateway.db` | SQLite |
| `RELAY_GATEWAY_ID` | `pc-gateway-local` | Receipt actor |
| `RELAY_GATEWAY_ADMIN_KEY` | ファイル永続 | 管理 API / ペアリング |
| `RELAY_GATEWAY_ADMIN_KEY_FILE` | `%USERPROFILE%\.relay\admin.key` | キー保存先 |
| `RELAY_GATEWAY_ANONYMOUS_INGRESS` | `true` | 公開同期 |
| `RELAY_GATEWAY_LAN_DISCOVERY` | `true` | UDP ビーコン |
| `RELAY_GATEWAY_DISCOVERY_PORT` | `42888` | ビーコン port |

## 任意: 運用者向けペアリング（verified）

1. 管理画面または `GET /api/pair/code`（`X-Admin-Key`）でコード生成
2. Bridge が `POST /api/pair/request`
3. 管理者が `POST /api/pair/approve` → Bearer token
4. `POST /api/sync/messages` + `GET /api/sync/receipts`（Bridge 単位にスコープ）
5. 拒否・失効: `POST /api/pair/reject` `{ "bridgeId": "..." }`

一般利用者の Android UI にはペアリング画面はありません。

## セキュリティ注意

- MVP の LAN HTTP は **TLS なし**。信頼できない Wi‑Fi に公開しない
- Public ネットワークプロファイルでポートを開けない
- 公開経路の情報は **未検証** として扱う
- 詳細: [PC_GATEWAY_SECURITY.md](PC_GATEWAY_SECURITY.md)

## Windows EXE パッケージ

前提: JDK 17+（`jpackage`）、WiX 3.x。

```powershell
.\scripts\build-pc-gateway-exe.ps1
```

未署名のため SmartScreen 確認が出ることがあります。EXE 実行中は上書きできないので、先にプロセスを停止してください。
