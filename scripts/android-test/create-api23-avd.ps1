<#
.SYNOPSIS
  Creates or reuses an API 23 AVD for smoke testing.
.DESCRIPTION
  Checks that the Android SDK has the required system image for API 23.
  If the image is installed, creates (or reuses) a test AVD.
  Does NOT delete existing personal AVDs.

  Exit codes:
    0 = AVD ready
    2 = BLOCKED (missing image, license, or tools)
#>
[CmdletBinding()]
param(
    [string]$AvdName = 'relay-api23-test'
)

. (Join-Path $PSScriptRoot 'common-emulator.ps1')

$sdk = Find-AndroidSdk
if (-not $sdk) {
    Write-Host 'BLOCKED_MISSING_IMAGE: Android SDK not found' -ForegroundColor Yellow
    exit 2
}

$avdManager = Get-AvdManager $sdk
if (-not $avdManager) {
    Write-Host 'BLOCKED_MISSING_IMAGE: avdmanager not found (install cmdline-tools)' -ForegroundColor Yellow
    exit 2
}

# Check for API 23 system image
$imagePackage = Get-InstalledSystemImage -SdkPath $sdk -ApiLevel 23
if (-not $imagePackage) {
    Write-Host 'BLOCKED_MISSING_IMAGE: No API 23 system image installed.' -ForegroundColor Yellow
    Write-Host '  Install with: sdkmanager "system-images;android-23;google_apis;x86_64"' -ForegroundColor Cyan
    # Check if sdkmanager is available and list what's available
    $sdkMgr = Get-SdkManager $sdk
    if ($sdkMgr) {
        Write-Host '  Available API 23 images:'
        $available = & $sdkMgr --list 2>&1 | Out-String
        $available -split "`n" | Where-Object { $_ -match 'system-images;android-23' } | ForEach-Object {
            Write-Host "    $_" -ForegroundColor DarkCyan
        }
    }
    exit 2
}

Write-Host "Found API 23 system image: $imagePackage"

# Check if AVD already exists (reuse it)
if (Test-AvdExists -AvdManager $avdManager -AvdName $AvdName) {
    Write-Host "AVD '$AvdName' already exists - reusing"
    exit 0
}

# Create the AVD
Write-Host "Creating AVD '$AvdName' with $imagePackage..."
$createOutput = echo 'no' | & $avdManager create avd `
    --name $AvdName `
    --package $imagePackage `
    --device 'pixel' `
    --force 2>&1 | Out-String

if ($LASTEXITCODE -ne 0) {
    Write-Host "BLOCKED_MISSING_IMAGE: Failed to create AVD" -ForegroundColor Yellow
    Write-Host $createOutput
    exit 2
}

Write-Host "AVD '$AvdName' created successfully" -ForegroundColor Green
exit 0
