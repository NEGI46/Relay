[CmdletBinding()]
param(
    [string]$Executable = "$env:ProgramFiles\RelayPcGateway\RelayPcGateway.exe",
    [string]$TaskName = 'Relay PC Gateway',
    [string]$HostBind = '0.0.0.0',
    [ValidateRange(1, 65535)]
    [int]$Port = 8080,
    [string]$GatewayId = 'pc-gateway-local',
    [string]$AdminKey = '',
    [string]$DbPath = '',
    [string]$RunnerPath = "$env:ProgramData\RelayPcGateway\run-gateway.ps1"
)

$ErrorActionPreference = 'Stop'

function Test-IsAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function ConvertTo-SingleQuotedLiteral([string]$Value) {
    return "'" + $Value.Replace("'", "''") + "'"
}

if (-not (Test-IsAdministrator)) {
    throw 'Run this script from an elevated Administrator PowerShell.'
}
if (-not (Test-Path -LiteralPath $Executable -PathType Leaf)) {
    throw "Gateway executable not found: $Executable"
}
if ([string]::IsNullOrWhiteSpace($TaskName) -or $TaskName.Length -gt 120) {
    throw 'TaskName must contain 1 to 120 characters.'
}
if ($GatewayId -notmatch '^[A-Za-z0-9._-]{1,64}$') {
    throw 'GatewayId must contain only letters, numbers, dot, underscore or hyphen (1-64 characters).'
}
$parsedAddress = $null
if (-not [Net.IPAddress]::TryParse($HostBind, [ref]$parsedAddress)) {
    throw 'HostBind must be a valid IP address.'
}

$Executable = [IO.Path]::GetFullPath($Executable)
$RunnerPath = [IO.Path]::GetFullPath($RunnerPath)
$relayDir = Join-Path $env:USERPROFILE '.relay'
New-Item -ItemType Directory -Path $relayDir -Force | Out-Null
$adminKeyFile = Join-Path $relayDir 'admin.key'

# The secret is stored in a file and never embedded in task arguments or runner source.
if ([string]::IsNullOrWhiteSpace($AdminKey)) {
    if (-not (Test-Path -LiteralPath $adminKeyFile -PathType Leaf) -or
        [string]::IsNullOrWhiteSpace((Get-Content -LiteralPath $adminKeyFile -Raw))) {
        [guid]::NewGuid().ToString() | Set-Content -LiteralPath $adminKeyFile -Encoding ascii
        Write-Output "Generated admin key file: $adminKeyFile"
    }
} else {
    $AdminKey.Trim() | Set-Content -LiteralPath $adminKeyFile -Encoding ascii
}

if ([string]::IsNullOrWhiteSpace($DbPath)) {
    $DbPath = Join-Path $relayDir 'relay-gateway.db'
}
$DbPath = [IO.Path]::GetFullPath($DbPath)

$runnerDirectory = Split-Path -Parent $RunnerPath
New-Item -ItemType Directory -Path $runnerDirectory -Force | Out-Null
$executableDirectory = Split-Path -Parent $Executable
$runnerLines = @(
    "`$ErrorActionPreference = 'Stop'"
    "`$env:RELAY_GATEWAY_HOST = $(ConvertTo-SingleQuotedLiteral $HostBind)"
    "`$env:RELAY_GATEWAY_PORT = $(ConvertTo-SingleQuotedLiteral $Port.ToString())"
    "`$env:RELAY_GATEWAY_ID = $(ConvertTo-SingleQuotedLiteral $GatewayId)"
    "`$env:RELAY_GATEWAY_DB = $(ConvertTo-SingleQuotedLiteral $DbPath)"
    "`$env:RELAY_GATEWAY_ADMIN_KEY_FILE = $(ConvertTo-SingleQuotedLiteral $adminKeyFile)"
    "`$env:RELAY_GATEWAY_ANONYMOUS_INGRESS = 'true'"
    "`$env:RELAY_GATEWAY_LAN_DISCOVERY = 'true'"
    "Set-Location -LiteralPath $(ConvertTo-SingleQuotedLiteral $executableDirectory)"
    "& $(ConvertTo-SingleQuotedLiteral $Executable)"
    '# Treat every unexpected gateway exit as a task failure so Task Scheduler restarts it.'
    "if (`$null -eq `$LASTEXITCODE -or `$LASTEXITCODE -eq 0) { exit 1 }"
    "exit `$LASTEXITCODE"
)
$runnerLines | Set-Content -LiteralPath $RunnerPath -Encoding UTF8

$powerShellExe = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$actionArguments = "-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$RunnerPath`""
$action = New-ScheduledTaskAction -Execute $powerShellExe -Argument $actionArguments
$trigger = New-ScheduledTaskTrigger -AtStartup -RandomDelay (New-TimeSpan -Seconds 30)
$settings = New-ScheduledTaskSettingsSet `
    -StartWhenAvailable `
    -RestartCount 999 `
    -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -MultipleInstances IgnoreNew `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest

$existingTask = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($existingTask -and $existingTask.State -eq 'Running') {
    Stop-ScheduledTask -TaskName $TaskName
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        if ((Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue).State -ne 'Running') {
            break
        }
        Start-Sleep -Milliseconds 250
    }
    if ((Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue).State -eq 'Running') {
        throw "Existing task did not stop safely: $TaskName"
    }
}
Register-ScheduledTask `
    -TaskName $TaskName `
    -Action $action `
    -Trigger $trigger `
    -Settings $settings `
    -Principal $principal `
    -Force | Out-Null

Write-Output "Registered supervised automatic startup task: $TaskName"
Write-Output "  Executable: $Executable"
Write-Output "  Runner: $RunnerPath"
Write-Output "  Host: $HostBind  Port: $Port"
Write-Output "  DB: $DbPath"
Write-Output "  Admin key file: $adminKeyFile (key value not printed)"
