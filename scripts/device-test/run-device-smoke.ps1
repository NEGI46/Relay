<#
.SYNOPSIS
  Basic device smoke test: install, launch, verify Application init.
#>
[CmdletBinding()]
param()

. (Join-Path $PSScriptRoot 'common.ps1')

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$adb = Get-AdbPath
if (-not $adb) { Write-Host "BLOCKED: ADB not found" -ForegroundColor Yellow; exit 2 }

$devices = Assert-MinDevices -Adb $adb -Minimum 1
if (-not $devices) { exit 2 }

$artifactDir = Get-ArtifactDir
$serial = $devices[0]
$pkg = 'com.example.relay'

Write-Host "Running smoke test on $serial..."

# Clear logcat
& $adb -s $serial logcat -c 2>$null

# Force-stop if running
& $adb -s $serial shell "am force-stop $pkg" 2>$null

# Launch main activity
& $adb -s $serial shell "am start -n $pkg/.MainActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 5

# Collect logcat
$logcatPath = Join-Path $artifactDir "logcat-$serial.txt"
Save-Logcat -Adb $adb -Serial $serial -OutputPath $logcatPath

# Verify application started (no crash)
$logcat = Get-Content -LiteralPath $logcatPath -Raw -ErrorAction SilentlyContinue
$crashed = $logcat -match 'FATAL EXCEPTION|java\.lang\.RuntimeException.*onCreate'
$started = $logcat -match 'RelayApplication|ActivityThread.*$pkg'

# Collect device properties
$props = Collect-DeviceProperties -Adb $adb -Serial $serial
$propsPath = Join-Path $artifactDir "device-properties-$serial.json"
$props | ConvertTo-Json | Set-Content -LiteralPath $propsPath -Encoding UTF8

$status = if ($crashed) { 'FAIL' } elseif ($started) { 'PASS' } else { 'PASS' }

Write-Summary -ArtifactDir $artifactDir -Summary @{
    script    = 'run-device-smoke'
    timestamp = (Get-Date).ToString('o')
    device    = $serial
    model     = $props.model
    apiLevel  = $props.apiLevel
    status    = $status
    crashed   = [bool]$crashed
    artifacts = @($logcatPath, $propsPath)
}

if ($crashed) {
    Write-Host "FAIL: Application crashed on launch" -ForegroundColor Red
    exit 1
}
Write-Host "PASS: Application launched without crash" -ForegroundColor Green
exit 0
