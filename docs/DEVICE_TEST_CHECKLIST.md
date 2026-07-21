# 実機試験チェックリスト（限定区域・訓練・共同実証）

**正本:** [FIELD_ACCEPTANCE_TEST.md](runbooks/FIELD_ACCEPTANCE_TEST.md)
**運用境界:** [MUNICIPAL_PILOT_READINESS.md](readiness/MUNICIPAL_PILOT_READINESS.md)

未実施は NOT_RUN、外部条件不足は BLOCKED と記録し、PASS にしない。これは119代替や実災害利用を認定する試験ではない。

## 0. 記録欄

| 試験日 | 担当 | Android機種/OEM | Android OS | Gateway commit | Broker/TLS構成 | 結果 |
|---|---|---|---|---|---|---|
|  |  |  |  |  |  |  |

## A. Android単体（機種・OSごと）

- [ ] APK の出所・署名状態を記録（debug/未署名を正式成果物と呼ばない）
- [ ] インストール、起動、クラッシュなし
- [ ] 位置/近距離通信/通知をそれぞれ許可・拒否した場合の安全な表示
- [ ] 省電力、バッテリー残量低下、画面消灯、force-stop、再起動後の挙動
- [ ] 安否/物資/合成SOSの作成と端末内保存（実在人物/GPS本文は使わない）
- [ ] release/pilotRelease が HTTP Gateway を使わず、debug/localDev だけが開発 HTTP を使えること
- [ ] ログ・画面・共有データに本文、GPS、token、秘密値が出ない

## B. Nearby / BLE（2台・3台多段）

- [ ] 2台: A の合成 REPORT が B に保存、重複排除、切断/再接続
- [ ] 3台: A→B→C の多段中継、各段が最終救助/最終配達を主張しない
- [ ] Bluetooth off/on、Nearby権限拒否、通知拒否、画面消灯、OEM省電力
- [ ] 端末再起動・アプリ再起動時の待機/再送/失敗表示
- [ ] 未認証/未検証の経路情報が救助担当や完了を自動変更しない

## C. Phone ↔ PC Gateway（プロファイル別）

- [ ] production 既定: loopback、匿名 ingress off、UDP discovery off、リモート管理 off
- [ ] 開発 compatibility: development を明示した場合だけ HTTP/匿名/legacy 管理キーを使用可能
- [ ] 閉域網: 明示的 closed-network、Windows Private/Firewall、LAN遮断時の安全な失敗
- [ ] TLS reverse proxy: Relay が loopback のまま、外部 HTTPS 証明書・cookie・アクセス制御が有効
- [ ] Gateway電源断、DB復旧、鍵ファイル権限不備、鍵期限警告時の fail-closed / warning
- [ ] VIEWER/OPERATOR/ADMIN、失効session、監査閲覧/CSV、担当開始・状態変更の境界

## D. モバイル回線 → HTTPS Broker → Gateway

- [ ] 実モバイル回線から HTTPS Broker upload、Gateway pull、署名Receipt return
- [ ] Broker 停止、TLS/DNS障害、Gateway停止、各復旧時に偽の成功/完了を表示しない
- [ ] shelter A 資格情報による shelter B pull / receipt upload が拒否される
- [ ] 資格情報失効後の拒否、再発行、原文tokenがログ/バックアップに無いこと
- [ ] Broker health に秘密値・個人情報がないこと

## E. 高負荷・偽SOS・レート制限

- [ ] 合成偽SOS、形式不正、巨大payload、replay、同時要求の拒否/隔離/監査
- [ ] 事前に決めた高負荷閾値、停止条件、CPU/メモリ/DB容量を記録
- [ ] 実消防・自治体の連絡先へ送らないことを試験前に確認

## F. 判定

- [ ] すべての FAIL / BLOCKED / NOT_RUN を責任者と共有
- [ ] 限定区域・訓練の範囲、停止条件、連絡先、復旧/バックアップ、個人情報取扱いを記録
- [ ] 実災害導入・HA・実機無線検証済み・119代替を主張しない
