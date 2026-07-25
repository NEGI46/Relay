<#
.SYNOPSIS
  Windows host orchestrator for Mobly real-device and mock-fallback test execution.
.DESCRIPTION
  Detects ADB-connected devices, installs debug APK and test bridge if possible,
  then runs Mobly scenario tests (multihop, gateway sync, contract, BLE submit).
  Falls back to mock-only execution when no devices or Mobly not installed.
  Result: PASS / FAIL / BLOCKED_NO_DEVICE / BLOCKED_NO_PYTHON
#>
param(
    [switch]$MockOnly,
    [switch]$Verbose
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

$moblyDir = Join-Path $PSScriptRoot '..\..\test-lab\mobly'
$artifactDir = Get-ArtifactDir
$results = @()

# --- Check Python ---
$python = $null
foreach ($cmd in @('python', 'python3', 'py')) {
    if (Get-Command $cmd -ErrorAction SilentlyContinue) {
        $python = $cmd
        break
    }
}
if (-not $python) {
    Write-Host 'BLOCKED_NO_PYTHON: Python not found' -ForegroundColor Yellow
    Write-Summary -ArtifactDir $artifactDir -Summary @{ test = 'mobly-orchestrator'; status = 'BLOCKED_NO_PYTHON' }
    exit 0
}

# --- Check Mobly availability ---
$hasMobly = $false
try {
    $check = & $python -c "import mobly; print('ok')" 2>&1
    $hasMobly = ($check -match 'ok')
} catch { }

# --- Check ADB and devices ---
$adb = Get-AdbPath
$deviceCount = 0
if ($adb -and -not $MockOnly) {
    $devices = Get-ConnectedDevices -Adb $adb
    $deviceCount = $devices.Count
}

Write-Host "=== Mobly Test Orchestrator ==="
Write-Host "  Python:   $python"
Write-Host "  Mobly:    $(if ($hasMobly) {'installed'} else {'NOT installed (mock fallback)'})"
Write-Host "  ADB:      $(if ($adb) {$adb} else {'NOT found'})"
Write-Host "  Devices:  $deviceCount"
Write-Host "  MockOnly: $MockOnly"
Write-Host ""

$testMode = 'MOCK'
if ($deviceCount -ge 2 -and $hasMobly -and -not $MockOnly) {
    $testMode = 'REAL_DEVICE'
} elseif ($deviceCount -ge 1 -and $hasMobly -and -not $MockOnly) {
    $testMode = 'SINGLE_DEVICE'
}

# --- Run Mobly tests ---
$testFiles = @(
    'config_contract_test.py',
    'relay_multihop_test.py',
    'gateway_sync_test.py',
    'ble_submit_prestage_test.py'
)

foreach ($testFile in $testFiles) {
    $testPath = Join-Path $moblyDir $testFile
    if (-not (Test-Path -LiteralPath $testPath)) {
        Write-Host "  SKIP: $testFile (not found)" -ForegroundColor Gray
        $results += @{ file = $testFile; status = 'NOT_RUN'; reason = 'File not found' }
        continue
    }

    Write-Host "  Running: $testFile ($testMode)..."
    $logPath = Join-Path $artifactDir "$($testFile -replace '\.py$', '.log')"

    try {
        if ($testMode -eq 'MOCK' -or -not $hasMobly) {
            # Run with unittest directly (mock fallback)
            $output = & $python -m pytest $testPath --tb=short 2>&1 | Out-String
            if ($LASTEXITCODE -ne 0) {
                # Try unittest as fallback
                $output = & $python -m unittest $testFile 2>&1 | Out-String
            }
        } else {
            # Run with Mobly
            $configPath = Join-Path $moblyDir 'configs\relay_emulator.yaml'
            $output = & $python $testPath -c $configPath 2>&1 | Out-String
        }

        $output | Set-Content -LiteralPath $logPath -Encoding UTF8

        if ($LASTEXITCODE -eq 0) {
            $results += @{ file = $testFile; status = 'PASS'; mode = $testMode }
            Write-Host "    PASS" -ForegroundColor Green
        } else {
            $results += @{ file = $testFile; status = 'FAIL'; mode = $testMode; exitCode = $LASTEXITCODE }
            Write-Host "    FAIL (exit code: $LASTEXITCODE)" -ForegroundColor Red
        }
    } catch {
        $results += @{ file = $testFile; status = 'FAIL'; mode = $testMode; error = $_.Exception.Message }
        Write-Host "    FAIL: $($_.Exception.Message)" -ForegroundColor Red
    }
}

# --- Summary ---
$overallStatus = if ($results | Where-Object { $_.status -eq 'FAIL' }) { 'FAIL' }
                 elseif ($testMode -eq 'MOCK') { 'PASS_MOCK_ONLY' }
                 else { 'PASS' }

# Important: Mock PASS is NOT device-tested
if ($overallStatus -eq 'PASS_MOCK_ONLY') {
    Write-Host "`nNote: Tests passed with MOCK fallback only." -ForegroundColor Cyan
    Write-Host "This is NOT equivalent to real device testing." -ForegroundColor Cyan
    Write-Host "Real device testing requires: 2+ ADB devices + Mobly installed." -ForegroundColor Cyan
}

Write-Summary -ArtifactDir $artifactDir -Summary @{
    test = 'mobly-orchestrator'
    status = $overallStatus
    mode = $testMode
    deviceCount = $deviceCount
    hasMobly = $hasMobly
    results = $results
}

Write-Host "`nOverall: $overallStatus" -ForegroundColor $(
    switch ($overallStatus) {
        'PASS' { 'Green' }
        'PASS_MOCK_ONLY' { 'Cyan' }
        default { 'Red' }
    }
)
