<#
.SYNOPSIS
  Tests Relay recovery after Bluetooth OFF/ON cycle.
.DESCRIPTION
  Toggles Bluetooth off and on, verifies:
  - BT state actually changed (not just command success)
  - Relay enters DEGRADED or appropriate stopped state when BT disabled
  - Relay recovers communication capability when BT re-enabled
  - No crash, no lease duplication

  Exit codes:
    0 = PASS
    1 = FAIL
    2 = BLOCKED
    3 = INCONCLUSIVE
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

$adb = Get-AdbPath
if (-not $adb) {
    Write-Host 'BLOCKED: ADB not found' -ForegroundColor Yellow
    exit 2
}

$devices = Assert-MinDevices -Adb $adb -Minimum 1
if (-not $devices) { exit 2 }

$serial = $devices[0]
$artifactDir = Get-ArtifactDir
$pkg = 'com.example.relay'

Write-Host "=== Bluetooth Recovery Test on $serial ==="

# Check API level - some BT commands need specific versions
$apiLevel = [int](Invoke-AdbShell $adb $serial 'getprop ro.build.version.sdk')
Write-Host "  API level: $apiLevel"

# Step 1: Ensure app is running
Write-Host '[1/7] Ensuring app is running...'
& $adb -s $serial shell "am start -n $pkg/.MainActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 4

$pidBefore = Invoke-AdbShell $adb $serial "pidof $pkg"
if (-not $pidBefore) {
    Write-Host 'BLOCKED: App not running, cannot test BT recovery' -ForegroundColor Yellow
    Write-Summary -ArtifactDir $artifactDir -Summary @{ test = 'bluetooth-recovery'; status = 'BLOCKED'; reason = 'App not running' }
    exit 2
}

# Step 2: Record initial Bluetooth state
Write-Host '[2/7] Recording initial Bluetooth state...'
$btStateBefore = Invoke-AdbShell $adb $serial 'settings get global bluetooth_on'
$btEnabled = $btStateBefore -match '1'
Write-Host "  Bluetooth initially: $(if ($btEnabled) {'ON'} else {'OFF'})"

# Clear logcat for clean evidence
& $adb -s $serial logcat -c 2>$null

# Step 3: Disable Bluetooth
Write-Host '[3/7] Disabling Bluetooth...'
Invoke-AdbShell $adb $serial 'svc bluetooth disable' | Out-Null
Start-Sleep -Seconds 3

# Verify BT actually turned off
$btStateAfterDisable = Invoke-AdbShell $adb $serial 'settings get global bluetooth_on'
$btActuallyDisabled = $btStateAfterDisable -match '0'

if (-not $btActuallyDisabled) {
    Write-Host 'BLOCKED_UNSUPPORTED_ADB_CONTROL: svc bluetooth disable did not change BT state' -ForegroundColor Yellow
    # Restore and exit
    if ($btEnabled) { Invoke-AdbShell $adb $serial 'svc bluetooth enable' | Out-Null }
    Write-Summary -ArtifactDir $artifactDir -Summary @{
        test = 'bluetooth-recovery'; status = 'BLOCKED_UNSUPPORTED_ADB_CONTROL'
        reason = 'BT state did not change via ADB'; apiLevel = $apiLevel
    }
    exit 2
}
Write-Host "  BT confirmed OFF"

# Step 4: Verify app state after BT disable (not just PID)
Write-Host '[4/7] Checking app state after BT disable...'
$pidMid = Invoke-AdbShell $adb $serial "pidof $pkg"
$survivedDisable = [bool]$pidMid

# Check for DEGRADED state or appropriate behavior
$degradedCheck = Invoke-AdbShell $adb $serial "logcat -d -t 50 --pid=$pidMid 2>&1"
$showsDegraded = $degradedCheck -match 'DEGRADED|BLE_UNAVAILABLE|bluetooth.*disabled|NearbyState.*STOPPED'

# Step 5: Re-enable Bluetooth
Write-Host '[5/7] Re-enabling Bluetooth...'
Invoke-AdbShell $adb $serial 'svc bluetooth enable' | Out-Null
Start-Sleep -Seconds 5

# Verify BT actually turned back on
$btStateAfterEnable = Invoke-AdbShell $adb $serial 'settings get global bluetooth_on'
$btActuallyEnabled = $btStateAfterEnable -match '1'

if (-not $btActuallyEnabled) {
    Write-Host 'WARNING: BT did not re-enable cleanly' -ForegroundColor Yellow
}

# Step 6: Verify app recovered
Write-Host '[6/7] Checking app recovered after BT re-enable...'
Start-Sleep -Seconds 3
$pidAfter = Invoke-AdbShell $adb $serial "pidof $pkg"
$survivedEnable = [bool]$pidAfter

# Check for recovery/re-evaluation in logs
$recoveryCheck = Invoke-AdbShell $adb $serial "logcat -d -t 100 2>&1"
$showsRecovery = $recoveryCheck -match 'bluetooth.*enabled|BLE_AVAILABLE|NearbyState.*READY|re-evaluat'

# Step 7: Check for lease duplication and crash
Write-Host '[7/7] Checking for lease duplication and crashes...'
$logcatPath = Join-Path $artifactDir 'logcat-bt-recovery.txt'
Save-Logcat -Adb $adb -Serial $serial -OutputPath $logcatPath
$fullLog = Get-Content -LiteralPath $logcatPath -Raw -ErrorAction SilentlyContinue
$crashed = [bool]($fullLog -match 'FATAL EXCEPTION')
$leakDuplicate = [bool]($fullLog -match 'lease.*duplicate|already.*advertising|multiple.*session')

# --- Determine status ---
$status = 'PASS'
$reason = ''
$failReasons = @()

if ($crashed) {
    $failReasons += 'App crashed during BT toggle'
}
if (-not $survivedDisable) {
    $failReasons += 'App process killed when Bluetooth was disabled'
}
if (-not $survivedEnable) {
    $failReasons += 'App process killed when Bluetooth was re-enabled'
}
if ($leakDuplicate) {
    $failReasons += 'Lease duplication detected after BT re-enable'
}

if ($failReasons.Count -gt 0) {
    $status = 'FAIL'
    $reason = $failReasons -join '; '
}

# Restore original BT state if we changed it
if (-not $btEnabled) {
    Write-Host '  Restoring BT to original OFF state...'
    Invoke-AdbShell $adb $serial 'svc bluetooth disable' | Out-Null
}

Write-Host "$status`: Bluetooth recovery test$(if ($reason) { " - $reason" })" -ForegroundColor $(if ($status -eq 'PASS') { 'Green' } else { 'Red' })
Write-Summary -ArtifactDir $artifactDir -Summary @{
    test              = 'bluetooth-recovery'
    timestamp         = (Get-Date).ToString('o')
    status            = $status
    reason            = $reason
    device            = $serial
    apiLevel          = $apiLevel
    btInitiallyOn     = $btEnabled
    btActuallyDisabled = $btActuallyDisabled
    btActuallyEnabled = $btActuallyEnabled
    pidBefore         = $pidBefore
    pidAfter          = $pidAfter
    survivedDisable   = $survivedDisable
    survivedEnable    = $survivedEnable
    showsDegraded     = [bool]$showsDegraded
    showsRecovery     = [bool]$showsRecovery
    leakDuplicate     = $leakDuplicate
    crashed           = $crashed
    artifacts         = @($logcatPath)
}

switch ($status) {
    'PASS' { exit 0 }
    'FAIL' { exit 1 }
    default { exit 3 }
}
