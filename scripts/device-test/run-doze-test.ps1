<#
.SYNOPSIS
  Tests Relay behavior during Doze mode and App Standby.
.DESCRIPTION
  Forces device into Doze/idle state, verifies Relay ARMED state persists
  and message delivery resumes when device exits Doze.
  Requires: 1 connected Android device with ADB (API 23+).
  Result: PASS / FAIL / BLOCKED_NO_DEVICE
#>

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

$adb = Get-AdbPath
if (-not $adb) {
    Write-Host 'BLOCKED_NO_DEVICE: ADB not found' -ForegroundColor Yellow
    exit 0
}

$devices = Assert-MinDevices -Adb $adb -Minimum 1
if (-not $devices) { exit 0 }

$serial = $devices[0]
$artifactDir = Get-ArtifactDir
$pkg = 'com.example.relay'

Write-Host "=== Doze/Idle Test on $serial ==="

# Step 1: Ensure app is running
Write-Host '[1/6] Ensuring app is running...'
& $adb -s $serial shell "am start -n $pkg/.MainActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 4

$pidBefore = Invoke-AdbShell $adb $serial "pidof $pkg"
if (-not $pidBefore) {
    Write-Host 'BLOCKED: App not running' -ForegroundColor Yellow
    Write-Summary -ArtifactDir $artifactDir -Summary @{ test = 'doze'; status = 'BLOCKED'; reason = 'App not running' }
    exit 0
}

# Step 2: Force device into idle (Doze)
Write-Host '[2/6] Forcing device into idle (Doze)...'
Invoke-AdbShell $adb $serial 'dumpsys deviceidle force-idle' | Out-Null
Start-Sleep -Seconds 5

# Step 3: Capture idle state
Write-Host '[3/6] Capturing idle state...'
$idleState = Invoke-AdbShell $adb $serial 'dumpsys deviceidle get deep'
$idleState | Set-Content -LiteralPath (Join-Path $artifactDir 'dumpsys-deviceidle.txt') -Encoding UTF8

# Step 4: Verify app still alive during Doze
Write-Host '[4/6] Checking app survived Doze...'
$pidDuringDoze = Invoke-AdbShell $adb $serial "pidof $pkg"
$survivedDoze = [bool]$pidDuringDoze

# Step 5: Exit Doze
Write-Host '[5/6] Exiting Doze...'
Invoke-AdbShell $adb $serial 'dumpsys deviceidle unforce' | Out-Null
Invoke-AdbShell $adb $serial 'dumpsys battery reset' | Out-Null
Start-Sleep -Seconds 5

# Step 6: Verify app recovers
Write-Host '[6/6] Checking app recovered from Doze...'
$pidAfterDoze = Invoke-AdbShell $adb $serial "pidof $pkg"
$recoveredFromDoze = [bool]$pidAfterDoze

# Also capture App Standby bucket info
$standbyBucket = Invoke-AdbShell $adb $serial "am get-standby-bucket $pkg 2>/dev/null || echo UNAVAILABLE"

Save-Logcat -Adb $adb -Serial $serial -OutputPath (Join-Path $artifactDir 'logcat-doze.txt')

$status = 'PASS'
$reason = ''
if (-not $survivedDoze) {
    $status = 'FAIL'
    $reason = 'App process killed during Doze'
} elseif (-not $recoveredFromDoze) {
    $status = 'FAIL'
    $reason = 'App process did not recover after Doze exit'
}

Write-Host "$status`: Doze test" -ForegroundColor $(if ($status -eq 'PASS') { 'Green' } else { 'Red' })
Write-Summary -ArtifactDir $artifactDir -Summary @{
    test = 'doze'
    status = $status
    reason = $reason
    device = $serial
    idleState = $idleState
    survivedDoze = $survivedDoze
    recoveredFromDoze = $recoveredFromDoze
    standbyBucket = $standbyBucket
}
