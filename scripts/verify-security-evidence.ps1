[CmdletBinding()]
param(
    [string[]]$EvidencePaths = @(
        'artifacts/syft-sbom.json',
        'artifacts/osv.json',
        'artifacts/grype.json',
        'artifacts/mobsf.json',
        'artifacts/signature-verification.json'
    ),
    [string]$OutputPath = 'artifacts/security-evidence-summary.json',
    [switch]$RequireComplete,
    [ValidateSet('report-only','block-high-critical')][string]$Mode = 'report-only'
)
$ErrorActionPreference = 'Stop'
$items = foreach ($path in $EvidencePaths) {
    if (-not (Test-Path -LiteralPath $path)) {
        [ordered]@{ path = $path; status = 'BLOCKED'; reason = 'evidence-file-missing' }
        continue
    }
    $parsed = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    if (-not $parsed.status -and (Test-Path -LiteralPath "$path.status.json")) {
        $parsed = Get-Content -LiteralPath "$path.status.json" -Raw | ConvertFrom-Json
    }
    $status = if ($parsed.status) { [string]$parsed.status } else { 'BLOCKED' }
    [ordered]@{ path = $path; status = $status.ToUpperInvariant(); reason = $parsed.reason }
}
$summaryStatus = if ($items.status -contains 'FAIL' -or $items.status -contains 'FAILED') { 'FAIL' } elseif ($items.status -contains 'BLOCKED') { 'BLOCKED' } else { 'PASS' }
$out = [IO.Path]::GetFullPath($OutputPath)
New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
[ordered]@{ status = $summaryStatus; evidence = @($items) } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $out
Write-Output "Security evidence summary: $summaryStatus"
if (($RequireComplete -or $Mode -eq 'block-high-critical') -and $summaryStatus -ne 'PASS') { throw "Security evidence is $summaryStatus; inspect $OutputPath" }
