<!-- GENERATED FILE - DO NOT EDIT.
     Source of truth: docs/readiness/status.yml
     Regenerate: python tools/readiness/readiness_tool.py generate -->

# Relay readiness table

Status date: **2026-07-26** / commit `89e7651` / branch `agent/zero-operation-relay`

> Relayは119、消防・警察・自治体の公式な緊急連絡手段の代替ではありません。 本ファイルのどの状態も、実災害での救助や自治体・消防の承認を保証しません。

State axes are independent: `IMPLEMENTED` and `AUTOMATED_TESTED` never imply `DEVICE_TESTED` or `FIELD_TESTED`.

| ID | 機能 | 領域 | 実装 | 自動試験 | Emulator | 実機 | 現地 | 外部判断 |
|---|---|---|---|---|---|---|---|---|
| `sos-rescue-request` | SOS・救助依頼の作成・更新・取消 | Android | IMPLEMENTED | AUTOMATED_TESTED | NOT_RUN | NOT_RUN | NOT_RUN | - |
| `encrypted-storage` | Room + SQLCipher暗号化保存（Keystore保護passphrase） | Android | IMPLEMENTED | AUTOMATED_TESTED | NOT_RUN | NOT_RUN | NOT_RUN | - |
| `location-update` | 明示同意時のみの位置更新 | Android | IMPLEMENTED | AUTOMATED_TESTED | NOT_RUN | NOT_RUN | NOT_RUN | - |
| `continuous-gps-tracking` | 継続GPS追跡（background location） | Android | NOT_IMPLEMENTED | N/A | N/A | N/A | N/A | - |
| `armed-emergency-state` | ARMED / EMERGENCY背景中継状態管理 | Android | IMPLEMENTED | AUTOMATED_TESTED | NOT_RUN | NOT_RUN | NOT_RUN | - |
| `auto-disaster-detection` | 自動災害検知（FCM・気象・Activation Manifest） | Android | NOT_IMPLEMENTED | N/A | N/A | N/A | N/A | - |
| `nearby-relay` | Nearby暗号化Envelope中継（Store-Carry-Forward） | 通信・中継 | IMPLEMENTED | AUTOMATED_TESTED | NOT_RUN | BLOCKED_EXTERNAL | NOT_RUN | Two/three physical Android devices for RF multi-hop validation |
| `nearby-connection-policy` | Nearby接続ポリシー（OPEN / TRUSTED） | 通信・中継 | IMPLEMENTED | AUTOMATED_TESTED | NOT_RUN | NOT_RUN | NOT_RUN | Allow-list distribution and update operation design |
| `gateway-enrollment-core` | LAN Gateway登録（token検証・永続化・rotation） | 通信・中継 | IMPLEMENTED | AUTOMATED_TESTED | NOT_RUN | NOT_RUN | NOT_RUN | - |
| `gateway-enrollment-ui` | Gateway登録画面（CameraX QRスキャナ含むCompose UI） | 通信・中継 | IMPLEMENTED | AUTOMATED_TESTED | NOT_RUN | NOT_RUN | NOT_RUN | - |
| `broker-manifest-enrollment` | Broker Manifest登録（debug/localDev限定） | 通信・中継 | IMPLEMENTED | AUTOMATED_TESTED | NOT_RUN | NOT_RUN | NOT_RUN | - |
| `ble-gateway-trust-chain` | BLE Gateway信頼chain（Root→Directory→Manifest→fingerprint） | 信頼・鍵 | IMPLEMENTED | AUTOMATED_TESTED | NOT_RUN | BLOCKED_EXTERNAL | BLOCKED_EXTERNAL | Formal regional Root and signed shelter Directory issuance by trust authority |
| `pc-gateway-console` | PC Gatewayスタッフ画面（アカウント・役割・監査・署名Receipt） | PC Gateway | IMPLEMENTED | AUTOMATED_TESTED | NOT_RUN | N/A | NOT_RUN | - |
| `sqlite-write-coordination` | SQLite write coordinatorとlock file | PC Gateway | IMPLEMENTED | AUTOMATED_TESTED | N/A | N/A | N/A | - |
| `csv-export-injection-safe` | CSV export（formula injection防止） | PC Gateway | IMPLEMENTED | AUTOMATED_TESTED | N/A | N/A | N/A | - |
| `https-broker` | HTTPS Broker（暗号文保存・重複排除・TTL・scoped credential） | Broker | IMPLEMENTED | AUTOMATED_TESTED | N/A | N/A | NOT_RUN | Production TLS/DNS/reverse proxy/WAF/hosting |
| `broker-observability-minimal` | Broker security event記録（秘密情報なし） | Broker | IMPLEMENTED | AUTOMATED_TESTED | N/A | N/A | N/A | - |
| `packaged-e2e` | Packaged Broker–Gateway E2E（black-box） | 検証 | IMPLEMENTED | AUTOMATED_TESTED | N/A | N/A | N/A | - |
| `broker-high-availability` | Broker高可用性・監視・災害復旧 | Broker | NOT_IMPLEMENTED | N/A | N/A | N/A | N/A | Infrastructure/SRE owner for HA, RTO/RPO, and monitoring design |
| `android6-compat` | Android 6.0互換基盤（minSdk 23） | 検証 | IMPLEMENTED | AUTOMATED_TESTED | NOT_RUN | NOT_RUN | NOT_RUN | - |
| `windows-validation` | Windows一括検証（PASS/FAIL/BLOCKED/NOT_RUN分類） | 検証 | IMPLEMENTED | AUTOMATED_TESTED | N/A | N/A | N/A | - |
| `device-test-harness` | 実機テスト基盤（ADB・Mobly script） | 検証 | IMPLEMENTED | AUTOMATED_TESTED | NOT_RUN | BLOCKED_EXTERNAL | BLOCKED_EXTERNAL | Physical Android devices and approved test network |
| `coverage-kover` | Kover coverage report | 検証 | IMPLEMENTED | AUTOMATED_TESTED | N/A | N/A | N/A | - |
| `build-reproducibility` | Build再現性（SOURCE_DATE_EPOCH＋APK比較） | Supply chain | IMPLEMENTED | AUTOMATED_TESTED | N/A | N/A | N/A | - |
| `dependency-security-scan` | 依存関係scan（Syft・OSV・Grype） | Supply chain | IMPLEMENTED | AUTOMATED_TESTED | N/A | N/A | N/A | - |
| `fuzz-decoders` | Jazzer decoder fuzz（回帰lane） | 検証 | IMPLEMENTED | AUTOMATED_TESTED | N/A | N/A | N/A | - |
| `formal-release` | 正式Release（組織署名・Authenticode・TUF/cosign） | Release | IMPLEMENTED | AUTOMATED_TESTED | N/A | BLOCKED_EXTERNAL | BLOCKED_EXTERNAL | Organization Android signing key; Windows Authenticode certificate and timestamp policy; cosign/TUF key governance; Release approval by responsible organization |
| `ios-preview` | iOS simulatorプレビュービルド | iOS | IMPLEMENTED | AUTOMATED_TESTED | EMULATOR_TESTED | BLOCKED_EXTERNAL | BLOCKED_EXTERNAL | Apple Developer signing and iPhone hardware |
| `meshtastic-adapter` | Meshtastic adapter（契約境界） | 外部連携 | IMPLEMENTED | AUTOMATED_TESTED | N/A | BLOCKED_EXTERNAL | BLOCKED_EXTERNAL | Physical Meshtastic hardware |
| `bp7-export` | BPv7 export境界 | 外部連携 | IMPLEMENTED | AUTOMATED_TESTED | N/A | N/A | N/A | - |
| `training-mode` | 訓練モード（本番データ完全分離） | 運用 | NOT_IMPLEMENTED | N/A | N/A | N/A | N/A | - |
| `official-info-provenance` | 公式情報の来歴モデル（JMA XML・CAP） | 運用 | NOT_IMPLEMENTED | N/A | N/A | N/A | N/A | - |
| `dpapi-key-protection` | Windows DPAPIによるGateway秘密鍵保護 | PC Gateway | NOT_IMPLEMENTED | N/A | N/A | N/A | N/A | - |
| `data-retention` | 個人・救助情報のretention管理 | 運用 | NOT_IMPLEMENTED | N/A | N/A | N/A | N/A | Privacy/legal owner approval of retention periods |
| `codeql-analysis` | CodeQL静的解析（java-kotlin / js-ts / actions） | Supply chain | IMPLEMENTED | NOT_RUN | N/A | N/A | N/A | - |
| `secret-scanning-gitleaks` | gitleaks秘密情報スキャン（Relay固有ルール） | Supply chain | IMPLEMENTED | AUTOMATED_TESTED | N/A | N/A | N/A | - |
| `workflow-lint-zizmor` | ワークフローlintと堅牢化監査（actionlint + zizmor） | Supply chain | IMPLEMENTED | AUTOMATED_TESTED | N/A | N/A | N/A | - |
| `action-sha-pinning-gate` | SHA固定されていないGitHub Actionsを拒否するCIゲート | Supply chain | IMPLEMENTED | AUTOMATED_TESTED | N/A | N/A | N/A | - |
| `dependency-review` | PR依存関係レビュー（high以上で失敗・ライセンス拒否リスト） | Supply chain | IMPLEMENTED | NOT_RUN | N/A | N/A | N/A | - |
| `scorecard-monitoring` | OSSF Scorecardサプライチェーン姿勢モニタリング | Supply chain | IMPLEMENTED | NOT_RUN | N/A | N/A | N/A | - |
| `dependabot-updates` | Dependabot更新設定（gradle/actions/npm/pip） | Supply chain | IMPLEMENTED | N/A | N/A | N/A | N/A | - |
| `branch-protection` | 必須セキュリティチェック付きブランチ保護 | Supply chain | BLOCKED_EXTERNAL | BLOCKED_EXTERNAL | N/A | N/A | N/A | Repository owner must apply the settings documented in docs/security/BRANCH_PROTECTION.md |
| `gradle-dependency-verification` | Gradle依存関係検証（sha256・fail-closed） | Supply chain | IMPLEMENTED | AUTOMATED_TESTED | N/A | N/A | N/A | - |
| `release-provenance-attestation` | 正式リリース成果物のビルド来歴attestation | Release | IMPLEMENTED | NOT_RUN | N/A | N/A | N/A | - |
| `detekt-static-analysis` | detekt静的解析（Relay固有の機微ログ禁止ルール） | 検証 | IMPLEMENTED | AUTOMATED_TESTED | N/A | N/A | N/A | - |

## Notes per feature

- `sos-rescue-request`: JVM unit tests only; no claim about physical device behavior.
- `encrypted-storage`: Instrumentation execution is an emulator/device lane, not part of the default PR lane.
- `continuous-gps-tracking`: Background location, location FGS, and periodic tracking are deliberately not implemented.
- `armed-emergency-state`: Persistent state, degrade/recovery, and communication lease covered by unit tests only.
- `auto-disaster-detection`: Design only; no code path claims detection.
- `nearby-relay`: RF behavior cannot be proven without physical devices.
- `nearby-connection-policy`: Fail-closed allow-list enforced in unit tests; distribution operations incomplete.
- `gateway-enrollment-core`: relay-gw:1: token validation, conflicting beacon rejection, manifest pinning.
- `gateway-enrollment-ui`: Screen and scanner are implemented (commit de6850a). Camera hardware behavior and real-LAN enrollment remain device work.
- `broker-manifest-enrollment`: Development-only path; disabled in release/pilotRelease.
- `ble-gateway-trust-chain`: Verification logic tested with fixtures; no formal trust anchors exist.
- `pc-gateway-console`: Windows validation lane runs Gateway build, tests, and /api/health smoke.
- `https-broker`: Broker never decrypts rescue bodies; production infrastructure is external.
- `broker-observability-minimal`: Coarse category logging only: auth failures, invalid proofs, rate limits.
- `packaged-e2e`: Scheduled heavy lane; loopback processes, not real network topology.
- `broker-high-availability`: Single SQLite instance; no HA claim possible.
- `android6-compat`: Managed-device lane defined; API 23 emulator execution not part of default PR lane.
- `windows-validation`: 0-test results are treated as failure; JUnit XML presence is enforced.
- `device-test-harness`: Script existence does not mean device PASS.
- `coverage-kover`: Visualization only; no threshold enforcement yet.
- `build-reproducibility`: Debug APK comparison only; full-artifact reproducibility unproven.
- `dependency-security-scan`: report-only in PR lane; block-high-critical in formal release.
- `fuzz-decoders`: Deterministic seed-corpus regression on PR; continuous fuzzing opt-in.
- `formal-release`: The gate exists and fails closed without organization secrets. No formal release has ever been produced; the workflow result is BLOCKED_EXTERNAL until keys exist.
- `ios-preview`: Simulator build evidence only; no physical iPhone claim.
- `training-mode`: Planned: separate DB/keys/accounts/credentials with mandatory training banner.
- `official-info-provenance`: Current JMA warning fetch has no provenance/verification-state model yet.
- `dpapi-key-protection`: Current boundary is owner-only file permission verification, deliberately not claimed as DPAPI/HSM/KMS.
- `data-retention`: Terminal-detail 30-day retention exists in Gateway; a general policy engine does not.
- `codeql-analysis`: Workflow linted locally (actionlint/zizmor) but not yet executed on GitHub-hosted runners.
- `secret-scanning-gitleaks`: Scan executed locally with checksum-pinned gitleaks 8.30.1; CI execution on GitHub runners pending first push.
- `workflow-lint-zizmor`: All checkouts use persist-credentials:false; publish workflows demoted to contents:read with job-level write.
- `dependency-review`: Only runs on pull_request events; requires GitHub dependency graph.
- `scorecard-monitoring`: Honestly gated to the default branch; will not produce results until merged there.
- `dependabot-updates`: Configuration only; GitHub activates it server-side once present on the default branch.
- `branch-protection`: CI cannot verify repository settings; remains blocked until the owner applies and confirms them.
- `gradle-dependency-verification`: Generated on Windows; platform-specific artifacts for ubuntu/macos CI lanes may need additions (fail-closed, documented).
- `release-provenance-attestation`: Real attestation requires an actual formal release run on GitHub; not executable locally.
- `detekt-static-analysis`: 1294 existing findings recorded as baseline debt (0 ForbiddenImport); CLI checksum-pinned, no Gradle plugin dependency added.
