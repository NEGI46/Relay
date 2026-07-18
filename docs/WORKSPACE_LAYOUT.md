# ワークスペース構成

このリポジトリは、アプリ本体とローカル作業状態を分けて扱う。日常作業では、下表の「追跡対象」を優先して確認する。

| 区分 | 主な場所 | 用途 | Git |
|---|---|---|---|
| Android アプリ | `app/` | Android UI、Nearby、Room、同期処理 | 追跡対象 |
| PC Gateway | `pc-gateway/` | Ktor/Netty の固定地点 Gateway | 追跡対象 |
| Gateway 契約 | `relay-protocol/` | Gateway HTTP の DTO と検証 | 追跡対象 |
| KMP/CMP 試作 | `shared/`, `composeApp/` | 共通ドメイン、iOS/desktop 向け土台 | 追跡対象 |
| 運用スクリプト | `scripts/`, `Start-PC-Gateway.cmd` | Gateway の起動・セットアップ・検証 | 追跡対象 |
| 設計・運用資料 | `docs/` | 設計、セットアップ、runbook、検証計画 | 追跡対象 |
| 配布・検証成果物 | `artifacts/` | APK、Gateway インストーラ、ハッシュ、引き継ぎ・検証記録 | 必要なものだけ追跡 |
| ビルドキャッシュ | `.gradle/`, `.kotlin/`, `**/build/` | Gradle/Kotlin の再生成可能な状態 | 無視 |
| ローカル補助ツール状態 | `.ua/`, `mcps/`, `terminals/` | 外部ツールの索引、定義、端末ログ | 無視・移動しない |

## 入口

- 開発状況と次の作業は [CODEX_HANDOFF.md](../artifacts/CODEX_HANDOFF.md)。
- Relay の動作モデルは [OPERATION_MODEL.md](OPERATION_MODEL.md)。
- PC Gateway の通常起動はリポジトリ直下の `Start-PC-Gateway.cmd`。詳細は [PC_GATEWAY_SETUP.md](PC_GATEWAY_SETUP.md)。

## 成果物の扱い

`artifacts/` は配布物と作業時の証跡を同居させる互換領域である。既存の参照を壊さないため、現在の APK、EXE、ハッシュ、引き継ぎ資料は移動しない。

- 配布・共有が必要なファイルだけを追跡する。
- 一時ログ、スモークテスト出力、パッチ、ローカル app-image は `.gitignore` に従い追跡しない。
- 新しい検証記録は `artifacts/build-verify-YYYYMMDD.md`、引き継ぎ資料は `artifacts/CODEX_HANDOFF*.md` の命名を使う。

## 整理のルール

1. `.ua/`、`mcps/`、`terminals/` はツールがルート直下を前提にする可能性があるため、削除・移動しない。
2. バイナリ（APK/EXE）や未コミットの実装は、明示的な依頼なしに削除・置換しない。
3. 新しい永続資料は内容に応じて `docs/`、配布物・検証証跡は `artifacts/` に置く。ルート直下にはビルド設定、起動入口、主要 README だけを置く。
