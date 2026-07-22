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
$osvScanner = Resolve-Scanner 'osv-scanner'
if (-not $osvScanner) {
    @{status='BLOCKED';reason='osv-scanner-not-installed';source=(Resolve-Path $SourcePath).Path} | ConvertTo-Json | Set-Content -LiteralPath $out
    $message = 'OSV-Scanner is not installed; dependency scanning is BLOCKED.'
    if ($RequireTool -or $Mode -eq 'block-high-critical') { throw $message }
    Write-Warning $message
    exit 0
}
$args = @('scan', 'source', '-r', (Resolve-Path $SourcePath).Path, '--format', 'json')
& $osvScanner @args | Tee-Object -FilePath $out
$code = $LASTEXITCODE
$status = if ($code -eq 0) { 'PASS' } else { 'FAIL' }
@{status=$status;tool='osv-scanner';exitCode=$code;report=$out} | ConvertTo-Json | Set-Content -LiteralPath "$out.status.json"
if ($Mode -eq 'report-only') { exit 0 }
if ($code -ne 0) { throw "OSV-Scanner reported vulnerabilities or failed (exit code $code)" }
