<#
.SYNOPSIS
  Safely stops a test emulator started by the API 23 smoke scripts.
.DESCRIPTION
  Kills only emulators matching the relay-api23-test AVD name.
  Does NOT affect other running emulators or personal AVDs.
#>
[CmdletBinding()]
param(
    [string]$AvdName = 'relay-api23-test'
)

. (Join-Path $PSScriptRoot 'common-emulator.ps1')

$sdk = Find-AndroidSdk
if (-not $sdk) { Write-Host 'SDK not found, nothing to stop'; exit 0 }

$adb = Get-Adb $sdk
if (-not $adb) { Write-Host 'ADB not found, nothing to stop'; exit 0 }

# Find running emulators and check which one is our test AVD
$devices = & $adb devices 2>&1 | Out-String
$emulators = $devices -split "`n" | Where-Object { $_ -match '^(emulator-\d+)\s+(device|offline)' } |
    ForEach-Object { ($_ -split '\s+')[0] }

$stopped = 0
foreach ($serial in $emulators) {
    $avdProp = & $adb -s $serial shell 'getprop ro.boot.qemu.avd_name' 2>&1 | Out-String
    $avdProp = $avdProp.Trim()
    # Also check emu avd name
    if (-not $avdProp -or $avdProp -eq '') {
        $avdProp = & $adb -s $serial emu avd name 2>&1 | Out-String
        $avdProp = ($avdProp -split "`n")[0].Trim()
    }
    if ($avdProp -eq $AvdName) {
        Write-Host "Stopping $serial (AVD: $AvdName)..."
        & $adb -s $serial emu kill 2>&1 | Out-Null
        $stopped++
    }
}

if ($stopped -eq 0) {
    Write-Host "No running emulator with AVD '$AvdName' found"
} else {
    Write-Host "Stopped $stopped emulator(s)"
}
exit 0
