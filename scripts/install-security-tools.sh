#!/usr/bin/env bash
# Install the exact scanner versions used by Relay CI into an isolated directory.
#
# The versions below are intentionally source/version pins. Go verifies downloaded modules
# through the public checksum database; this script refuses to use a custom checksum database
# or proxy so scanner availability cannot silently degrade into an unverified download.
set -euo pipefail

readonly syft_module='github.com/anchore/syft/cmd/syft'
readonly syft_root_module="${syft_module%/cmd/syft}"
readonly syft_version='v1.20.0'
readonly osv_module='github.com/google/osv-scanner/v2/cmd/osv-scanner'
readonly osv_version='v2.0.3'
readonly grype_module='github.com/anchore/grype/cmd/grype'
readonly grype_root_module="${grype_module%/cmd/grype}"
readonly grype_version='v0.116.0'

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

# `go install package@version` does not populate Syft's build-time version variable.
# Build the checksum-verified source module instead so the version check below attests the
# actual pinned scanner release rather than an unversioned binary.
syft_source_dir="$(go env GOMODCACHE)/$syft_root_module@$syft_version"
go mod download "$syft_root_module@$syft_version"
if [[ ! -f "$syft_source_dir/go.mod" ]]; then
  echo "Security scanner bootstrap BLOCKED: verified Syft source is unavailable." >&2
  exit 1
fi
(
  cd "$syft_source_dir"
  go build -trimpath -buildvcs=false -ldflags="-X main.version=${syft_version#v}" -o "$GOBIN/syft" ./cmd/syft
)

go install "$osv_module@$osv_version"

# Go deliberately rejects `go install package@version` when the target module has a
# replace directive. Grype v0.80.0 has one for its version-pinned archiver dependency, so
# download the checksum-verified module and build it as the main module instead.
grype_source_dir="$(go env GOMODCACHE)/$grype_root_module@$grype_version"
go mod download "$grype_root_module@$grype_version"
if [[ ! -f "$grype_source_dir/go.mod" ]]; then
  echo "Security scanner bootstrap BLOCKED: verified Grype source is unavailable." >&2
  exit 1
fi
(
  cd "$grype_source_dir"
  go build -trimpath -buildvcs=false -ldflags="-X main.version=${grype_version#v}" -o "$GOBIN/grype" ./cmd/grype
)

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
check_version osv-scanner "${osv_version#v}"
check_version grype "${grype_version#v}"

if [[ -n "${GITHUB_PATH:-}" ]]; then
  # GitHub Actions makes this available to subsequent steps, while this script keeps using
  # the explicit GOBIN path for its own verification.
  printf '%s\n' "$GOBIN" >> "$GITHUB_PATH"
fi

if [[ -n "${GITHUB_ENV:-}" ]]; then
  # The scan scripts use this explicit directory instead of an image-provided scanner that
  # happens to appear earlier on PATH.
  printf 'RELAY_SECURITY_TOOL_DIR=%s\n' "$GOBIN" >> "$GITHUB_ENV"
fi
