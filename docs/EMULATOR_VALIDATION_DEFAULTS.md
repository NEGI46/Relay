# Emulator validation defaults

Routine Android verification for Relay should use a headless emulator by default so the workstation remains responsive:

- AVD: `Medium_Phone`
- Mode: `-no-window -no-audio -no-boot-anim -no-snapshot -no-snapshot-save`
- GPU: `swiftshader_indirect`
- Resource ceiling: 2 CPU cores and 1536 MB RAM unless a test requires more
- Keep the emulator process bounded and stop it after the verification window

Use a physical device only when the behavior depends on real Bluetooth/RF, multi-device discovery, or other hardware-specific behavior that cannot be represented by an emulator.

## Reproducible instrumentation environment

The defaults above are encoded as a Gradle Managed Device (`mediumPhoneApi36`) in
`app/build.gradle.kts`, so Gradle provisions the emulator, runs the connected
tests, and tears it down identically on a workstation and in CI:

```
./gradlew :app:mediumPhoneApi36DebugAndroidTest
```

On Windows, `scripts/run-android-instrumentation.ps1` wraps that task: it resolves
the Android SDK (from `ANDROID_HOME`, `local.properties`, then the default SDK
location), forces the headless `swiftshader_indirect` GPU, runs the suite, and
exits non-zero on any failure. A local SDK path belongs only in the gitignored
`local.properties` (`sdk.dir=...`); it is never committed.
