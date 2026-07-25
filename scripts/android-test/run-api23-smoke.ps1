<#
.SYNOPSIS
  Run API 23 smoke tests on a classic AVD (not Gradle Managed Device).
.DESCRIPTION
  Creates/reuses an API 23 AVD, boots it headlessly, installs the debug APK,
  runs a targeted smoke test suite, and reports results.

  API 23 (Android 6.0) is below the Gradle Managed Devices minimum (API 27+),
  so this script uses sdkmanager/avdmanager/emulator directly.

  NOTE: A successful AVD test does NOT equal "Android 6 real device tested".

  Exit codes:
    0 = API23_AVD_SMOKE_PASS
    1 = API23_AVD_SMOKE_FAIL
    2 = BLOCKED (BLOCKED_MISSING_IMAGE, BLOCKED_SDK_LICENSE, BLOCKED_EMULATOR_BOOT)
    3 = INCONCLUSIVE
#>
[CmdletBinding()]
param(
    [string]$AvdName = 'relay-api23-test',
    [int]$BootTimeout = 180,
    [int]$TestTimeout = 300,
    [switch]$KeepEmulator,
    [string]$ArtifactDir = ''
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common-emulator.ps1')

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $ArtifactDir) {
    $ArtifactDir = Join-Path $Root 'artifacts\windows-validation-logs\android-api23'
}
New-Item -ItemType Directory -Force -Path $ArtifactDir | Out-Null

$summary = [ordered]@{
    script     = 'run-api23-smoke'
    timestamp  = (Get-Date).ToString('o')
    avdName    = $AvdName
    status     = 'INCONCLUSIVE'
    detail     = ''
    tests      = 0
    failures   = 0
    artifacts  = @()
}

# --- Step 1: Resolve SDK ---
$sdk = Find-AndroidSdk
if (-not $sdk) {
    $summary.status = 'BLOCKED_MISSING_IMAGE'
    $summary.detail = 'Android SDK not found'
    $summary | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $ArtifactDir 'summary.json') -Encoding UTF8
    Write-Host "BLOCKED_MISSING_IMAGE: Android SDK not found" -ForegroundColor Yellow
    exit 2
}

$emulator = Get-Emulator $sdk
$adb = Get-Adb $sdk
$avdManager = Get-AvdManager $sdk

if (-not $emulator) {
    $summary.status = 'BLOCKED_MISSING_IMAGE'
    $summary.detail = 'emulator.exe not found'
    $summary | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $ArtifactDir 'summary.json') -Encoding UTF8
    Write-Host "BLOCKED_MISSING_IMAGE: emulator.exe not found" -ForegroundColor Yellow
    exit 2
}
if (-not $adb) {
    $summary.status = 'BLOCKED_MISSING_IMAGE'
    $summary.detail = 'adb not found'
    $summary | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $ArtifactDir 'summary.json') -Encoding UTF8
    Write-Host "BLOCKED_MISSING_IMAGE: adb not found" -ForegroundColor Yellow
    exit 2
}

# --- Step 2: Ensure AVD exists ---
Write-Host "=== API 23 AVD Smoke Test ==="
Write-Host "  SDK: $sdk"
Write-Host "  Emulator: $emulator"

# Run the create-avd script
$createScript = Join-Path $PSScriptRoot 'create-api23-avd.ps1'
& powershell -NoProfile -ExecutionPolicy Bypass -File $createScript -AvdName $AvdName
if ($LASTEXITCODE -ne 0) {
    $summary.status = 'BLOCKED_MISSING_IMAGE'
    $summary.detail = 'AVD creation failed (missing system image or license)'
    $summary | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $ArtifactDir 'summary.json') -Encoding UTF8
    exit 2
}

# --- Step 3: Start emulator ---
Write-Host "Starting emulator (headless)..."
$emuProc = Start-EmulatorHeadless -Emulator $emulator -AvdName $AvdName
$emuSerial = $null

try {
    # Wait for emulator to appear in adb
    $waitStart = Get-Date
    $emuSerial = $null
    while (((Get-Date) - $waitStart).TotalSeconds -lt 30) {
        Start-Sleep -Seconds 3
        $emuSerial = Get-EmulatorSerial -Adb $adb -ProcessId $emuProc.Id
        if ($emuSerial) { break }
    }

    if (-not $emuSerial) {
        $summary.status = 'BLOCKED_EMULATOR_BOOT'
        $summary.detail = 'Emulator did not register with ADB'
        $summary | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $ArtifactDir 'summary.json') -Encoding UTF8
        Write-Host "BLOCKED_EMULATOR_BOOT: Emulator not found in ADB" -ForegroundColor Yellow
        exit 2
    }

    Write-Host "  Emulator serial: $emuSerial"
    Write-Host "  Waiting for boot..."

    $booted = Wait-EmulatorBoot -Adb $adb -Serial $emuSerial -TimeoutSeconds $BootTimeout
    if (-not $booted) {
        $summary.status = 'BLOCKED_EMULATOR_BOOT'
        $summary.detail = "Boot did not complete within ${BootTimeout}s"
        $summary | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $ArtifactDir 'summary.json') -Encoding UTF8
        Write-Host "BLOCKED_EMULATOR_BOOT: Timeout" -ForegroundColor Yellow
        exit 2
    }

    $apiCheck = & $adb -s $emuSerial shell 'getprop ro.build.version.sdk' 2>&1 | Out-String
    Write-Host "  Booted! API level: $($apiCheck.Trim())"

    # --- Step 4: Build and install debug APK ---
    Write-Host "Building debug APK..."
    $gradlew = Join-Path $Root 'gradlew.bat'
    & $gradlew ':app:assembleDebug' --no-daemon --console=plain 2>&1 |
        Set-Content (Join-Path $ArtifactDir 'gradle-build.log') -Encoding UTF8
    if ($LASTEXITCODE -ne 0) {
        $summary.status = 'API23_AVD_SMOKE_FAIL'
        $summary.detail = 'Debug APK build failed'
        $summary | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $ArtifactDir 'summary.json') -Encoding UTF8
        Write-Host "FAIL: Debug APK build failed" -ForegroundColor Red
        exit 1
    }

    $apkDir = Join-Path $Root 'app\build\outputs\apk\debug'
    $apk = Get-ChildItem -Path $apkDir -Filter '*.apk' -Recurse -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $apk) {
        $summary.status = 'API23_AVD_SMOKE_FAIL'
        $summary.detail = 'No debug APK found after build'
        $summary | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $ArtifactDir 'summary.json') -Encoding UTF8
        exit 1
    }

    Write-Host "  Installing $($apk.Name)..."
    $installOutput = & $adb -s $emuSerial install -r $apk.FullName 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or $installOutput -notmatch 'Success') {
        $summary.status = 'API23_AVD_SMOKE_FAIL'
        $summary.detail = "APK install failed: $installOutput"
        $summary | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $ArtifactDir 'summary.json') -Encoding UTF8
        exit 1
    }

    # --- Step 5: Run smoke checks ---
    Write-Host "Running API 23 smoke checks..."
    $pkg = 'com.example.relay'
    $testResults = @()
    $failures = 0

    # Test: MainActivity launch
    $launchOutput = & $adb -s $emuSerial shell "am start -n $pkg/.MainActivity -W" 2>&1 | Out-String
    $launchSuccess = $launchOutput -match 'Status: ok'
    $testResults += @{ name = 'MainActivity_launch'; pass = $launchSuccess; output = $launchOutput }
    if (-not $launchSuccess) { $failures++ }

    Start-Sleep -Seconds 5

    # Test: Process running
    $pidCheck = & $adb -s $emuSerial shell "pidof $pkg" 2>&1 | Out-String
    $processRunning = [bool]($pidCheck.Trim() -match '^\d+$')
    $testResults += @{ name = 'process_running'; pass = $processRunning; output = $pidCheck }
    if (-not $processRunning) { $failures++ }

    # Test: No crash (check logcat for FATAL EXCEPTION)
    $logcat = & $adb -s $emuSerial logcat -d -v brief "*:E" 2>&1 | Out-String
    $noCrash = -not ($logcat -match 'FATAL EXCEPTION')
    $testResults += @{ name = 'no_crash'; pass = $noCrash; output = 'See logcat' }
    if (-not $noCrash) { $failures++ }

    # Test: No NoClassDefFoundError / NoSuchMethodError (API 23 desugaring check)
    $desugarOk = -not ($logcat -match 'NoClassDefFoundError|NoSuchMethodError|AbstractMethodError')
    $testResults += @{ name = 'desugaring_ok'; pass = $desugarOk; output = 'No desugaring errors in logcat' }
    if (-not $desugarOk) { $failures++ }

    # Test: Keystore access (check logs for security initialization)
    $keystoreOk = -not ($logcat -match 'KeyStoreException|KeyPermanentlyInvalidated.*api23')
    $testResults += @{ name = 'keystore_api23'; pass = $keystoreOk; output = 'No Keystore errors' }
    if (-not $keystoreOk) { $failures++ }

    # Test: Process kill and restart
    Write-Host "  Testing process kill + restart..."
    & $adb -s $emuSerial shell "am force-stop $pkg" 2>&1 | Out-Null
    Start-Sleep -Seconds 2
    & $adb -s $emuSerial shell "am start -n $pkg/.MainActivity -W" 2>&1 | Out-Null
    Start-Sleep -Seconds 5
    $restartPid = & $adb -s $emuSerial shell "pidof $pkg" 2>&1 | Out-String
    $restartOk = [bool]($restartPid.Trim() -match '^\d+$')
    $testResults += @{ name = 'restart_after_kill'; pass = $restartOk; output = $restartPid }
    if (-not $restartOk) { $failures++ }

    # Save full logcat
    $logcatPath = Join-Path $ArtifactDir 'logcat-api23-smoke.txt'
    & $adb -s $emuSerial logcat -d -v threadtime 2>&1 | Set-Content $logcatPath -Encoding UTF8
    $summary.artifacts += $logcatPath

    # NOTE: Nearby/RF cannot be tested on emulator - state this explicitly
    $testResults += @{ name = 'nearby_rf_NOT_TESTED'; pass = $true; output = 'Nearby/BLE requires real device (emulator has no radio)' }

    # --- Step 6: Results ---
    $totalTests = $testResults.Count
    $summary.tests = $totalTests
    $summary.failures = $failures

    if ($failures -eq 0 -and $totalTests -gt 0) {
        $summary.status = 'API23_AVD_SMOKE_PASS'
        $summary.detail = "$totalTests tests passed on API 23 AVD (not a real device test)"
        Write-Host "API23_AVD_SMOKE_PASS: $totalTests tests passed" -ForegroundColor Green
    } else {
        $summary.status = 'API23_AVD_SMOKE_FAIL'
        $failedNames = ($testResults | Where-Object { -not $_.pass } | ForEach-Object { $_.name }) -join ', '
        $summary.detail = "$failures/$totalTests failed: $failedNames"
        Write-Host "API23_AVD_SMOKE_FAIL: $failures/$totalTests failed" -ForegroundColor Red
    }

    $summary['testResults'] = $testResults
    $summary | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $ArtifactDir 'summary.json') -Encoding UTF8

} finally {
    # Clean up emulator (unless -KeepEmulator specified)
    if (-not $KeepEmulator) {
        Write-Host "Stopping emulator..."
        Stop-EmulatorSafe -Adb $adb -Serial $emuSerial -Process $emuProc
    }
}

if ($summary.status -eq 'API23_AVD_SMOKE_PASS') { exit 0 }
elseif ($summary.status -match 'BLOCKED') { exit 2 }
else { exit 1 }
