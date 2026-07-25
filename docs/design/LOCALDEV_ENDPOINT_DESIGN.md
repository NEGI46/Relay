# localDev Endpoint Improvement Design

> Status: **DESIGN ONLY** — Separate PR candidate.  
> This document describes the proposed improvement. No production code was changed.

## Current State

- Broker endpoint URL is configured at APK build time via `build.gradle.kts`
- `release` and `pilotRelease` variants use hardcoded HTTPS endpoint (fail-closed)
- `debug` variant uses a build-time-configurable endpoint with HTTP allowed
- There is no runtime configuration for development endpoints

## Problem

Developers must rebuild the APK to change the development Broker endpoint, which slows iteration
when testing against local Broker instances or different staging environments.

## Proposed Solution: Development Config Import

### Approach: QR-based development config import (debug-only)

A debug-only QR reader that imports development configuration without weakening release security.

### Design

```
relay-dev-cfg:1:<json-base64>:<sha256-first-8>
```

JSON payload:
```json
{
  "brokerUrl": "http://192.168.1.100:8080",
  "gatewayUrl": "https://192.168.1.100:8443",
  "label": "Local dev server"
}
```

### Security Constraints

1. **Only available in `debug` and `localDev` build variants** — stripped from `release` and `pilotRelease`
2. **No HTTPS bypass in release** — HTTP endpoints only accepted in debug
3. **No credentials in QR** — QR contains only public endpoint URLs
4. **No auto-apply** — User must confirm before applying config
5. **Visual indicator** — When non-default config is active, show persistent banner
6. **Config does not persist across reinstall** — stored in SharedPreferences with `MODE_PRIVATE`
7. **Checksum validation** — SHA-256 checksum prevents corruption
8. **Clear separation from Gateway enrollment** — different prefix scheme (`relay-dev-cfg:` vs `relay-gw:`)

### Implementation Plan (for separate PR)

1. Create `DevConfigCodec` in `app/src/debug/` source set (not in `main/`)
2. Add `DevConfigImportScreen` Composable in debug source set
3. Store config in debug-only `DevPreferences` wrapper
4. Add `DevConfigBanner` Composable shown when non-default config is active
5. Modify `RelayHttpClient` to check `DevPreferences` in debug builds only
6. Add unit tests for codec validation (checksum, schema, bounds)
7. Verify via `./gradlew :app:assembleRelease` that no dev-config code leaks to release

### What This Does NOT Do

- Does NOT weaken release endpoint security
- Does NOT allow runtime URL changes in production
- Does NOT include credentials in QR
- Does NOT auto-apply without user confirmation
- Does NOT mix with Gateway enrollment trust model
- Does NOT send dev config to analytics or logging

### Files to Create (separate PR)

```
app/src/debug/java/com/example/relay/dev/DevConfigCodec.kt
app/src/debug/java/com/example/relay/dev/DevPreferences.kt
app/src/debug/java/com/example/relay/dev/DevConfigImportScreen.kt
app/src/debug/java/com/example/relay/dev/DevConfigBanner.kt
app/src/test/java/com/example/relay/dev/DevConfigCodecTest.kt
```

### Verification

- `./gradlew :app:assembleRelease` must not contain any `DevConfig*` classes
- `./gradlew :app:assembleDebug` must contain `DevConfig*` classes
- Unit tests verify: valid import, checksum mismatch, HTTP rejection (if non-debug), schema validation
