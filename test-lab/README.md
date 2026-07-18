
# Relay test lab

Host-only deterministic runners and device-lab contracts. This directory does
not change production code, CI workflows, or distribution scripts.

Run the host oracle:

```powershell
python test-lab/fault_injection/run_fault_matrix.py
```

Device lanes are documented under `mobly/`, `bumble/`, `toxiproxy/`, and
`maestro/`. Nearby radio behavior and Android accessibility still require an
emulator or physical device; those boundaries are marked explicitly.
