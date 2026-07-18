[CmdletBinding()]
param([ValidateSet('report-only','block-high-critical')][string]$Mode = 'report-only')
$ErrorActionPreference = 'Stop'
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { Write-Warning 'Java is not installed; skipping Jazzer.'; exit 0 }
if (-not (Test-Path 'gradlew') -and -not (Test-Path 'gradlew.bat')) { Write-Warning 'No Gradle wrapper is present; skipping Jazzer.'; exit 0 }
Write-Warning 'No dedicated Jazzer target is declared in the current repository; skipping Jazzer.'
if ($Mode -eq 'block-high-critical') { throw 'Jazzer block mode requires a configured fuzz target.' }
