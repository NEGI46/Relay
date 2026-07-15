# Relay Debug APK 導入手順

対象APKは`artifacts/relay-debug.apk`である。最新のハッシュは`artifacts/relay-debug.apk.sha256`を正とし、インストール前に必ず照合する。

2026-07-15時点ではADB接続端末が0台だったため、APK生成・署名・静的検査より後のインストール、起動、権限、Foreground Service、Nearby通信は**実機未検証**である。

## ADBでインストールする

```powershell
adb devices -l
Get-FileHash artifacts\relay-debug.apk -Algorithm SHA256
adb -s <SERIAL> install -r artifacts\relay-debug.apk
```

`INSTALL_FAILED_UPDATE_INCOMPATIBLE`などの署名不一致が発生した場合、既存アプリを勝手にアンインストールしない。更新元とDebug APKの署名が異なる場合は上書きできないため、既存データを消去してよいことを確認した後に限り、利用者が次を実行する。

```powershell
adb -s <SERIAL> uninstall com.example.relay
adb -s <SERIAL> install artifacts\relay-debug.apk
```

`adb uninstall`を実行すると、端末内のREPORT、Receipt、DeviceRole、アプリ内端末ID、設定などRelayの全ローカルデータが削除される。必要な検証データがある場合はアンインストールしない。

## APKを端末へ直接渡す

1. `artifacts/relay-debug.apk`をAndroid端末へコピーする。
2. 端末設定で、使用するファイル管理アプリまたはブラウザに「不明なアプリのインストール」を許可する。
3. APKを開いてインストールする。
4. インストール後、不要であればこの許可を解除する。

設定名と画面位置は端末メーカーとAndroidバージョンによって異なる。

## 初回起動と1台確認

```powershell
adb -s <SERIAL> shell am start -n com.example.relay/.MainActivity
```

- [ ] Relayホーム画面が表示され、直後にクラッシュしない
- [ ] 初期状態がNORMALで、Nearby通信が停止している
- [ ] 安否REPORTを作成し、地域情報一覧へ表示できる
- [ ] デバッグ画面を開ける
- [ ] QR、ファイル、ローカルWeb、USB、SMS、インターネット補助同期が利用可能に見えない
- [ ] DRILLまたはRELAYを選択して通信開始操作を行える
- [ ] 通信開始前に権限の利用理由が表示される
- [ ] 権限を拒否してもクラッシュせず、Nearby通信を開始しない
- [ ] 必要権限を許可するとForeground Service通知が表示される
- [ ] 通知に通信中表示、接続Peer数、停止Actionがある
- [ ] 通知または画面から通信を停止できる
- [ ] Bluetooth無効時とGoogle Play servicesエラー時に説明が表示される

保存復元は次の手順で確認する。

```powershell
adb -s <SERIAL> shell am force-stop com.example.relay
adb -s <SERIAL> shell am start -n com.example.relay/.MainActivity
```

- [ ] 作成済みREPORTが残っている
- [ ] DeviceRoleが変更前の値へ戻らず維持される
- [ ] 通信が勝手に再開せず、明示操作が必要である

## 2台でNearby同期する

1. 端末A・Bへ同一ハッシュのAPKをインストールする。
2. 両端末でDRILLまたはRELAYを明示的に開始し、必要な権限を許可する。
3. Peer画面で相手端末を選択し、接続を要求する。
4. 双方に表示された一時Peer名またはIDと認証コードが一致することを人が確認する。
5. コード一致後に双方で「承認」を押す。不一致または認証情報がない場合は「拒否」を押す。
6. Aで個人情報を含まないテストREPORTを作成する。
7. Bに同じmessageIdのREPORTが1件だけ保存され、hopCountが増えていることを確認する。
8. Peer ACKがGateway到達として表示されないことを確認する。
9. 切断・再接続後に同期してもREPORTが重複しないことを確認する。

災害時の一般利用者に接続承認を求めない。接続後の情報は未検証として表示し、DeviceRoleやcommunityIdだけを信頼根拠にしない。

## Android権限の目安

| Android API | 通信開始に必要な主なランタイム権限・状態 |
|---|---|
| 23–28 | 位置情報権限、Bluetooth有効、位置情報サービス有効 |
| 29–30 | 正確な位置情報権限、Bluetooth有効、位置情報サービス有効 |
| 31–32 | Bluetoothのスキャン・接続・アドバタイズ権限、Bluetooth有効 |
| 33–36 | 上記Bluetooth権限、周辺Wi-Fi、通知権限、Bluetooth有効 |

Relayは正確なGPS座標を収集しない。Android 11以前の位置情報権限は、Nearbyによる周辺端末発見をOSが許可するために要求する。API 33以降で通知権限を拒否した場合は、Foreground Serviceの常時通知を保証できないため通信を開始しない。

## 実装済み範囲と制限

- 実機Debug APKは`NearbyConnectionsTransport`を使用する。
- 通信はDRILLまたはRELAYを利用者が明示開始した場合だけ動作する。
- Foreground ServiceはOS、省電力設定、メーカー制限によって停止される可能性があり、常時通信は保証しない。
- 接続はNearbyの認証コードを双方で手動確認して承認する。
- Peer ACKは相手Relayアプリの検証・DB保存完了を表し、Gateway到達や最終配信完了ではない。
- Gateway ReceiptはGATEWAY端末で保存に成功した場合だけ生成する設計である。
- QR、Relayファイル、ローカルWeb、USB・外部ストレージ、SMS、インターネット補助同期、コミュニティPKI、自動接続は未実装である。

## APK情報

| 項目 | 値 |
|---|---|
| applicationId | `com.example.relay` |
| versionName | `0.1.0` |
| versionCode | `1` |
| build type | debug |
| minSdk | 23 |
| targetSdk | 36 |
| SHA-256 | `B0412E1B57CBD33C73045235FD27974959B56A9E66BFA5EDA6AE326DB3FE1838` |
| 署名 | Android Debug certificate。v1/v2検証PASS |
| Git commit hash | 未コミットの場合は`unknown` |
| Build日時 | 表示・デバッグ用途のみ。プロトコルやTTL判定には使用しない |

## Release APK

正式な署名鍵がない場合、正式公開用Release APKは生成しない。keystoreをGitへ保存せず、パスワードは環境変数またはローカル専用Gradleプロパティから渡す。Debug署名を正式公開に利用しない。
