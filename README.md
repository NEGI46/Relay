<div align="center">

<img src="docs/assets/relay-logo.svg" alt="Relay" width="620">

# Relay

### 災害時の「届かない」を、端末と人の移動でつなぐ。
### Keep critical information moving when networks cannot.

[![v1](https://img.shields.io/badge/v1-Fuchu%20Town-5B4FB2?style=for-the-badge)](docs/V1_FUCHU_PILOT.md)
[![Android](https://img.shields.io/badge/Android-6.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#download)
[![Windows](https://img.shields.io/badge/Windows-PC%20Gateway-0078D4?style=for-the-badge&logo=windows&logoColor=white)](#pc-gateway)

</div>

## まず結論

Relayは、インターネットが使えない・不安定な災害時に、スマートフォン同士と避難所PCで情報をStore–Carry–Forwardするローカル優先の中継システムです。

v1は **広島県安芸郡府中町の救助要請** に絞っています。

- Android: 赤いSOSを2秒長押し。GPS必須、避難所選択なし、暗号化して自動中継
- PC Gateway: 救助要請の正確な位置・人数・状態をスタッフ専用画面で確認
- 運用: 未確認SOSを最上位に表示し、担当開始、対応中、完了、取消を管理
- 公式情報: 府中町・広島県・気象庁だけを表示
- 日本語 / English: Android救助フローの右上ボタンで切替

> Relayは消防・警察・自治体の緊急連絡、公式警報、認証済み人命安全システムを置き換えません。実災害で使う前に、自治体・消防・避難所運営者との運用設計と無線試験が必要です。

## 画面イメージ

| Androidホーム | 救助ホーム | 公式情報 |
|---|---|---|
| <img src="docs/assets/relay-android-home.png" alt="Relay Android home" width="260"> | <img src="docs/assets/relay-android-rescue.png" alt="Relay rescue home" width="260"> | <img src="docs/assets/relay-android-official.png" alt="Relay official information" width="260"> |

PCスタッフ画面の確認手順と地図・公式情報の構成は [府中町v1パイロット仕様](docs/V1_FUCHU_PILOT.md) にまとめています。

## Download

最新版はGitHub Releasesからダウンロードします。下のボタンはReleaseの同名アセットへ直接つながります。

[![Download Android APK](https://img.shields.io/badge/Download-Android%20APK-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/NEGI46/Relay/releases/latest/download/Relay-Android-debug.apk)
[![Download Windows PC Gateway](https://img.shields.io/badge/Download-Windows%20PC%20Gateway-0078D4?style=for-the-badge&logo=windows&logoColor=white)](https://github.com/NEGI46/Relay/releases/latest/download/Relay-PC-Gateway-setup.exe)

- [全Releaseを見る](https://github.com/NEGI46/Relay/releases)
- [WindowsインストーラーのSHA-256](https://github.com/NEGI46/Relay/releases/latest/download/Relay-PC-Gateway-setup.exe.sha256)
- `artifacts/` は開発時の検証証跡です。利用者向けの配布場所はGitHub Releaseです。

Releaseがまだ作成されていない場合は、リポジトリのActionsから **Publish Relay release** を実行してください。ワークフローはAPK、Windowsインストーラー、SHA-256をまとめてReleaseへ登録します。

## Androidの使い方

1. APKをインストールする。
2. 初回起動で位置情報と近距離通信の権限を許可する。
3. 命の危険があるときは、ホームの赤いSOSを2秒長押しする。
4. それ以外は「状況を入力して救助を依頼」から人数と状態を選ぶ。
5. 依頼後はGPS付きで自動中継される。避難所・中継端末・再送の選択は不要。
6. 自分の依頼カードから状況・人数の更新、取消、避難所の受領・対応中・完了を確認する。

SOSは人数不明として保存され、命の危険、GPS位置、取得時刻、位置精度を含む暗号化依頼になります。通常依頼は人数を必須とし、4つの状態から1つ以上を選びます。自由記述と補足タグは任意です。

SOSの作成にWi-Fiやモバイル通信は必要ありません。府中町v1の公開鍵はアプリに同梱されるため、オフラインでも暗号化して端末へ保存し、近くのRelay端末へStore–Carry–Forwardできます。Wi-Fiが使える場合は、PC Gatewayへの直接配送も自動的に試行します。PC Gatewayが近くにあるのに接続できなくても、SOS作成そのものは止まりません。

## PC Gateway

Windowsインストーラーを実行すると、固定拠点用のPC Gatewayが入ります。起動後、スタッフPCで次を開きます。

```text
http://127.0.0.1:8080/
```

初期設定で `%USERPROFILE%\.relay\admin.key` の共通PINとPC表示名を設定します。画面はスタッフ専用で、未確認SOSを最上位・全画面警告・警告音付きで表示します。

スタッフは、最初に「担当開始」を押した1台が担当になり、次の順で状態を更新します。

```text
未確認 → 確認済み → 準備中 → 対応中 → 完了
```

対応状態は避難所の署名ReceiptとしてAndroid側へ戻ります。同じGatewayを複数スタッフPCで開けますが、独立した複数Gateway間の担当同期はv1対象外です。

## 仕組み

```text
Androidで作成
      ↓ 暗号化して端末保存
Nearby / BLEでStore–Carry–Forward
      ↓
府中町のPC Gatewayで復号・担当・対応
      ↓ 署名Receiptを再中継
依頼者のAndroidへ受領・対応中・完了を通知
```

### Broker経路（任意・モバイル通信）

インターネットが使える場合、AndroidはNearby/BLE/LANと並行してHTTPS Brokerへも救助Envelopeを送信します。Brokerは暗号文を復号せず、一時保存・重複排除・期限管理のみ行い、PC Gatewayが外向き通信で取得します。

```text
Android ──HTTPS──> Broker <──HTTPS poll── PC Gateway
Android <──HTTPS── Broker <──HTTPS POST── PC Gateway (Receipt Outbox)
```

- Broker送信は`hopCount`を増やさない（SCF中継ではない）
- Broker障害時もNearby/BLE/LANは独立動作
- 設定: Androidは`broker_endpoint`、Gatewayは`RELAY_BROKER_URL`環境変数
- 詳細: [Brokerアーキテクチャ](docs/BROKER_ARCHITECTURE.md)

中継端末には救助要請の本文・正確な位置・人数を表示しません。PC Gatewayだけが、宛先鍵で復号した詳細を扱います。完了・取消の依頼は全バージョンを30日後に削除します。

## 公式情報と地図

- Androidはユーザー投稿の地域情報をv1画面に表示せず、公式情報へのリンクだけを表示します。
- PCは府中町周辺の国土地理院地図をズーム13–15でオフラインキャッシュします。
- 気象庁の広島県警報データから府中町コード `3430200` を抽出し、最後の取得結果をキャッシュします。
- 地図利用時は [国土地理院コンテンツ利用規約](https://maps.gsi.go.jp/help/termsofuse.html) に従ってください。

## 開発者向け

### 必要環境

- Windows 10/11
- JDK 17
- Android SDK / API 36（Android StudioまたはSDK Managerで導入）
- Git

### ビルドとテスト

```powershell
.\gradlew.bat :shared:jvmTest :app:testDebugUnitTest :app:compileDebugKotlin :pc-gateway:test
.\gradlew.bat :app:assembleDebug
```

APKは `app/build/outputs/apk/debug/app-debug.apk` に生成されます。PC Gatewayの配布物はReleaseワークフローがWindows runner上で作成します。

### ヘッドレスAVD確認

画面を表示しないAVDで確認する場合は、`Medium_Phone` を使い、`-no-window -no-audio` を指定します。GPSの例は府中町です。

```powershell
adb emu geo fix 132.504 34.392
```

仮想端末のPASSは物理端末のBluetooth、OEM差、実際の無線環境を証明しません。実機2台以上のNearby試験は別途必要です。

## リポジトリ構成

| 場所 | 役割 |
|---|---|
| `shared/` | 救助契約、暗号化、署名Receipt、共通モデル |
| `app/` | Android UI、GPS、Nearby/BLE、暗号化Room DB、Broker配送 |
| `pc-gateway/` | PCの復号、担当、状態遷移、保持期間、地図、公式情報、Broker Pull/Outbox |
| `broker/` | HTTPS Broker（Ktor+SQLite）、暗号文の一時保存・重複排除・期限管理 |
| `docs/` | v1仕様、運用、セキュリティ、テスト、リリース手順 |
| `.github/workflows/` | CIとGitHub Release作成 |

## 主要ドキュメント

- [Brokerアーキテクチャ](docs/BROKER_ARCHITECTURE.md)
- [府中町v1パイロット仕様](docs/V1_FUCHU_PILOT.md)
- [PC Gatewayセットアップ](docs/PC_GATEWAY_SETUP.md)
- [PC Gatewayアーキテクチャ](docs/PC_GATEWAY_ARCHITECTURE.md)
- [PC Gatewayセキュリティ](docs/PC_GATEWAY_SECURITY.md)
- [オフライン地図設計](docs/design/OFFLINE_MAP.md)
- [デバイス試験チェックリスト](docs/DEVICE_TEST_CHECKLIST.md)
- [署名付き配布手順](docs/release/SIGNED_DISTRIBUTION.md)

## English summary

Relay is a local-first disaster relay for the Fuchu Town v1 pilot in Hiroshima, Japan. Android users can send a two-second SOS or a normal rescue request with mandatory GPS. The request is encrypted, carried automatically across nearby Relay devices, and decrypted only by the staff PC Gateway. Staff can claim, confirm, respond to, complete, or cancel requests; signed status receipts travel back to the requester.

The Android rescue flow includes a Japanese / English toggle. The Android information screen links only to official Fuchu Town, Hiroshima Prefecture, and Japan Meteorological Agency sources. The PC Gateway includes an offline GSI map cache for the Fuchu area.

Relay is a pilot and is not an emergency-service replacement. Validate the operational model with local authorities and perform physical RF, multi-device, and production-signing tests before deployment.
