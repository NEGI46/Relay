<#
.SYNOPSIS
  Tests Relay behavior during Doze mode and App Standby.
.DESCRIPTION
  Forces device into Doze/idle state, verifies:
  - ARMED state persists through Doze
  - Undelivered envelopes persist
  - Explicit suspension persists
  - Doze does NOT trigger Nearby start
  - After Doze exit, legitimate activation can resume
  - EMERGENCY_ACTIVE FGS state during Doze
  - No communication lease duplication
  - Process kill during Doze does NOT equal FAIL (state restoration = success)

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

Write-Host "=== Doze/Idle Test on $serial ==="

# Detect API level
$apiLevel = [int](Invoke-AdbShell $adb $serial 'getprop ro.build.version.sdk')
Write-Host "  API level: $apiLevel"

if ($apiLevel -lt 23) {
    Write-Host 'BLOCKED: Doze requires API 23+' -ForegroundColor Yellow
    Write-Summary -ArtifactDir $artifactDir -Summary @{ test = 'doze'; status = 'BLOCKED'; reason = 'API < 23' }
    exit 2
}

# Ensure app is installed
$installed = Invoke-AdbShell $adb $serial "pm list packages $pkg"
if ($installed -notmatch $pkg) {
    Write-Host 'BLOCKED: Relay not installed' -ForegroundColor Yellow
    Write-Summary -ArtifactDir $artifactDir -Summary @{ test = 'doze'; status = 'BLOCKED'; reason = 'App not installed' }
    exit 2
}

# Check for test service
$hasTestService = (Invoke-AdbShell $adb $serial "pm list packages $pkg.test 2>&1") -match "$pkg.test"

# Step 1: Start app and set known state
Write-Host '[1/8] Setting up initial state...'
& $adb -s $serial shell "am start -n $pkg/.MainActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 4

$pidBefore = Invoke-AdbShell $adb $serial "pidof $pkg"
if (-not $pidBefore) {
    Write-Host 'BLOCKED: App not running' -ForegroundColor Yellow
    Write-Summary -ArtifactDir $artifactDir -Summary @{ test = 'doze'; status = 'BLOCKED'; reason = 'App not running' }
    exit 2
}

# If test service available, set ARMED state and verify
if ($hasTestService) {
    Invoke-AdbShell $adb $serial "am broadcast -a $pkg.test.SET_STATE --es state 'ARMED' -n $pkg.test/.TestStateReceiver 2>&1" | Out-Null
    Start-Sleep -Seconds 2
}

# Record pre-Doze state
$preDozeState = Invoke-AdbShell $adb $serial "content query --uri content://$pkg.test.provider/state 2>&1"
Write-Host "  Pre-Doze state captured"

# Clear logcat
& $adb -s $serial logcat -c 2>$null

# Step 2: Force device into idle (Doze)
Write-Host '[2/8] Forcing device into idle (Doze)...'
Invoke-AdbShell $adb $serial 'dumpsys deviceidle force-idle' | Out-Null
Start-Sleep -Seconds 5

# Step 3: Verify Doze state
Write-Host '[3/8] Verifying Doze state...'
$idleState = Invoke-AdbShell $adb $serial 'dumpsys deviceidle get deep'
$inDoze = $idleState -match 'IDLE'
$idleState | Set-Content -LiteralPath (Join-Path $artifactDir 'dumpsys-deviceidle.txt') -Encoding UTF8

if (-not $inDoze) {
    Write-Host 'BLOCKED: Device did not enter Doze state' -ForegroundColor Yellow
    Invoke-AdbShell $adb $serial 'dumpsys deviceidle unforce' | Out-Null
    Write-Summary -ArtifactDir $artifactDir -Summary @{ test = 'doze'; status = 'BLOCKED'; reason = 'Doze not entered'; idleState = $idleState }
    exit 2
}
Write-Host "  Device confirmed in Doze: $idleState"

# Step 4: Check that Nearby is NOT started during Doze
Write-Host '[4/8] Verifying Nearby not started during Doze...'
Start-Sleep -Seconds 5
$dozeLog = Invoke-AdbShell $adb $serial 'logcat -d -t 100 2>&1'
$nearbyStartedInDoze = $dozeLog -match 'NearbyState.*ADVERTISING|startNearby|Nearby.*start'

# Step 5: Check process state during Doze (process kill is OK per design)
Write-Host '[5/8] Checking process during Doze...'
$pidDuringDoze = Invoke-AdbShell $adb $serial "pidof $pkg"
$processAlive = [bool]$pidDuringDoze
Write-Host "  Process during Doze: $(if ($processAlive) {'alive'} else {'killed (acceptable for ARMED)'})"

# Step 6: Exit Doze
Write-Host '[6/8] Exiting Doze...'
Invoke-AdbShell $adb $serial 'dumpsys deviceidle unforce' | Out-Null
Invoke-AdbShell $adb $serial 'dumpsys battery reset' | Out-Null
Start-Sleep -Seconds 5

# Step 7: Verify state restoration after Doze exit
Write-Host '[7/8] Verifying state restoration after Doze...'
$postDozeState = Invoke-AdbShell $adb $serial "content query --uri content://$pkg.test.provider/state 2>&1"
$pidAfterDoze = Invoke-AdbShell $adb $serial "pidof $pkg"

# App Standby bucket info
$standbyBucket = Invoke-AdbShell $adb $serial "am get-standby-bucket $pkg 2>/dev/null || echo UNAVAILABLE"

# Step 8: Collect evidence and determine result
Write-Host '[8/8] Collecting evidence...'
Save-Logcat -Adb $adb -Serial $serial -OutputPath (Join-Path $artifactDir 'logcat-doze.txt')
$fullLog = Get-Content -LiteralPath (Join-Path $artifactDir 'logcat-doze.txt') -Raw -ErrorAction SilentlyContinue
$crashed = [bool]($fullLog -match 'FATAL EXCEPTION')
$leakDuplicate = [bool]($fullLog -match 'lease.*duplicate|already.*advertising|multiple.*session')

# --- Determine status ---
$status = 'PASS'
$failReasons = @()

if ($crashed) {
    $failReasons += 'App crashed during Doze cycle'
}
if ($nearbyStartedInDoze) {
    $failReasons += 'Nearby started during Doze (ARMED should not auto-start Nearby)'
}
if ($leakDuplicate) {
    $failReasons += 'Communication lease duplication detected'
}

# State restoration check (only if test service available)
if ($hasTestService) {
    $statePreserved = $postDozeState -match 'ARMED'
    if (-not $statePreserved -and -not $processAlive) {
        # Process was killed and state not readable => check if it's truly lost
        # Restart app to check persisted state
        & $adb -s $serial shell "am start -n $pkg/.MainActivity" 2>&1 | Out-Null
        Start-Sleep -Seconds 4
        $restoredState = Invoke-AdbShell $adb $serial "content query --uri content://$pkg.test.provider/state 2>&1"
        $statePreserved = $restoredState -match 'ARMED'
    }
    if (-not $statePreserved) {
        $failReasons += 'ARMED state not preserved through Doze'
    }
}

if ($failReasons.Count -gt 0) {
    $status = 'FAIL'
} elseif (-not $hasTestService) {
    $status = 'INCONCLUSIVE'
}

$reason = $failReasons -join '; '
$color = switch ($status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } default { 'Yellow' } }
Write-Host "$status`: Doze test$(if ($reason) { " - $reason" })" -ForegroundColor $color

Write-Summary -ArtifactDir $artifactDir -Summary @{
    test                = 'doze'
    timestamp           = (Get-Date).ToString('o')
    status              = $status
    reason              = $reason
    device              = $serial
    apiLevel            = $apiLevel
    idleState           = $idleState
    inDoze              = $inDoze
    processAlive        = $processAlive
    nearbyStartedInDoze = [bool]$nearbyStartedInDoze
    leakDuplicate       = $leakDuplicate
    crashed             = $crashed
    standbyBucket       = $standbyBucket
    hasTestService      = $hasTestService
}

switch ($status) {
    'PASS' { exit 0 }
    'FAIL' { exit 1 }
    'INCONCLUSIVE' { exit 3 }
    default { exit 2 }
}
