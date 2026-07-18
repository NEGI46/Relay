# Relay 配布パッケージ

このフォルダーは、災害時のストア・アンド・フォワード伝言リレーを確認するための成果物です。

## まず試す（Windows + Android デバッグ版）

1. `Windows/RelayPcGateway/bin/pc-gateway.bat` を起動します。
2. PC と Android を同じプライベート Wi‑Fi に接続します。
3. Android に `Android/Relay-debug.apk` をインストールします。
4. Android のアプリデータを消去して初回起動し、10 秒ほど待ちます。PC の避難所公開鍵を自動取得します。
5. Android で「助けを求める」から救助要請を作成します。送信先避難所が表示されれば接続確認済みです。

詳細は `Android/DEBUG_LOCAL_TEST.md` と `Operations/PC_GATEWAY_SETUP.md` を参照してください。

ローカル検証用の Android／Windows debug・release 相当成果物を再生成する場合は、PowerShell で `build-local-artifacts.ps1` を実行します。生成される署名は開発用で、本番配布には使えません。

## 成果物

| 対象 | ファイル | 状態 |
| --- | --- | --- |
| Android デバッグ版 | `Android/Relay-debug.apk` | ローカル自動接続用。開発・検証専用 |
| Android 正規版ビルド | `Android/Relay-release-unsigned.apk` | 正式証明書で署名する前の APK |
| Android ローカル署名版 | `Android/Relay-release-signed.apk` | 開発用証明書。配布・本番利用不可 |
| Windows Gateway | `Windows/RelayPcGateway/` | Java 17 で起動する避難所 PC アプリ |
| Windows BLE Bridge | `Windows/BleBridge-Release/Relay.PcBleBridge.exe` | self-contained 実行ファイル。MSIX 内でのみ起動 |
| Windows BLE Bridge ローカル署名版 | `Windows/Relay.PcBleBridge-local-signed.msix` | 自己署名の検証用。正式配布不可 |
| Windows BLE Bridge デバッグ版 | `Windows/Debug/Relay.PcBleBridge-debug-local-signed.msix` | Debug 構成の自己署名検証用 |
| iPhone 共通コード | `iPhone/RelayAppleKit/` | macOS + Xcode でアプリへ組み込むソース |
| macOS Gateway | `macOS/` | ソースから Gateway を構築するスクリプト |

## 正式版に必要な外部作業

- Android: 組織のリリース keystore で署名し、署名済み APK/AAB を生成する。
- Windows: 組織の Publisher とコード署名証明書、MSIX 用画像を用意し、`Windows/BleBridge-Source/New-BridgeMsix.ps1` を実行する。
- iPhone/macOS: macOS + Xcode + Apple Developer の署名・プロビジョニングで IPA/app を Archive する。
- Android/iPhone/Windows の実機で Bluetooth、バックグラウンド、切断・再送を確認する。

鍵・証明書はリポジトリや配布フォルダーへ追加しないでください。
