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
    # Do not leak the captured test output through this function: otherwise the
    # caller receives an array of output lines instead of only the exit code.
    $null = & $python @Arguments 2>&1 | Tee-Object -FilePath $ReportPath
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
  @{status='PASS';coverage='deterministic-decoder-and-virtual-ble-regression';jazzerTarget='see jvm-status.json (:fuzz-jvm real Jazzer targets)'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputPath 'status.json')
} else {
  $message = 'Python is not installed; deterministic decoder regression is BLOCKED.'
  @{status='BLOCKED';reason='python-not-installed'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputPath 'status.json')
  if ($Mode -eq 'block-high-critical' -or $RequirePython) { throw $message }
  Write-Warning $message
}
# Real JVM Jazzer regression lane. The @FuzzTest targets in :fuzz-jvm call the production QR,
# rescue-envelope and gateway-DTO decoders and run the committed seed corpus deterministically
# (each seed once). This is cross-platform and needs no libFuzzer driver. Continuous, coverage-
# guided fuzzing is an opt-in lane (JAZZER_FUZZ=1); the Jazzer driver only supports Linux/macOS.
$onWindows = $env:OS -eq 'Windows_NT'
$continuous = $env:JAZZER_FUZZ -eq '1'
if ($continuous -and $onWindows) {
  throw 'JAZZER_FUZZ=1 continuous fuzzing is unsupported on Windows (the Jazzer libFuzzer driver is Linux/macOS only). Unset JAZZER_FUZZ to run the deterministic :fuzz-jvm regression lane here, or run continuous fuzzing on Linux/macOS CI.'
}
$gradlewName = if ($onWindows) { 'gradlew.bat' } else { 'gradlew' }
$gradlew = Join-Path (Get-Location) $gradlewName
if (Test-Path $gradlew) {
  $jvmLog = Join-Path $OutputPath 'jvm-jazzer.txt'
  $previousEap = $ErrorActionPreference
  # Java/Gradle write progress and warnings to stderr; capture them without turning a successful
  # run into a PowerShell terminating error.
  $ErrorActionPreference = 'Continue'
  & $gradlew ':fuzz-jvm:test' '--no-daemon' '--console=plain' 2>&1 | Tee-Object -FilePath $jvmLog | Out-Null
  $jvmExit = $LASTEXITCODE
  $ErrorActionPreference = $previousEap
  if ($jvmExit -ne 0) { throw "JVM Jazzer regression (:fuzz-jvm:test) failed with exit code $jvmExit." }
  $jvmMode = if ($continuous) { 'continuous' } else { 'regression' }
  @{status='PASS';coverage="jvm-jazzer-$jvmMode";jazzerTarget='EnvelopeDecoderFuzzTest,GatewayDtoFuzzTest,QrFrameFuzzTest'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputPath 'jvm-status.json')
} else {
  @{status='BLOCKED';reason='gradlew-not-found'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputPath 'jvm-status.json')
  $message = 'gradlew not found; JVM Jazzer regression is BLOCKED.'
  if ($Mode -eq 'block-high-critical') { throw $message }
  Write-Warning $message
}
