# Structural + behavioral checks for the one-click PC Gateway launcher path.
# Run: powershell -ExecutionPolicy Bypass -File .\scripts\tests\run-pc-gateway-launcher.tests.ps1
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$failed = 0

function Assert-True($cond, $msg) {
    if (-not $cond) {
        Write-Host "FAIL: $msg" -ForegroundColor Red
        $script:failed++
    } else {
        Write-Host "PASS: $msg"
    }
}

$cmd = Join-Path $Root 'Start-PC-Gateway.cmd'
$ps1 = Join-Path $Root 'scripts\run-pc-gateway.ps1'
$sh = Join-Path $Root 'scripts\run-pc-gateway.sh'

Assert-True (Test-Path -LiteralPath $cmd) 'Start-PC-Gateway.cmd exists at repo root (double-click entry)'
Assert-True (Test-Path -LiteralPath $ps1) 'scripts/run-pc-gateway.ps1 exists'
Assert-True (Test-Path -LiteralPath $sh) 'scripts/run-pc-gateway.sh exists (Mac/Linux parity)'

$cmdText = Get-Content -LiteralPath $cmd -Raw
Assert-True ($cmdText -match 'run-pc-gateway\.ps1') 'Start-PC-Gateway.cmd invokes run-pc-gateway.ps1'

$ps1Text = Get-Content -LiteralPath $ps1 -Raw
Assert-True ($ps1Text -match 'installDist') 'launcher builds installDist when missing'
Assert-True ($ps1Text -match 'pc-gateway\.bat') 'launcher starts real installDist entry (pc-gateway.bat)'
Assert-True ($ps1Text -match 'RELAY_GATEWAY_DB') 'launcher sets DB under user profile defaults'
Assert-True ($ps1Text -match 'api/health') 'launcher documents health URL'
Assert-True ($ps1Text -match "RELAY_PROFILE = 'production'") 'launcher explicitly defaults to production profile'
Assert-True ($ps1Text -match "127\.0\.0\.1") 'launcher default bind is loopback'
Assert-True ($ps1Text -match 'bootstrap-admin') 'launcher documents named administrator bootstrap'

# Parser / param surface: -SkipBuild and -NoBrowser must be accepted by the script AST.
$errors = $null
$tokens = $null
[System.Management.Automation.Language.Parser]::ParseFile($ps1, [ref]$tokens, [ref]$errors) | Out-Null
Assert-True ($null -eq $errors -or $errors.Count -eq 0) 'run-pc-gateway.ps1 parses without syntax errors'
Assert-True ($ps1Text -match 'NoBrowser') 'supports -NoBrowser'
Assert-True ($ps1Text -match 'SkipBuild') 'supports -SkipBuild'

if ($failed -gt 0) {
    Write-Host "$failed assertion(s) failed" -ForegroundColor Red
    exit 1
}
Write-Host 'All launcher assertions passed'
exit 0
