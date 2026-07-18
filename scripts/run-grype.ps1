[CmdletBinding()]
param(
    [string]$InputPath = 'artifacts/syft-sbom.json',
    [string]$OutputPath = 'artifacts/grype.json',
    [ValidateSet('report-only','block-high-critical')][string]$Mode = 'report-only'
)
$ErrorActionPreference = 'Stop'
$out = [IO.Path]::GetFullPath($OutputPath)
New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
if (-not (Get-Command grype -ErrorAction SilentlyContinue)) { @{status='skipped';reason='grype-not-installed';input=$InputPath} | ConvertTo-Json | Set-Content -LiteralPath $out; Write-Warning 'Grype is not installed; skipping vulnerability scan.'; exit 0 }
if (-not (Test-Path -LiteralPath $InputPath)) { @{status='skipped';reason='sbom-missing';input=$InputPath} | ConvertTo-Json | Set-Content -LiteralPath $out; Write-Warning "Grype input is missing; skipping: $InputPath"; exit 0 }
$args = @("sbom:$((Resolve-Path $InputPath).Path)", '-o', 'json')
if ($Mode -eq 'block-high-critical') { $args += @('--fail-on', 'high') }
& grype @args | Tee-Object -FilePath $out
$code = $LASTEXITCODE
if ($Mode -eq 'report-only') { exit 0 }
if ($code -ne 0) { throw "Grype reported High/Critical findings or failed (exit code $code)" }
