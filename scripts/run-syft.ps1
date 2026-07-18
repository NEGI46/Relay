[CmdletBinding()]
param(
    [string]$SourcePath = '.',
    [string]$OutputPath = 'artifacts/syft-sbom.json',
    [string]$SpdxOutputPath = 'artifacts/syft-sbom.spdx.json',
    [string]$SyftVersion = 'v1.28.0'
)
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $SourcePath).Path
$out = [IO.Path]::GetFullPath($OutputPath)
New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
if (-not (Get-Command syft -ErrorAction SilentlyContinue)) { @{status='skipped';reason='syft-not-installed';source=$source} | ConvertTo-Json | Set-Content -LiteralPath $out; Write-Warning 'Syft is not installed; skipping SBOM generation.'; exit 0 }
& syft "$source" -o "cyclonedx-json=$out"
if ($LASTEXITCODE -ne 0) { throw "Syft failed with exit code $LASTEXITCODE" }
$spdx = [IO.Path]::GetFullPath($SpdxOutputPath)
New-Item -ItemType Directory -Force -Path (Split-Path $spdx) | Out-Null
& syft "$source" -o "spdx-json=$spdx"
if ($LASTEXITCODE -ne 0) { throw "Syft SPDX generation failed with exit code $LASTEXITCODE" }
Write-Output "Syft $SyftVersion reports: $out and $spdx"
