[CmdletBinding()]
param(
    [string]$AndroidKeystore,
    [string]$AndroidAlias = 'relay-local-release',
    [string]$AndroidStorePassword = 'relay-local-only',
    [string]$AndroidKeyPassword = 'relay-local-only',
    [string]$WindowsCertificate,
    [string]$WindowsCertificatePassword = 'relay-msix-local-only',
    [string]$WindowsPublisher = 'CN=Relay Local MSIX'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$AndroidKeystore = if ($AndroidKeystore) { $AndroidKeystore } else { Join-Path $root 'signing\relay-local-release.jks' }
$WindowsCertificate = if ($WindowsCertificate) { $WindowsCertificate } else { Join-Path $root 'signing\relay-msix-local.pfx' }
$android = Join-Path $root 'scripts\build-android-artifacts.ps1'
$windows = Join-Path $root 'scripts\build-windows-bridge-artifacts.ps1'
if (-not (Test-Path $AndroidKeystore)) { throw "Local Android keystore not found: $AndroidKeystore" }
if (-not (Test-Path $WindowsCertificate)) { throw "Local Windows certificate not found: $WindowsCertificate" }

& powershell -NoProfile -ExecutionPolicy Bypass -File $android `
    -ReleaseKeystore (Resolve-Path $AndroidKeystore).Path `
    -ReleaseAlias $AndroidAlias `
    -ReleaseStorePassword $AndroidStorePassword `
    -ReleaseKeyPassword $AndroidKeyPassword
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$env:PATH = "C:\tmp\relay-dotnet;$env:PATH"
& powershell -NoProfile -ExecutionPolicy Bypass -File $windows `
    -Publisher $WindowsPublisher `
    -CertificateFile (Resolve-Path $WindowsCertificate).Path `
    -CertificatePassword $WindowsCertificatePassword
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Output "Local debug/release artifacts generated under $root\distribution\Relay-User-Package"
