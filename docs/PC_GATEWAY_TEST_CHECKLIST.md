# PC Gateway テストチェックリスト

自動テスト:

- [x] Gateway DTOのREPORT/Receipt往復
- [x] 初回SQLite保存
- [x] 同一messageIdの重複保存排除
- [x] DB保存成功後のみGateway Receipt生成
- [x] TTL/累積age拒否
- [x] 期限切れペアリングコード拒否
- [x] 未認証同期拒否
- [x] Android相当Engineのtokenなし停止
- [x] Android相当EngineのPC保存成功Receipt取込
- [x] STATUS_CHANGEによる対象REPORTの状態更新

同一PC実行確認:

- [x] `:pc-gateway:installDist`
- [x] 配布`.bat`起動
- [x] `/api/health`
- [x] 管理画面
- [x] SQLiteファイル生成
- [x] ペアリング要求・管理者承認
- [x] 認証済みREPORT送信
- [x] `GATEWAY_RECEIVED`生成・再取得

未実施:

- [ ] Android実機からPC GatewayへのLAN同期
- [ ] Windows Firewallを設定した別端末接続
- [ ] 大量Bridge、長時間切断、TLS、メーカー固有Android制約
# EXE packaging

- [ ] Run `scripts\build-pc-gateway-exe.ps1` on Windows with JDK `jpackage` and WiX 3.x.
- [ ] Verify `artifacts\relay-pc-gateway.exe` is non-empty and record its SHA-256.
- [ ] Install/run the unsigned EXE on a test PC and verify `/api/health`.
