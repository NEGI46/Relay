# PC Gateway 試験チェックリスト

**正本:** [OPERATION_MODEL.md](OPERATION_MODEL.md)

## 自動テスト（コード）

- [x] `:pc-gateway:test`（公開 Ingress、レート制限、store、reject、receipt スコープ等）
- [x] 公開経路 STATUS_CHANGE 拒否
- [x] UNVERIFIED receipt 生成

## プロセス単体（PC のみ）

- [ ] Health 200
- [ ] 管理画面表示、VERIFIED/UNVERIFIED 件数
- [ ] UDP 42888 ビーコン（LAN bind 時）
- [ ] admin key 再起動後も同一（`%USERPROFILE%\.relay\admin.key`）
- [ ] `POST /api/public/sync/messages` で REPORT 保存 + UNVERIFIED
- [ ] 重複 messageId が 1 件
- [ ] （任意）pair → approve → 認証 sync → verified receipt
- [ ] （任意）pair/reject 後に認証 sync 拒否

## デバイス E2E

- [ ] [PHONE_TO_PC_PUBLIC_SYNC_E2E.md](runbooks/PHONE_TO_PC_PUBLIC_SYNC_E2E.md)

## 配布

- [ ] `artifacts/relay-pc-gateway.exe` 起動
- [ ] 自動起動タスク登録
- [ ] Firewall Private のみ
- [ ] コード署名（正式配布時）
