#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

command -v swift >/dev/null || { echo "Swift 5.9+ is required" >&2; exit 2; }
swift test --package-path apple/RelayAppleKit

if [[ "${RELAY_SKIP_KMP:-0}" != "1" ]]; then
  [[ -x ./gradlew ]] || { echo "gradlew is missing" >&2; exit 2; }
  ./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64 --no-daemon
fi

if [[ -n "${RELAY_XCODE_PROJECT:-}" ]]; then
  : "${RELAY_XCODE_SCHEME:?Set RELAY_XCODE_SCHEME when RELAY_XCODE_PROJECT is set}"
  : "${RELAY_XCODE_ARCHIVE_PATH:?Set RELAY_XCODE_ARCHIVE_PATH when RELAY_XCODE_PROJECT is set}"
  xcodebuild archive \
    -project "$RELAY_XCODE_PROJECT" \
    -scheme "$RELAY_XCODE_SCHEME" \
    -configuration Release \
    -archivePath "$RELAY_XCODE_ARCHIVE_PATH"
else
  echo "No Xcode host supplied; Swift package and simulator framework checks completed."
fi
