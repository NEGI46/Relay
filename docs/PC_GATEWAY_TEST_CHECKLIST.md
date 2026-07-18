# PC Gateway 試験チェックリスト

**正本:** [OPERATION_MODEL.md](OPERATION_MODEL.md)

## 自動テスト（コード）

- [x] `:pc-gateway:test`（公開 Ingress、レート制限、store、reject、receipt スコープ等）
- [x] 公開経路 STATUS_CHANGE 拒否
- [x] UNVERIFIED receipt 生成
- [x] 認証済みBridge経路でも内容は `UNVERIFIED`
- [x] 旧 `ingress_trust=VERIFIED` を内容検証済みにせず非破壊migration

## プロセス単体（PC のみ）

- [ ] Health 200
- [ ] 管理画面表示、内容検証（VERIFIED/UNVERIFIED）と経路認証（Bridge/anonymous）の別集計
- [ ] UDP 42888 ビーコン（LAN bind 時）
- [ ] admin key 再起動後も同一（`%USERPROFILE%\.relay\admin.key`）
- [ ] `POST /api/public/sync/messages` で REPORT 保存 + UNVERIFIED
- [ ] 重複 messageId が 1 件
- [ ] （任意）pair → approve → 認証 sync → `GATEWAY_RECEIVED`（PC保存）かつ内容 `UNVERIFIED`
- [ ] （任意）pair/reject 後に認証 sync 拒否

## デバイス E2E

- [ ] [PHONE_TO_PC_PUBLIC_SYNC_E2E.md](runbooks/PHONE_TO_PC_PUBLIC_SYNC_E2E.md)

## 配布

- [ ] `artifacts/relay-pc-gateway.exe` 起動
- [ ] 自動起動タスク登録
- [ ] Firewall Private のみ
- [ ] コード署名（正式配布時）
