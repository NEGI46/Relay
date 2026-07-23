# Structural + behavioral checks for the development PC Gateway logon autostart script.
# Run: powershell -ExecutionPolicy Bypass -File .\scripts\tests\register-poc-gateway-autostart.tests.ps1
#
# These tests never touch the real Task Scheduler, Docker, or the real %LOCALAPPDATA%. The target
# script is dot-sourced (its Main is guarded) and its thin dependency wrappers are shadowed here.
$ErrorActionPreference = 'Stop'

$scriptsDirectory = Split-Path -Parent $PSScriptRoot
$targetScript = Join-Path $scriptsDirectory 'register-poc-gateway-autostart.ps1'
$failed = 0

function Assert-True($cond, $msg) {
    if (-not $cond) {
        Write-Host "FAIL: $msg" -ForegroundColor Red
        $script:failed++
    } else {
        Write-Host "PASS: $msg"
    }
}

# --- 1. Syntax / parse ------------------------------------------------------

$tokens = $null
$errors = $null
[System.Management.Automation.Language.Parser]::ParseFile($targetScript, [ref]$tokens, [ref]$errors) | Out-Null
Assert-True (($null -eq $errors) -or ($errors.Count -eq 0)) 'autostart script parses without syntax errors'

$scriptText = Get-Content -LiteralPath $targetScript -Raw
Assert-True ($scriptText -match "start-poc-broker-gateway-docker\.ps1") 'reuses the existing Docker start launcher'
Assert-True ($scriptText -match "http://127\.0\.0\.1:8080/api/health") 'checks the documented health endpoint'
Assert-True ($scriptText -match "'Relay-PC-Gateway-Autostart'") 'uses the collision-resistant task name default'
# The autostart log must never contain broker credentials or other secrets.
Assert-True ($scriptText -notmatch 'RELAY_BROKER_CREDENTIAL') 'does not reference the broker credential env var'

# Dot-source to load functions (Main is guarded and will not run).
. $targetScript

# --- 2. Task registration command has proper args --------------------------

$plan = Get-AutostartTaskPlan -TaskName 'Relay-PC-Gateway-Autostart' -ScriptPath 'C:\repo\scripts\register-poc-gateway-autostart.ps1' -ForwardParameters @{ EnableLanEnrollment = $true; BrokerUrl = 'https://example.trycloudflare.com' }

Assert-True ($plan.TaskName -eq 'Relay-PC-Gateway-Autostart') 'plan targets the expected task name'
Assert-True ($plan.Execute -match 'powershell\.exe$') 'plan executes Windows PowerShell'
Assert-True ($plan.TriggerType -eq 'AtLogon') 'plan registers a logon (not startup) trigger'
Assert-True ($plan.LogonType -eq 'Interactive') 'plan runs as the interactive current user'
Assert-True ($plan.RunLevel -eq 'Limited') 'plan runs with current-user (non-elevated) privileges'
Assert-True ($plan.StorePassword -eq $false) 'plan never stores a password'
Assert-True ($plan.Argument -match '-NoProfile') 'action argument uses -NoProfile'
Assert-True ($plan.Argument -match '-NonInteractive') 'action argument uses -NonInteractive'
Assert-True ($plan.Argument -match '-ExecutionPolicy Bypass') 'action argument sets execution policy'
Assert-True ($plan.Argument -match '-File "C:\\repo\\scripts\\register-poc-gateway-autostart\.ps1"') 'action argument invokes the script by file path'
Assert-True ($plan.Argument -match '(^|\s)-Run(\s|$)') 'action argument invokes the internal -Run mode'
Assert-True ($plan.Argument -match '-EnableLanEnrollment') 'switch passthrough is forwarded to the run mode'
Assert-True ($plan.Argument -match '-BrokerUrl "https://example.trycloudflare.com"') 'string passthrough is quoted and forwarded'

# --- 3. -Unregister removes only the target task ---------------------------

$script:unregisterCalls = @()
$script:stopCalls = @()
function Get-ScheduledTask { param([string]$TaskName, $ErrorAction) [pscustomobject]@{ TaskName = $TaskName; State = 'Ready' } }
function Stop-ScheduledTask { param([string]$TaskName, $ErrorAction) $script:stopCalls += $TaskName }
function Unregister-ScheduledTask { param([string]$TaskName, [switch]$Confirm) $script:unregisterCalls += $TaskName }

$removed = Unregister-AutostartTask -TaskName 'Relay-PC-Gateway-Autostart'
Assert-True ($removed -eq $true) 'unregister reports removal when the task exists'
Assert-True ($script:unregisterCalls.Count -eq 1) 'unregister deletes exactly one task'
Assert-True ($script:unregisterCalls[0] -eq 'Relay-PC-Gateway-Autostart') 'unregister deletes only the target task'
Assert-True ($scriptText -notmatch 'Unregister-ScheduledTask\s+-TaskName\s+\*') 'unregister never uses a wildcard task name'
Assert-True ($scriptText -notmatch 'Get-ScheduledTask\b(?![^\r\n]*-TaskName)') 'unregister/status always scope Get-ScheduledTask by task name'

# When the task does not exist, nothing is removed.
$script:unregisterCalls = @()
function Get-ScheduledTask { param([string]$TaskName, $ErrorAction) $null }
$removedNone = Unregister-AutostartTask -TaskName 'Relay-PC-Gateway-Autostart'
Assert-True ($removedNone -eq $false) 'unregister reports nothing removed when task is absent'
Assert-True ($script:unregisterCalls.Count -eq 0) 'unregister does not delete anything when task is absent'

# --- 4. Logs never contain secrets -----------------------------------------

$tempLocalAppData = Join-Path ([System.IO.Path]::GetTempPath()) ('relay-autostart-test-' + [guid]::NewGuid().ToString('N'))
$originalLocalAppData = $env:LOCALAPPDATA
$env:LOCALAPPDATA = $tempLocalAppData
try {
    # Redaction unit checks.
    $red1 = Remove-RelaySecret 'RELAY_BROKER_CREDENTIAL=supersecretvalue1234567890'
    Assert-True ($red1 -notmatch 'supersecretvalue') 'redacts broker credential values'
    $red2 = Remove-RelaySecret 'password: hunter2topsecret'
    Assert-True ($red2 -notmatch 'hunter2topsecret') 'redacts password values'
    $red3 = Remove-RelaySecret '-----BEGIN PRIVATE KEY-----MIIabc123-----END PRIVATE KEY-----'
    Assert-True ($red3 -notmatch 'MIIabc123') 'redacts PEM private key blocks'
    $red4 = Remove-RelaySecret 'latitude=35.68123 longitude=139.76712'
    Assert-True ($red4 -notmatch '35\.68123') 'redacts GPS coordinates'

    # Write path scrubs even if a secret is accidentally passed.
    Write-AutostartLog -Message 'RELAY_BROKER_CREDENTIAL=leakedsecretvalue0987654321' -Level 'INFO'
    $logContent = Get-Content -LiteralPath (Get-RelayLogPath) -Raw
    Assert-True ($logContent -notmatch 'leakedsecretvalue') 'log writer scrubs secrets before writing'
    Assert-True ($logContent -match '\*\*\*REDACTED\*\*\*') 'log writer records a redaction marker'

    # --- 5. Docker-not-ready path times out and does not start the gateway --

    # Fast unit-level timeout: docker info never succeeds.
    function Get-DockerInfoExitCode { return 1 }
    $ready = Wait-DockerReady -TimeoutSeconds 1 -PollSeconds 0
    Assert-True ($ready -eq $false) 'Wait-DockerReady returns false when docker never becomes ready'

    # Integration: run mode must log a clear timeout and must not call the start launcher.
    $script:startCalled = $false
    function Wait-DockerReady { param([int]$TimeoutSeconds, [int]$PollSeconds = 3) return $false }
    function Invoke-GatewayStart { param([string]$StartScriptPath, [hashtable]$ForwardParameters = @{}) $script:startCalled = $true }
    function Test-GatewayHealthy { param([string]$Url) return $false }

    $runResult = Invoke-AutostartRun -HealthUrl 'http://127.0.0.1:8080/api/health' -DockerWaitTimeoutSeconds 30 -HealthTimeoutSeconds 5 -StartScriptPath 'C:\repo\scripts\start-poc-broker-gateway-docker.ps1'
    Assert-True ($runResult.Success -eq $false) 'run fails when Docker never becomes ready'
    Assert-True ($runResult.Reason -eq 'docker-timeout') 'run reports a docker-timeout reason'
    Assert-True ($script:startCalled -eq $false) 'run does not start the gateway when Docker is unavailable'
    $logAfter = Get-Content -LiteralPath (Get-RelayLogPath) -Raw
    Assert-True ($logAfter -match 'Docker did not become ready') 'run logs a clear Docker timeout message'
}
finally {
    if ($null -eq $originalLocalAppData) {
        Remove-Item Env:LOCALAPPDATA -ErrorAction SilentlyContinue
    } else {
        $env:LOCALAPPDATA = $originalLocalAppData
    }
    if (Test-Path -LiteralPath $tempLocalAppData) {
        Remove-Item -LiteralPath $tempLocalAppData -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if ($failed -gt 0) {
    Write-Host "$failed assertion(s) failed" -ForegroundColor Red
    exit 1
}
Write-Host 'All PC Gateway autostart assertions passed'
exit 0
