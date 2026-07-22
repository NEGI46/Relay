[CmdletBinding()]
param(
    [string]$SourcePath = '.',
    [string]$OutputPath = 'artifacts/syft-sbom.json',
    [string]$SpdxOutputPath = 'artifacts/syft-sbom.spdx.json',
    [ValidateSet('report-only','block-high-critical')][string]$Mode = 'report-only',
    [switch]$RequireTool
)
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $SourcePath).Path
$out = [IO.Path]::GetFullPath($OutputPath)
New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
function Resolve-Scanner([string]$Name) {
    if ($env:RELAY_SECURITY_TOOL_DIR) {
        $candidate = Join-Path $env:RELAY_SECURITY_TOOL_DIR $Name
        if ($env:OS -eq 'Windows_NT') { $candidate = "$candidate.exe" }
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    return $null
}
$syft = Resolve-Scanner 'syft'
if (-not $syft) {
    @{status='BLOCKED';reason='syft-not-installed';source=$source} | ConvertTo-Json | Set-Content -LiteralPath $out
    $message = 'Syft is not installed; SBOM generation is BLOCKED.'
    if ($RequireTool -or $Mode -eq 'block-high-critical') { throw $message }
    Write-Warning $message
    exit 0
}
& $syft "$source" -o "cyclonedx-json=$out"
if ($LASTEXITCODE -ne 0) { throw "Syft failed with exit code $LASTEXITCODE" }
$spdx = [IO.Path]::GetFullPath($SpdxOutputPath)
New-Item -ItemType Directory -Force -Path (Split-Path $spdx) | Out-Null
& $syft "$source" -o "spdx-json=$spdx"
if ($LASTEXITCODE -ne 0) { throw "Syft SPDX generation failed with exit code $LASTEXITCODE" }
$statusPath = "$out.status.json"
@{status='PASS';tool='syft';sbom=$out;spdx=$spdx} | ConvertTo-Json | Set-Content -LiteralPath $statusPath
$version = (& $syft version 2>&1 | Out-String).Trim()
Write-Output "Syft ($version) reports: $out and $spdx"
