[CmdletBinding()]
param(
    [string]$ArtifactPath = 'distribution',
    [string]$OutputPath = 'artifacts/mobsf.json',
    [ValidateSet('report-only','block-high-critical')][string]$Mode = 'report-only',
    [string]$MobSFUrl = $env:MOBSF_URL,
    [string]$MobSFApiKey = $env:MOBSF_API_KEY
)
$ErrorActionPreference = 'Stop'
$out = [IO.Path]::GetFullPath($OutputPath)
New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
if ([string]::IsNullOrWhiteSpace($MobSFUrl) -or [string]::IsNullOrWhiteSpace($MobSFApiKey)) { Write-Warning 'MOBSF_URL and MOBSF_API_KEY are not configured; skipping MobSF.'; exit 0 }
$artifact = Get-ChildItem -LiteralPath $ArtifactPath -Recurse -File -ErrorAction SilentlyContinue | Where-Object Extension -in '.apk','.aab','.ipa','.msix' | Select-Object -First 1
if (-not $artifact) { Write-Warning "No mobile package found under $ArtifactPath; skipping MobSF."; exit 0 }
Write-Warning 'MobSF upload/scan integration is endpoint-dependent; configure a compatible runner before enabling block-high-critical.'
@{ status = 'not-run'; reason = 'MobSF endpoint integration requires an uploaded artifact'; artifact = $artifact.FullName } | ConvertTo-Json | Set-Content -LiteralPath $out
if ($Mode -eq 'block-high-critical') { throw 'MobSF is configured but no compatible scan adapter is available.' }
