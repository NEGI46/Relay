#!/usr/bin/env bash
# Install the exact scanner versions used by Relay CI into an isolated directory.
#
# The versions below are intentionally source/version pins. Go verifies downloaded modules
# through the public checksum database; this script refuses to use a custom checksum database
# or proxy so scanner availability cannot silently degrade into an unverified download.
set -euo pipefail

readonly syft_module='github.com/anchore/syft/cmd/syft'
readonly syft_version='v1.20.0'
readonly osv_module='github.com/google/osv-scanner/v2/cmd/osv-scanner'
readonly osv_version='v2.0.3'
readonly grype_module='github.com/anchore/grype/cmd/grype'
readonly grype_version='v0.80.0'

tool_dir="${1:-${RUNNER_TEMP:-/tmp}/relay-security-tools}"

if ! command -v go >/dev/null 2>&1; then
  echo 'Security scanner bootstrap BLOCKED: Go is unavailable.' >&2
  exit 1
fi

# Do not inherit a private/no-checksum configuration from a runner image.
export GOPROXY='https://proxy.golang.org,direct'
export GOSUMDB='sum.golang.org'
export GONOSUMDB=''
export GOBIN="$tool_dir"
mkdir -p "$GOBIN"

go install "$syft_module@$syft_version"
go install "$osv_module@$osv_version"
go install "$grype_module@$grype_version"

check_version() {
  local binary="$1"
  local expected="$2"
  local output
  if ! output="$("$GOBIN/$binary" version 2>&1)"; then
    output="$("$GOBIN/$binary" --version 2>&1)"
  fi
  printf '%s\n' "$output"
  if ! grep -Fq "$expected" <<<"$output"; then
    echo "Security scanner bootstrap failed: $binary did not report expected $expected." >&2
    exit 1
  fi
}

check_version syft "${syft_version#v}"
check_version osv-scanner "$osv_version"
check_version grype "${grype_version#v}"

if [[ -n "${GITHUB_PATH:-}" ]]; then
  # GitHub Actions makes this available to subsequent steps, while this script keeps using
  # the explicit GOBIN path for its own verification.
  printf '%s\n' "$GOBIN" >> "$GITHUB_PATH"
fi
