<#
.SYNOPSIS
  Collect battery, thermal, and device idle evidence from connected devices.
.DESCRIPTION
  Gathers dumpsys battery, deviceidle, and thermal information.
  Never collects SOS content, location, tokens, or secrets.
#>
[CmdletBinding()]
param()

. (Join-Path $PSScriptRoot 'common.ps1')

$adb = Get-AdbPath
if (-not $adb) { Write-Host "BLOCKED: ADB not found" -ForegroundColor Yellow; exit 2 }

$devices = Assert-MinDevices -Adb $adb -Minimum 1
if (-not $devices) { exit 2 }

$artifactDir = Get-ArtifactDir

foreach ($serial in $devices) {
    Write-Host "Collecting evidence from $serial..."
    $deviceDir = Join-Path $artifactDir $serial
    New-Item -ItemType Directory -Force -Path $deviceDir | Out-Null

    # Battery
    $battery = Invoke-AdbShell $adb $serial 'dumpsys battery'
    $battery | Set-Content -LiteralPath (Join-Path $deviceDir 'dumpsys-battery.txt') -Encoding UTF8

    # Device idle (Doze)
    $idle = Invoke-AdbShell $adb $serial 'dumpsys deviceidle'
    $idle | Set-Content -LiteralPath (Join-Path $deviceDir 'dumpsys-deviceidle.txt') -Encoding UTF8

    # Package info (filtered to relay only)
    $pkgInfo = Invoke-AdbShell $adb $serial 'dumpsys package com.example.relay'
    $pkgInfo | Set-Content -LiteralPath (Join-Path $deviceDir 'dumpsys-package.txt') -Encoding UTF8

    # Thermal status (API 29+)
    $thermal = Invoke-AdbShell $adb $serial 'dumpsys thermalservice'
    $thermal | Set-Content -LiteralPath (Join-Path $deviceDir 'thermal-status.txt') -Encoding UTF8

    # Device properties
    $props = Collect-DeviceProperties -Adb $adb -Serial $serial
    $props | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $deviceDir 'device-properties.json') -Encoding UTF8

    Write-Host "  Evidence saved to $deviceDir" -ForegroundColor Green
}

Write-Summary -ArtifactDir $artifactDir -Summary @{
    script    = 'collect-device-evidence'
    timestamp = (Get-Date).ToString('o')
    devices   = @($devices)
    status    = 'PASS'
}

Write-Host "PASS: Evidence collected" -ForegroundColor Green
exit 0
