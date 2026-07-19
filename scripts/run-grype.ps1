[CmdletBinding()]
param(
    [string]$InputPath = 'artifacts/syft-sbom.json',
    [string]$OutputPath = 'artifacts/grype.json',
    [ValidateSet('report-only','block-high-critical')][string]$Mode = 'report-only',
    [switch]$RequireTool
)
$ErrorActionPreference = 'Stop'
$out = [IO.Path]::GetFullPath($OutputPath)
New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
if (-not (Get-Command grype -ErrorAction SilentlyContinue)) {
    @{status='BLOCKED';reason='grype-not-installed';input=$InputPath} | ConvertTo-Json | Set-Content -LiteralPath $out
    $message = 'Grype is not installed; vulnerability scanning is BLOCKED.'
    if ($RequireTool -or $Mode -eq 'block-high-critical') { throw $message }
    Write-Warning $message
    exit 0
}
if (-not (Test-Path -LiteralPath $InputPath)) {
    @{status='BLOCKED';reason='sbom-missing';input=$InputPath} | ConvertTo-Json | Set-Content -LiteralPath $out
    $message = "Grype input is missing; vulnerability scanning is BLOCKED: $InputPath"
    if ($RequireTool -or $Mode -eq 'block-high-critical') { throw $message }
    Write-Warning $message
    exit 0
}
$args = @("sbom:$((Resolve-Path $InputPath).Path)", '-o', 'json')
if ($Mode -eq 'block-high-critical') { $args += @('--fail-on', 'high') }
& grype @args | Tee-Object -FilePath $out
$code = $LASTEXITCODE
$status = if ($code -eq 0) { 'PASS' } else { 'FAIL' }
@{status=$status;tool='grype';exitCode=$code;report=$out} | ConvertTo-Json | Set-Content -LiteralPath "$out.status.json"
if ($Mode -eq 'report-only') { exit 0 }
if ($code -ne 0) { throw "Grype reported High/Critical findings or failed (exit code $code)" }
