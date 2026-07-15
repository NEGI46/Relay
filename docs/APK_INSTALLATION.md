# APK インストール手順（zero-operation）

## 成果物

- 推奨: `artifacts/relay-debug.apk`（ビルド後にコピーされた debug APK）
- 直接: `app/build/outputs/apk/debug/app-debug.apk`

Debug 署名です。本番配布用ではありません。

## adb

```powershell
# platform-tools を PATH に
adb devices -l
adb install -r artifacts\relay-debug.apk
```

署名不一致で失敗する場合は一度 uninstall してから install（ローカルデータは消える）。

## 初回操作（一般利用者）

1. アプリ起動
2. 通信に必要な権限を許可（説明ダイアログあり）
3. 許可済みなら **災害通信が自動開始**（Foreground Service 通知）
4. 「無事・避難状況を登録」または「不足している物資を登録」
5. 「地域情報」で保存内容と配信状態を確認

**不要な操作:** 接続先 IP 入力、ペアリングコード、接続承認ボタン、Gateway token。

Nearby は周辺 Relay 端末へ自動接続します。受信データはアプリ層で検証されますが **未検証情報** として扱います。

## 固定中継（Bridge 端末）

- 常時給電の Android を避難所等に置き、同じ LAN に PC Gateway を置く
- 通信開始後、PC ビーコンを発見して公開同期する
- 詳細: [OPERATION_MODEL.md](OPERATION_MODEL.md)、[runbooks/PHONE_TO_PC_PUBLIC_SYNC_E2E.md](runbooks/PHONE_TO_PC_PUBLIC_SYNC_E2E.md)

## 確認ポイント

| 表示 | 意味 |
|------|------|
| 転送待ち / 転送完了（保存確認前） | 最終配信ではない |
| 近くの端末へ保存済み | Peer ACK。Gateway 到達ではない |
| 中継拠点へ保存済み（未認証） | PC 公開経路で保存 |
| Gatewayへ保存済み | 認証経路の verified Receipt |

## トラブル

- 権限が永久拒否 → 設定画面へ（アプリ内ボタンあり）
- Bluetooth / 位置 / Play services → 設定・更新を促す
- 中継拠点未検出 → 同一 Private LAN、PC 起動、FW（TCP+UDP 42888）、P0 修正済み APK か確認
