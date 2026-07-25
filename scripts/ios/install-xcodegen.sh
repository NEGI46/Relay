#!/usr/bin/env bash
# Install a pinned version of XcodeGen for reproducible Xcode project generation.
# Usage: ./scripts/ios/install-xcodegen.sh
set -euo pipefail

XCODEGEN_VERSION="2.45.0"
XCODEGEN_REPO="https://github.com/yonaskolb/XcodeGen"
INSTALL_DIR="${HOME}/.local/bin"

echo "=== Installing XcodeGen ${XCODEGEN_VERSION} ==="

# Check if already installed at correct version
if command -v xcodegen &>/dev/null; then
  CURRENT_VERSION=$(xcodegen version 2>/dev/null || echo "unknown")
  if [ "$CURRENT_VERSION" = "$XCODEGEN_VERSION" ]; then
    echo "XcodeGen ${XCODEGEN_VERSION} already installed."
    exit 0
  fi
  echo "Found XcodeGen ${CURRENT_VERSION}, upgrading to ${XCODEGEN_VERSION}..."
fi

# Download pre-built binary from GitHub Releases
DOWNLOAD_URL="${XCODEGEN_REPO}/releases/download/${XCODEGEN_VERSION}/xcodegen.zip"
TMPDIR_XCODEGEN=$(mktemp -d)
trap 'rm -rf "$TMPDIR_XCODEGEN"' EXIT

echo "Downloading from: ${DOWNLOAD_URL}"
curl -fsSL -o "${TMPDIR_XCODEGEN}/xcodegen.zip" "$DOWNLOAD_URL"

# Extract
echo "Extracting..."
unzip -q "${TMPDIR_XCODEGEN}/xcodegen.zip" -d "${TMPDIR_XCODEGEN}"

# Install
mkdir -p "${INSTALL_DIR}"
if [ -f "${TMPDIR_XCODEGEN}/xcodegen" ]; then
  cp "${TMPDIR_XCODEGEN}/xcodegen" "${INSTALL_DIR}/xcodegen"
elif [ -d "${TMPDIR_XCODEGEN}/XcodeGen" ]; then
  # Some releases bundle as directory
  cp "${TMPDIR_XCODEGEN}/XcodeGen/bin/xcodegen" "${INSTALL_DIR}/xcodegen"
else
  # Fallback: build from source via Homebrew or Mint
  echo "Pre-built binary not found in archive. Trying Homebrew..."
  if command -v brew &>/dev/null; then
    brew install xcodegen
    echo "Installed via Homebrew."
    xcodegen version
    exit 0
  fi
  echo "ERROR: Could not install XcodeGen ${XCODEGEN_VERSION}"
  exit 1
fi

chmod +x "${INSTALL_DIR}/xcodegen"

# Add to PATH if not present
if [[ ":$PATH:" != *":${INSTALL_DIR}:"* ]]; then
  export PATH="${INSTALL_DIR}:$PATH"
  echo "Added ${INSTALL_DIR} to PATH for this session."
  echo "Add 'export PATH=\"${INSTALL_DIR}:\$PATH\"' to your shell profile for permanence."
fi

echo "=== XcodeGen installed ==="
xcodegen version
