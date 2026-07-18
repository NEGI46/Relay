"""Compatibility entry point for the deterministic BLE simulator test lane."""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, os.fspath(ROOT))

from ble_sim import _run_unittest  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--junit-xml", type=Path, default=ROOT / "test-results" / "ble-sim.xml")
    return _run_unittest(parser.parse_args().junit_xml)


if __name__ == "__main__":
    raise SystemExit(main())
