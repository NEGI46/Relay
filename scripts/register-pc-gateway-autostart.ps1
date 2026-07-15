param(
    [string]$Executable = "$env:ProgramFiles\RelayPcGateway\RelayPcGateway.exe",
    [string]$TaskName = 'Relay PC Gateway',
    [string]$HostBind = '0.0.0.0',
    [string]$Port = '8080',
    [string]$GatewayId = 'pc-gateway-local',
    [string]$AdminKey = '',
    [string]$DbPath = ''
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $Executable)) {
    throw "Gateway executable not found: $Executable"
}

# Persist operator admin key so restarts keep dashboard/pairing access.
$relayDir = Join-Path $env:USERPROFILE '.relay'
if (-not (Test-Path -LiteralPath $relayDir)) {
    New-Item -ItemType Directory -Path $relayDir | Out-Null
}
$adminKeyFile = Join-Path $relayDir 'admin.key'
if ([string]::IsNullOrWhiteSpace($AdminKey)) {
    if (Test-Path -LiteralPath $adminKeyFile) {
        $AdminKey = (Get-Content -LiteralPath $adminKeyFile -Raw).Trim()
    } else {
        $AdminKey = [guid]::NewGuid().ToString()
        Set-Content -LiteralPath $adminKeyFile -Value $AdminKey -Encoding ascii
        Write-Output "Generated admin key file: $adminKeyFile"
    }
} else {
    Set-Content -LiteralPath $adminKeyFile -Value $AdminKey.Trim() -Encoding ascii
}

if ([string]::IsNullOrWhiteSpace($DbPath)) {
    $DbPath = Join-Path $relayDir 'relay-gateway.db'
}

# Scheduled tasks do not inherit interactive env; bake zero-op LAN defaults into the action.
$argList = @(
    "-NoProfile"
    "-WindowStyle"
    "Hidden"
    "-Command"
    ("`$env:RELAY_GATEWAY_HOST='$HostBind'; " +
     "`$env:RELAY_GATEWAY_PORT='$Port'; " +
     "`$env:RELAY_GATEWAY_ID='$GatewayId'; " +
     "`$env:RELAY_GATEWAY_DB='$DbPath'; " +
     "`$env:RELAY_GATEWAY_ADMIN_KEY='$AdminKey'; " +
     "`$env:RELAY_GATEWAY_ANONYMOUS_INGRESS='true'; " +
     "`$env:RELAY_GATEWAY_LAN_DISCOVERY='true'; " +
     "Start-Process -FilePath '$Executable' -WorkingDirectory (Split-Path -Parent '$Executable')")
)

$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ($argList -join ' ')
$trigger = New-ScheduledTaskTrigger -AtStartup -RandomDelay (New-TimeSpan -Seconds 30)
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -RunLevel Highest -Force | Out-Null

Write-Output "Registered automatic startup task: $TaskName"
Write-Output "  Executable: $Executable"
Write-Output "  Host: $HostBind  Port: $Port"
Write-Output "  DB: $DbPath"
Write-Output "  Admin key file: $adminKeyFile (key value not printed)"
Write-Output "Next: allow TCP $Port and UDP 42888 on Private network only."
Write-Output "  .\scripts\configure-pc-gateway-firewall.ps1 -Port $Port"
