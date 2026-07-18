[CmdletBinding()]
param(
    [string]$Executable = "$env:ProgramFiles\RelayPcGateway\RelayPcGateway.exe",
    [string]$TaskName = 'Relay PC Gateway',
    [ValidateRange(1, 65535)]
    [int]$Port = 8080,
    [ValidateRange(1, 65535)]
    [int]$DiscoveryPort = 42888,
    [string]$GatewayId = 'pc-gateway-local',
    [ValidateRange(5, 120)]
    [int]$HealthTimeoutSeconds = 30,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

function Test-IsAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

if (-not (Test-Path -LiteralPath $Executable -PathType Leaf)) {
    throw "Relay PC Gateway is not installed: $Executable"
}

$profiles = @(Get-NetConnectionProfile -ErrorAction Stop)
if ($profiles.Count -eq 0) {
    throw 'No Windows network profile is active. Connect the trusted emergency LAN, then run setup again.'
}

$publicProfiles = @($profiles | Where-Object { $_.NetworkCategory -eq 'Public' })
if ($publicProfiles.Count -gt 0) {
    $names = ($publicProfiles | ForEach-Object { "$($_.InterfaceAlias) [$($_.Name)]" }) -join ', '
    throw @"
Safety stop: Public network profile detected: $names
No firewall rule or scheduled task was changed.
Only after confirming this is the trusted, isolated emergency LAN, change it to Private in Windows Settings and run setup again.
"@
}

$nonPrivateProfiles = @($profiles | Where-Object { $_.NetworkCategory -ne 'Private' })
if ($nonPrivateProfiles.Count -gt 0) {
    $categories = ($nonPrivateProfiles | ForEach-Object { "$($_.InterfaceAlias)=$($_.NetworkCategory)" }) -join ', '
    throw "Safety stop: every active network must be Private for this setup ($categories). No changes were made."
}

Write-Output 'Network profile check: PASS (Private only)'
$profiles | ForEach-Object { Write-Output "  $($_.InterfaceAlias): $($_.NetworkCategory)" }

$firewallScript = Join-Path $PSScriptRoot 'configure-pc-gateway-firewall.ps1'
$autostartScript = Join-Path $PSScriptRoot 'register-pc-gateway-autostart.ps1'
if (-not (Test-Path -LiteralPath $firewallScript -PathType Leaf) -or
    -not (Test-Path -LiteralPath $autostartScript -PathType Leaf)) {
    throw 'Required Relay setup scripts are missing.'
}

if ($DryRun) {
    Write-Output 'DRY RUN: no firewall rule, scheduled task, process or database was changed.'
    Write-Output "  Would allow Private-profile TCP/$Port and UDP/$DiscoveryPort."
    Write-Output "  Would register and start supervised task: $TaskName"
    Write-Output "  Would launch: $Executable"
    Write-Output "  Would verify: http://127.0.0.1:$Port/api/health"
    return
}

if (-not (Test-IsAdministrator)) {
    throw 'Run this command once from an elevated Administrator PowerShell. No changes were made.'
}

# Both child scripts are idempotent: firewall rules are replaced by name and the task is registered with -Force.
& $firewallScript -Port $Port -DiscoveryPort $DiscoveryPort
& $autostartScript `
    -Executable $Executable `
    -TaskName $TaskName `
    -HostBind '0.0.0.0' `
    -Port $Port `
    -GatewayId $GatewayId

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction Stop
if ($task.State -ne 'Running') {
    Start-ScheduledTask -TaskName $TaskName
}

$healthUri = "http://127.0.0.1:$Port/api/health"
$deadline = [DateTime]::UtcNow.AddSeconds($HealthTimeoutSeconds)
$health = $null
$taskState = $null
do {
    $taskState = (Get-ScheduledTask -TaskName $TaskName -ErrorAction Stop).State
    try {
        $candidate = Invoke-RestMethod -Uri $healthUri -Method Get -TimeoutSec 2
        if ($candidate.status -eq 'ok') {
            $health = $candidate
        }
    } catch {
        $health = $null
    }
    if ($health -and $taskState -eq 'Running') {
        break
    }
    Start-Sleep -Seconds 1
} while ([DateTime]::UtcNow -lt $deadline)

if (-not $health -or $taskState -ne 'Running') {
    $taskInfo = Get-ScheduledTaskInfo -TaskName $TaskName -ErrorAction SilentlyContinue
    $lastResult = if ($taskInfo) { $taskInfo.LastTaskResult } else { 'unknown' }
    throw "Gateway setup did not become healthy within ${HealthTimeoutSeconds}s (task=$taskState, lastResult=$lastResult). Check port conflicts and Windows Event Viewer."
}

Write-Output 'Relay PC Gateway setup: PASS'
Write-Output "  Task: $TaskName ($taskState, supervised restart enabled)"
Write-Output "  Health: $healthUri"
Write-Output '  Firewall: Private profile only'
Write-Output '  Admin key remains in its local key file and was not printed.'
