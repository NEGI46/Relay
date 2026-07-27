#!/usr/bin/env python3
"""Relay readiness single-source-of-truth tool.

docs/readiness/status.yml is the ONLY authoritative machine-readable record
of implementation / test / device readiness. This tool:

  validate  - JSON Schema validation plus semantic rules that the schema
              cannot express (unique ids, no false-green state combinations).
  generate  - regenerates all derived documents from status.yml:
                * README.md generated block (between markers)
                * docs/readiness/READINESS_TABLE.md
                * docs/readiness/OPEN_ITEMS.md
                * docs/readiness/MUNICIPAL_SUMMARY.md
                * docs/readiness/RELEASE_EVIDENCE.md
  check     - fails (exit 1) when any derived document drifts from what
              generate would produce. CI runs validate + check.
  summary   - writes a GitHub Actions Job Summary style markdown to stdout
              (and to $GITHUB_STEP_SUMMARY when set).

Design rule: this tool NEVER converts a blocked or missing state into a
passing one. Unknown states are validation errors, not warnings.
"""

from __future__ import annotations

import argparse
import io
import json
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    print("ERROR: PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

try:
    import jsonschema
except ImportError:  # pragma: no cover
    print("ERROR: jsonschema is required (pip install jsonschema)", file=sys.stderr)
    sys.exit(2)

REPO_ROOT = Path(__file__).resolve().parents[2]
STATUS_PATH = REPO_ROOT / "docs" / "readiness" / "status.yml"
SCHEMA_PATH = REPO_ROOT / "docs" / "readiness" / "status.schema.json"
README_PATH = REPO_ROOT / "README.md"
TABLE_PATH = REPO_ROOT / "docs" / "readiness" / "READINESS_TABLE.md"
OPEN_ITEMS_PATH = REPO_ROOT / "docs" / "readiness" / "OPEN_ITEMS.md"
MUNICIPAL_PATH = REPO_ROOT / "docs" / "readiness" / "MUNICIPAL_SUMMARY.md"
EVIDENCE_PATH = REPO_ROOT / "docs" / "readiness" / "RELEASE_EVIDENCE.md"

README_BEGIN = "<!-- BEGIN GENERATED: readiness-summary (tools/readiness/readiness_tool.py; edit docs/readiness/status.yml instead) -->"
README_END = "<!-- END GENERATED: readiness-summary -->"

GENERATED_HEADER = (
    "<!-- GENERATED FILE - DO NOT EDIT.\n"
    "     Source of truth: docs/readiness/status.yml\n"
    "     Regenerate: python tools/readiness/readiness_tool.py generate -->\n"
)

AREA_LABELS_JA = {
    "android": "Android",
    "transport": "通信・中継",
    "trust": "信頼・鍵",
    "gateway": "PC Gateway",
    "broker": "Broker",
    "verification": "検証",
    "supply-chain": "Supply chain",
    "release": "Release",
    "apple": "iOS",
    "integrations": "外部連携",
    "operations": "運用",
}

# Axis value -> short cell text. Values must stay honest: automated evidence
# is never rendered as device or field readiness.
CELL = {
    "IMPLEMENTED": "IMPLEMENTED",
    "NOT_IMPLEMENTED": "NOT_IMPLEMENTED",
    "DEPRECATED": "DEPRECATED",
    "BLOCKED_EXTERNAL": "BLOCKED_EXTERNAL",
    "AUTOMATED_TESTED": "AUTOMATED_TESTED",
    "EMULATOR_TESTED": "EMULATOR_TESTED",
    "DEVICE_TESTED": "DEVICE_TESTED",
    "FIELD_TESTED": "FIELD_TESTED",
    "NOT_RUN": "NOT_RUN",
    "NOT_APPLICABLE": "N/A",
}

AXES = ("implementation", "automated_test", "emulator_simulator", "real_device", "field_test")


def load_status() -> dict:
    with STATUS_PATH.open("r", encoding="utf-8") as fh:
        return yaml.safe_load(fh)


def load_schema() -> dict:
    with SCHEMA_PATH.open("r", encoding="utf-8") as fh:
        return json.load(fh)


def validate(status: dict) -> list[str]:
    """Schema validation plus semantic rules. Returns a list of error strings."""
    errors: list[str] = []
    validator = jsonschema.Draft202012Validator(load_schema())
    for err in sorted(validator.iter_errors(status), key=lambda e: list(e.absolute_path)):
        path = "/".join(str(p) for p in err.absolute_path) or "<root>"
        errors.append(f"schema: {path}: {err.message}")

    features = status.get("features") or []
    seen_ids: set[str] = set()
    for idx, feature in enumerate(features):
        if not isinstance(feature, dict):
            continue
        fid = feature.get("id", f"<index {idx}>")
        if fid in seen_ids:
            errors.append(f"semantic: duplicate feature id '{fid}'")
        seen_ids.add(fid)

        # Fail-closed rule: a feature that is not implemented can never carry
        # a tested state on any other axis.
        if feature.get("implementation") in ("NOT_IMPLEMENTED", "BLOCKED_EXTERNAL"):
            for axis in AXES[1:]:
                value = feature.get(axis)
                if value in ("AUTOMATED_TESTED", "EMULATOR_TESTED", "DEVICE_TESTED", "FIELD_TESTED"):
                    errors.append(
                        f"semantic: {fid}: {axis}={value} is impossible while "
                        f"implementation={feature.get('implementation')}"
                    )

        # DEVICE_TESTED / FIELD_TESTED require dated external_record evidence.
        for axis, state in (("real_device", "DEVICE_TESTED"), ("field_test", "FIELD_TESTED")):
            if feature.get(axis) == state:
                records = [
                    ev for ev in feature.get("evidence", [])
                    if isinstance(ev, dict) and ev.get("kind") == "external_record"
                    and ev.get("date") and ev.get("recorded_by")
                ]
                if not records:
                    errors.append(
                        f"semantic: {fid}: {axis}={state} requires dated "
                        f"external_record evidence with recorded_by"
                    )

        # BLOCKED_EXTERNAL anywhere requires at least one named external decision.
        blocked_axes = [a for a in AXES if feature.get(a) == "BLOCKED_EXTERNAL"]
        if blocked_axes and not feature.get("external_decisions"):
            errors.append(
                f"semantic: {fid}: axes {blocked_axes} are BLOCKED_EXTERNAL but "
                f"external_decisions is empty"
            )
    return errors


def counts(features: list[dict]) -> dict:
    return {
        "total": len(features),
        "implemented": sum(1 for f in features if f["implementation"] == "IMPLEMENTED"),
        "not_implemented": sum(1 for f in features if f["implementation"] == "NOT_IMPLEMENTED"),
        "automated_tested": sum(1 for f in features if f["automated_test"] == "AUTOMATED_TESTED"),
        "emulator_tested": sum(1 for f in features if f["emulator_simulator"] == "EMULATOR_TESTED"),
        "device_tested": sum(1 for f in features if f["real_device"] == "DEVICE_TESTED"),
        "field_tested": sum(1 for f in features if f["field_test"] == "FIELD_TESTED"),
        "blocked_external": sum(
            1 for f in features if any(f[a] == "BLOCKED_EXTERNAL" for a in AXES)
        ),
    }


def render_readme_block(status: dict) -> str:
    features = status["features"]
    c = counts(features)
    lines = [
        README_BEGIN,
        "> [!NOTE]",
        "> この節は `docs/readiness/status.yml`（唯一の正）から自動生成されます。手で編集しないでください。",
        ">",
        f"> **status基準: {status['status_date']} / commit `{status['source_commit']}` / branch `{status['branch']}`**",
        ">",
        f"> 管理対象 {c['total']}機能: 実装済み {c['implemented']} / 未実装 {c['not_implemented']} / "
        f"自動試験済み {c['automated_tested']} / emulator検証済み {c['emulator_tested']} / "
        f"**実機検証済み {c['device_tested']} / 現地検証済み {c['field_tested']}** / "
        f"外部判断待ちを含む {c['blocked_external']}",
        ">",
        "> IMPLEMENTEDやAUTOMATED_TESTEDはDEVICE_TESTED・FIELD_TESTEDを意味しません。"
        "全機能の軸別状態は [READINESS_TABLE](docs/readiness/READINESS_TABLE.md)、"
        "未完了項目は [OPEN_ITEMS](docs/readiness/OPEN_ITEMS.md)、"
        "自治体向け要約は [MUNICIPAL_SUMMARY](docs/readiness/MUNICIPAL_SUMMARY.md) を参照してください。",
        README_END,
    ]
    return "\n".join(lines)


def render_table(status: dict) -> str:
    out = io.StringIO()
    out.write(GENERATED_HEADER)
    out.write("\n# Relay readiness table\n\n")
    out.write(f"Status date: **{status['status_date']}** / commit `{status['source_commit']}` "
              f"/ branch `{status['branch']}`\n\n")
    out.write(f"> {status['disclaimer_ja']}\n\n")
    out.write("State axes are independent: `IMPLEMENTED` and `AUTOMATED_TESTED` never imply "
              "`DEVICE_TESTED` or `FIELD_TESTED`.\n\n")
    out.write("| ID | 機能 | 領域 | 実装 | 自動試験 | Emulator | 実機 | 現地 | 外部判断 |\n")
    out.write("|---|---|---|---|---|---|---|---|---|\n")
    for f in status["features"]:
        ext = "; ".join(f["external_decisions"]) if f["external_decisions"] else "-"
        out.write(
            f"| `{f['id']}` | {f['name_ja']} | {AREA_LABELS_JA[f['area']]} "
            f"| {CELL[f['implementation']]} | {CELL[f['automated_test']]} "
            f"| {CELL[f['emulator_simulator']]} | {CELL[f['real_device']]} "
            f"| {CELL[f['field_test']]} | {ext} |\n"
        )
    out.write("\n## Notes per feature\n\n")
    for f in status["features"]:
        if f["notes"]:
            out.write(f"- `{f['id']}`: {f['notes']}\n")
    return out.getvalue()


def render_open_items(status: dict) -> str:
    out = io.StringIO()
    out.write(GENERATED_HEADER)
    out.write("\n# Relay open readiness items\n\n")
    out.write(f"Status date: **{status['status_date']}** / commit `{status['source_commit']}`\n\n")

    not_impl = [f for f in status["features"] if f["implementation"] != "IMPLEMENTED"]
    out.write("## 未実装・実装不能（外部判断待ち）\n\n")
    if not_impl:
        for f in not_impl:
            out.write(f"- `{f['id']}` {f['name_ja']} — {CELL[f['implementation']]}")
            if f["external_decisions"]:
                out.write(f"（外部判断: {'; '.join(f['external_decisions'])}）")
            out.write("\n")
    else:
        out.write("なし\n")

    out.write("\n## 実装済みだが実機未検証（DEVICE_TESTEDなし）\n\n")
    for f in status["features"]:
        if f["implementation"] == "IMPLEMENTED" and f["real_device"] in ("NOT_RUN", "BLOCKED_EXTERNAL"):
            out.write(f"- `{f['id']}` {f['name_ja']} — 実機: {CELL[f['real_device']]}\n")

    out.write("\n## 外部判断が必要な項目（機能別）\n\n")
    out.write("| ID | 機能 | 外部判断 |\n|---|---|---|\n")
    for f in status["features"]:
        if f["external_decisions"]:
            out.write(f"| `{f['id']}` | {f['name_ja']} | {'; '.join(f['external_decisions'])} |\n")
    out.write("\n詳細な背景は [BLOCKED_BY_EXTERNAL_DECISIONS](BLOCKED_BY_EXTERNAL_DECISIONS.md) を参照。\n")
    return out.getvalue()


def municipal_stage(f: dict) -> tuple[str, str]:
    """Returns (現在の段階, 実機・現地確認) for the municipal summary."""
    if f["implementation"] == "NOT_IMPLEMENTED":
        return "未実装", "—"
    if f["implementation"] == "BLOCKED_EXTERNAL":
        return "外部判断待ち", "—"
    if f["field_test"] == "FIELD_TESTED":
        return "現地検証済み", "済み"
    if f["real_device"] == "DEVICE_TESTED":
        return "実機検証済み", "済み"
    stage = "自動試験まで完了" if f["automated_test"] == "AUTOMATED_TESTED" else "実装のみ"
    if f["real_device"] == "BLOCKED_EXTERNAL" or f["field_test"] == "BLOCKED_EXTERNAL":
        return stage, "外部準備待ち"
    if f["real_device"] == "NOT_APPLICABLE" and f["field_test"] == "NOT_APPLICABLE":
        return stage, "対象外"
    return stage, "未実施"


def render_municipal(status: dict) -> str:
    out = io.StringIO()
    out.write(GENERATED_HEADER)
    out.write("\n# Relay 自治体向け現状サマリ\n\n")
    out.write(f"基準日: **{status['status_date']}**（commit `{status['source_commit']}`）\n\n")
    out.write(f"> {status['disclaimer_ja']}\n\n")
    out.write("「自動試験まで完了」は開発環境での自動テスト成功のみを意味し、"
              "実機・電波環境・停電・避難所運用での検証を意味しません。\n\n")
    out.write("| 機能 | 現在の段階 | 実機・現地確認 |\n|---|---|---|\n")
    for f in status["features"]:
        stage, device = municipal_stage(f)
        out.write(f"| {f['name_ja']} | {stage} | {device} |\n")
    c = counts(status["features"])
    out.write(f"\n実機検証済み: **{c['device_tested']}件** / 現地検証済み: **{c['field_tested']}件**"
              f"（{status['status_date']}時点）\n")
    return out.getvalue()


def render_evidence(status: dict) -> str:
    out = io.StringIO()
    out.write(GENERATED_HEADER)
    out.write("\n# Relay release evidence index\n\n")
    out.write(f"Status date: **{status['status_date']}** / commit `{status['source_commit']}`\n\n")
    out.write("Evidence listed here proves only what its kind states. `source`/`test`/`script`/"
              "`workflow`/`doc` entries are automated or written evidence; only dated "
              "`external_record` entries can support DEVICE_TESTED / FIELD_TESTED claims.\n\n")
    for f in status["features"]:
        out.write(f"## `{f['id']}` — {f['name']}\n\n")
        if f["evidence"]:
            for ev in f["evidence"]:
                line = f"- **{ev['kind']}**: `{ev['ref']}`"
                if ev.get("date"):
                    line += f" (date: {ev['date']}, recorded by: {ev.get('recorded_by', '?')})"
                if ev.get("note"):
                    line += f" — {ev['note']}"
                out.write(line + "\n")
        else:
            out.write("- 証跡なし（NOT_IMPLEMENTEDまたは設計のみ）\n")
        out.write("\n")
    return out.getvalue()


def render_job_summary(status: dict) -> str:
    c = counts(status["features"])
    out = io.StringIO()
    out.write("## Relay readiness (docs/readiness/status.yml)\n\n")
    out.write(f"Status date **{status['status_date']}**, commit `{status['source_commit']}`\n\n")
    out.write("| Axis | Count |\n|---|---|\n")
    out.write(f"| Features tracked | {c['total']} |\n")
    out.write(f"| IMPLEMENTED | {c['implemented']} |\n")
    out.write(f"| AUTOMATED_TESTED | {c['automated_tested']} |\n")
    out.write(f"| EMULATOR_TESTED | {c['emulator_tested']} |\n")
    out.write(f"| DEVICE_TESTED | {c['device_tested']} |\n")
    out.write(f"| FIELD_TESTED | {c['field_tested']} |\n")
    out.write(f"| Blocked on external decisions | {c['blocked_external']} |\n\n")
    if c["device_tested"] == 0:
        out.write("> No feature is DEVICE_TESTED. Automated results never imply device readiness.\n")
    return out.getvalue()


def replace_readme_block(readme_text: str, block: str) -> str | None:
    begin = readme_text.find(README_BEGIN)
    end = readme_text.find(README_END)
    if begin == -1 or end == -1 or end < begin:
        return None
    end += len(README_END)
    return readme_text[:begin] + block + readme_text[end:]


def derived_outputs(status: dict) -> dict[Path, str]:
    return {
        TABLE_PATH: render_table(status),
        OPEN_ITEMS_PATH: render_open_items(status),
        MUNICIPAL_PATH: render_municipal(status),
        EVIDENCE_PATH: render_evidence(status),
    }


def cmd_generate(status: dict) -> int:
    for path, content in derived_outputs(status).items():
        path.write_text(content, encoding="utf-8", newline="\n")
        print(f"generated {path.relative_to(REPO_ROOT)}")
    readme = README_PATH.read_text(encoding="utf-8")
    updated = replace_readme_block(readme, render_readme_block(status))
    if updated is None:
        print("ERROR: README.md is missing the generated readiness-summary markers", file=sys.stderr)
        return 1
    if updated != readme:
        README_PATH.write_text(updated, encoding="utf-8", newline="\n")
        print("updated README.md generated block")
    else:
        print("README.md generated block already up to date")
    return 0


def cmd_check(status: dict) -> int:
    """Fail when derived documents drift from status.yml. Never auto-fixes."""
    drift: list[str] = []
    for path, expected in derived_outputs(status).items():
        rel = path.relative_to(REPO_ROOT).as_posix()
        if not path.exists():
            drift.append(f"{rel}: missing (run generate)")
            continue
        if path.read_text(encoding="utf-8").replace("\r\n", "\n") != expected:
            drift.append(f"{rel}: content drifted from status.yml")
    readme = README_PATH.read_text(encoding="utf-8")
    expected_readme = replace_readme_block(readme.replace("\r\n", "\n"), render_readme_block(status))
    if expected_readme is None:
        drift.append("README.md: generated readiness-summary markers are missing")
    elif expected_readme != readme.replace("\r\n", "\n"):
        drift.append("README.md: generated readiness-summary block drifted from status.yml")
    if drift:
        print("READINESS DRIFT DETECTED (hand-written docs contradict status.yml):", file=sys.stderr)
        for item in drift:
            print(f"  - {item}", file=sys.stderr)
        print("Fix: edit docs/readiness/status.yml, then run "
              "'python tools/readiness/readiness_tool.py generate' and commit.", file=sys.stderr)
        return 1
    print("readiness check OK: derived documents match status.yml")
    return 0


def cmd_summary(status: dict) -> int:
    summary = render_job_summary(status)
    print(summary)
    import os
    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        with open(step_summary, "a", encoding="utf-8") as fh:
            fh.write(summary)
    return 0


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["validate", "generate", "check", "summary"])
    args = parser.parse_args()

    status = load_status()
    errors = validate(status)
    if errors:
        print(f"status.yml validation FAILED ({len(errors)} error(s)):", file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        return 1
    if args.command == "validate":
        print(f"status.yml validation OK ({len(status['features'])} features)")
        return 0
    if args.command == "generate":
        return cmd_generate(status)
    if args.command == "check":
        return cmd_check(status)
    if args.command == "summary":
        return cmd_summary(status)
    return 2


if __name__ == "__main__":
    sys.exit(main())
