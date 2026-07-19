[CmdletBinding()]
param(
    [string]$SourcePath = '.',
    [string]$OutputPath = 'artifacts/osv.json',
    [ValidateSet('report-only','block-high-critical')][string]$Mode = 'report-only',
    [switch]$RequireTool
)
$ErrorActionPreference = 'Stop'
$out = [IO.Path]::GetFullPath($OutputPath)
New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
if (-not (Get-Command osv-scanner -ErrorAction SilentlyContinue)) {
    @{status='BLOCKED';reason='osv-scanner-not-installed';source=(Resolve-Path $SourcePath).Path} | ConvertTo-Json | Set-Content -LiteralPath $out
    $message = 'OSV-Scanner is not installed; dependency scanning is BLOCKED.'
    if ($RequireTool -or $Mode -eq 'block-high-critical') { throw $message }
    Write-Warning $message
    exit 0
}
$args = @('scan', 'source', '-r', (Resolve-Path $SourcePath).Path, '--format', 'json')
& osv-scanner @args | Tee-Object -FilePath $out
$code = $LASTEXITCODE
$status = if ($code -eq 0) { 'PASS' } else { 'FAIL' }
@{status=$status;tool='osv-scanner';exitCode=$code;report=$out} | ConvertTo-Json | Set-Content -LiteralPath "$out.status.json"
if ($Mode -eq 'report-only') { exit 0 }
if ($code -ne 0) { throw "OSV-Scanner reported vulnerabilities or failed (exit code $code)" }
