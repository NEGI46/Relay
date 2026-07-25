<#
.SYNOPSIS
  Tests Relay behavior when runtime permissions are denied.
.DESCRIPTION
  Revokes key permissions and verifies:
  - Revoke commands actually changed the permission state
  - App degrades gracefully without crashing
  - No dangerous automatic fallback behavior
  - Original permission state is restored afterward
  - API-level-specific permissions are handled correctly

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

Write-Host "=== Permission Denial Test on $serial ==="

$apiLevel = [int](Invoke-AdbShell $adb $serial 'getprop ro.build.version.sdk')
Write-Host "  API level: $apiLevel"

# Permissions to test - filter by API level
$allPermissions = @(
    @{ perm = 'android.permission.ACCESS_FINE_LOCATION'; minApi = 23 },
    @{ perm = 'android.permission.ACCESS_COARSE_LOCATION'; minApi = 23 },
    @{ perm = 'android.permission.BLUETOOTH_SCAN'; minApi = 31 },
    @{ perm = 'android.permission.BLUETOOTH_ADVERTISE'; minApi = 31 },
    @{ perm = 'android.permission.BLUETOOTH_CONNECT'; minApi = 31 },
    @{ perm = 'android.permission.POST_NOTIFICATIONS'; minApi = 33 },
    @{ perm = 'android.permission.NEARBY_WIFI_DEVICES'; minApi = 33 }
)

$permissions = @($allPermissions | Where-Object { $_.minApi -le $apiLevel } | ForEach-Object { $_.perm })
Write-Host "  Testable permissions for API $apiLevel`: $($permissions.Count)"

if ($permissions.Count -eq 0) {
    Write-Host 'BLOCKED: No testable permissions for this API level' -ForegroundColor Yellow
    Write-Summary -ArtifactDir $artifactDir -Summary @{ test = 'permission-denial'; status = 'BLOCKED'; reason = 'No permissions for API level'; apiLevel = $apiLevel }
    exit 2
}

# Step 1: Ensure app is installed and running
Write-Host '[1/5] Ensuring app is running...'
& $adb -s $serial shell "am start -n $pkg/.MainActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 4

$pidBefore = Invoke-AdbShell $adb $serial "pidof $pkg"
if (-not $pidBefore) {
    Write-Host 'BLOCKED: App not running' -ForegroundColor Yellow
    Write-Summary -ArtifactDir $artifactDir -Summary @{ test = 'permission-denial'; status = 'BLOCKED'; reason = 'App not running' }
    exit 2
}

# Step 2: Record initial permission state (to restore later)
Write-Host '[2/5] Recording initial permission state...'
$initialState = @{}
foreach ($perm in $permissions) {
    $check = Invoke-AdbShell $adb $serial "dumpsys package $pkg 2>&1"
    $granted = $check -match "$perm`: granted=true"
    $initialState[$perm] = $granted
}
Write-Host "  Initial grants recorded: $($initialState.Values | Where-Object { $_ } | Measure-Object | Select-Object -ExpandProperty Count) of $($permissions.Count)"

# Clear logcat
& $adb -s $serial logcat -c 2>$null

# Step 3: Revoke permissions and verify each revoke succeeded
Write-Host '[3/5] Revoking permissions...'
$revokeResults = @{}
$revokeFailCount = 0
foreach ($perm in $permissions) {
    $output = Invoke-AdbShell $adb $serial "pm revoke $pkg $perm 2>&1"
    # Verify permission is now actually denied
    Start-Sleep -Milliseconds 500
    $postRevoke = Invoke-AdbShell $adb $serial "dumpsys package $pkg 2>&1"
    $stillGranted = $postRevoke -match "$perm`: granted=true"
    if ($stillGranted -and $initialState[$perm]) {
        # Revoke failed - permission still granted
        $revokeResults[$perm] = 'REVOKE_FAILED'
        $revokeFailCount++
        Write-Host "    WARNING: $perm revoke did not take effect" -ForegroundColor Yellow
    } else {
        $revokeResults[$perm] = 'REVOKED'
        Write-Host "    Revoked: $perm"
    }
}

if ($revokeFailCount -eq $permissions.Count) {
    Write-Host 'BLOCKED: Could not revoke any permissions' -ForegroundColor Yellow
    Write-Summary -ArtifactDir $artifactDir -Summary @{ test = 'permission-denial'; status = 'BLOCKED'; reason = 'All revokes failed'; revokeResults = $revokeResults }
    exit 2
}

Start-Sleep -Seconds 2

# Step 4: Force-stop and relaunch to test degraded behavior
Write-Host '[4/5] Relaunching with permissions revoked...'
& $adb -s $serial shell "am force-stop $pkg" 2>&1 | Out-Null
Start-Sleep -Seconds 2
& $adb -s $serial shell "am start -n $pkg/.MainActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 6

$pidAfterRevoke = Invoke-AdbShell $adb $serial "pidof $pkg"
$survivedRevoke = [bool]$pidAfterRevoke

# Collect logcat to check for dangerous fallbacks
$logcatPath = Join-Path $artifactDir 'logcat-permission-denial.txt'
Save-Logcat -Adb $adb -Serial $serial -OutputPath $logcatPath
$logContent = Get-Content -LiteralPath $logcatPath -Raw -ErrorAction SilentlyContinue
$crashed = [bool]($logContent -match 'FATAL EXCEPTION')
$dangerousFallback = [bool]($logContent -match 'EMERGENCY.*without.*permission|bypass.*permission|ignore.*denied')

# Step 5: Restore permissions to original state
Write-Host '[5/5] Restoring original permission state...'
foreach ($perm in $permissions) {
    if ($initialState[$perm]) {
        # Was granted before => grant it back
        Invoke-AdbShell $adb $serial "pm grant $pkg $perm 2>&1" | Out-Null
    }
    # If it wasn't granted before, do NOT grant it now
}

# Verify restoration
$restorationOk = $true
foreach ($perm in $permissions) {
    if ($initialState[$perm]) {
        $restored = (Invoke-AdbShell $adb $serial "dumpsys package $pkg 2>&1") -match "$perm`: granted=true"
        if (-not $restored) {
            Write-Host "    WARNING: Could not restore $perm" -ForegroundColor Yellow
            $restorationOk = $false
        }
    }
}

# --- Determine status ---
$status = 'PASS'
$failReasons = @()

if ($crashed) {
    $failReasons += 'App crashed after permissions were revoked'
}
if (-not $survivedRevoke) {
    $failReasons += 'App process killed after relaunch with revoked permissions'
}
if ($dangerousFallback) {
    $failReasons += 'Dangerous automatic fallback detected in logs'
}

if ($failReasons.Count -gt 0) {
    $status = 'FAIL'
}

$reason = $failReasons -join '; '
$color = switch ($status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } default { 'Yellow' } }
Write-Host "$status`: Permission denial test$(if ($reason) { " - $reason" })" -ForegroundColor $color

Write-Summary -ArtifactDir $artifactDir -Summary @{
    test             = 'permission-denial'
    timestamp        = (Get-Date).ToString('o')
    status           = $status
    reason           = $reason
    device           = $serial
    apiLevel         = $apiLevel
    survivedRevoke   = $survivedRevoke
    crashed          = $crashed
    dangerousFallback = $dangerousFallback
    revokeResults    = $revokeResults
    restorationOk    = $restorationOk
    permissionsCount = $permissions.Count
    artifacts        = @($logcatPath)
}

switch ($status) {
    'PASS' { exit 0 }
    'FAIL' { exit 1 }
    default { exit 3 }
}
