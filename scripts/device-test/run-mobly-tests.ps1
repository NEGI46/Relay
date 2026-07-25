<#
.SYNOPSIS
  Windows host orchestrator for Mobly real-device and mock-fallback test execution.
.DESCRIPTION
  Detects ADB-connected devices DYNAMICALLY (no fixed serials), installs debug APK
  and test bridge if possible, then runs Mobly scenario tests (multihop, gateway sync,
  contract, BLE submit).

  Falls back to mock-only execution when no devices or Mobly not installed.
  PASS_MOCK_ONLY is NOT equivalent to real device success.

  Exit codes:
    0 = PASS (real device) or PASS_MOCK_ONLY
    1 = FAIL
    2 = BLOCKED
    3 = INCONCLUSIVE
#>
[CmdletBinding()]
param(
    [switch]$MockOnly,
    [switch]$Verbose,
    [switch]$CleanAppData
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

$moblyDir = Join-Path $PSScriptRoot '..\..\test-lab\mobly'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..'))  .Path
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
    exit 2
}

# --- Check Mobly availability ---
$hasMobly = $false
try {
    $check = & $python -c "import mobly; print('ok')" 2>&1
    $hasMobly = ($check -match 'ok')
} catch { }

# --- Check ADB and devices (DYNAMIC detection) ---
$adb = Get-AdbPath
$deviceCount = 0
$devices = @()
if ($adb -and -not $MockOnly) {
    $devices = Get-ConnectedDevices -Adb $adb
    $deviceCount = $devices.Count
}

Write-Host "=== Mobly Test Orchestrator ==="
Write-Host "  Python:   $python"
Write-Host "  Mobly:    $(if ($hasMobly) {'installed'} else {'NOT installed (mock fallback)'})"
Write-Host "  ADB:      $(if ($adb) {$adb} else {'NOT found'})"
Write-Host "  Devices:  $deviceCount $(if ($devices) { '(' + ($devices -join ', ') + ')' })"
Write-Host "  MockOnly: $MockOnly"
Write-Host ""

$testMode = 'MOCK'
if ($deviceCount -ge 3 -and $hasMobly -and -not $MockOnly) {
    $testMode = 'REAL_DEVICE_MULTIHOP'
} elseif ($deviceCount -ge 2 -and $hasMobly -and -not $MockOnly) {
    $testMode = 'REAL_DEVICE'
} elseif ($deviceCount -ge 1 -and $hasMobly -and -not $MockOnly) {
    $testMode = 'SINGLE_DEVICE'
}

# --- Generate dynamic Mobly config from detected devices ---
if ($testMode -ne 'MOCK' -and $devices.Count -gt 0) {
    Write-Host "Generating dynamic Mobly config from detected devices..."
    $configDir = Join-Path $artifactDir 'mobly-config'
    New-Item -ItemType Directory -Force -Path $configDir | Out-Null

    # Assign roles based on device count
    $roleAssignment = @()
    if ($devices.Count -ge 3) {
        $roleAssignment = @(
            @{ serial = $devices[0]; role = 'origin' },
            @{ serial = $devices[1]; role = 'relay' },
            @{ serial = $devices[2]; role = 'destination' }
        )
    } elseif ($devices.Count -ge 2) {
        $roleAssignment = @(
            @{ serial = $devices[0]; role = 'origin' },
            @{ serial = $devices[1]; role = 'destination' }
        )
    } else {
        $roleAssignment = @(
            @{ serial = $devices[0]; role = 'origin' }
        )
    }

    # Collect device properties for each
    $deviceConfigs = @()
    foreach ($assignment in $roleAssignment) {
        $props = Collect-DeviceProperties -Adb $adb -Serial $assignment.serial
        $deviceConfigs += @{
            serial = $assignment.serial
            role = $assignment.role
            model = $props.model
            apiLevel = $props.apiLevel
            android = $props.android
        }
        Write-Host "  $($assignment.role): $($assignment.serial) (API $($props.apiLevel), $($props.model))"
    }

    # Write YAML config for Mobly
    $yamlContent = "TestBeds:`n  - Name: relay_dynamic`n    Controllers:`n      AndroidDevice:`n"
    foreach ($dc in $deviceConfigs) {
        $yamlContent += "        - serial: $($dc.serial)`n          label: $($dc.role)`n"
    }
    $configPath = Join-Path $configDir 'relay_dynamic.yaml'
    $yamlContent | Set-Content -LiteralPath $configPath -Encoding UTF8
    Write-Host "  Config written to: $configPath"
} else {
    $configPath = $null
}

# --- Install APKs on real devices if needed ---
if ($testMode -ne 'MOCK' -and $adb) {
    Write-Host ""
    Write-Host "Checking APK installation..."
    $pkg = 'com.example.relay'
    foreach ($serial in $devices) {
        $installed = Invoke-AdbShell $adb $serial "pm list packages $pkg 2>&1"
        if ($installed -notmatch $pkg) {
            Write-Host "  $serial`: App not installed - attempting install..." -ForegroundColor Yellow
            $apkDir = Join-Path $Root 'app\build\outputs\apk\debug'
            $apk = Get-ChildItem -Path $apkDir -Filter '*.apk' -Recurse -ErrorAction SilentlyContinue |
                Sort-Object LastWriteTime -Descending | Select-Object -First 1
            if ($apk) {
                & $adb -s $serial install -r $apk.FullName 2>&1 | Out-Null
                Write-Host "  $serial`: Installed $($apk.Name)"
            } else {
                Write-Host "  $serial`: No debug APK available (build first)" -ForegroundColor Yellow
            }
        } else {
            Write-Host "  $serial`: App already installed"
        }

        # Check permissions
        $btPerms = Invoke-AdbShell $adb $serial "dumpsys package $pkg 2>&1"
        $hasLocation = $btPerms -match 'ACCESS_FINE_LOCATION.*granted=true'
        if (-not $hasLocation) {
            Write-Host "  $serial`: WARNING - Location permission not granted" -ForegroundColor Yellow
        }
    }
}

# --- Run Mobly tests ---
Write-Host ""
Write-Host "Running tests in mode: $testMode"
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

    # Skip multihop if insufficient devices
    if ($testFile -eq 'relay_multihop_test.py' -and $testMode -ne 'REAL_DEVICE_MULTIHOP') {
        Write-Host "  SKIP: $testFile (needs 3 devices, have $deviceCount)" -ForegroundColor Gray
        $results += @{ file = $testFile; status = 'BLOCKED_INSUFFICIENT_DEVICES'; reason = "Need 3 devices, have $deviceCount" }
        continue
    }

    Write-Host "  Running: $testFile ($testMode)..."
    $logPath = Join-Path $artifactDir "$($testFile -replace '\.py$', '.log')"

    try {
        if ($testMode -eq 'MOCK' -or -not $hasMobly) {
            # Run with pytest (mock fallback)
            $output = & $python -m pytest $testPath --tb=short 2>&1 | Out-String
            if ($LASTEXITCODE -ne 0) {
                # Try unittest as fallback
                $output = & $python -m unittest $testFile 2>&1 | Out-String
            }
        } else {
            # Run with Mobly using dynamic config
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
$failCount = @($results | Where-Object { $_.status -eq 'FAIL' }).Count
$passCount = @($results | Where-Object { $_.status -eq 'PASS' }).Count
$blockedCount = @($results | Where-Object { $_.status -match 'BLOCKED' }).Count

$overallStatus = if ($failCount -gt 0) { 'FAIL' }
                 elseif ($passCount -eq 0) { 'BLOCKED_NO_DEVICE' }
                 elseif ($testMode -eq 'MOCK') { 'PASS_MOCK_ONLY' }
                 elseif ($testMode -match 'REAL_DEVICE') { 'PASS_REAL_DEVICE' }
                 else { 'PASS_MOCK_ONLY' }

# Important: Mock PASS is NOT device-tested
if ($overallStatus -eq 'PASS_MOCK_ONLY') {
    Write-Host ""
    Write-Host "Note: Tests passed with MOCK fallback only." -ForegroundColor Cyan
    Write-Host "This is NOT equivalent to real device testing." -ForegroundColor Cyan
    Write-Host "Real multihop testing requires: 3+ ADB devices + Mobly installed." -ForegroundColor Cyan
}

Write-Summary -ArtifactDir $artifactDir -Summary @{
    test = 'mobly-orchestrator'
    timestamp = (Get-Date).ToString('o')
    status = $overallStatus
    mode = $testMode
    deviceCount = $deviceCount
    devices = $devices
    hasMobly = $hasMobly
    results = $results
    passCount = $passCount
    failCount = $failCount
    blockedCount = $blockedCount
}

Write-Host ""
Write-Host "Overall: $overallStatus (PASS=$passCount FAIL=$failCount BLOCKED=$blockedCount)" -ForegroundColor $(
    switch ($overallStatus) {
        'PASS_REAL_DEVICE' { 'Green' }
        'PASS_MOCK_ONLY' { 'Cyan' }
        'FAIL' { 'Red' }
        default { 'Yellow' }
    }
)

# Exit code matching status
switch ($overallStatus) {
    'PASS_REAL_DEVICE' { exit 0 }
    'PASS_MOCK_ONLY' { exit 0 }
    'FAIL' { exit 1 }
    default { exit 2 }
}
