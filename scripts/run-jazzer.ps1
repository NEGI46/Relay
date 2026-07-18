[CmdletBinding()]
param([ValidateSet('report-only','block-high-critical')][string]$Mode = 'report-only',[string]$OutputPath='artifacts/jazzer')
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $OutputPath | Out-Null
$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) {
  $candidate = Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
  if (Test-Path $candidate) { $python = $candidate }
}
if ($python) {
  $env:PYTHONPATH = (Join-Path (Get-Location) 'tools/ble-sim')
  & $python -m unittest discover tools/ble-sim -p 'test_*.py' 2>&1 | Tee-Object (Join-Path $OutputPath 'ble-sim.txt')
  if ($LASTEXITCODE -ne 0) { throw 'BLE decoder regression tests failed.' }
  & $python -m unittest discover test-lab/fuzz -p '*_test.py' 2>&1 | Tee-Object (Join-Path $OutputPath 'decoder-regression.txt')
  if ($LASTEXITCODE -ne 0) { throw 'Decoder regression corpus failed.' }
  & $python tools/ble-sim/run_tests.py 2>&1 | Tee-Object (Join-Path $OutputPath 'ble-sim-junit.txt')
  Copy-Item tools/ble-sim/test-results/ble-sim.xml (Join-Path $OutputPath 'ble-sim.xml') -Force
} else {
  Write-Warning 'Python is not installed; host fuzz regression lane skipped.'
  if ($Mode -eq 'block-high-critical') { throw 'Fuzz regression requires Python.' }
}
if ($env:JAZZER_FUZZ -eq '1') {
  if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'JAZZER_FUZZ=1 requires Java.' }
  if (-not (Test-Path 'gradlew') -and -not (Test-Path 'gradlew.bat')) { throw 'JAZZER_FUZZ=1 requires Gradle wrapper.' }
  Write-Warning 'JAZZER_FUZZ=1 requested, but no JVM Jazzer target is configured; host regression completed.'
}
