# iOS Build Status

## Toolchain

| Component | Before | After | Rationale |
|-----------|--------|-------|-----------|
| Kotlin | 2.2.10 | 2.2.21 | Minimum for Xcode 26 support (kotlin-native) |
| KSP | 2.2.10-2.0.2 | 2.2.21-2.0.5 | Matches Kotlin 2.2.21 |
| Compose Multiplatform | 1.8.2 | 1.11.1 | Latest stable compatible with Kotlin 2.2.21 |
| AGP | 8.13.2 | 8.13.2 | Unchanged — already in supported range |
| MapLibre Compose | 0.13.0 | 0.13.1 | Latest stable |

### Compatibility Evidence

- **Gate A (pre-upgrade):** All JVM tests pass on Kotlin 2.2.10 (`:shared:jvmTest`, `:app:testDebugUnitTest`, `:pc-gateway:test`, `:broker:test`, `:composeApp:desktopTest`, `:fuzz-jvm:test`)
- **Gate B (post-upgrade):** All JVM tests pass on Kotlin 2.2.21 with Compose MP 1.11.1
- **Desktop compilation:** `:composeApp:compileKotlinDesktop` confirmed green after MapLibre feature-gate

### Xcode Selection

- **Xcode 26.0** (build 17A5) pinned in `codemagic.yaml`
- Kotlin 2.2.21 is the first Kotlin version to officially support Xcode 26
- Reference: [Kotlin docs - Xcode compatibility](https://kotlinlang.org/docs/multiplatform-compatibility-guide.html)

## MapLibre iOS Feature-Gate

### Current Status: FEATURE_GATED

MapLibre Compose is **not available on iOS** in this PR. iOS displays a placeholder:

```
地図を準備中（iOS版は今後対応予定）
```

### Implementation

- `expect`/`actual` pattern in `PlatformMapView.kt`
- `isPlatformMapAvailable` flag per platform
- Android + Desktop: Full MapLibre map rendering (unchanged)
- iOS: Placeholder `Text` composable

### Why Feature-Gated

1. Official Kotlin SPM import for MapLibre iOS requires **Kotlin 2.4.20-Beta2** (incompatible with 2.2.21)
2. Third-party `spm4kmp` Gradle plugin compatibility with Kotlin 2.2.21 is unverified
3. MapLibre's CocoaPods support for Compose Multiplatform is not documented

### Re-enable Conditions

To re-enable MapLibre on iOS, ONE of these must be true:

- [ ] Upgrade to Kotlin 2.4.20+ (when stable) and use official SPM import
- [ ] Verify `spm4kmp` plugin works with current Kotlin version
- [ ] Manual XCFramework vendoring with wrapper Composable

When a path is confirmed, replace `PlatformMapView.ios.kt` placeholder with actual MapLibre rendering.

## Test Execution Status

Each capability has a distinct verification state:

| State | Meaning |
|-------|---------|
| `SOURCE_IMPLEMENTED` | Code exists in repo |
| `LOCALLY_COMPILED` | Compiles on developer machine |
| `CI_BUILD_GREEN` | Builds on Codemagic without errors |
| `UNIT_TESTED` | Covered by automated unit tests |
| `INTEGRATION_TESTED` | End-to-end test on device/simulator |
| `FIELD_VALIDATED` | Confirmed working by manual QA or user |

### Current State

| Component | Status | Notes |
|-----------|--------|-------|
| Toolchain upgrade | `UNIT_TESTED` | Gate A/B passed on JVM targets |
| MapLibre feature-gate | `LOCALLY_COMPILED` | Desktop + Android confirmed, iOS needs macOS |
| iOS host app (SwiftUI) | `SOURCE_IMPLEMENTED` | Cannot build on Windows (needs Xcode) |
| XcodeGen project | `SOURCE_IMPLEMENTED` | Validated structure, needs macOS to generate |
| RelayAppleKit integration | `SOURCE_IMPLEMENTED` | Local package reference in project.yml |
| Codemagic simulator workflow | `SOURCE_IMPLEMENTED` | YAML validated, needs first CI run |
| Codemagic signed archive | `SOURCE_IMPLEMENTED` | YAML validated, needs signing credentials |

## Known Issues

1. **iOS targets gated behind `isMac`**: In `composeApp/build.gradle.kts`, iOS targets are only registered when building on macOS (`if (isMac)`). This means iOS-specific Gradle tasks are unavailable on Windows/Linux. The Codemagic workflow handles this by running on `mac_mini_m2`.

2. **Privacy Manifest**: Apple requires a privacy manifest (`PrivacyInfo.xcprivacy`) for apps using certain APIs. This needs to be audited once the app builds on Codemagic. Not falsely set in this PR.

3. **`ITSAppUsesNonExemptEncryption`**: Must be set correctly before App Store submission. Left for human review — not set to `false` without proper audit.

## Next PR Candidates

- [ ] First successful Codemagic build (trigger workflow, capture artifacts)
- [ ] RelayAppleKit CoreBluetooth integration (thin facade)
- [ ] MapLibre iOS re-enablement (when toolchain permits)
- [ ] Privacy Manifest audit
- [ ] Push notification setup (APNs)
- [ ] App icon design asset
