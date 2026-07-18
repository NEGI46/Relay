# リリース引き継ぎチェックリスト

## この環境で検証済み

- Android `:app:testDebugUnitTest`：成功
- PC Gateway `:pc-gateway:test`：成功
- Android `:app:assembleRelease`：成功
- Android debug APK：v1/v2 署名検証済み
- Android ローカル署名 release APK：v1/v2/v3 署名検証済み
- Windows BLE Bridge：`dotnet build` 0 warning / 0 error
- Windows BLE Bridge：win-x64 self-contained publish 成功
- Windows BLE Bridge：ローカル自己署名 MSIX の生成成功（信頼チェーンは未登録）
- Windows BLE Bridge：Debug 構成のローカル自己署名 MSIX 生成成功
- PC Gateway：`/api/health` が HTTP 200 を返すことを確認済み
- `:composeApp:compileKotlinDesktop` / `:composeApp:compileDebugKotlinAndroid`：成功

## 正式配布前に必須

ローカル検証版はリポジトリルートの `scripts/build-local-artifacts.ps1`（または配布フォルダーの同名ラッパー）で Android／Windows の debug・release 相当を再生成できます。

配布フォルダーの整合性は `verify-distribution.ps1` で確認できます。

Apple 側は macOS 上で `iPhone/build-apple-artifacts.sh` を実行できます。Xcode ホストを指定した場合だけ Release Archive を行い、未指定時は Swift Package と Simulator framework の検証で安全に停止します。

1. Android 組織用 keystore で release APK/AAB を署名する。
2. 避難所ごとの公開鍵・Manifest を運用環境で発行し、Android release trust store に配布する。debug の自動登録（TOFU）は release では無効です。
3. Windows の組織 Publisher、コード署名証明書、3 枚の PNG アイコンを用意し、`Windows/BleBridge-Source/New-BridgeMsix.ps1` で正式 MSIX を作成・署名する。
4. macOS + Xcode + Apple Developer 証明書で iPhone/macOS の Archive、署名、実機インストールを行う。
5. Android 2 台、Windows Gateway、可能なら iPhone で Bluetooth 発見、バックグラウンド復旧、切断・再送、重複、期限切れ、受領証明検証を確認する。

## 署名に関する注意

`Android/Relay-release-signed.apk`、`Windows/Relay.PcBleBridge-local-signed.msix`、`Windows/Debug/Relay.PcBleBridge-debug-local-signed.msix` は開発用自己署名証明書のスモークテストです。本番の正規版として配布しないでください。秘密鍵・パスワード・Apple 証明書を配布フォルダーへコピーしないでください。
