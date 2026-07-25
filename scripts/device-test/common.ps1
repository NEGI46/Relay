<#
.SYNOPSIS
  Shared functions for device-test scripts.
#>

$ErrorActionPreference = 'Stop'

function Get-AdbPath {
    if (Get-Command 'adb' -ErrorAction SilentlyContinue) { return 'adb' }
    $sdkAdb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    if (Test-Path -LiteralPath $sdkAdb) { return $sdkAdb }
    if ($env:ANDROID_HOME) {
        $homeAdb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
        if (Test-Path -LiteralPath $homeAdb) { return $homeAdb }
    }
    return $null
}

function Get-ConnectedDevices {
    param([string]$Adb)
    $output = & $Adb devices 2>&1 | Out-String
    $lines = $output -split "`n" | Where-Object { $_ -match '^\S+\s+device$' }
    return @($lines | ForEach-Object { ($_ -split '\s+')[0] })
}

function Assert-MinDevices {
    param([string]$Adb, [int]$Minimum)
    $devices = Get-ConnectedDevices -Adb $Adb
    if ($devices.Count -lt $Minimum) {
        Write-Host "BLOCKED_NO_DEVICE: Found $($devices.Count) device(s), need $Minimum" -ForegroundColor Yellow
        return $null
    }
    return $devices
}

function Get-ArtifactDir {
    $timestamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
    $dir = Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path "artifacts\device-tests\$timestamp"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    return $dir
}

function Invoke-AdbShell {
    param([string]$Adb, [string]$Serial, [string]$Command)
    $result = & $Adb -s $Serial shell $Command 2>&1 | Out-String
    return $result.Trim()
}

function Save-Logcat {
    param([string]$Adb, [string]$Serial, [string]$OutputPath, [string]$Tag = 'relay')
    & $Adb -s $Serial logcat -d -v threadtime "*:V" 2>$null | Set-Content -LiteralPath $OutputPath -Encoding UTF8
}

function Collect-DeviceProperties {
    param([string]$Adb, [string]$Serial)
    return @{
        model       = Invoke-AdbShell $Adb $Serial 'getprop ro.product.model'
        apiLevel    = Invoke-AdbShell $Adb $Serial 'getprop ro.build.version.sdk'
        manufacturer = Invoke-AdbShell $Adb $Serial 'getprop ro.product.manufacturer'
        buildId     = Invoke-AdbShell $Adb $Serial 'getprop ro.build.display.id'
        android     = Invoke-AdbShell $Adb $Serial 'getprop ro.build.version.release'
    }
}

function Write-Summary {
    param([string]$ArtifactDir, [hashtable]$Summary)
    $json = $Summary | ConvertTo-Json -Depth 6
    $path = Join-Path $ArtifactDir 'summary.json'
    $json | Set-Content -LiteralPath $path -Encoding UTF8
    Write-Host "Summary written to $path"
}
