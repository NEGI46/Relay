<#
.SYNOPSIS
  Multi-hop relay test using 2-3 ADB-connected devices.
.DESCRIPTION
  Exercises the Nearby store-carry-forward by orchestrating message creation and
  forwarding across multiple devices using the debug test service.
  Requires 2+ devices for basic test, 3 for full multi-hop.
#>
[CmdletBinding()]
param(
    [int]$MinDevices = 2
)

. (Join-Path $PSScriptRoot 'common.ps1')

$adb = Get-AdbPath
if (-not $adb) { Write-Host "BLOCKED: ADB not found" -ForegroundColor Yellow; exit 2 }

$devices = Assert-MinDevices -Adb $adb -Minimum $MinDevices
if (-not $devices) { exit 2 }

$artifactDir = Get-ArtifactDir
$pkg = 'com.example.relay'

Write-Host "Multi-hop test with $($devices.Count) devices..."
Write-Host "NOTE: This test requires actual Nearby Connections RF communication."
Write-Host "      Emulators cannot establish Nearby transport."

# Verify Relay installed on all devices
foreach ($serial in $devices) {
    $installed = Invoke-AdbShell $adb $serial "pm list packages $pkg"
    if ($installed -notmatch $pkg) {
        Write-Host "BLOCKED: Relay not installed on $serial" -ForegroundColor Yellow
        Write-Summary -ArtifactDir $artifactDir -Summary @{
            script = 'run-multihop-test'; status = 'BLOCKED_NO_DEVICE'; detail = "Relay not installed on $serial"
        }
        exit 2
    }
}

# Collect device properties
$deviceInfo = @()
foreach ($serial in $devices) {
    $props = Collect-DeviceProperties -Adb $adb -Serial $serial
    $deviceInfo += @{ serial = $serial; model = $props.model; apiLevel = $props.apiLevel }
}

# This test relies on the debug test snippet service being available.
# The actual Nearby transport cannot be tested via ADB commands alone - 
# it requires physical proximity and RF communication between devices.
# We document this as requiring real Mobly or manual verification.

Write-Host ""
Write-Host "BLOCKED: Multi-hop Nearby relay requires physical RF proximity." -ForegroundColor Yellow
Write-Host "  This script verifies prerequisites only."
Write-Host "  Full automation requires Mobly with real snippet bridge."
Write-Host ""

# Collect evidence
foreach ($serial in $devices) {
    $logcatPath = Join-Path $artifactDir "logcat-$serial.txt"
    Save-Logcat -Adb $adb -Serial $serial -OutputPath $logcatPath
}

Write-Summary -ArtifactDir $artifactDir -Summary @{
    script      = 'run-multihop-test'
    timestamp   = (Get-Date).ToString('o')
    devices     = $deviceInfo
    status      = 'BLOCKED'
    detail      = 'Nearby RF relay requires physical proximity; use Mobly snippet bridge for full automation'
    artifacts   = @(Get-ChildItem -Path $artifactDir -Filter '*.txt' | ForEach-Object { $_.FullName })
}

exit 2
