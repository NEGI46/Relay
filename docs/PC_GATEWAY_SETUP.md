# PC Gateway セットアップ

Java 17が必要です。初回依存取得にはネットワークが必要ですが、配布時は生成済み`installDist`または`distZip`をオフラインPCへコピーできます。

```powershell
.\gradlew.bat :pc-gateway:build
.\gradlew.bat :pc-gateway:installDist
```

起動:

```powershell
$env:RELAY_GATEWAY_HOST = '127.0.0.1'
$env:RELAY_GATEWAY_PORT = '8080'
$env:RELAY_GATEWAY_DB = "$PWD\data\relay-gateway.db"
$env:RELAY_GATEWAY_ID = 'pc-gateway-local'
$env:RELAY_GATEWAY_ADMIN_KEY = '<local-admin-key>'
.\pc-gateway\build\install\pc-gateway\bin\pc-gateway.bat
```

ブラウザで`http://127.0.0.1:8080/`を開く。Healthは`/api/health`である。管理画面は外部CDNを使わず、保存REPORT数、ACTIVE数、REPORT一覧、Bridge一覧、ペアリング操作を表示する。

## ペアリング

1. PC管理画面または`GET /api/pair/code`（`X-Admin-Key`必須）で5分間有効なコードを生成する。
2. AndroidのPC Gateway設定へPCのIP/ポート、Bridge ID、Gateway名、コードを入力し、ペアリング要求を送る。
3. PC管理画面でBridge IDを確認し、`POST /api/pair/approve`を管理者キー付きで実行する。
4. 発行されたBearer tokenをAndroidへ入力する。tokenはAndroid Keystoreで暗号化保存される。
5. Androidで手動同期または自動同期を開始する。

LAN公開する場合は`RELAY_GATEWAY_HOST=0.0.0.0`を明示し、Windows FirewallのTCPポートをPrivate network・Bridgeサブネットだけに限定する。Public networkへ開放しない。
# Windows EXE package

The PC Gateway can be packaged as a Windows console installer EXE. The package is unsigned and is intended for local/offline deployments.

Prerequisites:

- Full JDK 17 or newer with `jpackage` on `PATH` (the Android Studio runtime is not sufficient).
- WiX Toolset 3.x with `candle.exe` and `light.exe` on `PATH`.

Build reproducibly from the repository root:

```powershell
.\scripts\build-pc-gateway-exe.ps1
```

The generated installer is `artifacts\relay-pc-gateway.exe`. It installs a console launcher and the Gateway runtime. A Java runtime is bundled by `jpackage`; configure `RELAY_GATEWAY_HOST`, `RELAY_GATEWAY_PORT`, `RELAY_GATEWAY_DB`, and `RELAY_GATEWAY_ADMIN_KEY` before starting the installed launcher. The installer is not code-signed; Windows SmartScreen may require an explicit user confirmation.

If an older Gateway EXE is still running, Windows may lock the destination file during rebuild. Stop the running `relay-pc-gateway.exe` first, then rerun `scripts\build-pc-gateway-exe.ps1`. The latest build candidate is also emitted as `artifacts\relay-pc-gateway-updated.exe` when the original destination is locked.

## Hands-off fixed gateway operation

The fixed PC is not registered by individual phones. Set the Gateway to a Private-network address (for example `RELAY_GATEWAY_HOST=0.0.0.0`) and allow the configured TCP port only on the emergency LAN. The Gateway broadcasts a discovery hint on UDP port `42888`; Relay Android devices use it to find `/api/public/sync/messages` automatically.

For a fixed site, register the installed EXE as a delayed Windows startup task once:

```powershell
.\scripts\register-pc-gateway-autostart.ps1
```

After that, powering on the PC starts the Gateway. No phone, Bridge ID, pairing code, IP entry, or token entry is required. Unregistered Bridges may submit valid REPORT records through the public ingress; they are rate-limited, stored as `UNVERIFIED`, and receive `GATEWAY_RECEIVED_UNVERIFIED`. The dashboard is for local observation and operator administration only.
