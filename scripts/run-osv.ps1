[CmdletBinding()]
param(
    [string]$SourcePath = '.',
    [string]$OutputPath = 'artifacts/osv.json',
    [ValidateSet('report-only','block-high-critical')][string]$Mode = 'report-only'
)
$ErrorActionPreference = 'Stop'
$out = [IO.Path]::GetFullPath($OutputPath)
New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
if (-not (Get-Command osv-scanner -ErrorAction SilentlyContinue)) { Write-Warning 'OSV-Scanner is not installed; skipping dependency scan.'; exit 0 }
$args = @('scan', 'source', '-r', (Resolve-Path $SourcePath).Path, '--format', 'json')
& osv-scanner @args | Tee-Object -FilePath $out
$code = $LASTEXITCODE
if ($Mode -eq 'report-only') { exit 0 }
if ($code -ne 0) { throw "OSV-Scanner reported vulnerabilities or failed (exit code $code)" }
