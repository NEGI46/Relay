[CmdletBinding()]
param(
    [string]$PackageRoot = 'distribution',
    [ValidateSet('report-only','block-high-critical')][string]$Mode = 'report-only',
    [string]$Cosign = 'cosign',
    [string]$PublicKey,
    [switch]$RequireBundles,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $PackageRoot)) { if($Mode -eq 'block-high-critical'){throw "Distribution directory is missing: $PackageRoot"}; Write-Warning "Distribution directory is missing; skipping signature verification: $PackageRoot"; exit 0 }
$files = Get-ChildItem -LiteralPath $PackageRoot -Recurse -File | Where-Object Extension -in '.apk','.aab','.ipa','.msix','.zip'
if (-not $files) { if($Mode -eq 'block-high-critical'){throw 'No signed distribution candidates found.'}; Write-Warning 'No signed distribution candidates found; skipping cosign.'; exit 0 }
$cosignCommand=Get-Command $Cosign -ErrorAction SilentlyContinue
if (-not $cosignCommand) { if($Mode -eq 'block-high-critical' -or $RequireBundles){throw "cosign is required but was not found: $Cosign"}; Write-Warning 'cosign is not installed; signature verification not performed.'; exit 0 }
$failed=@()
foreach($file in $files) {
    $sig="$($file.FullName).sig"; $bundle="$($file.FullName).bundle"
    if (-not (Test-Path -LiteralPath $sig) -or -not (Test-Path -LiteralPath $bundle)) { $failed+=$file.FullName; continue }
    $args=@('verify-blob','--signature',$sig,'--bundle',$bundle)
    if($Offline){$args+='--offline'}
    if($PublicKey){$args+=@('--key',$PublicKey)}
    $args+=$file.FullName
    & $Cosign @args 2>&1 | Out-Null
    if($LASTEXITCODE -ne 0){$failed+=$file.FullName}
}
if($failed.Count -gt 0){$failed|ForEach-Object{Write-Warning "Cosign signature/bundle missing or invalid: $_"}; if($Mode -eq 'block-high-critical' -or $RequireBundles){throw 'cosign bundle verification failed.'}}
else{Write-Output "Cosign bundle verification passed: $($files.Count) artifact(s) (offline=$Offline)"}
