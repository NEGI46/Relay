# Emulator validation defaults

Routine Android verification for Relay should use a headless emulator by default so the workstation remains responsive:

- AVD: `Medium_Phone`
- Mode: `-no-window -no-audio -no-boot-anim -no-snapshot -no-snapshot-save`
- GPU: `swiftshader_indirect`
- Resource ceiling: 2 CPU cores and 1536 MB RAM unless a test requires more
- Keep the emulator process bounded and stop it after the verification window

Use a physical device only when the behavior depends on real Bluetooth/RF, multi-device discovery, or other hardware-specific behavior that cannot be represented by an emulator.
