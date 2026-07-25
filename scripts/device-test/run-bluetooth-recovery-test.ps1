<#
.SYNOPSIS
  Tests Relay recovery after Bluetooth OFF/ON cycle.
.DESCRIPTION
  Toggles Bluetooth off and on, verifies Relay does not crash and recovers gracefully.
  Requires: 1 connected Android device with ADB.
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

Write-Host "=== Bluetooth Recovery Test on $serial ==="

# Step 1: Ensure app is running
Write-Host '[1/5] Ensuring app is running...'
& $adb -s $serial shell "am start -n $pkg/.MainActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 4

$pidBefore = Invoke-AdbShell $adb $serial "pidof $pkg"
if (-not $pidBefore) {
    Write-Host 'BLOCKED: App not running, cannot test BT recovery' -ForegroundColor Yellow
    Write-Summary -ArtifactDir $artifactDir -Summary @{ test = 'bluetooth-recovery'; status = 'BLOCKED'; reason = 'App not running' }
    exit 0
}

# Step 2: Disable Bluetooth
Write-Host '[2/5] Disabling Bluetooth...'
Invoke-AdbShell $adb $serial 'svc bluetooth disable' | Out-Null
Start-Sleep -Seconds 3

# Step 3: Verify app still alive
Write-Host '[3/5] Checking app survived BT disable...'
$pidMid = Invoke-AdbShell $adb $serial "pidof $pkg"
$survivedDisable = [bool]$pidMid

# Step 4: Re-enable Bluetooth
Write-Host '[4/5] Re-enabling Bluetooth...'
Invoke-AdbShell $adb $serial 'svc bluetooth enable' | Out-Null
Start-Sleep -Seconds 5

# Step 5: Verify app still alive after re-enable
Write-Host '[5/5] Checking app survived BT re-enable...'
$pidAfter = Invoke-AdbShell $adb $serial "pidof $pkg"
$survivedEnable = [bool]$pidAfter

Save-Logcat -Adb $adb -Serial $serial -OutputPath (Join-Path $artifactDir 'logcat-bt-recovery.txt')

$status = 'PASS'
$reason = ''
if (-not $survivedDisable) {
    $status = 'FAIL'
    $reason = 'App crashed when Bluetooth was disabled'
} elseif (-not $survivedEnable) {
    $status = 'FAIL'
    $reason = 'App crashed when Bluetooth was re-enabled'
}

Write-Host "$status`: Bluetooth recovery test" -ForegroundColor $(if ($status -eq 'PASS') { 'Green' } else { 'Red' })
Write-Summary -ArtifactDir $artifactDir -Summary @{
    test = 'bluetooth-recovery'
    status = $status
    reason = $reason
    device = $serial
    pidBefore = $pidBefore
    pidAfter = $pidAfter
    survivedDisable = $survivedDisable
    survivedEnable = $survivedEnable
}
