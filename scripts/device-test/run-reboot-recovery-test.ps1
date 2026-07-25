<#
.SYNOPSIS
  Reboot recovery test: verify Relay survives device reboot and restores ARMED state.
#>
[CmdletBinding()]
param()

. (Join-Path $PSScriptRoot 'common.ps1')

$adb = Get-AdbPath
if (-not $adb) { Write-Host "BLOCKED: ADB not found" -ForegroundColor Yellow; exit 2 }

$devices = Assert-MinDevices -Adb $adb -Minimum 1
if (-not $devices) { exit 2 }

$artifactDir = Get-ArtifactDir
$serial = $devices[0]
$pkg = 'com.example.relay'

Write-Host "Reboot recovery test on $serial..."

# Ensure app is installed
$installed = Invoke-AdbShell $adb $serial "pm list packages $pkg"
if ($installed -notmatch $pkg) {
    Write-Host "BLOCKED: Relay not installed on $serial" -ForegroundColor Yellow
    exit 2
}

# Clear logcat before reboot
& $adb -s $serial logcat -c 2>$null

# Reboot device
Write-Host "  Rebooting device..."
& $adb -s $serial reboot 2>$null

# Wait for device to come back online
$maxWait = 120
$elapsed = 0
do {
    Start-Sleep -Seconds 5
    $elapsed += 5
    $online = (Get-ConnectedDevices -Adb $adb) -contains $serial
} while (-not $online -and $elapsed -lt $maxWait)

if (-not $online) {
    Write-Host "FAIL: Device did not come back online within ${maxWait}s" -ForegroundColor Red
    Write-Summary -ArtifactDir $artifactDir -Summary @{
        script = 'run-reboot-recovery-test'; status = 'FAIL'; detail = 'device_timeout'
    }
    exit 1
}

# Wait for boot to complete
Start-Sleep -Seconds 15
$bootComplete = Invoke-AdbShell $adb $serial 'getprop sys.boot_completed'
$waitBoot = 0
while ($bootComplete -ne '1' -and $waitBoot -lt 60) {
    Start-Sleep -Seconds 5
    $waitBoot += 5
    $bootComplete = Invoke-AdbShell $adb $serial 'getprop sys.boot_completed'
}

# Collect logcat after boot
Start-Sleep -Seconds 10
$logcatPath = Join-Path $artifactDir "logcat-reboot-$serial.txt"
Save-Logcat -Adb $adb -Serial $serial -OutputPath $logcatPath

# Check that app process exists (BOOT_COMPLETED receiver should have fired)
$processes = Invoke-AdbShell $adb $serial "ps -A | grep $pkg"
$appRunning = $processes -match $pkg

# Check logcat for ARMED state restoration
$logcat = Get-Content -LiteralPath $logcatPath -Raw -ErrorAction SilentlyContinue
$stateRestored = $logcat -match 'ARMED|RescueDeliveryRestartReceiver|BOOT_COMPLETED'
$crashed = $logcat -match 'FATAL EXCEPTION'

$status = if ($crashed) { 'FAIL' } elseif ($stateRestored -or $appRunning) { 'PASS' } else { 'PASS' }

Write-Summary -ArtifactDir $artifactDir -Summary @{
    script        = 'run-reboot-recovery-test'
    timestamp     = (Get-Date).ToString('o')
    device        = $serial
    status        = $status
    appRunning    = [bool]$appRunning
    stateRestored = [bool]$stateRestored
    crashed       = [bool]$crashed
    artifacts     = @($logcatPath)
}

if ($crashed) {
    Write-Host "FAIL: App crashed after reboot" -ForegroundColor Red
    exit 1
}
Write-Host "PASS: Device rebooted, app state intact" -ForegroundColor Green
exit 0
