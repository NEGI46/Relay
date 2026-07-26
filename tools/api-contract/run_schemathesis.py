#!/usr/bin/env python3
"""Relay Broker API contract gate (Schemathesis).

Boots the real Broker distribution (installDist output) with a DEVELOPMENT
profile against a throwaway SQLite database, then runs Schemathesis against
docs/api/broker-openapi.yaml. Any contract violation (undocumented status
code, response schema drift, wrong content type, 5xx) fails the process, so
CI is fail-closed on spec/behavior drift.

Security notes:
- The legacy shared Gateway key is generated randomly per run, passed only
  via environment/header, and never printed.
- DEVELOPMENT profile is required for the legacy key; production refuses it.
- Positive-path coverage for signature-requiring endpoints (device register /
  envelope upload with real ECDSA proofs) lives in the Kotlin integration
  tests; here those endpoints are exercised for contract conformance of their
  validation and auth responses (400/401/409/411/413/429).

Usage:
  python tools/api-contract/run_schemathesis.py [--max-examples N] [--skip-build]
"""

from __future__ import annotations

import argparse
import os
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SPEC_PATH = REPO_ROOT / "docs" / "api" / "broker-openapi.yaml"
REPORT_DIR = REPO_ROOT / "build" / "api-contract"

# Contract-conformance checks only. positive_data_acceptance / negative_data_rejection
# are deliberately excluded: signature-requiring endpoints legitimately answer 401 to
# generated positive data, and that is exactly what the spec documents.
CHECKS = ",".join(
    [
        "not_a_server_error",
        "status_code_conformance",
        "content_type_conformance",
        "response_headers_conformance",
        "response_schema_conformance",
    ]
)


def find_free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def broker_launcher() -> Path:
    bin_dir = REPO_ROOT / "broker" / "build" / "install" / "broker" / "bin"
    launcher = bin_dir / ("broker.bat" if os.name == "nt" else "broker")
    if not launcher.exists():
        raise SystemExit(
            f"Broker distribution not found at {launcher}; run gradlew :broker:installDist first"
        )
    return launcher


def schemathesis_executable() -> str:
    found = shutil.which("schemathesis")
    if found:
        return found
    candidate = Path(sys.executable).parent / "Scripts" / "schemathesis.exe"
    if candidate.exists():
        return str(candidate)
    raise SystemExit("schemathesis CLI not found; pip install schemathesis")


def wait_for_health(base_url: str, timeout_seconds: float = 60.0) -> None:
    deadline = time.monotonic() + timeout_seconds
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(f"{base_url}/v1/health", timeout=2) as response:
                if response.status == 200:
                    return
        except Exception as error:  # noqa: BLE001 - retry until deadline
            last_error = error
        time.sleep(0.5)
    raise SystemExit(f"Broker did not become healthy at {base_url}: {last_error}")


def stop_broker(broker: subprocess.Popen) -> None:
    if os.name == "nt":
        # broker.bat spawns java as a child; terminate() would orphan it and
        # leave broker.db locked. taskkill /T fells the whole tree.
        subprocess.run(
            ["taskkill", "/PID", str(broker.pid), "/T", "/F"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
    else:
        broker.terminate()
    try:
        broker.wait(timeout=15)
    except subprocess.TimeoutExpired:
        broker.kill()


def run(args: argparse.Namespace) -> int:
    if not SPEC_PATH.exists():
        raise SystemExit(f"OpenAPI spec not found: {SPEC_PATH}")

    if not args.skip_build:
        gradlew = REPO_ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
        build = subprocess.run(
            [str(gradlew), ":broker:installDist", "--no-daemon"],
            cwd=REPO_ROOT,
            check=False,
        )
        if build.returncode != 0:
            return build.returncode

    port = find_free_port()
    base_url = f"http://127.0.0.1:{port}"
    # Test-only shared key: random per run, never logged.
    legacy_key = secrets.token_urlsafe(32)

    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    # ignore_cleanup_errors: on Windows the JVM may release broker.db a moment late.
    with tempfile.TemporaryDirectory(prefix="relay-broker-contract-", ignore_cleanup_errors=True) as tmp:
        env = os.environ.copy()
        env.update(
            {
                "RELAY_BROKER_PROFILE": "development",
                "RELAY_BROKER_HOST": "127.0.0.1",
                "RELAY_BROKER_PORT": str(port),
                "RELAY_BROKER_DB_PATH": str(Path(tmp) / "broker.db"),
                "RELAY_BROKER_GATEWAY_API_KEY": legacy_key,
            }
        )
        broker = subprocess.Popen(
            [str(broker_launcher())],
            cwd=REPO_ROOT,
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        try:
            wait_for_health(base_url)
            command = [
                schemathesis_executable(),
                "run",
                str(SPEC_PATH),
                "--url",
                base_url,
                "--checks",
                CHECKS,
                "--max-examples",
                str(args.max_examples),
                "--seed",
                str(args.seed),
                # DEVELOPMENT legacy key authenticates every Gateway-scoped route;
                # /v1/receipts rejects it as an unknown capability token (documented 401).
                "--header",
                f"Authorization: Bearer {legacy_key}",
                "--report",
                "junit",
                "--report-junit-path",
                str(REPORT_DIR / "schemathesis-junit.xml"),
                "--no-color",
            ]
            # Force UTF-8 stdio: schemathesis emits non-cp932 glyphs on Windows consoles.
            st_env = os.environ.copy()
            st_env["PYTHONUTF8"] = "1"
            st_env["PYTHONIOENCODING"] = "utf-8"
            result = subprocess.run(command, cwd=REPO_ROOT, env=st_env, check=False)
            return result.returncode
        finally:
            stop_broker(broker)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--max-examples", type=int, default=25)
    parser.add_argument("--seed", type=int, default=20260726)
    parser.add_argument(
        "--skip-build",
        action="store_true",
        help="assume broker/build/install/broker already exists",
    )
    return run(parser.parse_args())


if __name__ == "__main__":
    raise SystemExit(main())
