# Relay

災害時にインターネットがなくても、Android端末同士・PC Gateway・BLE
Bridgeを使って安全情報と救助要請を Store-Carry-Forward するプロジェクトです。

## まず読む

1. [リポジトリ案内](docs/REPOSITORY_GUIDE.md)
2. [運用モデル](docs/OPERATION_MODEL.md)
3. [アーキテクチャ](docs/architecture.md)
4. [実装監査](docs/IMPLEMENTATION_AUDIT.md)
5. [実機テスト計画](docs/DEVICE_TEST_PLAN.md)

## 主要モジュール

| 場所 | 役割 |
|---|---|
| `app/` | Androidアプリ（Compose、Room、Nearby、BLE Courier） |
| `shared/` | Kotlin Multiplatformの共有モデル・暗号・QR |
| `relay-protocol/` | Gatewayとの共通プロトコル |
| `composeApp/` | Compose Multiplatform UI |
| `pc-gateway/` | JVM Gateway（SQLite、HTTP、LAN beacon） |
| `pc-ble-bridge/` | Windows BLE GATT Bridge（.NET 8） |
| `apple/` | Apple向け共有・GATT契約 |
| `gateway-*` | Meshtastic / BPv7 の外部境界adapter |
| `scripts/` | ビルド、配布、署名、セキュリティ、運用スクリプト |
| `test-lab/`, `tools/`, `tests/`, `maestro/` | ホスト・実機・UI・障害試験 |
| `docs/` | 設計、運用、セキュリティ、実機受入れ文書 |

## よく使うコマンド

```powershell
# 共有・UI・ホストのローカル検証
.\gradlew.bat :shared:jvmTest :composeApp:desktopTest --no-daemon
.\test-lab\run-host-checks.ps1

# Android本体とinstrumentation testのコンパイル
.\gradlew.bat :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --no-daemon

# セキュリティ／配布検証
.\scripts\run-jazzer.ps1
.\scripts\run-syft.ps1
.\scripts\run-grype.ps1
.\scripts\run-osv.ps1
.\scripts\verify-distribution-signatures.ps1
```

実機が必要な検証は、成功したことを推測してPASSにしません。未接続の
Android端末、実Meshtastic無線機、実MobSF/cosign/age環境は、テスト結果に
明示的な未実施またはskipとして残します。

## 開発ルール

- 変更は機能単位で小さくコミットする。
- 秘密鍵、Keystore、実データ、生成APKはコミットしない。
- 外部通信のないFake/host testを先に実行し、最後に実機受入れを行う。
- 署名・暗号化・実機通信の境界を越えて責任範囲を混ぜない。
