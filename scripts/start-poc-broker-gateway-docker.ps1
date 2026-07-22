<#
.SYNOPSIS
Runs the PC Gateway inside Docker for the Quick Tunnel proof of concept.

.DESCRIPTION
Use this launcher when host outbound HTTPS is restricted. The Gateway's Broker pull runs on the
Docker network while its operator console remains on port 8080. The short-lived scoped Broker
credential exists only in the current PowerShell environment and the running PoC container.
#>
[CmdletBinding()]
param(
    [string]$StateRoot = (Join-Path $env:USERPROFILE '.relay'),
    [string]$BrokerUrl,
    [ValidateRange(1, 24)]
    [int]$CredentialLifetimeHours = 2,
    [switch]$EnableLanEnrollment
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root
$stateRootPath = (Resolve-Path $StateRoot).Path
$rescueKeyFile = Join-Path $stateRootPath 'rescue-keys.json'
if (-not (Test-Path -LiteralPath $rescueKeyFile)) { throw "Rescue key file is missing: $rescueKeyFile" }
$shelterId = (Get-Content -LiteralPath $rescueKeyFile -Raw | ConvertFrom-Json).manifest.shelterId
if ([string]::IsNullOrWhiteSpace($shelterId)) { throw 'The local rescue key file has no shelterId.' }

# Replace only a prior Docker Gateway run. A separate host process still requires explicit stop.
$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$stopOutput = & docker compose -f compose.quick-tunnel.yml stop pc-gateway 2>&1
$ErrorActionPreference = $previousErrorActionPreference
if ($LASTEXITCODE -ne 0) { throw 'Could not stop the prior Docker PC Gateway.' }
if (netstat -ano | Select-String -Pattern ':8080\s+.*LISTENING') {
    throw 'Port 8080 is already in use. Stop the host Gateway before starting the Docker Gateway.'
}
if ([string]::IsNullOrWhiteSpace($BrokerUrl)) {
    $tunnelLog = & docker compose -f compose.quick-tunnel.yml logs --no-log-prefix cloudflared 2>&1
    $match = [regex]::Match(($tunnelLog -join "`n"), 'https://[a-z0-9-]+\.trycloudflare\.com')
    if (-not $match.Success) { throw 'Quick Tunnel URL was not found. Start Docker Compose first.' }
    $BrokerUrl = $match.Value
}
if ($BrokerUrl -notmatch '^https://[^/]+$') { throw 'BrokerUrl must be an HTTPS origin with no path.' }

& .\gradlew.bat ':pc-gateway:installDist'
if ($LASTEXITCODE -ne 0) { throw "PC Gateway build failed with exit $LASTEXITCODE" }
$expiry = [DateTimeOffset]::UtcNow.AddHours($CredentialLifetimeHours).ToUnixTimeMilliseconds()
$issued = & docker compose -f compose.quick-tunnel.yml exec -T broker java -cp '/opt/relay/lib/*' com.example.relay.broker.MainKt issue-gateway-credential --gateway-id poc-gateway --shelter-id $shelterId --expires-at $expiry 2>&1
if ($LASTEXITCODE -ne 0) { throw 'Broker credential issuance failed.' }
$credentialLine = $issued | Where-Object { $_ -match '^RELAY_BROKER_CREDENTIAL=' } | Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($credentialLine)) { throw 'Broker returned no credential.' }

try {
    $env:RELAY_POC_STATE_ROOT = $stateRootPath
    $env:RELAY_GATEWAY_ID = 'poc-gateway'
    $env:RELAY_SHELTER_ID = $shelterId
    $env:RELAY_BROKER_URL = $BrokerUrl
    $env:RELAY_BROKER_CREDENTIAL = $credentialLine.Substring('RELAY_BROKER_CREDENTIAL='.Length)
    if ($EnableLanEnrollment) {
        $env:RELAY_GATEWAY_BIND_ADDRESS = '0.0.0.0'
        $env:RELAY_GATEWAY_LAN_MODE = 'closed-network'
        $env:RELAY_GATEWAY_LAN_DISCOVERY = 'true'
        $env:RELAY_GATEWAY_ANONYMOUS_INGRESS = 'true'
        $env:RELAY_GATEWAY_DOCKER_LOCAL_OPERATOR = 'false'
    } else {
        $env:RELAY_GATEWAY_BIND_ADDRESS = '127.0.0.1'
        $env:RELAY_GATEWAY_LAN_MODE = 'disabled'
        $env:RELAY_GATEWAY_LAN_DISCOVERY = 'false'
        $env:RELAY_GATEWAY_ANONYMOUS_INGRESS = 'false'
        $env:RELAY_GATEWAY_DOCKER_LOCAL_OPERATOR = 'true'
    }
    & docker compose -f compose.quick-tunnel.yml up -d --build pc-gateway
    if ($LASTEXITCODE -ne 0) { throw "Docker Gateway startup failed with exit $LASTEXITCODE" }
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    $healthy = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/health' -TimeoutSec 3 | Out-Null
            $healthy = $true
            break
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    if (-not $healthy) { throw 'Docker PC Gateway did not become healthy within 30 seconds. Inspect its logs with docker compose -f compose.quick-tunnel.yml logs pc-gateway.' }
    Write-Host 'Docker PC Gateway is running at http://127.0.0.1:8080/.' -ForegroundColor Green
    Write-Host 'Inspect non-sensitive logs with: docker compose -f compose.quick-tunnel.yml logs -f pc-gateway'
} finally {
    Remove-Item Env:RELAY_BROKER_CREDENTIAL -ErrorAction SilentlyContinue
}
