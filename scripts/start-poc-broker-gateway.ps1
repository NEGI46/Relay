<#
.SYNOPSIS
Starts the local PC Gateway for the Quick Tunnel Broker proof of concept.

.DESCRIPTION
Obtains a short-lived, scoped Broker credential directly from the local Docker Broker and keeps it
only in this PowerShell process. The raw credential is never written to the console, a file, or
command history. Run this script from the operator's normal PowerShell session, not from a
sandboxed automation session that blocks outbound HTTPS.
#>
[CmdletBinding()]
param(
    [string]$StateRoot = (Join-Path $env:USERPROFILE '.relay'),
    [string]$DatabasePath,
    [string]$BrokerUrl,
    [ValidateRange(1, 24)]
    [int]$CredentialLifetimeHours = 2,
    [switch]$EnableLanEnrollment
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root

if ([string]::IsNullOrWhiteSpace($DatabasePath)) {
    $DatabasePath = Join-Path $StateRoot 'relay-gateway.db'
}
$rescueKeyFile = Join-Path $StateRoot 'rescue-keys.json'
if (-not (Test-Path -LiteralPath $rescueKeyFile)) {
    throw "Rescue key file is missing: $rescueKeyFile"
}
$shelterId = (Get-Content -LiteralPath $rescueKeyFile -Raw | ConvertFrom-Json).manifest.shelterId
if ([string]::IsNullOrWhiteSpace($shelterId)) { throw 'The local rescue key file has no shelterId.' }

if ([string]::IsNullOrWhiteSpace($BrokerUrl)) {
    $tunnelLog = & docker compose -f compose.quick-tunnel.yml logs --no-log-prefix cloudflared 2>&1
    $match = [regex]::Match(($tunnelLog -join "`n"), 'https://[a-z0-9-]+\.trycloudflare\.com')
    if (-not $match.Success) { throw 'Quick Tunnel URL was not found. Start Docker Compose first.' }
    $BrokerUrl = $match.Value
}
if ($BrokerUrl -notmatch '^https://[^/]+$') { throw 'BrokerUrl must be an HTTPS origin with no path.' }

$expiry = [DateTimeOffset]::UtcNow.AddHours($CredentialLifetimeHours).ToUnixTimeMilliseconds()
$issued = & docker compose -f compose.quick-tunnel.yml exec -T broker java -cp '/opt/relay/lib/*' com.example.relay.broker.MainKt issue-gateway-credential --gateway-id poc-gateway --shelter-id $shelterId --expires-at $expiry 2>&1
if ($LASTEXITCODE -ne 0) { throw 'Broker credential issuance failed.' }
$credentialLine = $issued | Where-Object { $_ -match '^RELAY_BROKER_CREDENTIAL=' } | Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($credentialLine)) { throw 'Broker returned no credential.' }
$credential = $credentialLine.Substring('RELAY_BROKER_CREDENTIAL='.Length)

if (netstat -ano | Select-String -Pattern ':8080\s+.*LISTENING') {
    throw 'Port 8080 is already in use. Stop the existing Gateway, then run this script again.'
}

try {
    $env:RELAY_PROFILE = 'development'
    $env:RELAY_GATEWAY_ID = 'poc-gateway'
    $env:RELAY_SHELTER_ID = $shelterId
    $env:RELAY_BROKER_URL = $BrokerUrl
    $env:RELAY_BROKER_CREDENTIAL = $credential
    $env:RELAY_GATEWAY_DB = $DatabasePath
    $env:RELAY_RESCUE_KEY_FILE = $rescueKeyFile
    $env:RELAY_BLE_BRIDGE_SECRET_FILE = Join-Path $StateRoot 'ble-bridge.key'
    if ($EnableLanEnrollment) {
        $env:RELAY_GATEWAY_HOST = '0.0.0.0'
        $env:RELAY_GATEWAY_LAN_MODE = 'closed-network'
        $env:RELAY_GATEWAY_LAN_DISCOVERY = 'true'
        $env:RELAY_GATEWAY_ANONYMOUS_INGRESS = 'true'
        Write-Host 'Temporary private-LAN enrollment is enabled. Keep the Windows network profile Private.' -ForegroundColor Yellow
    } else {
        $env:RELAY_GATEWAY_HOST = '127.0.0.1'
        $env:RELAY_GATEWAY_LAN_MODE = 'disabled'
        $env:RELAY_GATEWAY_LAN_DISCOVERY = 'false'
        $env:RELAY_GATEWAY_ANONYMOUS_INGRESS = 'false'
    }
    $env:RELAY_GATEWAY_PORT = '8080'
    $env:RELAY_GATEWAY_PUBLIC_PORT = '8080'
    $env:RELAY_GATEWAY_PUBLIC_SCHEME = 'http'
    & .\gradlew.bat ':pc-gateway:run'
    if ($LASTEXITCODE -ne 0) { throw "PC Gateway exited with $LASTEXITCODE" }
} finally {
    Remove-Item Env:RELAY_BROKER_CREDENTIAL -ErrorAction SilentlyContinue
}
