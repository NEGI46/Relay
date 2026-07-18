[CmdletBinding()]
param(
    [string]$ReleaseKeystore = $env:RELAY_RELEASE_KEYSTORE,
    [string]$ReleaseAlias = $env:RELAY_RELEASE_ALIAS,
    [string]$ReleaseStorePassword = $env:RELAY_RELEASE_STORE_PASSWORD,
    [string]$ReleaseKeyPassword = $env:RELAY_RELEASE_KEY_PASSWORD
)

$ErrorActionPreference = 'Stop'
$rootScript = Join-Path $PSScriptRoot '..\..\..\scripts\build-android-artifacts.ps1'
& powershell -NoProfile -ExecutionPolicy Bypass -File (Resolve-Path $rootScript).Path `
    -ReleaseKeystore $ReleaseKeystore -ReleaseAlias $ReleaseAlias `
    -ReleaseStorePassword $ReleaseStorePassword -ReleaseKeyPassword $ReleaseKeyPassword
exit $LASTEXITCODE
