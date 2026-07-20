# PC Gateway セットアップ

**正本の運用モデル:** [OPERATION_MODEL.md](OPERATION_MODEL.md)

固定中継地点では、**個々のスマホ登録なし**で Android Bridge が PC へ REPORT を送れます（zero-operation / public ingress）。  
ペアリング + Bearer token は **運用者向けの認証済みBridge経路**（任意）です。
これは提出Bridgeの経路認証であり、REPORT本文や発信元の内容検証ではありません。

## 必要環境

- Java 17（ソースから起動する場合）
- または jpackage 済み EXE（`artifacts/relay-pc-gateway.exe`、ランタイム同梱）
- Windows ではネットワークを **Private** にし、Firewall で必要ポートだけ許可

## 起動（推奨・ワンアクション）

**日常の起動はこれだけです。** 管理者権限や Firewall 設定は不要です（localhost / 同一 PC での health・公開同期）。

### Windows（ダブルクリック）

1. エクスプローラーでリポジトリの **`Start-PC-Gateway.cmd`** をダブルクリックする
   （または PowerShell で `.\Start-PC-Gateway.cmd` / `.\scripts\run-pc-gateway.ps1`）
2. 初回のみ Gradle が `installDist` を構築します（**JDK 17** が必要）
3. コンソールが開いたら管理画面 **http://127.0.0.1:8080/** が自動で開きます
4. Health: **http://127.0.0.1:8080/api/health**
5. 停止: 起動ウィンドウで **Ctrl+C**

`artifacts/relay-pc-gateway.exe` は **インストール型（固定地点向け・WiX）** の別経路です。開発・日次起動の第一選択は `Start-PC-Gateway.cmd` です。

### macOS / Linux

```bash
chmod +x scripts/run-pc-gateway.sh
./scripts/run-pc-gateway.sh
```


## 起動（上級・手動 installDist）

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

1. `artifacts\relay-pc-gateway.exe` を実行してインストールする
2. 接続先が信頼できる固定地点LANであり、Windowsのネットワークプロファイルが **Private** であることを確認する
3. リポジトリルートの管理者PowerShellで、次の1コマンドを実行する

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\setup-pc-gateway.ps1
```

このコマンドは、Private限定のTCP 8080 / UDP 42888 Firewall規則、自動起動タスク登録、タスク開始、`/api/health`確認までを順番に行います。同じコマンドを再実行しても同名設定を更新するだけです。タスクはGatewayを同期実行して終了を監視し、異常終了時は1分間隔で再起動します。ログオン前、UPS・バッテリー動作中も起動を継続する設定です。

変更内容だけを非昇格で確認する場合:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\setup-pc-gateway.ps1 -DryRun
```

Publicプロファイルが1つでも存在する場合、セットアップは **Firewallやタスクを変更する前に停止** します。スクリプトが自動でPrivateへ変更することはありません。固定地点の隔離LANであることを運用者が確認した後、Windows設定でPrivateへ変更して再実行してください。

構文と安全条件だけを検査する場合:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\tests\pc-gateway-setup.tests.ps1
```

完了後は、電源投入でGatewayが自動起動し、同一LANのAndroidが自動発見・公開同期します。

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

## 任意: 運用者向けペアリング（Bridge経路認証）

1. 管理画面または `GET /api/pair/code`（`X-Admin-Key`）でコード生成
2. Bridge が `POST /api/pair/request`
3. 管理者が `POST /api/pair/approve` → Bearer token
4. `POST /api/sync/messages` + `GET /api/sync/receipts`（Bridge 単位にスコープ）
5. 拒否・失効: `POST /api/pair/reject` `{ "bridgeId": "..." }`

一般利用者の Android UI にはペアリング画面はありません。

認証同期の保存成功時は `GATEWAY_RECEIVED` が返ります。意味は
「認証済みBridge経路から受信し、PCのSQLite保存が完了した」です。
REPORT内容の署名検証、公式情報、本人確認、最終宛先への配信完了を意味しません。
管理画面では経路を `AUTHENTICATED_BRIDGE`、内容を `UNVERIFIED` と別々に表示します。

## セキュリティ注意

- MVP の LAN HTTP は **TLS なし**。信頼できない Wi‑Fi に公開しない
- Public ネットワークプロファイルでポートを開けない
- `setup-pc-gateway.ps1` がPublic判定で停止した場合、ネットワークの安全性を確認せずに回避しない
- 管理者キーの値はScheduled Task引数へ保存せず、ローカルの `%USERPROFILE%\.relay\admin.key` をSYSTEMタスクから参照する
- 公開経路の情報は **未検証** として扱う
- 詳細: [PC_GATEWAY_SECURITY.md](PC_GATEWAY_SECURITY.md)

## Windows EXE パッケージ

前提:

- フル JDK 17+（`jpackage`、例: `C:\Program Files\Java\jdk-17\bin`）
- WiX Toolset 3.x の `candle.exe` / `light.exe`

WiX はシステムインストール不要です。次を展開するとスクリプトが自動検出します:

```powershell
# 例: リポジトリ内 tools/wix314 へ展開
# https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip
```

```powershell
.\scripts\build-pc-gateway-exe.ps1 -AppVersion 0.2.2
```

`AppVersion` はReleaseタグから先頭の `v` を除いた値と一致させます。`RelayPcGateway` という名前とvendor `Relay` は既存インストールの更新識別子に使われるため変更しません。

成果物:

| ファイル | 内容 |
|----------|------|
| `artifacts/relay-pc-gateway.exe` | **Windows インストーラ**（jpackage + WiX） |
| `artifacts/relay-pc-gateway.exe.sha256` | SHA-256 |

生成例（2026-07-16）: サイズ約 78 MB、最新管理 UI 同梱。未署名のため SmartScreen 確認が出ることがあります。  
インストーラ実行後の本体は通常 `C:\Program Files\RelayPcGateway\RelayPcGateway.exe` です。上書きビルド前に実行中の Gateway / インストーラを停止してください。
