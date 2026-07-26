<# Starts the Windows-only PUERTA local pilot. It never changes Firewall rules. #>
[CmdletBinding()]
param([switch]$AllowLan,[ValidateRange(1,65535)][int]$Port=8080,[string]$Username,[securestring]$Password,[string]$Executable,[switch]$NoBrowser)
$ErrorActionPreference='Stop'
$minimumPowerShell = [Version]'5.1'
if ($PSVersionTable.PSVersion -lt $minimumPowerShell) { throw "PowerShell $minimumPowerShell or later is required." }
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'A JDK is required to build the PC Gateway.' }
$repo=Split-Path $PSScriptRoot -Parent
if (Test-Path (Join-Path $repo 'gradlew.bat')) { & (Join-Path $repo 'gradlew.bat') ':pc-gateway:classes'; if($LASTEXITCODE -ne 0){throw 'Gateway build failed.'} }
$args=@('-Port',$Port,'-NoBrowser')
if($AllowLan){$args+='-AllowLan'}; if($Username){$args+=@('-Username',$Username)}; if($Password){$args+=@('-Password',$Password)}; if($Executable){$args+=@('-Executable',$Executable)}
$pilotProcessId = & (Join-Path $PSScriptRoot 'start-pc-gateway-development.ps1') @args | Select-Object -Last 1
if($pilotProcessId -isnot [int] -and "$pilotProcessId" -notmatch '^\d+$'){throw 'Gateway started but a managed PID was not returned.'}
$managedProcess = Get-Process -Id ([int]$pilotProcessId) -ErrorAction Stop
$stateDir=Join-Path $env:LOCALAPPDATA 'Relay\local-pilot';New-Item -ItemType Directory -Force -Path $stateDir|Out-Null
@{processId=[int]$pilotProcessId;processName=$managedProcess.ProcessName;processStartedAt=$managedProcess.StartTime.ToUniversalTime().ToString('o');port=$Port;startedAt=[DateTime]::UtcNow.ToString('o')}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $stateDir 'run.json') -Encoding UTF8
Write-Host "Open http://127.0.0.1:$Port/local-pilot (training only; not a 119 replacement)."
Write-Host 'Stop with .\scripts\Stop-Relay-Local-Pilot.ps1. No Firewall configuration was changed.'
