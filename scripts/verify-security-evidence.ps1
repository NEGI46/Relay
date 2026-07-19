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
    [switch]$RequireComplete
)
$ErrorActionPreference = 'Stop'
$items = foreach ($path in $EvidencePaths) {
    if (-not (Test-Path -LiteralPath $path)) {
        [ordered]@{ path = $path; status = 'BLOCKED'; reason = 'evidence-file-missing' }
        continue
    }
    $parsed = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    $status = if ($parsed.status) { [string]$parsed.status } else { 'PASS' }
    [ordered]@{ path = $path; status = $status.ToUpperInvariant(); reason = $parsed.reason }
}
$summaryStatus = if ($items.status -contains 'FAIL' -or $items.status -contains 'FAILED') { 'FAIL' } elseif ($items.status -contains 'BLOCKED') { 'BLOCKED' } else { 'PASS' }
$out = [IO.Path]::GetFullPath($OutputPath)
New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
[ordered]@{ status = $summaryStatus; evidence = @($items) } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $out
Write-Output "Security evidence summary: $summaryStatus"
if ($RequireComplete -and $summaryStatus -ne 'PASS') { throw "Security evidence is $summaryStatus; inspect $OutputPath" }
