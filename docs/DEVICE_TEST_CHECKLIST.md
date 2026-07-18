# 実機試験チェックリスト（zero-operation）

**正本:** [OPERATION_MODEL.md](OPERATION_MODEL.md)  
**E2E runbook:** [runbooks/PHONE_TO_PC_PUBLIC_SYNC_E2E.md](runbooks/PHONE_TO_PC_PUBLIC_SYNC_E2E.md)

未実施項目を PASS と書かない。手動の接続承認は **主経路ではない**（診断用の Nearby 桁のみ残る場合あり）。

## A. 単体 Android

- [ ] APK インストール・起動・クラッシュなし
- [ ] 権限許可後、災害通信が自動開始（FGS 通知）
- [ ] 安否 / 物資 REPORT 作成・保存
- [ ] force-stop 後の REPORT 復元
- [ ] 通信停止で Transport 終了
- [ ] 未実装機能（QR 等）が利用可能に見えない
- [ ] 地域情報の配信ラベルが Peer / 未認証 / Gateway を区別する

## B. Nearby 2 台（自動接続）

- [ ] 双方起動後、**承認 UI なし**で接続
- [ ] A の REPORT が B に保存
- [ ] Peer 到達表示が「最終配信ではない」こと
- [ ] 切断・再接続で重複排除
- [ ] 認証コード入力・双方承認を **要求しない**

## C. Phone ↔ PC 公開同期

- [ ] PC Gateway 起動、Health 200
- [ ] UDP 42888 ビーコン有効
- [ ] Windows Private + Firewall（TCP API + UDP 発見）
- [ ] Android が IP/token 入力なしで中継拠点を見つける
- [ ] REPORT が PC SQLite に UNVERIFIED で保存
- [ ] Android に「中継拠点へ保存済み（未認証）」
- [ ] 同一 messageId の重複が 1 件
- [ ] （推奨）PC 停止中作成 → 復旧後再送

## D. 任意: Bridge経路のペアリング

- [ ] 管理画面でコード生成・承認
- [ ] 認証同期で `GATEWAY_RECEIVED`（PC保存）
- [ ] PC管理画面で経路は `AUTHENTICATED_BRIDGE`、内容は `UNVERIFIED`
- [ ] `GATEWAY_RECEIVED`を公式情報・最終配信と表示しない
- [ ] reject/revoke 後は同期拒否

## E. ログ・プライバシー

- [ ] メッセージ本文・token が logcat に出ない
