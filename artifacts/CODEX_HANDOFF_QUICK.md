# Codex クイック引き継ぎ（1 枚）

**リポジトリ:** `C:\Users\matubayasi\Documents\Relay`  
**ブランチ:** `agent/zero-operation-relay`  
**詳細:** [`CODEX_HANDOFF.md`](./CODEX_HANDOFF.md)

## 今すぐ知ること

1. **未コミットが多い** — セッション成果はほぼ working tree にしかない  
2. **PC 起動:** ダブルクリック `Start-PC-Gateway.cmd` → `http://127.0.0.1:8080/`  
3. **Android 主線:** ローカル Nearby + PC Gateway 公開 `GATEWAY_RECEIVED_UNVERIFIED`  
4. **GPS:** 作成時ワンショット + API 32+ で FINE/COARSE を runtime 要求  
5. **ネット復帰:** `InternetPrioritySync` 実装済みだがソースは `EmptyPriorityMessageSource`  
6. **iPhone:** SwiftUI ではない。`shared` + `composeApp`（Mac でビルド）

## 検証

```powershell
.\gradlew.bat :app:testDebugUnitTest :relay-protocol:test :pc-gateway:test :app:assembleDebug --no-daemon
```

composeApp をルート `test` で巻き込まないこと（SDK build-tools 破損に注意）。

## 次の一手（推奨）

1. dirty の意味あるコミット分割  
2. `PriorityMessageSource` を実 URL/JSON に接続  
3. 実機 E2E（GPS + Phone→PC public）

## 壊すな

zero-op / UNVERIFIED ラベル / SCF hop-TTL / 送信失敗の観測性 / 登録不要 UX
