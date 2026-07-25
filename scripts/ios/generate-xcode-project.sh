#!/usr/bin/env bash
# Generate the Xcode project from project.yml using XcodeGen.
# Usage: ./scripts/ios/generate-xcode-project.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
IOS_APP_DIR="${REPO_ROOT}/composeApp/iosApp"

echo "=== Generating Xcode Project ==="
echo "Project spec: ${IOS_APP_DIR}/project.yml"

# Verify XcodeGen is available
if ! command -v xcodegen &>/dev/null; then
  echo "ERROR: xcodegen not found. Run scripts/ios/install-xcodegen.sh first."
  exit 1
fi

echo "XcodeGen version: $(xcodegen version)"

# Generate
cd "$IOS_APP_DIR"
xcodegen generate

echo ""
echo "=== Validating generated project ==="

# Verify .xcodeproj exists
if [ ! -d "RelayIOS.xcodeproj" ]; then
  echo "ERROR: RelayIOS.xcodeproj not generated"
  exit 1
fi
echo "OK: RelayIOS.xcodeproj exists"

# Verify scheme
xcodebuild -list -project RelayIOS.xcodeproj 2>/dev/null | head -20
echo ""

# Verify local Swift Package resolution
echo "Checking RelayAppleKit package reference..."
if [ -d "${REPO_ROOT}/apple/RelayAppleKit/Package.swift" ] || [ -f "${REPO_ROOT}/apple/RelayAppleKit/Package.swift" ]; then
  echo "OK: RelayAppleKit Package.swift exists at expected path"
else
  echo "WARNING: RelayAppleKit Package.swift not found at ${REPO_ROOT}/apple/RelayAppleKit/Package.swift"
fi

echo ""
echo "=== Xcode project generation complete ==="
