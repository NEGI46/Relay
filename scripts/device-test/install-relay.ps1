<#
.SYNOPSIS
  Install Relay debug APK and test bridge APK on connected devices.
.DESCRIPTION
  Builds debug APK, installs it on all connected devices, and verifies launch.
  Requires ADB and Android SDK. Exits with BLOCKED_NO_DEVICE if no devices found.
#>
[CmdletBinding()]
param(
    [switch]$SkipBuild
)

. (Join-Path $PSScriptRoot 'common.ps1')

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$adb = Get-AdbPath
if (-not $adb) {
    Write-Host "BLOCKED: ADB not found" -ForegroundColor Yellow
    exit 2
}

$devices = Assert-MinDevices -Adb $adb -Minimum 1
if (-not $devices) { exit 2 }

$artifactDir = Get-ArtifactDir

# Build debug APK
if (-not $SkipBuild) {
    Write-Host "Building debug APK..."
    $gradlew = Join-Path $Root 'gradlew.bat'
    & $gradlew :app:assembleDebug --no-daemon --console=plain 2>&1 | Tee-Object -FilePath (Join-Path $artifactDir 'build.log')
    if ($LASTEXITCODE -ne 0) {
        Write-Host "FAIL: APK build failed" -ForegroundColor Red
        exit 1
    }
}

# Find APK
$apk = Get-ChildItem -Path (Join-Path $Root 'app\build\outputs\apk\debug') -Filter '*.apk' -Recurse | Select-Object -First 1
if (-not $apk) {
    Write-Host "FAIL: Debug APK not found" -ForegroundColor Red
    exit 1
}

# Install on each device
$results = @()
foreach ($serial in $devices) {
    Write-Host "Installing on $serial..."
    $props = Collect-DeviceProperties -Adb $adb -Serial $serial
    & $adb -s $serial install -r -t $apk.FullName 2>&1 | Tee-Object -FilePath (Join-Path $artifactDir "install-$serial.log")
    $installOk = $LASTEXITCODE -eq 0

    # Verify package installed
    $installed = Invoke-AdbShell $adb $serial 'pm list packages com.example.relay'
    $packageOk = $installed -match 'com\.example\.relay'

    $results += @{
        serial    = $serial
        model     = $props.model
        apiLevel  = $props.apiLevel
        installed = $installOk -and $packageOk
    }

    if ($installOk -and $packageOk) {
        Write-Host "  PASS: installed on $serial ($($props.model) API $($props.apiLevel))" -ForegroundColor Green
    } else {
        Write-Host "  FAIL: install failed on $serial" -ForegroundColor Red
    }
}

Write-Summary -ArtifactDir $artifactDir -Summary @{
    script    = 'install-relay'
    timestamp = (Get-Date).ToString('o')
    devices   = $results
    status    = if ($results | Where-Object { -not $_.installed }) { 'FAIL' } else { 'PASS' }
}
