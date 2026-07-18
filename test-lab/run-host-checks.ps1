[CmdletBinding()]
param([switch]$IncludeGradle)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) {
    $candidate = Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
    if (Test-Path $candidate) { $python = $candidate } else { throw 'Python 3 is required for host checks.' }
} else { $python = $python.Source }

Push-Location $root
try {
    & $python 'test-lab/fault_injection/run_fault_matrix.py'
    $env:PYTHONPATH = "$root/gateway-meshtastic-adapter;$root/gateway-bp7-export"
    $sample = '{"requestId":"host-check","shelterId":"shelter","urgency":"HIGH","coarseLocation":"north","payloadHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","createdAt":9999999999,"ttlSeconds":900}'
    $sample | & $python -m gateway_meshtastic_adapter.main --mock | Out-Null
    & $python -m unittest discover -s gateway-meshtastic-adapter/tests -p 'test_*.py' -v
    if ($IncludeGradle) {
        & .\gradlew.bat :shared:jvmTest :composeApp:desktopTest --no-daemon
    }
    Write-Output 'Relay host checks passed.'
} finally { Pop-Location }
