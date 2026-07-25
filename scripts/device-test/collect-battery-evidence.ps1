<#
.SYNOPSIS
  Collects battery and thermal information from connected device.
.DESCRIPTION
  Captures battery level, temperature, charging state, thermal throttling status,
  and device idle state for evidence reporting.
  Requires: 1 connected Android device with ADB.
  Result: Evidence files written to artifacts directory.
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

Write-Host "=== Collecting Battery & Thermal Evidence from $serial ==="

# Battery info
Write-Host '[1/4] Collecting battery info...'
$battery = Invoke-AdbShell $adb $serial 'dumpsys battery'
$battery | Set-Content -LiteralPath (Join-Path $artifactDir 'dumpsys-battery.txt') -Encoding UTF8

# Thermal info
Write-Host '[2/4] Collecting thermal status...'
$thermal = Invoke-AdbShell $adb $serial 'dumpsys thermalservice 2>/dev/null || echo UNAVAILABLE'
$thermal | Set-Content -LiteralPath (Join-Path $artifactDir 'thermal-status.txt') -Encoding UTF8

# Device idle info
Write-Host '[3/4] Collecting device idle state...'
$idle = Invoke-AdbShell $adb $serial 'dumpsys deviceidle'
$idle | Set-Content -LiteralPath (Join-Path $artifactDir 'dumpsys-deviceidle.txt') -Encoding UTF8

# Device properties
Write-Host '[4/4] Collecting device properties...'
$props = Collect-DeviceProperties -Adb $adb -Serial $serial
$props | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $artifactDir 'device-properties.json') -Encoding UTF8

# Parse key metrics from battery output
$batteryLevel = if ($battery -match 'level:\s*(\d+)') { $Matches[1] } else { 'unknown' }
$batteryTemp = if ($battery -match 'temperature:\s*(\d+)') { [int]$Matches[1] / 10 } else { 'unknown' }
$isCharging = if ($battery -match 'status:\s*(\d+)') { $Matches[1] -eq '2' -or $Matches[1] -eq '5' } else { $false }

Write-Host "  Battery: ${batteryLevel}%, Temp: ${batteryTemp}C, Charging: $isCharging"
Write-Host "  Evidence written to $artifactDir"

Write-Summary -ArtifactDir $artifactDir -Summary @{
    test = 'battery-evidence'
    status = 'COLLECTED'
    device = $serial
    batteryLevel = $batteryLevel
    temperatureCelsius = $batteryTemp
    isCharging = $isCharging
    properties = $props
}
