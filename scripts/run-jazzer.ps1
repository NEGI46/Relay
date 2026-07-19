[CmdletBinding()]
param([ValidateSet('report-only','block-high-critical')][string]$Mode = 'report-only',[string]$OutputPath='artifacts/jazzer',[switch]$RequirePython)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $OutputPath | Out-Null
$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) {
  $candidate = Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
  if (Test-Path $candidate) { $python = $candidate }
}
if ($python) {
  $env:PYTHONPATH = (Join-Path (Get-Location) 'tools/ble-sim')
  # Python's unittest writes normal progress and skips to stderr. Capture that stream in
  # the report without converting a successful optional skip into a PowerShell error record.
  function Invoke-PythonRegression([string[]]$Arguments, [string]$ReportPath) {
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    & $python @Arguments 2>&1 | Tee-Object -FilePath $ReportPath
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorActionPreference
    return $exitCode
  }
  $exitCode = Invoke-PythonRegression @('-m', 'unittest', 'discover', 'tools/ble-sim', '-p', 'test_*.py') (Join-Path $OutputPath 'ble-sim.txt')
  if ($exitCode -ne 0) { throw 'BLE decoder regression tests failed.' }
  $exitCode = Invoke-PythonRegression @('-m', 'unittest', 'discover', 'test-lab/fuzz', '-p', '*_test.py') (Join-Path $OutputPath 'decoder-regression.txt')
  if ($exitCode -ne 0) { throw 'Decoder regression corpus failed.' }
  $exitCode = Invoke-PythonRegression @('tools/ble-sim/run_tests.py') (Join-Path $OutputPath 'ble-sim-junit.txt')
  if ($exitCode -ne 0) { throw 'BLE decoder regression tests failed.' }
  Copy-Item tools/ble-sim/test-results/ble-sim.xml (Join-Path $OutputPath 'ble-sim.xml') -Force
  @{status='PASS';coverage='deterministic-decoder-and-virtual-ble-regression';jazzerTarget='NOT_RUN'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputPath 'status.json')
} else {
  $message = 'Python is not installed; deterministic decoder regression is BLOCKED.'
  @{status='BLOCKED';reason='python-not-installed'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputPath 'status.json')
  if ($Mode -eq 'block-high-critical' -or $RequirePython) { throw $message }
  Write-Warning $message
}
if ($env:JAZZER_FUZZ -eq '1') {
  throw 'JAZZER_FUZZ=1 requested, but no JVM Jazzer target is configured. Deterministic regression is not Jazzer fuzzing.'
}
