# Gateway recovery smoke tests

Run with the bundled Python runtime:

```powershell
$py="$env:USERPROFILE\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
& $py -m unittest discover tests/gateway-recovery -p '*_smoke.py' -v
```

The SQLite replica/restore and plaintext guard always run. The age case runs
only when both `age` and `age-keygen` are installed; a missing external binary
is an explicit skipped test, not a simulated pass.
