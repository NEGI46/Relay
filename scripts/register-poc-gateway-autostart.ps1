<#
.SYNOPSIS
Windows ログオン時に development プロファイルの PC Gateway (Docker) を自動起動する補助スクリプト。

.DESCRIPTION
実証 (development / PoC) 用途のみを想定しています。Windows Task Scheduler に「ログオン時」タスクを
登録し、現在のユーザー権限で（パスワードを保存せずに）Docker ベースの PC Gateway を復旧します。
本番用の scripts/register-pc-gateway-autostart.ps1（native EXE / SYSTEM 常駐）とは別系統であり、
本番運用機能は追加しません。

起動処理は既存の scripts/start-poc-broker-gateway-docker.ps1 を利用します。
Docker Desktop の起動完了を待ち、Gateway の health を確認し、結果を秘密情報を含めずにログへ記録します。

.NOTES
Broker 資格情報・管理者パスワード・救助内容・GPS・秘密鍵は一切ログへ出力しません。
Windows 以外では明確に失敗し、何も変更しません。
#>
[CmdletBinding(DefaultParameterSetName = 'Register')]
param(
    # 既定動作: ログオン時タスクを登録する。
    [Parameter(ParameterSetName = 'Register')]
    [switch]$Register,

    # タスクから内部的に呼び出される実行モード（手動指定は不要）。
    [Parameter(ParameterSetName = 'Run')]
    [switch]$Run,

    # 登録済みタスクを安全に削除する。
    [Parameter(ParameterSetName = 'Unregister')]
    [switch]$Unregister,

    # タスク登録状況 / Docker 稼働状況 / Gateway health のみ表示する。
    [Parameter(ParameterSetName = 'Status')]
    [switch]$Status,

    [string]$TaskName = 'Relay-PC-Gateway-Autostart',

    [ValidateRange(30, 3600)]
    [int]$DockerWaitTimeoutSeconds = 300,

    [ValidateRange(5, 300)]
    [int]$HealthTimeoutSeconds = 30,

    [string]$HealthUrl = 'http://127.0.0.1:8080/api/health',

    # 以下は start-poc-broker-gateway-docker.ps1 への任意の非秘密パススルー。
    [string]$BrokerUrl,
    [string]$StateRoot,
    [switch]$EnableLanEnrollment,
    [ValidateRange(0, 24)]
    [int]$CredentialLifetimeHours = 0
)

$ErrorActionPreference = 'Stop'

# --- OS ガード -------------------------------------------------------------

function Test-IsWindowsOs {
    # Windows PowerShell 5.1 には $IsWindows が存在しない（=Windows 上でのみ動作）。
    if ($null -ne (Get-Variable -Name IsWindows -ErrorAction SilentlyContinue)) {
        return [bool]$IsWindows
    }
    return $true
}

function Assert-Windows {
    if (-not (Test-IsWindowsOs)) {
        throw 'This script supports Windows only. No changes were made.'
    }
}

# --- ログ（秘密情報を含めない） -------------------------------------------

function Get-RelayLogPath {
    $base = $env:LOCALAPPDATA
    if ([string]::IsNullOrWhiteSpace($base)) {
        $base = Join-Path $env:USERPROFILE 'AppData\Local'
    }
    return Join-Path $base 'Relay\logs\pc-gateway-autostart.log'
}

function Remove-RelaySecret {
    # 万一ログ経路へ秘密情報が混入しても出力されないようにする多層防御。
    param([string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return $Text }
    $redacted = $Text
    # key=value / key: value 形式の資格情報・トークン・パスワード・救助関連。
    $redacted = [regex]::Replace(
        $redacted,
        '(?i)((?:RELAY_[A-Z0-9_]*)?(?:credential|password|passwd|secret|token|api[_-]?key|private[_-]?key|rescue)[A-Za-z0-9_]*)\s*[:=]\s*("?)[^\s"]+("?)',
        '$1=***REDACTED***')
    # PEM 秘密鍵ブロック。
    $redacted = [regex]::Replace(
        $redacted,
        '(?is)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----',
        '***REDACTED_PRIVATE_KEY***')
    # GPS 座標。
    $redacted = [regex]::Replace(
        $redacted,
        '(?i)\b(lat|latitude|lon|lng|longitude|gps)\b\s*[:=]\s*-?\d{1,3}\.\d+',
        '$1=***REDACTED***')
    # 長い不透明トークン（base64/hex 相当、40 文字以上）。
    $redacted = [regex]::Replace($redacted, '\b[A-Za-z0-9+/=_-]{40,}\b', '***REDACTED***')
    return $redacted
}

function Write-AutostartLog {
    param(
        [Parameter(Mandatory = $true)][string]$Message,
        [ValidateSet('INFO', 'WARN', 'ERROR')][string]$Level = 'INFO'
    )
    $logPath = Get-RelayLogPath
    $logDir = Split-Path -Parent $logPath
    if (-not (Test-Path -LiteralPath $logDir)) {
        New-Item -ItemType Directory -Path $logDir -Force | Out-Null
    }
    $safe = Remove-RelaySecret $Message
    $line = ('{0} [{1}] {2}' -f (Get-Date).ToString('yyyy-MM-ddTHH:mm:sszzz'), $Level, $safe)
    Add-Content -LiteralPath $logPath -Value $line -Encoding UTF8
}

# --- Docker / Gateway 判定（テストで差し替え可能な薄いラッパー） -----------

function Get-DockerInfoExitCode {
    # Discard every stream: a stopped daemon writes to stderr, and command-not-found throws.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    try {
        & docker info *> $null
        if ($null -eq $LASTEXITCODE) { return 1 }
        return $LASTEXITCODE
    } catch {
        return 1
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Wait-DockerReady {
    param(
        [int]$TimeoutSeconds,
        [int]$PollSeconds = 3
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ((Get-DockerInfoExitCode) -eq 0) { return $true }
        if ($PollSeconds -gt 0) { Start-Sleep -Seconds $PollSeconds }
    }
    return ((Get-DockerInfoExitCode) -eq 0)
}

function Invoke-GatewayHealthRequest {
    param([string]$Url)
    Invoke-RestMethod -Uri $Url -TimeoutSec 3 -Method Get | Out-Null
}

function Test-GatewayHealthy {
    param([string]$Url)
    try {
        Invoke-GatewayHealthRequest -Url $Url
        return $true
    } catch {
        return $false
    }
}

function Wait-GatewayHealthy {
    param(
        [string]$Url,
        [int]$TimeoutSeconds,
        [int]$PollSeconds = 1
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-GatewayHealthy -Url $Url) { return $true }
        if ($PollSeconds -gt 0) { Start-Sleep -Seconds $PollSeconds }
    }
    return (Test-GatewayHealthy -Url $Url)
}

function Invoke-GatewayStart {
    param(
        [string]$StartScriptPath,
        [hashtable]$ForwardParameters = @{}
    )
    if (-not (Test-Path -LiteralPath $StartScriptPath)) {
        throw "Gateway start script not found: $StartScriptPath"
    }
    # 起動スクリプトの標準出力は取り込まない（非秘密の URL 等でもログを汚さない）。
    & $StartScriptPath @ForwardParameters
    if (($null -ne $LASTEXITCODE) -and ($LASTEXITCODE -ne 0)) {
        throw "Gateway start script exited with code $LASTEXITCODE"
    }
}

# --- タスク定義（純粋関数：ScheduledTasks モジュール無しでも検証可能） ------

function Get-AutostartPowerShellPath {
    return (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe')
}

function Get-AutostartActionArgument {
    param(
        [string]$ScriptPath,
        [hashtable]$ForwardParameters = @{}
    )
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append('-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass')
    [void]$sb.Append(' -File "').Append($ScriptPath).Append('"')
    [void]$sb.Append(' -Run')
    foreach ($key in ($ForwardParameters.Keys | Sort-Object)) {
        $value = $ForwardParameters[$key]
        if (($value -is [bool]) -or ($value -is [switch])) {
            if ([bool]$value) { [void]$sb.Append(' -').Append($key) }
        } else {
            $escaped = ([string]$value).Replace('"', '\"')
            [void]$sb.Append(' -').Append($key).Append(' "').Append($escaped).Append('"')
        }
    }
    return $sb.ToString()
}

function Get-AutostartTaskPlan {
    param(
        [string]$TaskName,
        [string]$ScriptPath,
        [hashtable]$ForwardParameters = @{}
    )
    return [pscustomobject]@{
        TaskName      = $TaskName
        Execute       = Get-AutostartPowerShellPath
        Argument      = Get-AutostartActionArgument -ScriptPath $ScriptPath -ForwardParameters $ForwardParameters
        TriggerType   = 'AtLogon'
        UserId        = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        LogonType     = 'Interactive'  # 現在のユーザー。パスワードは保存しない。
        RunLevel      = 'Limited'      # 現在のユーザー権限（昇格しない）。
        StorePassword = $false
    }
}

function Register-AutostartTask {
    param([pscustomobject]$Plan)
    $action = New-ScheduledTaskAction -Execute $Plan.Execute -Argument $Plan.Argument
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $Plan.UserId -RandomDelay (New-TimeSpan -Seconds 30)
    $settings = New-ScheduledTaskSettingsSet `
        -StartWhenAvailable `
        -MultipleInstances IgnoreNew `
        -AllowStartIfOnBatteries `
        -DontStopIfGoingOnBatteries `
        -ExecutionTimeLimit (New-TimeSpan -Hours 1)
    $principal = New-ScheduledTaskPrincipal -UserId $Plan.UserId -LogonType Interactive -RunLevel Limited
    Register-ScheduledTask `
        -TaskName $Plan.TaskName `
        -Action $action `
        -Trigger $trigger `
        -Settings $settings `
        -Principal $principal `
        -Force | Out-Null
}

function Unregister-AutostartTask {
    param([string]$TaskName)
    $existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if (-not $existing) { return $false }
    if ($existing.State -eq 'Running') {
        Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    }
    # 対象タスクのみを削除する（ワイルドカードや他タスクには触れない）。
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    return $true
}

function Get-AutostartStatus {
    param(
        [string]$TaskName,
        [string]$HealthUrl
    )
    $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    $taskState = 'NotRegistered'
    if ($task) { $taskState = [string]$task.State }
    return [pscustomobject]@{
        TaskName       = $TaskName
        TaskRegistered = [bool]$task
        TaskState      = $taskState
        DockerReady    = ((Get-DockerInfoExitCode) -eq 0)
        GatewayHealthy = (Test-GatewayHealthy -Url $HealthUrl)
    }
}

function Show-AutostartStatus {
    param(
        [string]$TaskName,
        [string]$HealthUrl
    )
    $s = Get-AutostartStatus -TaskName $TaskName -HealthUrl $HealthUrl
    Write-Output 'Relay PC Gateway autostart status:'
    Write-Output ('  Task registered : {0}' -f $s.TaskRegistered)
    Write-Output ('  Task state      : {0}' -f $s.TaskState)
    Write-Output ('  Docker ready    : {0}' -f $s.DockerReady)
    Write-Output ('  Gateway healthy : {0}' -f $s.GatewayHealthy)
}

# --- 実行モード（タスクが呼び出す本体） -----------------------------------

function Invoke-AutostartRun {
    param(
        [string]$HealthUrl,
        [int]$DockerWaitTimeoutSeconds,
        [int]$DockerPollSeconds = 3,
        [int]$HealthTimeoutSeconds,
        [string]$StartScriptPath,
        [hashtable]$ForwardParameters = @{}
    )
    Assert-Windows
    Write-AutostartLog 'PC Gateway autostart run started.'
    Write-AutostartLog ('Waiting for Docker Desktop (timeout {0}s).' -f $DockerWaitTimeoutSeconds)
    if (-not (Wait-DockerReady -TimeoutSeconds $DockerWaitTimeoutSeconds -PollSeconds $DockerPollSeconds)) {
        Write-AutostartLog -Level ERROR -Message ('Docker did not become ready within {0}s. Start Docker Desktop and retry.' -f $DockerWaitTimeoutSeconds)
        return [pscustomobject]@{ Success = $false; Reason = 'docker-timeout' }
    }
    Write-AutostartLog 'Docker Desktop is ready.'

    if (Test-GatewayHealthy -Url $HealthUrl) {
        Write-AutostartLog 'Gateway already healthy; skipping start (no double start).'
        return [pscustomobject]@{ Success = $true; Reason = 'already-healthy' }
    }

    Write-AutostartLog 'Gateway not healthy; starting via start-poc-broker-gateway-docker.ps1.'
    try {
        Invoke-GatewayStart -StartScriptPath $StartScriptPath -ForwardParameters $ForwardParameters
    } catch {
        Write-AutostartLog -Level ERROR -Message ('Gateway start script failed: {0}' -f (Remove-RelaySecret $_.Exception.Message))
        return [pscustomobject]@{ Success = $false; Reason = 'start-failed' }
    }

    if (Wait-GatewayHealthy -Url $HealthUrl -TimeoutSeconds $HealthTimeoutSeconds) {
        Write-AutostartLog 'Gateway healthy after start.'
        return [pscustomobject]@{ Success = $true; Reason = 'started' }
    }
    Write-AutostartLog -Level ERROR ('Gateway did not become healthy within {0}s after start.' -f $HealthTimeoutSeconds)
    return [pscustomobject]@{ Success = $false; Reason = 'unhealthy' }
}

# --- ディスパッチ ----------------------------------------------------------

function Main {
    $scriptPath = $PSCommandPath
    $startScriptPath = Join-Path (Split-Path -Parent $scriptPath) 'start-poc-broker-gateway-docker.ps1'

    # 提供された引数から非秘密のパススルーのみを組み立てる。
    $forward = @{}
    if ($PSBoundParameters.ContainsKey('BrokerUrl') -and $BrokerUrl) { $forward['BrokerUrl'] = $BrokerUrl }
    if ($PSBoundParameters.ContainsKey('StateRoot') -and $StateRoot) { $forward['StateRoot'] = $StateRoot }
    if ($EnableLanEnrollment) { $forward['EnableLanEnrollment'] = $true }
    if ($CredentialLifetimeHours -gt 0) { $forward['CredentialLifetimeHours'] = $CredentialLifetimeHours }

    switch ($PSCmdlet.ParameterSetName) {
        'Run' {
            Assert-Windows
            $result = Invoke-AutostartRun `
                -HealthUrl $HealthUrl `
                -DockerWaitTimeoutSeconds $DockerWaitTimeoutSeconds `
                -HealthTimeoutSeconds $HealthTimeoutSeconds `
                -StartScriptPath $startScriptPath `
                -ForwardParameters $forward
            if (-not $result.Success) { exit 1 }
            exit 0
        }
        'Unregister' {
            Assert-Windows
            $removed = Unregister-AutostartTask -TaskName $TaskName
            if ($removed) {
                Write-Output "Removed scheduled task: $TaskName"
            } else {
                Write-Output "No scheduled task named '$TaskName' was registered."
            }
        }
        'Status' {
            Assert-Windows
            Show-AutostartStatus -TaskName $TaskName -HealthUrl $HealthUrl
        }
        default {
            # Register
            Assert-Windows
            if (-not (Test-Path -LiteralPath $startScriptPath)) {
                throw "Required start script not found: $startScriptPath"
            }
            $plan = Get-AutostartTaskPlan -TaskName $TaskName -ScriptPath $scriptPath -ForwardParameters $forward
            Register-AutostartTask -Plan $plan
            Write-Output "Registered logon autostart task: $TaskName"
            Write-Output ("  Runs as : {0} (current user, no stored password)" -f $plan.UserId)
            Write-Output "  Trigger : at logon"
            Write-Output ("  Log     : {0}" -f (Get-RelayLogPath))
            Write-Output "  Remove with: -Unregister   Inspect with: -Status"
        }
    }
}

# ドットソース（テスト）時は Main を実行しない。
if ($MyInvocation.InvocationName -ne '.') {
    Main
}
