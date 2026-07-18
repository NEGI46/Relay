# Apple ターゲット（iPhone / macOS）

## 現在の成果物

- `apple/RelayAppleKit`：Windows BLE Bridge と同じ GATT フレーム、暗号化済み不透明キュー、避難所信頼検証、受領証明検証を提供する Swift Package。
- `composeApp`：Kotlin Multiplatform の iOS framework ターゲット。macOS + Xcode でアプリへ組み込めます。
- `scripts/setup-pc-gateway-macos.sh`：macOS 上で PC Gateway を構築するスクリプト。

## macOS での確認

```bash
cd apple/RelayAppleKit
swift test
cd ../..
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```

Simulator 用 framework は iPhone 実機向け IPA ではありません。実機版には Xcode の Release Archive、Apple Developer の署名・プロビジョニング、CoreBluetooth の権限設定が必要です。

## iPhone ホストアプリの責務

ホストアプリは `RelayTrustedGattLink` を CoreBluetooth へ接続し、`NSBluetoothAlwaysUsageDescription`、バックグラウンド Bluetooth、暗号化ストレージ、署名済み地域ディレクトリ、受領証明検証を実装します。スキャン名や RSSI、未検証 JSON を避難所の信頼判断に使ってはいけません。

Windows では Swift／Xcode／IPA 署名と CoreBluetooth 実機試験を実行できません。したがって、このリポジトリに含まれる Apple 成果物はソースと framework ビルド手順までで、IPA は未生成です。
