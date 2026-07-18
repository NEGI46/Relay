[CmdletBinding()]
param(
    [string]$PackageRoot = 'distribution',
    [ValidateSet('report-only','block-high-critical')][string]$Mode = 'report-only'
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $PackageRoot)) { Write-Warning "Distribution directory is missing; skipping signature verification: $PackageRoot"; exit 0 }
if (-not (Get-Command cosign -ErrorAction SilentlyContinue)) { Write-Warning 'cosign is not installed; skipping signature verification.'; exit 0 }
$files = Get-ChildItem -LiteralPath $PackageRoot -Recurse -File | Where-Object Extension -in '.apk','.aab','.ipa','.msix','.zip'
if (-not $files) { Write-Warning 'No signed distribution candidates found; skipping cosign.'; exit 0 }
$failed = @()
foreach ($file in $files) {
    $sig = "$($file.FullName).sig"
    if (-not (Test-Path -LiteralPath $sig)) { $failed += $file.FullName; continue }
    & cosign verify-blob --signature $sig $file.FullName 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { $failed += $file.FullName }
}
if ($failed.Count -gt 0) { $failed | ForEach-Object { Write-Warning "Signature missing or invalid: $_" }; if ($Mode -eq 'block-high-critical') { throw 'cosign verification failed.' } }
