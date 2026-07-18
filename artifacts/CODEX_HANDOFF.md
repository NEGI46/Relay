# Codex 引き継ぎ資料 — Relay

**作成日:** 2026-07-16  
**ブランチ:** `agent/zero-operation-relay`  
**直近コミット (HEAD 時点のベース):** `ccc85d2` *Package PC Gateway Windows installer with local WiX Toolset.*  
**作業ツリー:** **大量の未コミット変更あり**（このセッションの成果はほぼ uncommitted / untracked）

> Codex は **まず本ファイル** を読み、次に `README.md`・`docs/OPERATION_MODEL.md`・`docs/APPLE_TARGETS.md`・`docs/PC_GATEWAY_SETUP.md` を参照すること。

---

## 1. プロダクト一言

災害時 **Store–Carry–Forward** で安否・物資不足を端末間中継する実証アプリ。

| 要素 | 内容 |
|------|------|
| Android `:app` | Jetpack Compose / Room / Nearby / FGS / PC Gateway 公開同期 |
| `:relay-protocol` | Gateway HTTP DTO（JVM） |
| `:pc-gateway` | Ktor/Netty + SQLite 固定中継（Windows / Mac / Linux JVM） |
| `:shared` + `:composeApp` | KMP/CMP（iPhone 向け共有ドメイン・Compose UI 骨格） |
| 運用モデル | **zero-operation**（登録・ペアリングなしで public LAN ingress） |

**やらないこと（意図的）:** AI、電話/メール必須、本番 PKI、Play 署名必須、Android↔iOS 直接 Nearby 互換。

---

## 2. 今セッションで進めたこと（未コミット含む）

### 2.1 アプリ完成バー / 防御的観測性
- `SyncDebugEvent.SendFailed` — 送信失敗を成功扱いしない
- `deliveryPresentationLabel` — 未認証/未検証ラベル
- 主要 Compose `contentDescription`
- テスト: `SyncSendFailureObservabilityTest`, `ScfProductPathIntegrationTest`, `HttpGatewayBridgeClientIntegrationTest` など

### 2.2 PC Gateway ワンクリック起動
| ファイル | 役割 |
|----------|------|
| **`Start-PC-Gateway.cmd`** | Windows ダブルクリック入口（推奨） |
| `scripts/run-pc-gateway.ps1` | installDist なければビルド → 起動 → 任意でブラウザ |
| `scripts/run-pc-gateway.sh` | macOS/Linux 同等 |
| `scripts/setup-pc-gateway-macos.sh` | Mac の pf / LaunchAgent |
| `scripts/tests/run-pc-gateway-launcher.tests.ps1` | ランチャー構造テスト |

**運用者向け起動:** リポジトリ直下 `Start-PC-Gateway.cmd` をダブルクリック。  
上級: `docs/PC_GATEWAY_SETUP.md` の手動 installDist。  
固定地点 EXE: `artifacts/relay-pc-gateway.exe`（WiX 経路、日次開発の第一選択ではない）。

### 2.3 ローカル優先 UI + GPS + ネット優先プル（最後の大きな目標）
| 領域 | 実装 |
|------|------|
| UI | 主ナビ: HOME / SAFETY / SUPPLY / REGIONAL / SETTINGS のみ。ペア/DEBUG/GATEWAY を既定から削除 |
| GPS | `location/LocationProvider.kt`, `AndroidLocationProvider.kt`（last-known + **one-shot** `getCurrentLocation` / `requestLocationUpdates`） |
| 権限 | `NearbyPermissionPolicy`: API 32+ で FINE+COARSE を start 時の `missingPermissions()` に含める。`canUseNearby()` は transport のみ |
| ネット | `cloud/InternetPrioritySync.kt` が `ServerSyncGateway` を実装。HIGH/CRITICAL を Room に追加。本番ソースは **`EmptyPriorityMessageSource`**（URL 未接続） |
| 配線 | `RelayApplication.locationProvider` / `internetPrioritySync`、Service 15s ループ、ViewModel 同様 |

**スケプティックで直したバグ（重要）:**
1. targetSdk 36 で位置が runtime 要求リストに無く GPS が死んでいた → API 32+ で FINE/COARSE を要求
2. last-known ポーリングのみで one-shot が無かった → `getCurrentLocation` / single update を実装

### 2.4 Apple 方向（SwiftUI ではない）
- SwiftUI ツリーは捨てた
- `docs/APPLE_TARGETS.md` — iPhone は KMP/CMP、Mac は同じ pc-gateway JVM
- `shared/` + `composeApp/` が骨格。iOS framework リンクは **Mac + Xcode 必須**
- Windows では `composeApp` の Android ターゲットが **Build-Tools 35.0.0 corrupt** で壊れることがある → 製品ゲートは `:app` を使う

---

## 3. モジュールと起動コマンド

```text
Relay/
  app/                 # 本番 Android
  relay-protocol/      # Gateway DTO (JVM)
  pc-gateway/          # PC 固定中継
  shared/              # KMP common (domain + gateway client)
  composeApp/          # CMP (desktop/iOS/android bridge)
  Start-PC-Gateway.cmd # Windows ワンクリック
  scripts/
  docs/
  artifacts/
```

### 検証コマンド（Windows / JDK 17）

```powershell
# 製品ゲート（composeApp を巻き込まない）
.\gradlew.bat :app:testDebugUnitTest :relay-protocol:test :pc-gateway:test :app:assembleDebug --no-daemon

# 今回の GPS / ネット / 権限
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.relay.location.*" --tests "com.example.relay.cloud.InternetPrioritySyncTest" --tests "com.example.relay.permissions.NearbyPermissionPolicyTest" --no-daemon

# PC Gateway ランチャー構造
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\tests\run-pc-gateway-launcher.tests.ps1

# PC Gateway 起動
.\Start-PC-Gateway.cmd
# Health: http://127.0.0.1:8080/api/health
# 公開: POST /api/public/sync/messages → GATEWAY_RECEIVED_UNVERIFIED
```

**注意:**
- `testDebugUnitTest` を引数なしでルートから叩くと `:composeApp` まで走り、SDK build-tools 破損で落ちることがある → **モジュール明示**すること
- Gradle daemon がメモリで落ちることがある → `--no-daemon` や test / assemble 分割
- `MultiHopSyncTest` が負荷時に Timeout することがある（再実行で通る場合あり）

---

## 4. 重要ファイルマップ

| 関心 | パス |
|------|------|
| UI | `app/.../ui/RelayApp.kt`, `RelayViewModel.kt` |
| GPS | `app/.../location/LocationProvider.kt`, `AndroidLocationProvider.kt` |
| 権限 | `app/.../permissions/NearbyPermissionPolicy.kt` |
| ネット優先 | `app/.../cloud/InternetPrioritySync.kt`, `ServerSyncGateway.kt` |
| 同期 | `app/.../sync/SyncCoordinator.kt`（`SendFailed`） |
| 信頼ラベル | `domain/RelayMessage.kt` → `deliveryPresentationLabel` |
| Gateway クライアント | `app/.../gateway/GatewaySyncEngine.kt`, `HttpGatewayBridgeClient` |
| PC Gateway | `pc-gateway/.../GatewayServer.kt`, `Main.kt` |
| DI | `RelayApplication.kt`, `MainActivity.kt` |
| 運用正本 | `docs/OPERATION_MODEL.md` |
| 状態 JSON（やや古い可能性） | `artifacts/relay_status.json` |

---

## 5. 未コミットの整理方針（Codex 推奨）

現状は **ベースコミットよりかなり進んだ dirty tree**。推奨コミット分割:

1. `feat(pc-gateway): one-click Start-PC-Gateway.cmd + run scripts`
2. `feat(app): SendFailed observability, trust labels, a11y`
3. `feat(app): GPS one-shot + location permissions API 32+`
4. `feat(app): InternetPrioritySync (empty source default)`
5. `feat(kmp): shared + composeApp scaffold + APPLE_TARGETS docs`
6. `docs: README / OPERATION_MODEL / PC_GATEWAY_SETUP honesty`

**コミットしない方がよい:** `.ua/`, `mcps/`, `terminals/`, 巨大 `artifacts/*.exe` の無意味なバイナリ更新（必要なら別途）。

---

## 6. 未完了・次の仕事候補（優先度順）

### P0 — 製品として穴
1. **`PriorityMessageSource` 本番実装**  
   今は `EmptyPriorityMessageSource` のみ。HTTPS フィード or 設定 URL + JSON デコードを `InternetPrioritySync` に接続。テストは既存 `InternetPrioritySyncTest` パターンを踏襲。
2. **コミット整理** — 上記 dirty を意味のある単位で commit（ユーザー承認後）。
3. **実機 / エミュレータ**  
   - Phone→PC public E2E: `docs/runbooks/PHONE_TO_PC_PUBLIC_SYNC_E2E.md`  
   - GPS 権限ダイアログ + 座標が `approximateLocation` に入ること  
   - adb 0 台の環境では自動化ゲートのみ

### P1 — UX / 品質
4. ホーム「無事ワンタップ」（GPS 自動）
5. ネット優先受信時の通知
6. 状態チップ: Offline / LAN Gateway / Internet
7. `composeApp` の Android ビルドを SDK 破損から切り離す（android ターゲット無効化 or build-tools 修復）

### P2 — プラットフォーム
8. iOS: Mac で `linkDebugFrameworkIosSimulatorArm64` + Xcode ホスト（`composeApp/iosApp/README.md`）
9. Android↔iOS peer は非互換のまま — 当面 PC Gateway 経由のみ

### やらない方がよい（スコープ外）
- SwiftUI 別アプリ再導入
- 連続 GPS 追跡 / 監視
- 本番 PKI・Play 署名必須化
- 管理者 Firewall を one-click 必須にすること

---

## 7. 設計上の不変条件（壊さない）

1. **zero-operation public ingress** → 保存成功で `GATEWAY_RECEIVED_UNVERIFIED` + trust `unverified`
2. 内容検証は MVP で **UNVERIFIED**（署名 NoOp）
3. SCF: hop / TTL / Manifest 差分 / Fake multi-hop テスト
4. 送信失敗を `PayloadTransferCompleted` にしない
5. UI ラベルは公式到達・最終配信完了を主張しない
6. 電話・メール・HW ID・AI を必須にしない
7. GPS は **作成時ワンショット**、拒否しても登録可能

---

## 8. 環境メモ（このマシン）

| 項目 | 状態 |
|------|------|
| OS | Windows |
| JDK | 17 想定 |
| adb | しばしば 0 devices（エミュレータ boot 不安定） |
| Android SDK build-tools 35.0.0 | corrupt 報告あり → composeApp android に注意 |
| ダッシュボード | 以前 Vite を background 起動していた可能性（不要なら停止） |

---

## 9. Codex への依頼テンプレ（コピー用）

```
@artifacts/CODEX_HANDOFF.md を読んでから作業して。

現状: branch agent/zero-operation-relay、未コミットが多い。
直近: GPS 権限 API32+ + one-shot location、InternetPrioritySync（Empty source）、Start-PC-Gateway.cmd。

次にやってほしいこと:
1) ...
2) ...

制約: zero-op / UNVERIFIED / SCF を壊さない。テストは shipped コードを叩く。
検証: .\gradlew.bat :app:testDebugUnitTest :relay-protocol:test :pc-gateway:test :app:assembleDebug --no-daemon
```

---

## 10. 関連ドキュメント索引

| 文書 | 内容 |
|------|------|
| `README.md` | 概要・起動・完成判定 |
| `docs/OPERATION_MODEL.md` | zero-op / GPS / ネット復帰 |
| `docs/PC_GATEWAY_SETUP.md` | ワンクリック主、手動上級 |
| `docs/APPLE_TARGETS.md` | iPhone KMP / Mac gateway |
| `docs/runbooks/PHONE_TO_PC_PUBLIC_SYNC_E2E.md` | 実機 E2E |
| `artifacts/relay_status.json` | ステージ JSON（更新が遅れている可能性） |
| `artifacts/build-verify-20260716.md` | 過去検証ログ |

---

## 11. 引き継ぎチェックリスト（Codex 初日）

- [ ] `git status` / `git log -5` で dirty を確認
- [ ] `CODEX_HANDOFF.md` と `OPERATION_MODEL.md` を読む
- [ ] `:app:testDebugUnitTest`（関連パッケージ）を一度緑にする
- [ ] `Start-PC-Gateway.cmd` または `run-pc-gateway.ps1 -NoBrowser -Port 18080` で health 200 を確認
- [ ] ユーザー目標を再確認してから大きなリファクタに入る
- [ ] コミットはユーザー依頼があるまで勝手に force-push / 破壊的操作しない

---

*End of handoff. 質問があれば「未コミットをどう分割コミットするか」「PriorityMessageSource の URL 仕様」から始めるのが安全。*
