# iPhone 版を macOS でビルドする

このフォルダーには IPA は含まれていません。iOS アプリの署名・実機ビルドは macOS と Xcode が必要です。

## 必要なもの

- macOS、Xcode 15 以降
- Apple Developer Program の署名証明書と provisioning profile
- Bluetooth / バックグラウンド動作を確認できる iPhone
- Relay リポジトリ全体（`RelayAppleKit` と `composeApp`）

## 共通コードの確認

```bash
cd distribution/Relay-User-Package/iPhone/RelayAppleKit
swift test
```

リポジトリ全体の Apple 検証は `iPhone/build-apple-artifacts.sh`（またはルートの `scripts/build-apple-artifacts.sh`）で実行できます。Xcode ホストを用意した場合は `RELAY_XCODE_PROJECT`、`RELAY_XCODE_SCHEME`、`RELAY_XCODE_ARCHIVE_PATH` を設定すると Release Archive まで実行します。

アプリ本体は Xcode プロジェクトへ `RelayAppleKit` を Swift Package として追加し、iOS の Bluetooth 権限、バックグラウンドモード、Gateway 接続画面を設定してください。Release は Xcode の Archive から組織の証明書で署名します。

## 現在の制約

- Windows 上では IPA を生成・署名できません。
- Android と iPhone の実 Bluetooth 相互接続は、iOS 実機を用いた統合試験が必要です。
- Apple の証明書や秘密鍵はこのリポジトリへ置かないでください。

共通コードのテストと iOS Simulator 用 framework のビルドは確認用です。iPhone 実機用 IPA は Xcode の Release Archive と Apple Developer 署名で生成してください。
