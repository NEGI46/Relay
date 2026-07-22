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
$grype = Resolve-Scanner 'grype'
if (-not $grype) {
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
& $grype db update
if ($LASTEXITCODE -ne 0) {
    @{status='BLOCKED';reason='grype-db-update-failed';input=$InputPath} | ConvertTo-Json | Set-Content -LiteralPath $out
    $message = 'Grype vulnerability database update failed; vulnerability scanning is BLOCKED.'
    if ($RequireTool -or $Mode -eq 'block-high-critical') { throw $message }
    Write-Warning $message
    exit 0
}
$args = @("sbom:$((Resolve-Path $InputPath).Path)", '-o', 'json')
if ($Mode -eq 'block-high-critical') { $args += @('--fail-on', 'high') }
& $grype @args 2>&1 | Tee-Object -FilePath $out
$code = $LASTEXITCODE
$status = if ($code -eq 0) { 'PASS' } else { 'FAIL' }
@{status=$status;tool='grype';exitCode=$code;report=$out} | ConvertTo-Json | Set-Content -LiteralPath "$out.status.json"
if ($Mode -eq 'report-only') { exit 0 }
if ($code -ne 0) { throw "Grype reported High/Critical findings or failed (exit code $code)" }
