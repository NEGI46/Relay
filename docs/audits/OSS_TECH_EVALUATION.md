# Relay OSS・無償技術評価

**調査日:** 2026-07-19  
**範囲:** `relay_codex_goal_prompts.json` のフェーズ3で指定された候補。製品コード、Gradle依存関係、ライセンスは変更していない。  
**根拠の範囲:** 公式ドキュメント、公式GitHub、IETF RFC、公開仕様のみ。各「保守状況」は調査日時点の公式配布元の状態であり、導入直前にリリース・脆弱性情報を再確認する。

## 前提と判断方法

現行Relayには、Androidの `OfflineTransport` / `SyncCoordinator`、非Nearby入出力の `MessageImportExportTransport`、Windows BLE Bridge、Meshtasticの外部プロセスadapter、BPv7のexport専用境界、署名検証済みのオフライン地図pack境界がある。したがって本書の「採用」は、原則としてその境界を保つ小さな追加を指す。無線実装、ルーティング、DTNスタックをRelay coreへ直接コピーする意味ではない。

「未選定ライセンスとの相性」は、法的結論ではない。Apache-2.0 / MIT / BSDは通常は候補にしやすいが、NOTICE、商標、データライセンス、バイナリ再配布義務を別途レビューする。GPL系は隔離プロセス・外部ツールとの連携を検討できても、Relay coreへコードをコピーしない。

各行は、要件JSONの全評価項目を `解決 / URL / 保守 / SPDX / 相性 / API / 権限 / サイズ / 資源 / 安全性 / 境界 / 移行 / 工数 / 試験 / 判定 / 理由` の順で記載する。`API=—` はAndroidライブラリでないこと、`サイズ=—` はAndroid APKへ追加しない判断を表す。

|要件JSONの評価列|本書での表記|
|---|---|
|候補名|各評価表の左列|
|解決する問題|`解決`|
|公式URL|`URL`（資料IDまたは直接リンク）|
|最終更新・保守状況|`保守`|
|ライセンス/SPDX|`SPDX`|
|Relayのライセンス未選定状態との相性|`相性`|
|Android最低API|`API`|
|追加権限|`権限`|
|APK/配布サイズ|`サイズ`|
|CPU・メモリ・バッテリー|`資源`|
|セキュリティとプライバシー|`安全性`|
|既存境界への適合性|`境界`|
|移行・互換性|`移行`|
|導入工数|`工数`|
|テスト可能性|`試験`|
|採用 / 小規模prototype / 保留 / 不採用|`判定`|
|判断理由|`理由`|

## 一次資料一覧

|ID|一次資料|
|---|---|
|A1|[Android Wi-Fi Aware](https://developer.android.com/develop/connectivity/wifi/wifi-aware)|
|A2|[Android Wi-Fi Direct service discovery](https://developer.android.com/develop/connectivity/wifi/nsd-wifi-direct)|
|A3|[Android NSD / DNS-SD](https://developer.android.com/develop/connectivity/wifi/use-nsd)・[NsdManager API](https://developer.android.com/reference/android/net/nsd/NsdManager)|
|A4|[Android BLE overview](https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview)・[Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)|
|A5|[Android Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)|
|A6|[Berty / weshnet official repository](https://github.com/berty/weshnet)|
|A7|[Meshtastic official firmware repository](https://github.com/meshtastic/firmware)・[project documentation](https://meshtastic.org/docs/)|
|A8|[RFC 9171: Bundle Protocol Version 7](https://www.rfc-editor.org/rfc/rfc9171.html)・[DTN7-rs official repository](https://github.com/dtn7/dtn7-rs)|
|A9|[RFC 6693: PRoPHET](https://www.rfc-editor.org/rfc/rfc6693.html)・[Spray-and-Wait original paper](https://www.cs.dartmouth.edu/~campbell/cs65/lecture06/lecture06.html)・[IBLT reference implementation](https://github.com/minkull/IBLT)|
|A10|[RFC 8949: CBOR](https://www.rfc-editor.org/rfc/rfc8949.html)・[Protocol Buffers official documentation](https://protobuf.dev/)|
|A11|[detekt](https://github.com/detekt/detekt)・[ktlint](https://github.com/pinterest/ktlint)・[Kover](https://github.com/Kotlin/kotlinx-kover)・[Jazzer](https://github.com/CodeIntelligenceTesting/jazzer)|
|A12|[Android Gradle Managed Devices](https://developer.android.com/studio/test/gradle-managed-devices)・[actionlint](https://github.com/rhysd/actionlint)・[zizmor](https://github.com/zizmorcore/zizmor)|
|A13|[Dependabot version updates](https://docs.github.com/en/code-security/dependabot/working-with-dependabot/dependabot-version-updates/about-dependabot-version-updates)・[Gradle dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html)・[dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html)|
|A14|[MobSF](https://github.com/MobSF/Mobile-Security-Framework-MobSF)・[Semgrep OSS](https://github.com/semgrep/semgrep)・[LeakCanary](https://github.com/square/leakcanary)・[Android StrictMode](https://developer.android.com/reference/android/os/StrictMode)|
|A15|[MapLibre Native](https://github.com/maplibre/maplibre-native)・[MapLibre Android PMTiles example](https://maplibre.org/maplibre-native/android/examples/data/PMTiles/)・[MBTiles specification](https://github.com/mapbox/mbtiles-spec)・[PMTiles specification](https://github.com/protomaps/PMTiles)|
|A16|[OpenStreetMap copyright/licence](https://www.openstreetmap.org/copyright)・[OpenStreetMap tile usage policy](https://operations.osmfoundation.org/policies/tiles/)|

## 通信・搬送・DTN候補

|候補名|評価|
|---|---|
|Android Wi-Fi Aware (NAN)|**解決:** Play services非依存の近距離発見・直接IP接続。**URL:** A1。**保守:** Android公式API、API 26+。**SPDX:** Android SDK規約（アプリ依存ではない）。**相性:** 高い。**API:** 26。**権限:** `ACCESS_WIFI_STATE`、`CHANGE_WIFI_STATE`、Android 13+は`NEARBY_WIFI_DEVICES`等を公式資料で再確認。**サイズ:** 小（SDK API）。**資源:** Wi-Fi無線利用、連続discoveryは電池消費。**安全性:** 発見・リンク確立は本人性を与えない。アプリ層署名、短い認証コード、レート制限が必要。**境界:** `OfflineTransport` 実装として隔離可能。**移行:** Nearby主経路を置換せず、capability検出後のfallback。**工数:** 大。**試験:** 対応/非対応実機、複数OEM、画面消灯、再接続。**判定:** 小規模prototype。**理由:** Google Play servicesなし端末の価値は高いが、対応端末差とRF試験負債が大きい。|
|Wi-Fi Direct P2P service discovery|**解決:** AP/LANなしで近傍サービスを見つけ、ソケット接続する。**URL:** A2。**保守:** Android公式、現行資料あり。**SPDX:** SDK規約。**相性:** 高い。**API:** 16（Android 13+権限変化あり）。**権限:** `ACCESS_WIFI_STATE`、`CHANGE_WIFI_STATE`、`INTERNET`、API 32以下の位置、33+ `NEARBY_WIFI_DEVICES`。**サイズ:** 小。**資源:** Wi-Fi接続確立とdiscoveryが電池・状態遷移を増やす。**安全性:** P2P名/サービス広告は信頼根拠でない。**境界:** `OfflineTransport` adapter。**移行:** Nearbyと並走、サービス名に恒久IDを入れない。**工数:** 大。**試験:** group owner選択、権限拒否、競合、OEM。**判定:** 保留。**理由:** Awareより対応範囲は広いが、権限とOS/OEM差が大きく、現行Nearbyの代替として先に必要とは示せない。|
|Android NSD / DNS-SD / mDNS|**解決:** 同一LAN上のGateway/Bridgeの発見。**URL:** A3。**保守:** Android公式、`NsdManager` はAPI 16+、DNS-SD/mDNS準拠。**SPDX:** SDK規約。**相性:** 高い。**API:** 16。**権限:** 通常`INTERNET`/LAN接続。**サイズ:** ほぼゼロ。**資源:** multicast環境依存、軽量だが常時探索は禁止。**安全性:** discoveryは接続先の認証ではない。**境界:** Gateway discoveryを現行UDP beaconの代替候補に限定。**移行:** 署名済みmetadata又は鍵fingerprint pinningと組み合わせ、UDPを即削除しない。**工数:** 中。**試験:** guest Wi-Fi、IPv6、同名衝突、偽広告。**判定:** 小規模prototype。**理由:** LANのゼロ設定性に合うが、偽Gateway対策を別途完了してから。|
|BLE GATT|**解決:** 低消費電力の小容量近傍搬送、Bridge/専用無線機との接続。**URL:** A4。**保守:** Android公式、GATT APIはAPI 18+。**SPDX:** SDK規約。**相性:** 高い。**API:** 18、権限モデルは31+で変化。**権限:** `BLUETOOTH_SCAN`、`ADVERTISE`、`CONNECT`（31+）、旧版位置権限を要件ごとに確認。**サイズ:** 小。**資源:** 小データ向きだがscan/advertise/再接続は電池を消費し、MTU断片化が必要。**安全性:** Android公式も機微情報にはアプリ層保護を要求する。**境界:** 既存Windows BLE Bridgeと同じwire codecを使う`OfflineTransport`に限定。**移行:** REPORT/救助Envelopeのサイズ上限・ACK意味を再定義せず、chunk/再開だけをadapterで処理。**工数:** 大。**試験:** 実機2台以上、MTU差、permission、Bluetooth OFF/ON、OS kill。**判定:** 保留。**理由:** 外部Bridgeは既存境界で十分。端末間GATT追加はRFと電池の検証なしに採用しない。|
|QRによる単発オフライン搬送|**解決:** 電波・権限・Play servicesなしで少量の署名済みデータを人手搬送。**URL:** QR仕様は[ISO/IEC 18004 overview](https://www.iso.org/standard/83389.html)、既存Relayの`MessageImportExportTransport`。**保守:** 規格成熟。**SPDX:** 規格/自実装。**相性:** 高い。**API:** カメラを使う場合21+を基準に設計。**権限:** 読取時`CAMERA`、表示のみ不要。**サイズ:** 低。**資源:** 低、ユーザー操作が律速。**安全性:** QRは秘密性を提供しない。署名、TTL、サイズ制限、重複排除、信頼表示を通常受信と同一にする。**境界:** `MessageImportExportTransport`。**移行:** 新規wire envelopeをversionedで追加し、JSONをそのままQR化しない。**工数:** 中。**試験:** 破損/再撮影/古いQR/不正署名/大文字サイズ。**判定:** 小規模prototype。**理由:** 災害時の最小fallbackとして価値がある。|
|animated QR / fountain code / Uniform Resources (UR)|**解決:** 大きいpayloadを複数フレームへ分割し、欠落耐性を上げる。**URL:** [Blockchain Commons UR specification](https://github.com/BlockchainCommons/Research/blob/master/papers/bcr-2020-005-ur.md)・[BC-UR reference](https://github.com/BlockchainCommons/BC-UR)。**保守:** 公式GitHubを要観察。**SPDX:** リポジトリごとに確認必須。**相性:** 未確認。**API:** 21+想定。**権限:** `CAMERA`。**サイズ:** 中。**資源:** エンコード/デコードと画面表示時間が増える。**安全性:** 再構成前後の厳格な上限・タイムアウトが必要。**境界:** QR import/export内部だけ。**移行:** 単発QR prototypeの後にのみ検討。**工数:** 大。**試験:** 欠落、順序入替、無限フレーム、メモリ上限。**判定:** 保留。**理由:** 現行メッセージのサイズ実測と単発QRの失敗証拠がない。|
|Storage Access Frameworkによる暗号化ファイル搬送|**解決:** SDカード・Files・近距離共有等を使った明示的ファイル輸出入。**URL:** A5。**保守:** Android公式、API 19+。**SPDX:** SDK規約。**相性:** 高い。**API:** 19。**権限:** システムpickerを用いれば広範なストレージ権限不要。**サイズ:** 小。**資源:** ファイルI/Oのみ、暗号化・ハッシュに比例。**安全性:** exportは復号済みDBではなく、暗号化されたversioned bundleだけ。URI永続権限・誤送付・復号鍵の扱いを明示する。**境界:** `MessageImportExportTransport`。**移行:** QRと同じ署名/検証パイプラインを共有。**工数:** 中。**試験:** 取消、破損、容量不足、旧version、他provider。**判定:** 小規模prototype。**理由:** 端末差の少ない明示的fallbackで、追加依存が不要。|
|Berty / weshnet|**解決:** 非同期メッシュ、暗号化P2P、gRPCベースのWesh protocol。**URL:** A6。**保守:** 公式repoはrelease/issueを公開（導入時再確認）。**SPDX:** Apache-2.0 OR MIT。**相性:** ライセンス候補としては良いが、Go coreとgRPC/ネットワーク設計の導入は重い。**API:** Android SDKとしては—。**権限:** sidecar/SDK形態次第。**サイズ:** 大。**資源:** libp2p/Go runtime/バックグラウンド接続で高い。**安全性:** 独自に信頼モデルを増やすため、Relayの署名・Receipt意味と混在させない。**境界:** 使うなら別Gateway/sidecar adapterのみ。**移行:** core protocolを置換しない。**工数:** 非常に大。**試験:** 相互運用、鍵管理、オフライン収束、障害時回復。**判定:** 不採用。**理由:** Relayの小さなSCF境界に対して過大で、依存・運用・攻撃面を大幅に増やす。|
|Meshtastic|**解決:** LoRaベースのoff-gridメッシュ無線を外部経路として使う。**URL:** A7。**保守:** 公式firmwareは継続公開。**SPDX:** GPL-3.0（firmware）。**相性:** Relay coreへ取り込むのは不適、独立adapterなら評価可。**API:** —。**権限:** AndroidでBLE/USBを使うadapterなら追加検討。**サイズ:** APKに埋め込まない。**資源:** 低帯域・高遅延、専用ハードウェア運用が必要。**安全性:** channel/無線設定はRelayの情報真正性を保証しない。**境界:** 現行`gateway-meshtastic-adapter`を維持。**移行:** JSON契約とサイズ/hop/TTL変換をversion固定。**工数:** 中（adapter）、大（現地運用）。**試験:** 実機無線、リージョン設定、電波法、重複/遅延。**判定:** 保留（外部adapter）。**理由:** 災害冗長経路の価値はあるが、GPLコード混入とRadio運用を避ける。|
|BPv7 / DTN7|**解決:** DTNのbundle、age/hop/status、断続ネットワーク用store-and-forward。**URL:** A8。**保守:** RFC 9171は標準、DTN7-rsは別実装。**SPDX:** RFCは仕様、実装は個別確認。**相性:** 仕様参照は高い、フルstack埋込は低い。**API:** —。**権限:** 外部daemon/transport依存。**サイズ:** Androidへのフルstack追加は大。**資源:** bundle管理・永続化・contact planで増加。**安全性:** BPv7はCBOR形式とstatusを定義するがRelayの署名・信頼を代替しない。**境界:** 現行BPv7 exportのみ維持。**移行:** `messageId`/TTL/hop/receiptを機械的に同一視しない。**工数:** 非常に大。**試験:** DTN相互運用、age/clock、fragment、BPSEC。**判定:** 不採用（core import）、保留（export）。**理由:** 参照設計として有益だが、Relayの現状規模にフル互換は過剰。|
|epidemic routing|**解決:** 遭遇した全peerへ複製して到達率を上げる。**URL:** [Vahdat & Beckerの原論文](https://www.cs.ucsb.edu/~ravenben/classes/276/papers/epidemic.pdf)。**保守:** 論文アルゴリズム。**SPDX:** 実装不要。**相性:** 概念は中。**API:** —。**権限:** —。**サイズ:** ほぼゼロ。**資源:** peer数・message数に対し通信/保存が急増。**安全性:** DoS、電池枯渇、追跡可能性を悪化。**境界:** `SyncCoordinator` policyのみ。**移行:** 無制限複製は禁止。**工数:** 小（概念）/大（安全化）。**試験:** 3台以上、DB満杯、ID rotation。**判定:** 不採用。**理由:** Relayの災害UXと資源制約に対して無制限複製は不適。|
|Spray-and-Wait|**解決:** 複製数を上限化しepidemicより資源消費を抑える。**URL:** [原論文](https://www.cs.dartmouth.edu/~campbell/cs65/lecture06/lecture06.html)。**保守:** 論文アルゴリズム。**SPDX:** 実装不要。**相性:** 中。**API/権限/サイズ:** —。**資源:** copy budget管理が必要。**安全性:** budget偽装、peer ID rotation、Receipt偽造への防御が必要。**境界:** Sync policy。**移行:** message schemaへcopy countを足す前に互換性/署名対象を設計。**工数:** 大。**試験:** copy枯渇、partition、悪意peer。**判定:** 保留。**理由:** 現行同期の測定・輻輳失敗が未証明であり、プロトコル変更を急がない。|
|PRoPHET routing|**解決:** 遭遇履歴から配送確率を推定し転送先を選ぶ。**URL:** A9。**保守:** IETF実験的RFC。**SPDX:** 仕様。**相性:** 低〜中。**API/権限/サイズ:** —。**資源:** peer encounter履歴を永続化し計算する。**安全性:** 履歴の汚染、追跡可能性、DoSが増える。**境界:** policy層のみ。**移行:** 署名済みmessage protocolとは分離。**工数:** 大。**試験:** synthetic mobilityとプライバシー評価。**判定:** 不採用。**理由:** 被災地での履歴予測の前提が弱く、個人追跡面も増える。|
|Bloom filter|**解決:** inventory/manifestの小型化、既知ID照会。**URL:** [Bloom filter原論文](https://dl.acm.org/doi/10.1145/362686.362692)。**保守:** 基礎データ構造。**SPDX:** 自実装。**相性:** 高い。**API/権限:** —。**サイズ:** 小。**資源:** O(k)照会、偽陽性は必ずある。**安全性:** 偽陽性を「既に保存済み」と扱うと配送損失になる。攻撃者が密度を上げられる。**境界:** manifest差分最適化のみ。**移行:** 明示的request/receiptによる再確認を残す。**工数:** 中。**試験:** 偽陽性率、悪意集合、再起動。**判定:** 保留。**理由:** 現行manifestの規模測定が先で、lossless配送の根拠にしてはいけない。|
|IBLT|**解決:** 両者集合差を小さく同期し、完全ID列挙を減らす。**URL:** A9。**保守:** 研究/参照実装。**SPDX:** 実装ごとに確認。**相性:** 中。**API/権限:** —。**サイズ:** 小〜中。**資源:** 剥離失敗時のfallbackが必要。**安全性:** poison/過負荷入力を制限しないとCPU/メモリDoS。**境界:** manifest差分codecだけ。**移行:** 既存全manifest fallbackを維持。**工数:** 大。**試験:** 差分過大、衝突、malformed bucket、収束。**判定:** 不採用。**理由:** 実装/検証複雑性が大きく、現行規模のボトルネック証拠がない。|
|CBOR|**解決:** JSONよりコンパクトなバイナリwire表現。**URL:** A10。**保守:** IETF標準。**SPDX:** 仕様。**相性:** 高い。**API:** Kotlinライブラリ選定が別途必要。**権限:** —。**サイズ:** 小〜中。**資源:** JSONより通信量を下げ得るが、canonical encodingと入力上限が必須。**安全性:** 署名対象はdeterministic encodingを固定し、曖昧なデコードを拒否する。**境界:** `relay-protocol` codec。**移行:** versioned dual decoder/encoderを設計してから。**工数:** 大。**試験:** canonical bytes、malformed/巨大nest、後方互換fuzz。**判定:** 保留。**理由:** BPv7でもCBORを使うが、今すぐのwire変更は大きく、現行JSONの実測不足。|
|Protocol Buffers|**解決:** schema駆動のコンパクトwireと多言語生成。**URL:** A10。**保守:** Google公式プロジェクト。**SPDX:** BSD-3-Clause（公式repo確認を導入時に実施）。**相性:** 概ね良い。**API:** Kotlin/JVM生成を追加。**権限:** —。**サイズ:** 中（runtime/plugin）。**資源:** 効率的だがschema運用・unknown fields設計が必要。**安全性:** protobuf自体は署名・canonical bytesを保証しない。**境界:** `relay-protocol` のみに封じる。**移行:** JSONと同時受信、明示version、migration期限が必須。**工数:** 大。**試験:** 旧新相互運用、サイズ上限、fuzz。**判定:** 保留。**理由:** PC/Android間のschema共有には有用だが、現行の危険を直接解決しない。|

## 品質・サプライチェーン・実行時診断候補

|候補名|評価|
|---|---|
|detekt|**解決:** Kotlinの静的品質・複雑度・危険パターン検出。**URL:** A11。**保守:** 公式repoで継続保守を導入時確認。**SPDX:** Apache-2.0。**相性:** 高い。**API:** JVM/Gradle。**権限:** —。**サイズ:** 開発/CIのみ。**資源:** CI時間増、baseline乱用に注意。**安全性:** セキュリティ保証ではない。**境界:** build/CIのみ。**移行:** 新規違反をfail、既存は期限付きbaseline。**工数:** 小。**試験:** 意図的違反fixture。**判定:** 採用候補。**理由:** Kotlin中心のRelayで低リスク、ただしルール選定を先にレビューする。|
|ktlint|**解決:** Kotlin書式の一貫性。**URL:** A11。**保守:** 公式repo。**SPDX:** MIT。**相性:** 高い。**API:** JVM/Gradle。**権限:** —。**サイズ:** CIのみ。**資源:** 小。**安全性:** security toolではない。**境界:** build/CIのみ。**移行:** CIはcheckのみ、無関係な全自動整形は避ける。**工数:** 小。**試験:** check task。**判定:** 採用候補。**理由:** レビュー負荷を下げるが、機能品質の証拠にはしない。|
|Kover|**解決:** Kotlin/JVM test coverage可視化。**URL:** A11。**保守:** Kotlin公式組織。**SPDX:** Apache-2.0。**相性:** 高い。**API:** JVM/Gradle。**権限:** —。**サイズ:** CIのみ。**資源:** 中。**安全性:** coverage値は配送/無線品質を証明しない。**境界:** build/CIのみ。**移行:** 初期はレポート、critical codecのみ条件を検討。**工数:** 小。**試験:** レポート生成と除外妥当性。**判定:** 小規模prototype。**理由:** 数値目標がテストの質を歪めないか確認が必要。|
|Jazzer|**解決:** JVM/Kotlin codec・parserのcoverage-guided fuzz。**URL:** A11。**保守:** 公式repo、Apache-2.0。**SPDX:** Apache-2.0。**相性:** 高い。**API:** JVM。**権限:** —。**サイズ:** test/CIのみ。**資源:** 高（時間上限・corpus管理が必要）。**安全性:** malformed packet/CBOR/JSON/QRの入力境界に有効。**境界:** `relay-protocol` とGateway decoderのみ。**移行:** 実targetを先に置き、未導入をPASSと表記しない。**工数:** 中。**試験:** 固定seed、crash corpus、時間制限付きCI。**判定:** 採用候補。**理由:** 現行のuntrusted packet境界に直接効く。|
|Android Gradle Managed Devices|**解決:** 再現可能なemulator matrixでinstrumentationをCI実行。**URL:** A12。**保守:** Android公式。**SPDX:** SDK/Gradle規約。**相性:** 高い。**API:** 任意のsystem image。**権限:** CI上のemulator。**サイズ:** CI cache/imageが大。**資源:** 高（低spec PCで常用しない）。**安全性:** RF/BLE/Play services/OEM挙動の証明にはならない。**境界:** test infrastructure。**移行:** headless AVDの現行方針を維持し、1 API levelから。**工数:** 中。**試験:** clean install、DB、migration、permission拒否。**判定:** 小規模prototype。**理由:** 手元/CI差を減らすが、実機の代替と誤記しない。|
|actionlint|**解決:** GitHub Actions workflow構文・式の静的検査。**URL:** A12。**保守:** 公式repo。**SPDX:** MIT。**相性:** 高い。**API:** —。**権限:** —。**サイズ:** CI tool。**資源:** 小。**安全性:** action SHA pinningや最小権限の完全保証ではない。**境界:** CIのみ。**移行:** workflowに明示installし、未導入をskip成功にしない。**工数:** 小。**試験:** 壊れたworkflow fixture。**判定:** 採用候補。**理由:** CI偽緑の構文要因を低コストで減らせる。|
|zizmor|**解決:** GitHub Actionsのsecurity lint。**URL:** A12。**保守:** 公式repo。**SPDX:** MIT。**相性:** 高い。**API:** —。**権限:** —。**サイズ:** CI tool。**資源:** 小。**安全性:** advisoryを無視しないtriageが必要。**境界:** CIのみ。**移行:** report-onlyでなく、重要ruleを期限付きfailへ。**工数:** 小。**試験:** 意図的unsafe workflow fixture。**判定:** 採用候補。**理由:** actionlintと補完関係にある。|
|Dependabot|**解決:** Gradle/GitHub Actions依存の更新PR作成。**URL:** A13。**保守:** GitHub公式。**SPDX:** GitHubサービス機能。**相性:** 高い。**API:** —。**権限:** GitHub設定権限。**サイズ:** APK影響なし。**資源:** PR/CI負荷。**安全性:** 自動mergeしない、更新前後のverification/lock更新をレビュー。**境界:** `.github/dependabot.yml`。**移行:** GradleとActionsを分離し頻度/上限を設定。**工数:** 小。**試験:** dry configuration review。**判定:** 採用候補。**理由:** 更新滞留を減らすが、人のレビューを代替しない。|
|Gradle dependency verification|**解決:** artifactのchecksum/署名検証でsupply-chain改ざんを検出。**URL:** A13。**保守:** Gradle公式。**SPDX:** Gradle機能。**相性:** 高い。**API:** Gradle版依存。**権限:** —。**サイズ:** VCS上のmetadataのみ。**資源:** resolve/metadata保守。**安全性:** vulnerability検出ではなく、bootstrap時は既存repoを信頼する。**境界:** Gradle設定のみ。**移行:** 一度隔離環境で生成し、公式hash/keyとレビューしてstrict CI。**工数:** 中。**試験:** 意図的hash不一致でfail。**判定:** 採用候補。**理由:** Relayの配布・CI信頼性に直接効く。|
|Gradle dependency locking|**解決:** transitive含む解決versionを固定し再現性を上げる。**URL:** A13。**保守:** Gradle公式。**SPDX:** Gradle機能。**相性:** 高い。**API:** Gradle版依存。**権限:** —。**サイズ:** lockfileのみ。**資源:** 更新PRごとのlock更新。**安全性:** same-coordinate改ざん防止はverificationで補う。**境界:** Gradle設定のみ。**移行:** dynamic/changing versionの有無を先に監査。**工数:** 中。**試験:** lock外解決をfail。**判定:** 採用候補。**理由:** verificationと相補的で、安定ビルドに寄与。|
|MobSF Docker|**解決:** APK/Android設定の静的・動的セキュリティ診断。**URL:** A14。**保守:** 公式repo。**SPDX:** GPL-3.0。**相性:** coreには入れないためCIコンテナとしては検討可。**API:** —。**権限:** Docker/CI runner。**サイズ:** 大。**資源:** 高。**安全性:** scanner結果は手動triage必須、秘密入りAPKを外部サービスへ送らない。**境界:** 隔離CI job。**移行:** 明示install失敗はBLOCKED/FAIL、report-only常用禁止。**工数:** 中。**試験:** known-bad fixture。**判定:** 保留。**理由:** 価値はあるが低spec/CI資源とGPL運用を見積もってから。|
|Semgrep OSS|**解決:** Kotlin/Java/CI設定向けSAST rule実行。**URL:** A14。**保守:** 公式repo。**SPDX:** LGPL-2.1（engine、ruleは個別確認）。**相性:** CI toolとしては可、core組込不要。**API:** —。**権限:** —。**サイズ:** CI tool。**資源:** 中。**安全性:** rule品質とsuppression管理が鍵。**境界:** CIのみ。**移行:** first-party/自作ruleのライセンスも記録し、high-confidenceからfail。**工数:** 中。**試験:** known-bad snippets。**判定:** 保留。**理由:** 既存の契約検査・Android Lintとの重複とfalse positiveを測る。|
|LeakCanary|**解決:** debug時のAndroid memory leak可視化。**URL:** A14。**保守:** 公式repo。**SPDX:** Apache-2.0。**相性:** 高い（debug限定）。**API:** Android。**権限:** 通常不要。**サイズ:** releaseへ入れない。**資源:** debugでメモリ/解析負荷。**安全性:** PIIをleak trace/logへ出さない運用が必要。**境界:** debug dependencyのみ。**移行:** release source set除外を契約テスト。**工数:** 小。**試験:** 意図的leakをdebugで検出。**判定:** 小規模prototype。**理由:** 長時間FGS/Composeの健全性には有益だが、災害端末の常用依存にはしない。|
|StrictMode|**解決:** main-thread I/O、漏れたclosable等の開発時違反検出。**URL:** A14。**保守:** Android platform。**SPDX:** SDK規約。**相性:** 高い。**API:** 9+。**権限:** —。**サイズ:** ほぼゼロ。**資源:** debug penaltyにより遅くなる。**安全性:** 本番対策ではなく、検出/テスト用。**境界:** debug/Application初期化。**移行:** `penaltyDeath`範囲をdebug/testに限定し、正当なI/Oを見直す。**工数:** 小。**試験:** main thread disk/network fixture。**判定:** 採用候補。**理由:** 追加依存なしでUI/ANRリスクを早期に見つけられる。|

## オフライン地図候補

|候補名|評価|
|---|---|
|MapLibre Native / Android|**解決:** ベンダーロックインなしの地図レンダリング。**URL:** A15。**保守:** MapLibre公式組織、現行Relayにも地図境界がある。**SPDX:** BSD-3-Clause。**相性:** 高い。**API:** Android minSdk/依存版は採用時固定。**権限:** ローカルpack表示のみ不要、位置追跡は別。**サイズ:** native library/rendererを伴い中〜大。**資源:** style/tiles/GPU/メモリを使用。**安全性:** 外部style URLを許容せず、現行のhash/signature検証済みpackだけを初期化前に通す。**境界:** `VerifiedOfflineMapPack` の後段のみ。**移行:** online mapへ戻さず、pack manifestにversion/size/hashを追加。**工数:** 中。**試験:** 改ざんstyle、巨大pack、GPU非対応、offline起動。**判定:** 採用済み境界を維持。**理由:** 新規置換は不要。|
|MBTiles|**解決:** SQLite単一ファイルにraster/vector tilesを持つオフラインpack。**URL:** A15。**保守:** 公開仕様、実装互換は個別確認。**SPDX:** 仕様は別、データは別ライセンス。**相性:** 高い。**API:** —。**権限:** file import時はSAF。**サイズ:** 地域/zoomに比例し大きくなり得る。**資源:** SQLite I/O、packの索引/検証が必要。**安全性:** packを未検証のSQLiteとして直接開かず、hash/signature/容量上限/原子的配置を行う。**境界:** offline map loader。**移行:** 既存map pack検証を流用。**工数:** 中。**試験:** 壊れたDB、容量不足、旧pack。**判定:** 小規模prototype。**理由:** local-firstに適するが、実際のMapLibre依存版での読み込みを先に確認する。|
|PMTiles|**解決:** 単一ファイルでvector tilesを効率的に配布・ローカル参照。**URL:** A15。**保守:** Protomaps仕様、MapLibre Android公式例では11.7.0以降に対応。**SPDX:** 仕様/実装/データを別確認。**相性:** 高い。**API:** MapLibre依存。**権限:** local fileなら不要、SAF import時のみ。**サイズ:** 地域/zoomに比例、大容量。**資源:** range/indexアクセス、GPU描画。**安全性:** `file://` を許可する前にpackの署名・hash・path検証。**境界:** verified loaderからのみ。**移行:** MBTilesとの二重対応はprototype結果後。**工数:** 中。**試験:** ローカルfile、改ざん、巨大index、offline no-network。**判定:** 小規模prototype。**理由:** MapLibreとの公式連携があり、配布単位として有望。|
|OpenStreetMapデータのオフライン利用|**解決:** 地図の原データ/表示用tile供給。**URL:** A16。**保守:** OSM Foundationのライセンス/利用規約。**SPDX:** ODbL-1.0（データ）、生成物・style・タイルは別条件。**相性:** 条件付き。**API:** —。**権限:** local packなら不要。**サイズ:** 地域範囲とzoomで非常に大きくなる。**資源:** 前処理、配布、端末storageが必要。**安全性:** OSM dataは信頼済み避難情報ではない。更新日時/出典/免責をUIへ表示。**境界:** build-time pack作成とverified local loader。**移行:** 公開tile serverを災害時runtime依存にしない。**工数:** 大。**試験:** attribution、ライセンス表示、pack更新、容量。**判定:** 保留。**理由:** 地図需要はあるが、データ更新・配布・ライセンス運用を先に決める必要がある。|

## 横断的な選定結果

### 先に小さく検証する候補

1. **SAF暗号化bundle搬送**: QRより大きいpayloadにも対応でき、追加無線依存を増やさない。export/import後は必ず通常の署名・TTL・重複排除パイプラインへ戻す。
2. **LAN NSD + Gateway鍵検証**: UDP discoveryを置換するかを判断するための限定prototype。ただし発見と認証を分離する。
3. **Wi-Fi Aware**: Google Play services非搭載端末向けの価値を、対応実機を用意できる場合だけ検証する。
4. **detekt / ktlint / Jazzer / actionlint / zizmor / dependency verification / locking / Dependabot / StrictMode**: 最初に「実行して失敗できる」状態と、導入失敗をBLOCKEDとして記録することを契約にする。
5. **PMTilesまたはMBTiles**: 既存のverified map boundaryの内側で、実際の地域packサイズ・起動時間・改ざん拒否を比較する。

### 採用しない、または現行境界を超えない候補

- **Berty/weshnet、BPv7フルstack、epidemic routing、PRoPHET、IBLT**は、現行の小さなSCF実装に対し運用・状態・攻撃面を大きく増やす。仕様参照、export、別プロセスadapterは残せるが、Relay coreを置き換えない。
- **Meshtastic**はGPL-3.0コードをRelayへコピーせず、現在の外部adapter境界を維持する。無線機・地域規制・実機試験は別の運用課題として扱う。
- **BLE端末間GATT、Wi-Fi Direct、animated QR/UR、CBOR/Protobuf**は、現行経路の具体的な失敗・容量・性能測定が出るまで本採用しない。いずれもwire/権限/RF/互換性を変える。
- **MobSF、Semgrep OSS**は有用になり得るが、CI資源、false positive、導入失敗時の扱いを先に設計するまで保留する。

## 導入時の共通ゲート

各候補を実装する前に、次を満たす。

1. 依存の正確なversion、SPDX、配布物、公式release、既知のセキュリティ情報を再調査する。
2. Android最低API、manifest/runtime権限、Play services要否、Google非搭載端末のfallbackを明示する。
3. `OfflineTransport`、`MessageImportExportTransport`、Gateway adapter、`relay-protocol` のどれに接続するかをADRに固定する。transport成功をDB保存・最終到達・救助完了と表示しない。
4. payload上限、CPU/メモリ上限、電池予算、再試行、TTL/hop、重複排除、署名検証、error telemetryのテストを先に追加する。
5. RF、BLE、Wi-Fi Aware/Direct、複数端末収束、メーカー省電力は物理端末でしかPASSにしない。エミュレータはDB/permission/UI/プロセスkillなどに限定する。
6. 依存追加は小さい独立コミットにし、lock/verification/SBOM/NOTICEを更新してから、意図的な失敗fixtureでCIが赤くなることを確認する。

## 本当にこれでいいのか？ 技術選定の反証

- Wi-Fi系やBLEを追加しても、端末差・権限拒否・電池制限が消えるわけではない。RF成功は対応実機の組合せでのみ証明できる。
- compact codecは帯域を減らせても、署名対象bytes、decoder上限、version migrationを誤れば偽情報・DoS・互換性の危険を増やす。
- 地図packを端末へ置いても、データの最新性・避難情報の真正性・ライセンス遵守は別問題である。
- 静的解析やスキャナを増やしても、未インストール/未実行/skipをPASSに変えてはならない。CIで実際に失敗できるかを毎回確認する。

**結論:** 現行Relayには新しい巨大mesh/DTN coreより、既存境界を守った小さなfallback（SAF、QR、LAN discovery）と、再現可能な品質・供給網検証を優先する方が適合する。無線の本採用は、低リスクprototypeと実機RF試験の証拠が揃ってから判断する。
