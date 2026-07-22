# PC Gateway ログオン自動起動 (development / PoC)

Windows へログオンした後に development プロファイルの PC Gateway (Docker) を自動起動し、
PC 再起動後もローカル管理画面 (`http://127.0.0.1:8080/`) と Broker Pull を復旧するための手順です。

- 対象: 実証 (development / PoC) 環境のみ。本番運用機能は含みません。
- スクリプト: [`scripts/register-poc-gateway-autostart.ps1`](../../scripts/register-poc-gateway-autostart.ps1)
- 起動本体: 既存の [`scripts/start-poc-broker-gateway-docker.ps1`](../../scripts/start-poc-broker-gateway-docker.ps1) を再利用します。
- タスク名: `Relay-PC-Gateway-Autostart`
- 実行権限: **現在のユーザー**（昇格なし・パスワード非保存）。トリガーは **ログオン時**。

> 本番 (native EXE / SYSTEM 常駐) の自動起動は別系統の
> [`scripts/register-pc-gateway-autostart.ps1`](../../scripts/register-pc-gateway-autostart.ps1) です。
> 本 PoC スクリプトはそれを置き換えません。

## 前提

- Docker Desktop がインストール済みで、`compose.quick-tunnel.yml` の PoC 構成が使える状態。
- 少なくとも一度は
  [Quick Tunnel Broker PoC](QUICK_TUNNEL_BROKER_POC.md) の手順で Broker / cloudflared を起動し、
  救助鍵ファイル（`~/.relay/rescue-keys.json`）が用意されていること。

## 登録

通常のユーザー PowerShell（管理者昇格は不要）で実行します。

```powershell
.\scripts\register-poc-gateway-autostart.ps1
```

任意で `start-poc-broker-gateway-docker.ps1` への非秘密パラメータを渡せます（Broker 資格情報など
秘密情報は渡しません。資格情報は起動時にスクリプトが都度発行します）。

```powershell
.\scripts\register-poc-gateway-autostart.ps1 -BrokerUrl https://<random>.trycloudflare.com -EnableLanEnrollment
```

登録内容（実際に Task Scheduler に入るコマンド）は、実行後の出力で確認できます。

## 状態確認

タスク登録状況・Docker 稼働状況・Gateway health のみを表示します（秘密情報は出力しません）。

```powershell
.\scripts\register-poc-gateway-autostart.ps1 -Status
```

出力例:

```
Relay PC Gateway autostart status:
  Task registered : True
  Task state      : Ready
  Docker ready    : True
  Gateway healthy : True
```

## 解除

登録済みタスク（`Relay-PC-Gateway-Autostart`）だけを安全に削除します。他タスクや
ユーザー設定・DB・救助鍵・Docker volume には一切触れません。

```powershell
.\scripts\register-poc-gateway-autostart.ps1 -Unregister
```

## 動作概要

ログオン時、タスクは同スクリプトを `-Run` で起動し、次を順に行います。

1. `docker info` が成功するまで待機・再試行（既定タイムアウト 300 秒）。タイムアウト時は
   分かりやすいエラーをログへ記録して失敗終了します。
2. すでに Gateway health が正常なら **二重起動しません**。
3. 正常でなければ `start-poc-broker-gateway-docker.ps1` で起動します。
4. 起動後、`http://127.0.0.1:8080/api/health` を最大 30 秒確認します。
5. 成功・失敗の結果を、秘密情報を含めずにログへ記録します。

## ログ

- 場所: `%LOCALAPPDATA%\Relay\logs\pc-gateway-autostart.log`
- Broker 資格情報・管理者パスワード・救助内容・GPS・秘密鍵は**出力しません**
  （多層防御として書き込み時にも自動でマスクします）。

```powershell
Get-Content "$env:LOCALAPPDATA\Relay\logs\pc-gateway-autostart.log" -Tail 50
```

## 障害時の確認

1. `-Status` で「どこまで復旧しているか」を確認する。
2. `Docker ready : False` の場合 → Docker Desktop の起動を確認。手動確認は `docker info`。
3. `Gateway healthy : False` の場合 → PoC ログを確認する（秘密情報は含まれません）。

   ```powershell
   docker compose -f compose.quick-tunnel.yml logs -f pc-gateway
   ```
4. ポート競合（8080 使用中）が疑われる場合はホスト側 Gateway を停止してから再試行。
5. 自動起動の履歴は上記ログファイルと、Task Scheduler の「Relay-PC-Gateway-Autostart」
   タスク履歴で確認できます。

## テスト

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\tests\register-poc-gateway-autostart.tests.ps1
```

- タスク登録コマンドの引数（現在ユーザー・ログオン時・パスワード非保存・`-Run`）
- `-Unregister` が対象タスクだけを削除すること
- ログに秘密情報を出力しないこと
- Docker 未起動時にタイムアウトし、Gateway を起動しないこと

を検証します。
