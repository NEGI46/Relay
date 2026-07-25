#!/usr/bin/env bash
# Validate codemagic.yaml and project configuration.
# Runs on any platform (macOS/Linux/WSL). No Codemagic account required.
# Usage: ./scripts/ios/validate-codemagic-config.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

ERRORS=0
WARNINGS=0

pass() { echo "  PASS: $1"; }
fail() { echo "  FAIL: $1"; ERRORS=$((ERRORS + 1)); }
warn() { echo "  WARN: $1"; WARNINGS=$((WARNINGS + 1)); }

echo "=== Relay iOS Configuration Validation ==="
echo ""

# --- 1. codemagic.yaml exists ---
echo "[1] codemagic.yaml"
CODEMAGIC_YAML="${REPO_ROOT}/codemagic.yaml"
if [ -f "$CODEMAGIC_YAML" ]; then
  pass "codemagic.yaml exists"
else
  fail "codemagic.yaml not found at repo root"
fi

# --- 2. YAML syntax ---
echo "[2] YAML syntax"
if command -v python3 &>/dev/null; then
  if python3 -c "import yaml; yaml.safe_load(open('${CODEMAGIC_YAML}'))" 2>/dev/null; then
    pass "YAML syntax valid"
  else
    fail "YAML syntax error in codemagic.yaml"
  fi
elif command -v ruby &>/dev/null; then
  if ruby -e "require 'yaml'; YAML.load_file('${CODEMAGIC_YAML}')" 2>/dev/null; then
    pass "YAML syntax valid (ruby)"
  else
    fail "YAML syntax error (ruby)"
  fi
else
  warn "No YAML parser available (python3 or ruby). Skipping syntax check."
fi

# --- 3. Required workflow names ---
echo "[3] Required workflows"
if grep -q "ios-simulator-smoke:" "$CODEMAGIC_YAML"; then
  pass "ios-simulator-smoke workflow present"
else
  fail "ios-simulator-smoke workflow missing"
fi
if grep -q "ios-signed-archive:" "$CODEMAGIC_YAML"; then
  pass "ios-signed-archive workflow present"
else
  fail "ios-signed-archive workflow missing"
fi

# --- 4. No secrets ---
echo "[4] Secret scan"
SECRET_PATTERNS='(PRIVATE_KEY|-----BEGIN|password|secret.*=.*[A-Za-z0-9+/]{20})'
if grep -iEr "$SECRET_PATTERNS" "$CODEMAGIC_YAML" 2>/dev/null | grep -v "^#" | grep -v "REPLACE_ME" | head -5; then
  fail "Possible secret found in codemagic.yaml"
else
  pass "No obvious secrets in codemagic.yaml"
fi

# --- 5. No 'xcode: latest' ---
echo "[5] Xcode version pinned"
if grep -q 'xcode:.*latest' "$CODEMAGIC_YAML"; then
  fail "'xcode: latest' found — must pin a specific version"
else
  pass "Xcode version is pinned (not 'latest')"
fi

# --- 6. No publishing.app_store_connect ---
echo "[6] No App Store Connect publishing"
if grep -q "app_store_connect" "$CODEMAGIC_YAML"; then
  fail "app_store_connect publishing found — must not auto-publish"
else
  pass "No app_store_connect publishing configured"
fi

# --- 7. project.yml exists ---
echo "[7] XcodeGen project.yml"
PROJECT_YML="${REPO_ROOT}/composeApp/iosApp/project.yml"
if [ -f "$PROJECT_YML" ]; then
  pass "project.yml exists"
else
  fail "project.yml not found"
fi

# --- 8. Bundle ID consistency ---
echo "[8] Bundle ID consistency"
BUNDLE_FROM_CODEMAGIC=$(grep -oP 'bundle_identifier:\s*\K\S+' "$CODEMAGIC_YAML" 2>/dev/null | head -1 || true)
BUNDLE_FROM_PROJECT=$(grep -oP 'PRODUCT_BUNDLE_IDENTIFIER:\s*\K\S+' "$PROJECT_YML" 2>/dev/null | head -1 || true)
if [ -n "$BUNDLE_FROM_CODEMAGIC" ] && [ -n "$BUNDLE_FROM_PROJECT" ]; then
  if [ "$BUNDLE_FROM_CODEMAGIC" = "$BUNDLE_FROM_PROJECT" ]; then
    pass "Bundle ID consistent: $BUNDLE_FROM_CODEMAGIC"
  else
    fail "Bundle ID mismatch: codemagic=$BUNDLE_FROM_CODEMAGIC project=$BUNDLE_FROM_PROJECT"
  fi
else
  warn "Could not extract bundle IDs for comparison"
fi

# --- 9. Shell scripts syntax (bash -n) ---
echo "[9] Shell script syntax"
for script in "${REPO_ROOT}"/scripts/ios/*.sh; do
  if [ -f "$script" ]; then
    if bash -n "$script" 2>/dev/null; then
      pass "$(basename "$script") syntax OK"
    else
      fail "$(basename "$script") has syntax errors"
    fi
  fi
done

# --- 10. No .p8 / .p12 / .mobileprovision committed ---
echo "[10] No signing artifacts in repo"
if find "$REPO_ROOT" -name "*.p8" -o -name "*.p12" -o -name "*.mobileprovision" 2>/dev/null | grep -q .; then
  fail "Signing artifact (.p8/.p12/.mobileprovision) found in repo"
else
  pass "No signing artifacts found"
fi

echo ""
echo "=== Results: ${ERRORS} errors, ${WARNINGS} warnings ==="
if [ "$ERRORS" -gt 0 ]; then
  echo "VALIDATION FAILED"
  exit 1
fi

echo "VALIDATION PASSED"
echo ""
echo "NOTE: This validates configuration structure only."
echo "Codemagic JSON Schema cannot verify: Xcode version existence,"
echo "signing credentials, artifact existence, or network access."
