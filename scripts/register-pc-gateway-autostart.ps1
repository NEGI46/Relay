param(
    [string]$Executable = "$env:ProgramFiles\RelayPcGateway\RelayPcGateway.exe",
    [string]$TaskName = 'Relay PC Gateway'
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $Executable)) {
    throw "Gateway executable not found: $Executable"
}

# The fixed gateway is infrastructure, so it starts after Windows boots and
# retries after a network interruption. This does not register user phones.
$action = New-ScheduledTaskAction -Execute $Executable
$trigger = New-ScheduledTaskTrigger -AtStartup -RandomDelay (New-TimeSpan -Seconds 30)
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -RunLevel Highest -Force | Out-Null
Write-Output "Registered automatic startup task: $TaskName"
