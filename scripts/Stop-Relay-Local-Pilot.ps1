<# Stops only the PID explicitly supplied by the local pilot caller. #>
[CmdletBinding(SupportsShouldProcess)] param([int]$ProcessId)
$stateFile=Join-Path $env:LOCALAPPDATA 'Relay\local-pilot\run.json'
if(-not $ProcessId -and (Test-Path -LiteralPath $stateFile)){$state=Get-Content -Raw -LiteralPath $stateFile|ConvertFrom-Json;$ProcessId=[int]$state.processId}
if(-not $ProcessId){throw 'No managed local-pilot PID exists. Refusing to stop an unspecified process.'}
$p=Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
if($null -eq $p){Write-Host 'Pilot process is not running.';exit 0}
if($null -eq $state){throw 'A PID alone is insufficient to prove ownership. Refusing to stop the process.'}
if($p.ProcessName -ne $state.processName -or $p.StartTime.ToUniversalTime().ToString('o') -ne $state.processStartedAt){throw 'The managed PID was reused or does not match the recorded Relay process. Refusing to stop it.'}
if($PSCmdlet.ShouldProcess("PID $ProcessId ($($p.ProcessName))",'Stop local Relay pilot')){Stop-Process -Id $ProcessId}
if(Test-Path -LiteralPath $stateFile){Remove-Item -LiteralPath $stateFile -Force}
