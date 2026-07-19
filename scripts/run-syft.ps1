[CmdletBinding()]
param(
    [string]$SourcePath = '.',
    [string]$OutputPath = 'artifacts/syft-sbom.json',
    [string]$SpdxOutputPath = 'artifacts/syft-sbom.spdx.json',
    [switch]$RequireTool
)
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $SourcePath).Path
$out = [IO.Path]::GetFullPath($OutputPath)
New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
if (-not (Get-Command syft -ErrorAction SilentlyContinue)) {
    @{status='BLOCKED';reason='syft-not-installed';source=$source} | ConvertTo-Json | Set-Content -LiteralPath $out
    $message = 'Syft is not installed; SBOM generation is BLOCKED.'
    if ($RequireTool) { throw $message }
    Write-Warning $message
    exit 0
}
& syft "$source" -o "cyclonedx-json=$out"
if ($LASTEXITCODE -ne 0) { throw "Syft failed with exit code $LASTEXITCODE" }
$spdx = [IO.Path]::GetFullPath($SpdxOutputPath)
New-Item -ItemType Directory -Force -Path (Split-Path $spdx) | Out-Null
& syft "$source" -o "spdx-json=$spdx"
if ($LASTEXITCODE -ne 0) { throw "Syft SPDX generation failed with exit code $LASTEXITCODE" }
$version = (& syft version 2>&1 | Out-String).Trim()
Write-Output "Syft ($version) reports: $out and $spdx"
