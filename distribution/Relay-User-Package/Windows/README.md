# Windows 配布物

## Gateway（避難所 PC アプリ）

`RelayPcGateway/bin/pc-gateway.bat` を実行してください。Java 17 以降が必要です。起動後、ブラウザーで `http://127.0.0.1:8080` を開きます。

Gateway はインターネットなしで動作し、Android 端末から暗号化された救助要請を受信・復号・一覧表示し、受領証明を返します。

## BLE Bridge

`BleBridge-Release/Relay.PcBleBridge.exe` は .NET 8 self-contained の実行ファイルです。Windows BLE のバックグラウンド権限とパッケージ ID が必要なため、単体 exe のままでは起動せず、MSIX 内で実行する設計です。

正式 MSIX は、組織の Publisher、コード署名証明書、3 枚の PNG アイコンを用意して、`BleBridge-Source/New-BridgeMsix.ps1` で生成してください。スクリプトは placeholder Publisher を拒否します。

`Relay.PcBleBridge-local-signed.msix` は自己署名証明書で作成したローカル検証版です。証明書を Windows の信頼された発行元へ登録しない限りインストールできません。本番配布には使用しないでください。

`Debug/Relay.PcBleBridge-debug-local-signed.msix` は Debug 構成の検証版です。運用環境へ配布しないでください。

単体 exe の起動失敗は fail-closed の想定動作です。Bluetooth がない環境や未署名パッケージで、未認証の受信を開始しません。
