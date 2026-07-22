# Android build variants

## `Relay-debug.apk`

This is the local end-to-end test build. It can discover a `development` PC Gateway on the same
private Wi-Fi and, only in this debug/localDev build, pin that Gateway's generated public manifest
for the local device. The pin is marked development-only and is rejected outside the explicit
development gate. It is not suitable for disaster deployment.

## `Relay-release-unsigned.apk`

This is the non-debug release build. `BuildConfig.DEBUG` is false, so the local trust-on-first-use enrollment is disabled. It is intentionally unsigned because the organization's Android signing key was not supplied. Sign it with the organization's release keystore before installation or distribution.

`Relay-release-signed.apk` is included as a packaging smoke-test artifact. It is signed with a local development-only certificate and is not an official production build. The older `Relay-release-local-signed.apk` is retained for compatibility with prior test instructions.

The Windows package follows the same distinction: `Windows/Relay.PcBleBridge-local-signed.msix` is a local self-signed smoke-test package; an organization certificate is required for the production MSIX.

The release build also requires a regionally signed shelter directory to be provisioned before a member can create a rescue request. Do not replace that requirement with the debug enrollment path.

For repeatable builds, run `build-android-artifacts.ps1`. If `RELAY_RELEASE_KEYSTORE`, `RELAY_RELEASE_ALIAS`, and the two password variables are absent, it intentionally produces only the unsigned release artifact.
