# Maestro and accessibility lane

The four flows under `/maestro` are release-safety smoke flows. Each captures
a screenshot and asserts that Relay never claims a 119 dispatch merely because
a local report or rescue request was created. Run them on a configured debug
emulator with:

```powershell
maestro test maestro/first_launch.yaml maestro/create_safety_report.yaml maestro/create_rescue_request.yaml maestro/regional_feed.yaml
```

The text selectors are the UI contract and must be updated together with the
localized Compose semantics. The flows are intentionally not considered a
substitute for real Android device testing; font scale, dark mode, rotation,
and locale variants are matrix inputs in the device-lab checklist.
