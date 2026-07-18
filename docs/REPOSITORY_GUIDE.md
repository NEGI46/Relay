# Relay repository guide

この文書は「どのファイルを触ればよいか」を最短で見つけるための案内です。
物理的なディレクトリ名はGradle、CI、配布スクリプトから参照されるため、
役割ごとの境界を保ったまま安定したトップレベル構成にしています。

## 変更場所の選び方

### アプリと共有ロジック

- Android画面・ViewModel・Service: `app/src/main/`
- Android専用テスト: `app/src/test/`, `app/src/androidTest/`
- Debug専用Binder/Snippet: `app/src/debug/`（releaseへ混入させない）
- 共有モデル・暗号・QR: `shared/src/`
- PCと共有するwire契約: `relay-protocol/`
- Compose Multiplatform画面: `composeApp/src/`

### Gatewayと外部接続

- JVM Gateway本体: `pc-gateway/`
- Windows BLE peripheral: `pc-ble-bridge/`
- Meshtastic adapter: `gateway-meshtastic-adapter/`
- BPv7 export boundary: `gateway-bp7-export/`
- Apple実装・GATT契約: `apple/`

MeshtasticとBPv7はRelay coreへ直接取り込まず、外部プロセス／export境界に
隔離します。OpenWrt/LibreMeshはコードではなく、`docs/design/`と
`docs/runbooks/`の設計・設置受入れ範囲です。

### 試験と品質

- 決定的なKotlin試験: 各モジュールの`src/test` / `src/commonTest`
- Android実機受入れ: `app/src/androidTest/` と `docs/DEVICE_TEST_*`
- Mobly契約: `test-lab/mobly/`
- BLE仮想GATT: `tools/ble-sim/`
- Decoder fuzz回帰: `test-lab/fuzz/`
- Toxiproxy／障害注入: `test-lab/toxiproxy/`, `test-lab/fault_injection/`
- Maestro UIフロー: `maestro/`
- Gateway・配布fixture: `tests/`, `tests/gateway-recovery/`, `tests/distribution/`

### 運用・配布

- ビルド／起動／バックアップ: `scripts/`
- GitHub Actions: `.github/workflows/relay-ci.yml`
- 設計・運用・セキュリティ: `docs/`
- 配布入力と署名metadata: `distribution/`
- 生成物・手元状態: `artifacts/`（ユーザー状態ファイル以外は原則未追跡）

## 変更の流れ

1. 実装対象のモジュールと契約文書を確認する。
2. 最小のunit/host testを追加する。
3. `test-lab/run-host-checks.ps1` と関係するGradle taskを実行する。
4. 署名・配布・外部境界はfixtureとfail-closed testを実行する。
5. 機能単位でコミットし、実機未検証を文書に残す。

## コミットしないもの

Keystore/PFX、API key、実機ログ、SQLite DB、`build/`、Python
`__pycache__/`、テスト実行で生成したJUnit XML、ローカルAPKは追跡対象外です。
