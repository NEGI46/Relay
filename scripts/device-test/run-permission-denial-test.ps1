<#
.SYNOPSIS
  Tests Relay behavior when runtime permissions are denied.
.DESCRIPTION
  Revokes key permissions (location, nearby, notifications) and verifies
  Relay degrades gracefully without crashing.
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

Write-Host "=== Permission Denial Test on $serial ==="

# Permissions to test (revoke then grant back)
$permissions = @(
    'android.permission.ACCESS_FINE_LOCATION',
    'android.permission.ACCESS_COARSE_LOCATION',
    'android.permission.BLUETOOTH_SCAN',
    'android.permission.BLUETOOTH_ADVERTISE',
    'android.permission.BLUETOOTH_CONNECT',
    'android.permission.POST_NOTIFICATIONS',
    'android.permission.NEARBY_WIFI_DEVICES'
)

# Step 1: Ensure app is installed and running
Write-Host '[1/4] Ensuring app is running...'
& $adb -s $serial shell "am start -n $pkg/.MainActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 4

$pidBefore = Invoke-AdbShell $adb $serial "pidof $pkg"
if (-not $pidBefore) {
    Write-Host 'BLOCKED: App not running' -ForegroundColor Yellow
    Write-Summary -ArtifactDir $artifactDir -Summary @{ test = 'permission-denial'; status = 'BLOCKED'; reason = 'App not running' }
    exit 0
}

# Step 2: Revoke permissions one by one
Write-Host '[2/4] Revoking permissions...'
$revokeResults = @{}
foreach ($perm in $permissions) {
    $output = Invoke-AdbShell $adb $serial "pm revoke $pkg $perm 2>&1"
    $revokeResults[$perm] = $output
    Write-Host "  Revoked: $perm"
}
Start-Sleep -Seconds 3

# Step 3: Force-stop and relaunch
Write-Host '[3/4] Relaunching with permissions revoked...'
& $adb -s $serial shell "am force-stop $pkg" 2>&1 | Out-Null
Start-Sleep -Seconds 2
& $adb -s $serial shell "am start -n $pkg/.MainActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 6

$pidAfterRevoke = Invoke-AdbShell $adb $serial "pidof $pkg"
$survivedRevoke = [bool]$pidAfterRevoke

# Step 4: Restore permissions
Write-Host '[4/4] Restoring permissions...'
foreach ($perm in $permissions) {
    Invoke-AdbShell $adb $serial "pm grant $pkg $perm 2>&1" | Out-Null
}

Save-Logcat -Adb $adb -Serial $serial -OutputPath (Join-Path $artifactDir 'logcat-permission-denial.txt')

$status = 'PASS'
$reason = ''
if (-not $survivedRevoke) {
    $status = 'FAIL'
    $reason = 'App crashed after permissions were revoked'
}

Write-Host "$status`: Permission denial test" -ForegroundColor $(if ($status -eq 'PASS') { 'Green' } else { 'Red' })
Write-Summary -ArtifactDir $artifactDir -Summary @{
    test = 'permission-denial'
    status = $status
    reason = $reason
    device = $serial
    survivedRevoke = $survivedRevoke
    permissionsRevoked = $permissions
}
