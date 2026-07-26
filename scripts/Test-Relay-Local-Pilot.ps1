<# Conservative verifier: BLOCKED and NOT_RUN are never reported as PASS. #>
[CmdletBinding()]param([ValidateRange(1,65535)][int]$Port=8080)
$ErrorActionPreference='Continue';$repo=Split-Path $PSScriptRoot -Parent;$result=@{}
function Run-Check([string]$Name,[scriptblock]$Action){try{& $Action;if($LASTEXITCODE -and $LASTEXITCODE -ne 0){$result[$Name]='FAIL'}else{$result[$Name]='PASS'}}catch{$result[$Name]='BLOCKED'}}
Run-Check 'unit_and_integration' { Push-Location $repo; & .\gradlew.bat ':pc-gateway:test'; Pop-Location }
try{$health=Invoke-RestMethod "http://127.0.0.1:$Port/api/health" -TimeoutSec 3;$result['gateway_health']='PASS';$result['local_pilot']=if((Invoke-WebRequest "http://127.0.0.1:$Port/local-pilot" -UseBasicParsing).StatusCode -eq 200){'PASS'}else{'FAIL'}}catch{$result['gateway_health']='NOT_RUN';$result['local_pilot']='NOT_RUN'}
foreach($k in $result.Keys|Sort-Object){"$k : $($result[$k])"};if($result.Values | Where-Object { $_ -ne 'PASS' }){exit 1}
