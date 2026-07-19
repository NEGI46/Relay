[CmdletBinding()]
param(
    [string]$ArtifactPath = 'distribution',
    [string]$OutputPath = 'artifacts/mobsf.json',
    [ValidateSet('report-only','block-high-critical')][string]$Mode = 'report-only',
    [string]$MobSFUrl = $env:MOBSF_URL,
    [string]$MobSFApiKey = $env:MOBSF_API_KEY,
    [switch]$RequireTool
)
$ErrorActionPreference = 'Stop'
$out = [IO.Path]::GetFullPath($OutputPath)
New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
if ([string]::IsNullOrWhiteSpace($MobSFUrl) -or [string]::IsNullOrWhiteSpace($MobSFApiKey)) {
    @{status='BLOCKED';reason='MOBSF_URL or MOBSF_API_KEY is not configured'} | ConvertTo-Json | Set-Content -LiteralPath $out
    $message = 'MOBSF_URL and MOBSF_API_KEY are not configured; MobSF scanning is BLOCKED.'
    if ($RequireTool) { throw $message }
    Write-Warning $message
    exit 0
}
$artifact = Get-ChildItem -LiteralPath $ArtifactPath -Recurse -File -ErrorAction SilentlyContinue | Where-Object Extension -in '.apk','.aab','.ipa','.msix' | Select-Object -First 1
if (-not $artifact) {
    @{status='BLOCKED';reason='no mobile package found';artifactRoot=$ArtifactPath} | ConvertTo-Json | Set-Content -LiteralPath $out
    $message = "No mobile package found under $ArtifactPath; MobSF scanning is BLOCKED."
    if ($RequireTool) { throw $message }
    Write-Warning $message
    exit 0
}
$base = $MobSFUrl.TrimEnd('/')
$headers = @{ Authorization = $MobSFApiKey }
try {
    $upload = Invoke-RestMethod -Method Post -Uri "$base/api/v1/upload" -Headers $headers -Form @{ file = $artifact }
    if (-not $upload.hash) { throw 'MobSF upload response did not contain a hash.' }
    $scanBody = @{ hash = [string]$upload.hash; scan_type = switch -Regex ($artifact.Extension.ToLowerInvariant()) { '\.ipa' { 'ios' ; break } '\.msix' { 'windows' ; break } default { 'apk' } } }
    $scan = Invoke-RestMethod -Method Post -Uri "$base/api/v1/scan" -Headers $headers -Body $scanBody
    $score = $null
    try { $score = Invoke-RestMethod -Method Post -Uri "$base/api/v1/scorecard" -Headers $headers -Body @{ hash = [string]$upload.hash } } catch { Write-Warning "MobSF scorecard endpoint unavailable: $($_.Exception.Message)" }
    $high = 0; $critical = 0
    $jsonText = ($scan | ConvertTo-Json -Depth 30)
    $critical += ([regex]::Matches($jsonText, '"severity"\s*:\s*"CRITICAL"', 'IgnoreCase')).Count
    $high += ([regex]::Matches($jsonText, '"severity"\s*:\s*"HIGH"', 'IgnoreCase')).Count
    $result = [ordered]@{ status='completed'; artifact=$artifact.FullName; hash=$upload.hash; high=$high; critical=$critical; scan=$scan; scorecard=$score }
    $result | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $out
    if ($Mode -eq 'block-high-critical' -and ($high -gt 0 -or $critical -gt 0)) { throw "MobSF found $critical critical and $high high severity findings." }
} catch {
    @{status='failed';reason=$_.Exception.Message;artifact=$artifact.FullName} | ConvertTo-Json | Set-Content -LiteralPath $out
    if ($Mode -eq 'block-high-critical') { throw }
    Write-Warning "MobSF scan failed in report-only mode: $($_.Exception.Message)"
}
