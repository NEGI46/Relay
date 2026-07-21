[CmdletBinding()]
param(
    [string]$PackageRoot = 'distribution',
    [ValidateSet('report-only','block-high-critical')][string]$Mode = 'report-only',
    [string]$Cosign = 'cosign',
    [string]$PublicKey,
    [switch]$RequireBundles,
    [switch]$Offline,
    [switch]$RequireVerification,
    [string]$OutputPath = 'artifacts/signature-verification.json'
)
$ErrorActionPreference = 'Stop'
function Write-VerificationStatus([string]$Status, [string]$Reason, [object]$Files = @()) {
    $out = [IO.Path]::GetFullPath($OutputPath)
    New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
    [ordered]@{ status = $Status; reason = $Reason; files = @($Files) } | ConvertTo-Json | Set-Content -LiteralPath $out
}
function Stop-OrWarnBlocked([string]$Reason) {
    Write-VerificationStatus 'BLOCKED' $Reason
    if ($Mode -eq 'block-high-critical' -or $RequireBundles -or $RequireVerification) { throw $Reason }
    Write-Warning $Reason
}
if (-not (Test-Path -LiteralPath $PackageRoot)) { Stop-OrWarnBlocked "Distribution directory is missing; signature verification is BLOCKED: $PackageRoot"; exit 0 }
$files = Get-ChildItem -LiteralPath $PackageRoot -Recurse -File | Where-Object Extension -in '.apk','.aab','.ipa','.msix','.msi','.exe','.zip'
if (-not $files) { Stop-OrWarnBlocked 'No signed distribution candidates found; signature verification is BLOCKED.'; exit 0 }
$cosignCommand=Get-Command $Cosign -ErrorAction SilentlyContinue
if (-not $cosignCommand) { Stop-OrWarnBlocked "cosign is not installed; signature verification is BLOCKED: $Cosign"; exit 0 }
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
if($failed.Count -gt 0){
    $reason = 'cosign signature/bundle missing or invalid.'
    Write-VerificationStatus 'FAIL' $reason $failed
    $failed|ForEach-Object{Write-Warning "Cosign signature/bundle missing or invalid: $_"}
    if($Mode -eq 'block-high-critical' -or $RequireBundles -or $RequireVerification){throw $reason}
}
else{
    Write-VerificationStatus 'PASS' 'cosign bundles verified' $files.FullName
    Write-Output "Cosign bundle verification passed: $($files.Count) artifact(s) (offline=$Offline)"
}
