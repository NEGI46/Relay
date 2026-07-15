# Relay 実機検証レポート

更新日: 2026-07-15

## 結論

実機用Nearby Connections Transport、Android版別の権限処理、connected-device Foreground Service、手動認証UI、および既存`SyncCoordinator`への実運用配線を実装した。従来のCritical 3件はコード上解消している。

ただし、`adb devices -l`で接続端末は0台だった。このため、インストール、起動、REPORT保存・復元、権限拒否、Foreground Service通知、Peer発見、認証、直接同期および多段中継は、いずれも実機では未実施である。実施していない項目をPASSとは判定しない。

その後、PC Gateway（Kotlin/JVM/Ktor/SQLite）とAndroid Bridge同期コードを追加した。PCプロセス単体のHealth、管理画面、SQLite保存、ペアリング、認証済みREPORT投入、Gateway Receipt再取得は実行確認済みだが、Android実機からのLAN同期は未実施である。

最終Gradle検証、APK署名検査、Manifest検査およびハッシュ照合まで完了した。実機接続は0台のため、端末上の結果は未実施である。

## 作業ツリーの保護

| 項目 | 結果 |
|---|---|
| ブランチ | `master` |
| Git履歴 | コミットなし |
| 作業ツリー | プロジェクト全体が未追跡。既存変更に対するreset、checkout、stash、削除は未実施 |
| QR・ファイル・Web・USB・SMS・補助同期 | 未実装のまま。今回の対象外 |

## 従来のCritical 3件と対応

| 従来のCritical問題 | 今回の対応 | コード上の状態 | 実機確認 |
|---|---|---|---|
| 実機用Nearby Transportがなく、発見・接続・認証・Payload送受信ができない | `NearbyConnectionsTransport`とGoogle Play services用の薄い`GoogleNearbyPlatform`を追加。Advertising、Discovery、手動承認・拒否、BYTES送受信、転送結果、停止時cleanupを実装 | 修正済み | 未実施 |
| Nearby権限とRelay用Foreground Serviceがない | Android版別の権限ポリシー、事前条件確認、`connectedDevice` Foreground Service、通知Channel、停止Actionを追加 | 修正済み | 未実施 |
| UIがFake表示のみで実Transport・同期処理へ接続されていない | `RelayApplication`をcomposition rootとしてNearby Transport、`SyncCoordinator`、共有Runtimeを構成し、ViewModel、ホーム、Peer、デバッグ画面およびServiceへ配線 | 修正済み | 未実施 |

Debug実運用ビルドのcomposition rootは`GoogleNearbyPlatform -> NearbyConnectionsTransport -> SyncCoordinator -> RelayCommunicationRuntime`である。一般利用者向けUIにFake切替はない。既存JVMテストは`FakeOfflineTransport`を直接生成するため、Google Play servicesへ依存しない。

## 独立レビューで検出し、統合中に修正した問題

以下は初回実装後の独立コードレビューで検出した。最終統合では、実機テスト前に修正対象とした。

1. AdvertisingまたはDiscovery開始失敗を正常開始として扱い、Runtime/UIが通信中になり得る問題。開始結果を失敗としてRuntimeとServiceへ伝播し、通信中表示を残さないよう修正した。
2. 小容量BYTESのPayload完了callbackが待機登録より先に到着すると完了を取りこぼす競合。Payload IDと待機状態を送信前に登録できる境界へ修正した。
3. Nearby APIへの送信要求とPayload転送完了を同じ成功ログとして扱い得る問題。送信要求、転送完了・失敗、Peer ACK、Gateway Receiptを別イベントとして記録するよう修正した。

これらのコード修正は最終単体テスト対象に含めるが、Google Play services実装のcallback順序とOS動作は実機未検証である。

## 実装構成

### Nearby Transport

- 通信戦略は、小容量データを複数Peer間で交換できる`P2P_CLUSTER`を使用する。
- AdvertisingとDiscoveryはDRILLまたはRELAYモードを明示開始した場合だけ動作する。
- 接続開始時にNearbyの認証コードを双方へ表示し、ユーザーが承認または拒否する。ロールや自己申告IDによる自動承認は行わない。
- 受信BYTESはTransportから既存`SyncCoordinator`へ渡す。TransportからRoomへ直接保存する経路はない。
- 不正JSON、サイズ制限、TTL、hopLimit、重複排除、ACKおよびReceiptは既存の検証・同期パイプラインを通る。

### ACKの区別

| 段階 | 意味 |
|---|---|
| Payload送信要求 | Nearby APIが送信要求を受理した段階 |
| Payload転送完了 | NearbyのPayload callbackが転送成功を報告した段階 |
| Peer ACK | 相手Relayアプリが検証し、ローカル保存に成功した段階 |
| Gateway Receipt | GATEWAYロールの端末が保存に成功した段階 |

Peer ACKはGateway到達や最終宛先への配信完了として表示しない。Gateway ReceiptはGATEWAY保存成功時だけ生成し、保存拒否、collisionまたは検証失敗時には生成しない。

### Foreground Service

- Manifestで`foregroundServiceType="connectedDevice"`を宣言する。
- DRILLまたはRELAY開始時だけ起動し、接続Peer数と停止Actionを常時通知へ表示する。
- Service、ViewModelおよびUIはApplicationスコープの同一Runtimeを参照する。
- Service停止時は同期SessionとTransportを停止し、UI stateを停止へ戻す。
- OSによる長時間バックグラウンド動作は保証しない。

## DeviceRole互換性

- `DeviceRole`は`MEMBER`、`COURIER`、`RELAY`、`GATEWAY`、`ADMIN`である。
- RoleはSharedPreferencesへordinalではなくenum名の文字列で保存する。
- `MEMBER`と`GATEWAY`を含む全Roleの往復、未知値の安全なフォールバックおよび旧`"GATEWAY"`値をJVMテスト対象としている。
- DeviceRoleは同期Wire modelとRoom message schemaには含まれないため、Role追加に伴うRoom migrationは不要である。
- Roleは再起動後も復元する。動作モードは安全側の`NORMAL`へ戻し、通信を自動再開しない。

## Import・Export契約

`MessageImportTransport`と`MessageExportTransport`には実装・DI・UI経路がない。成功を返すNoOp実装もない。QR、Relayファイル、ローカルWeb、USB、SMSおよびインターネット補助同期は利用可能に見せていない。

将来Importが既存検証経路を必ず通ることは文書上の制約であり、interfaceの型だけではRoomへの直接書込みを完全には禁止できない。この点は将来実装時のMajor設計課題だが、現在のAPKにはImport入口がないため、今回の実機Nearby検証を妨げない。

## 最終ビルド・テスト結果

| コマンド・確認 | 結果 |
|---|---|
| `.\gradlew.bat clean --no-daemon --console=plain` | PASS |
| `.\gradlew.bat testDebugUnitTest --no-daemon --console=plain` | PASS: 47件、失敗0、エラー0 |
| `.\gradlew.bat lintDebug --no-daemon --console=plain` | PASS: Error 0、Warning 48 |
| `.\gradlew.bat assembleDebug --no-daemon --console=plain` | PASS |
| Debug署名 | PASS: Android Debug、v1/v2検証成功 |
| APK Manifest / metadata | PASS: applicationId、SDK、Launcher、権限、`connectedDevice` Serviceを確認 |

従来の29件に加え、Nearby event変換、承認前送信拒否、拒否・切断、Payload転送状態、権限不足、二重開始、Service停止seam、受信検証、MEMBER ACK、Gateway成功・失敗を対象とするテストを追加した。最終結果は45件PASS、失敗0、エラー0である。

## APK

| 項目 | 結果 |
|---|---|
| Gradle出力 | `app/build/outputs/apk/debug/app-debug.apk` |
| 配布コピー | `artifacts/relay-debug.apk` |
| ファイルサイズ | 12,453,533 bytes |
| SHA-256 | `B0412E1B57CBD33C73045235FD27974959B56A9E66BFA5EDA6AE326DB3FE1838` |
| 出力元と配布コピーの一致 | PASS |
| applicationId | `com.example.relay` |
| versionName / versionCode | `0.1.0` / `1` |
| minSdk / targetSdk / compileSdk | 23 / 36 / 36 |
| Launcher Activity | `com.example.relay.MainActivity` |
| アプリ名 | `Relay` |
| 署名 | PASS: Android Debug certificate、v1/v2 |

Manifestには旧Android向けBluetooth・位置情報権限、Android 12以降のBluetooth権限、Android 13以降のNearby Wi-Fi・通知権限、Foreground Service権限と`connectedDevice` Service宣言が含まれることを、生成APKから確認した。

## 実機接続と実施状況

| 項目 | 結果 |
|---|---|
| `adb devices -l` | 接続端末0台 |
| 使用端末 | なし |
| Androidバージョン | 未取得 |
| APKインストール | 未実施 |
| 起動・ホーム表示 | 未実施 |
| REPORT作成・一覧表示 | 未実施 |
| プロセス終了後のRoom復元 | 未実施 |
| DeviceRole復元 | 未実施 |
| 権限許可・拒否 | 未実施 |
| Bluetooth無効 | 未実施 |
| Foreground Service通知・停止 | 未実施 |
| 1台スモークテスト | 未実施 |
| 2台Nearby同期 | 未実施 |
| 3台多段中継 | 未実施 |
| logcat | 未取得。保存先なし |

## 残存問題

### Critical

コードレビュー、最終ビルド、単体テストおよびLint時点で、既知の未修正Criticalは0件である。ただし実機は未接続のため、実機固有のCriticalが存在しないことを証明したものではない。

### Major

1. **Nearby実機動作が全面的に未検証**: 端末発見、双方の認証コード、承認、接続、Payload送受信、切断、再接続および複数Peer動作を確認していない。
2. **Foreground Service実体のAndroidテスト不足**: JVMテストはRuntime停止seamを確認するが、Service生成、通知Channel、Android版別の`startForeground`、通知停止Action、OS停止時の挙動はinstrumented testまたは実機でのみ確認可能である。
3. **Google Play servicesの解決UIが限定的**: 利用不能状態は開始失敗として表示するが、更新・有効化などGoogle提供の解決Dialogへ直接誘導するUIは未実装である。
4. **権限と端末設定の組合せが未検証**: 通常拒否、永久拒否、通知拒否、Bluetooth無効、Android 11以前の位置情報サービス無効を実機で確認していない。
5. **callback・Service lifecycleの端末差が未検証**: Payload callback順序、プロセス終了、Activity再生成、Service停止およびメーカー固有のバックグラウンド制限を確認していない。
6. **Import境界の型による強制が不完全**: 将来のImport実装が共通検証パイプラインを迂回しないことをinterfaceだけでは保証できない。現在はImport実装がないため実機Nearby検証には影響しない。
7. **高頻度callbackと並行操作は未検証**: Google Nearby callbackは容量付きFlowへ非同期投入しており、極端なburst時のイベント欠落や複数Peerからの同時接続・切断に対する状態競合は未検証である。MVPの小容量・少数Peer試験で挙動を観測し、必要ならChannelによる直列化を行う。
8. **手動Gateway Receipt APIが残る**: 自動生成経路はGATEWAYの保存成功後に限定されているが、未使用の`recordGatewayReceipt`公開メソッドは保存済み確認を型で強制しない。現在UIや実運用経路からの呼出しはないため、実機試験を妨げないが、将来の公開範囲縮小が必要である。

### Minor

1. 権限説明・設定誘導の表示状態は、画面回転やActivity再生成時に改めて確認が必要である。
2. デバッグイベントは本文・位置を記録せずIDを短縮しているが、実機logcatにGoogle Play services由来の予期しない情報が出ないか未確認である。
3. LintはError 0、Warning 48。依存関係更新候補、インラインAPI、KTX提案、アイコン未設定等であり、Debug APK生成や今回の実機導入を妨げるCritical警告はない。

## 到達段階

| 段階 | 判定 |
|---|---|
| BUILD_READY | PASS |
| NEARBY_IMPLEMENTED | PASS: 実装、単体テスト、Lint、APK生成・静的検査完了 |
| PC_GATEWAY_IMPLEMENTED | PC Gatewayビルド、PC単体テスト、同一PC API実行確認、Android Bridgeコード完了 |
| SINGLE_DEVICE_VERIFIED | 未実施 |
| TWO_DEVICE_VERIFIED | 未実施 |
| MULTIHOP_VERIFIED | Fake統合テスト対象。実機では未実施 |
| Gateway Receipt | Fake統合テスト対象。実機では未検証 |

現在の到達段階は`NEARBY_IMPLEMENTED`である。ADB端末で確認していないため、`SINGLE_DEVICE_VERIFIED`以降には進めない。

## 次に必要な最小作業

1. Android端末1台を接続し、インストール、起動、REPORT保存・復元、Role復元、権限拒否、DRILL/RELAY開始・停止、Foreground Service通知を確認する。
2. 2台でPeer発見、同一認証コード、双方承認、REPORT同期、Peer ACK、重複排除および再接続を確認する。
3. 必要に応じて端末ごとの匿名化logcatを保存し、実機固有の失敗だけを最小修正する。
