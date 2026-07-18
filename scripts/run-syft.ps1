[CmdletBinding()]
param(
    [string]$SourcePath = '.',
    [string]$OutputPath = 'artifacts/syft-sbom.json',
    [string]$SyftVersion = 'v1.28.0'
)
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $SourcePath).Path
$out = [IO.Path]::GetFullPath($OutputPath)
New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
if (-not (Get-Command syft -ErrorAction SilentlyContinue)) { Write-Warning 'Syft is not installed; skipping SBOM generation.'; exit 0 }
& syft "$source" -o "cyclonedx-json=$out"
if ($LASTEXITCODE -ne 0) { throw "Syft failed with exit code $LASTEXITCODE" }
Write-Output "Syft $SyftVersion report: $out"
