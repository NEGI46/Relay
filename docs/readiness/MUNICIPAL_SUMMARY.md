<!-- GENERATED FILE - DO NOT EDIT.
     Source of truth: docs/readiness/status.yml
     Regenerate: python tools/readiness/readiness_tool.py generate -->

# Relay 自治体向け現状サマリ

基準日: **2026-07-26**（commit `89e7651`）

> Relayは119、消防・警察・自治体の公式な緊急連絡手段の代替ではありません。 本ファイルのどの状態も、実災害での救助や自治体・消防の承認を保証しません。

「自動試験まで完了」は開発環境での自動テスト成功のみを意味し、実機・電波環境・停電・避難所運用での検証を意味しません。

| 機能 | 現在の段階 | 実機・現地確認 |
|---|---|---|
| SOS・救助依頼の作成・更新・取消 | 自動試験まで完了 | 未実施 |
| Room + SQLCipher暗号化保存（Keystore保護passphrase） | 自動試験まで完了 | 未実施 |
| 明示同意時のみの位置更新 | 自動試験まで完了 | 未実施 |
| 継続GPS追跡（background location） | 未実装 | — |
| ARMED / EMERGENCY背景中継状態管理 | 自動試験まで完了 | 未実施 |
| 自動災害検知（FCM・気象・Activation Manifest） | 未実装 | — |
| Nearby暗号化Envelope中継（Store-Carry-Forward） | 自動試験まで完了 | 外部準備待ち |
| Nearby接続ポリシー（OPEN / TRUSTED） | 自動試験まで完了 | 未実施 |
| LAN Gateway登録（token検証・永続化・rotation） | 自動試験まで完了 | 未実施 |
| Gateway登録画面（CameraX QRスキャナ含むCompose UI） | 自動試験まで完了 | 未実施 |
| Broker Manifest登録（debug/localDev限定） | 自動試験まで完了 | 未実施 |
| BLE Gateway信頼chain（Root→Directory→Manifest→fingerprint） | 自動試験まで完了 | 外部準備待ち |
| PC Gatewayスタッフ画面（アカウント・役割・監査・署名Receipt） | 自動試験まで完了 | 未実施 |
| SQLite write coordinatorとlock file | 自動試験まで完了 | 対象外 |
| CSV export（formula injection防止） | 自動試験まで完了 | 対象外 |
| HTTPS Broker（暗号文保存・重複排除・TTL・scoped credential） | 自動試験まで完了 | 未実施 |
| Broker security event記録（秘密情報なし） | 自動試験まで完了 | 対象外 |
| Packaged Broker–Gateway E2E（black-box） | 自動試験まで完了 | 対象外 |
| Broker高可用性・監視・災害復旧 | 未実装 | — |
| Android 6.0互換基盤（minSdk 23） | 自動試験まで完了 | 未実施 |
| Windows一括検証（PASS/FAIL/BLOCKED/NOT_RUN分類） | 自動試験まで完了 | 対象外 |
| 実機テスト基盤（ADB・Mobly script） | 自動試験まで完了 | 外部準備待ち |
| Kover coverage report | 自動試験まで完了 | 対象外 |
| Build再現性（SOURCE_DATE_EPOCH＋APK比較） | 自動試験まで完了 | 対象外 |
| 依存関係scan（Syft・OSV・Grype） | 自動試験まで完了 | 対象外 |
| Jazzer decoder fuzz（回帰lane） | 自動試験まで完了 | 対象外 |
| 正式Release（組織署名・Authenticode・TUF/cosign） | 自動試験まで完了 | 外部準備待ち |
| iOS simulatorプレビュービルド | 自動試験まで完了 | 外部準備待ち |
| Meshtastic adapter（契約境界） | 自動試験まで完了 | 外部準備待ち |
| BPv7 export境界 | 自動試験まで完了 | 対象外 |
| 訓練モード（本番データ完全分離） | 未実装 | — |
| 公式情報の来歴モデル（JMA XML・CAP） | 未実装 | — |
| Windows DPAPIによるGateway秘密鍵保護 | 未実装 | — |
| 個人・救助情報のretention管理 | 未実装 | — |
| CodeQL静的解析（java-kotlin / js-ts / actions） | 実装のみ | 対象外 |
| gitleaks秘密情報スキャン（Relay固有ルール） | 自動試験まで完了 | 対象外 |
| ワークフローlintと堅牢化監査（actionlint + zizmor） | 自動試験まで完了 | 対象外 |
| SHA固定されていないGitHub Actionsを拒否するCIゲート | 自動試験まで完了 | 対象外 |
| PR依存関係レビュー（high以上で失敗・ライセンス拒否リスト） | 実装のみ | 対象外 |
| OSSF Scorecardサプライチェーン姿勢モニタリング | 実装のみ | 対象外 |
| Dependabot更新設定（gradle/actions/npm/pip） | 実装のみ | 対象外 |
| 必須セキュリティチェック付きブランチ保護 | 外部判断待ち | — |
| Gradle依存関係検証（sha256・fail-closed） | 自動試験まで完了 | 対象外 |
| 正式リリース成果物のビルド来歴attestation | 実装のみ | 対象外 |

実機検証済み: **0件** / 現地検証済み: **0件**（2026-07-26時点）
