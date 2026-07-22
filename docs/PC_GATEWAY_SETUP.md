# PC Gateway セットアップ

正本の安全な共同実証手順は [PRODUCTION_GATEWAY_DEPLOYMENT.md](runbooks/PRODUCTION_GATEWAY_DEPLOYMENT.md) です。Relay は119代替や実災害向け完成品ではありません。

## 既定の安全な起動

production が既定です。何も明示しない場合、Gateway は 127.0.0.1:8080 にだけ bind し、匿名 ingress、UDP discovery、リモート管理、旧 X-Admin-Key を無効にします。

Windows のローカル開発/確認:

~~~powershell
.\scripts\run-pc-gateway.ps1
# health: http://127.0.0.1:8080/api/health
~~~

production/lab は、承認済みの rescue private-key file が owner-only 権限で既に provision されていないと起動しません。これは鍵喪失時に新しい鍵を黙って作らないための fail-closed です。鍵を provision した後の初回 health の `bootstrap_required` は障害ではなく、スタッフ画面を fail-closed にしている状態です。既定パスワードはありません。ローカル console で named ADMIN を一度だけ作成します。

~~~powershell
$env:RELAY_GATEWAY_DB = "$env:USERPROFILE\.relay\relay-gateway.db"
$secure = Read-Host 'Bootstrap password' -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
  $env:RELAY_GATEWAY_BOOTSTRAP_CLI_SECRET = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
  .\pc-gateway\build\install\pc-gateway\bin\pc-gateway.bat bootstrap-admin --username shelter-admin
} finally {
  [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
  Remove-Item Env:RELAY_GATEWAY_BOOTSTRAP_CLI_SECRET -ErrorAction SilentlyContinue
}
~~~

ブラウザは staff username/password でログインし、HttpOnly/SameSite session cookie を使います。ADMIN は API/UI から OPERATOR と VIEWER を追加・無効化できます。旧共有 PIN / admin.key / X-Admin-Key を production/lab で作成・配布・再有効化してはいけません。

## LAN を使う場合

LAN は既定で使いません。次のどちらかを、自治体/ネットワーク責任者の明示判断後に選びます。

1. closed-network: approved closed network と Private-only firewall を確認した限定 ingress。リモート staff console は無効のまま。Android release/pilotRelease は plaintext HTTP を拒否するため、モバイル Gateway 接続には別途承認済み HTTPS 終端が必要。
2. tls-reverse-proxy: Gateway は loopback のまま、別運用の TLS reverse proxy が HTTPS、証明書、アクセス制御を担当。リモート管理を有効にするなら Secure cookie が必須。

Windows scheduled task の例:

~~~powershell
# loopback-only production
powershell -ExecutionPolicy Bypass -File .\scripts\setup-pc-gateway.ps1

# approved closed network only; do not use on Public/guest Wi-Fi
powershell -ExecutionPolicy Bypass -File .\scripts\setup-pc-gateway.ps1 -LanMode closed-network -HostBind 10.0.0.10 -EnableAnonymousIngress -EnableLanDiscovery
~~~

スクリプトは Public network profile を検出すると LAN firewall/task 変更前に停止します。TLS proxy、DNS、証明書、WAF、閉域網の正当性をスクリプトが検証済みと主張することはありません。

## 開発互換モード

既存の匿名 LAN / UDP discovery / HTTP 管理キー経路を試す必要がある場合だけ、明示的に development を選びます。

~~~powershell
$env:RELAY_PROFILE = 'development'
$env:RELAY_GATEWAY_LAN_MODE = 'disabled'
.\scripts\run-pc-gateway.ps1
~~~

これは限定されたローカル開発用です。共同実証、release/pilotRelease Android、または実運用に使わないでください。

### 開発プレビューの簡単な起動（Windows）

開発版インストーラーを実行した後、配布物にある
`Start-Relay-PC-Gateway-Development.cmd` をダブルクリックしてください。初回は管理者の
**ユーザー名と12文字以上のパスワードだけ**を入力します。開発用DB・生成鍵は
`%LOCALAPPDATA%\Relay\development` に隔離され、localhostの管理画面を開きます。

この開発ランチャーは、デバッグ版Androidが同一LAN上で見つけた開発Gatewayの公開鍵を自動登録できるよう、`development` profile の匿名救助受信とUDP discoveryを有効にします。production/pilot のアプリ・Gatewayはこの経路を受け入れません。既存のGatewayが同じポートで動いている場合は二重起動せず、その画面を開きます。

GitHub の **Publish Relay development preview** は、このランチャーと unsigned Windows
installer、debug/localDev Android APK を GitHub prerelease として公開します。これは
**正式版ではありません**。署名済みの `Publish Relay formal release` とは完全に別で、
実証・緊急運用には使わないでください。

## Broker

RELAY_BROKER_URL は HTTPS のみです。Broker は production/lab で loopback bind し、別の TLS proxy の背後に置きます。Gateway ごと・避難所ごとの scoped credential は Broker の issue-gateway-credential で発行し、Gateway に RELAY_BROKER_CREDENTIAL として渡します。共有 RELAY_BROKER_API_KEY は development 互換だけです。

## 鍵、バックアップ、配布

- rescue private key file は owner-only permission/ACL が確認できないと起動を停止します。DPAPI/HSM/KMS はこの実装ではありません。
- DB backup は [GATEWAY_BACKUP.md](runbooks/GATEWAY_BACKUP.md) を使い、private key/credential/secret file を含めません。
- debug/未署名/ローカル署名 APK と未署名 Windows installer は正式成果物ではありません。正式 release は組織署名、SBOM、脆弱性検査、署名検証が揃うまで fail-closed です。

実機・現地試験は [FIELD_ACCEPTANCE_TEST.md](runbooks/FIELD_ACCEPTANCE_TEST.md)、外部前提は [BLOCKED_BY_EXTERNAL_DECISIONS.md](readiness/BLOCKED_BY_EXTERNAL_DECISIONS.md) を参照してください。
