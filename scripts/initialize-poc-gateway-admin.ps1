<#
.SYNOPSIS
Creates the one-time local administrator for the Quick Tunnel proof of concept.

.DESCRIPTION
Reads a username and password interactively, and passes the password only through a process-local
environment variable to Relay's `bootstrap-admin` command. The password is never an argument,
file, or console output. This script targets the temporary PoC database under C:\tmp by default.
#>
[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]{2,63}$')]
    [string]$Username,
    [securestring]$Password,
    [string]$StateRoot = 'C:\tmp\relay-broker-poc',
    [string]$DatabasePath,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root

if ([string]::IsNullOrWhiteSpace($Username)) {
    $Username = Read-Host 'Administrator username (3-64 chars: letters, digits, . _ -)'
}
if ($Username -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]{2,63}$') {
    throw 'Username must be 3-64 characters and use only letters, digits, dot, underscore, or hyphen.'
}
if ($null -eq $Password) {
    $Password = Read-Host 'Administrator password (12+ characters)' -AsSecureString
}

$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Password)
try {
    $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    if ($plainPassword.Length -lt 12) { throw 'Password must be at least 12 characters.' }

    $gateway = Join-Path $root 'pc-gateway\build\install\pc-gateway\bin\pc-gateway.bat'
    if (-not $SkipBuild) {
        # Always rebuild: an existing installDist can belong to an older Relay revision.
        & (Join-Path $root 'gradlew.bat') ':pc-gateway:installDist'
        if ($LASTEXITCODE -ne 0) { throw "PC Gateway build failed with exit $LASTEXITCODE" }
    }
    if (-not (Test-Path -LiteralPath $gateway)) {
        throw "PC Gateway launcher is missing: $gateway"
    }

    # Match the temporary Gateway started for this PoC. Do not use this script for production DBs.
    if ([string]::IsNullOrWhiteSpace($DatabasePath)) {
        $DatabasePath = Join-Path $StateRoot 'gateway.db'
    }
    $rescueKeyFile = Join-Path $StateRoot 'rescue-keys.json'
    if (-not (Test-Path -LiteralPath $rescueKeyFile)) {
        throw "Rescue key file is missing: $rescueKeyFile"
    }
    # Match the local key's public shelter identity. The document is never displayed or logged.
    $shelterId = (Get-Content -LiteralPath $rescueKeyFile -Raw | ConvertFrom-Json).manifest.shelterId
    if ([string]::IsNullOrWhiteSpace($shelterId)) {
        throw 'The local rescue key file has no shelterId.'
    }
    $env:RELAY_PROFILE = 'development'
    $env:RELAY_GATEWAY_LAN_MODE = 'disabled'
    $env:RELAY_GATEWAY_ID = 'poc-gateway'
    $env:RELAY_SHELTER_ID = $shelterId
    $env:RELAY_GATEWAY_DB = $DatabasePath
    $env:RELAY_RESCUE_KEY_FILE = $rescueKeyFile
    $env:RELAY_BLE_BRIDGE_SECRET_FILE = Join-Path $StateRoot 'ble-bridge.key'
    $env:RELAY_GATEWAY_BOOTSTRAP_CLI_SECRET = $plainPassword

    & $gateway bootstrap-admin --username $Username
    if ($LASTEXITCODE -ne 0) { throw "Administrator bootstrap failed with exit $LASTEXITCODE. It can only be performed once." }
    Write-Host "Administrator '$Username' was created for the PoC Gateway. The password was not printed or saved." -ForegroundColor Green
} finally {
    Remove-Item Env:RELAY_GATEWAY_BOOTSTRAP_CLI_SECRET -ErrorAction SilentlyContinue
    if ($null -ne $bstr -and $bstr -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}
