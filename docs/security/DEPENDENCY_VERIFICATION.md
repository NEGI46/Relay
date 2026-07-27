# Gradle Dependency Verification

`gradle/verification-metadata.xml` の存在により、Gradle は解決する全アーティファクト
（依存ライブラリ・Gradle プラグイン・POM/module メタデータ）の SHA-256 を検証します。
チェックサムが一致しない、または未登録のアーティファクトが解決された場合、
ビルドは **fail-closed** で失敗します。

## 検証済みの動作（ローカル、2026-07-26）

- 正常系: `:relay-protocol:test` など通常ビルドが検証有効のまま成功（exit 0）
- 異常系: メタデータ内の全 sha256 を改ざん → `Dependency verification failed for
  configuration 'detachedConfiguration1'`（Android Gradle Plugin の POM で即失敗、exit 1）
- 検証は「実際に解決されたアーティファクト」に対してのみ行われる（Gradle の仕様）。
  未解決バージョンのエントリ改ざんではビルドは失敗しない。

## メタデータの再生成・追記

依存を追加・更新した場合（Dependabot PR を含む）は次で追記します:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat --write-verification-metadata sha256 <対象タスク> --no-daemon
```

タスクを実行せず解決だけ行う場合は `--dry-run` を併用します。生成された差分は
必ずレビューし、意図しない依存の混入がないか確認してからコミットしてください。

## 既知の制約（正直な申告）

- メタデータは Windows 上で生成したため、**他 OS でのみ解決される
  プラットフォーム固有アーティファクト**（例: skiko の linux-x64 / macos ランタイム）が
  未登録の可能性があります。GitHub Actions の ubuntu / macos ジョブが
  `Dependency verification failed` で失敗した場合は、レポート HTML に列挙された
  不足エントリを上記コマンド（該当 OS 上）または手動追記で補完してください。
  これは fail-closed の想定動作であり、検証を無効化して回避してはいけません。
- PGP 署名検証（`verify-signatures`）は現時点で無効です。導入する場合は
  鍵の信頼リスト整備が必要です。

## Dependency Locking を導入しない判断（記録）

Gradle の lockfile（`--write-locks`）は導入していません。理由:

1. 直接依存は `gradle/libs.versions.toml` で全て固定バージョン指定であり、
   動的バージョン（`+` / `latest.release` / 範囲指定）は 0 件（2026-07-26 走査）。
2. 推移的依存が別バージョンに変動した場合、そのアーティファクトは
   verification-metadata に未登録のため **ビルドが fail-closed で失敗**する。
   つまりチェックサム検証が事実上のロックとして機能する。
3. 7 モジュール × 全 configuration の lockfile は Dependabot 更新のたびに
   大量差分を生み、レビュー品質を下げる（誤マージリスクの方が大きい）。

動的バージョンを導入する変更が入った場合は、この判断を再評価すること。

## カバーしたタスク

生成時に以下を解決済み: `:app:compileDebugKotlin` / `:app:testDebugUnitTest`(実行) と、
`--dry-run` で `:app:assembleDebug` `:app:assembleRelease` `:app:assembleLocalDev`
`:app:lintDebug` `:pc-gateway:build` `:pc-gateway:installDist` `:broker:test`
`:broker:installDist` `:shared:jvmTest` `:relay-protocol:test` `:composeApp:desktopTest`
`:fuzz-jvm:classes`（一部は実行パスで解決）。
