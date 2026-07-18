[CmdletBinding()]
param(
    [string]$PackageRoot = (Join-Path $PSScriptRoot '..\distribution\Relay-User-Package'),
    [string]$MetadataRoot = (Join-Path $PSScriptRoot '..\distribution\metadata\tuf'),
    [string]$CosignKey,
    [string]$Cosign = 'cosign',
    [string]$Syft = 'syft',
    [string]$TufKeyId = 'relay-offline-root-1',
    [string]$TufSignature = 'REPLACE_WITH_OFFLINE_TUF_SIGNATURE',
    [switch]$ManifestOnly
)
$ErrorActionPreference='Stop'; $PackageRoot=(Resolve-Path $PackageRoot).Path; New-Item -ItemType Directory -Force $MetadataRoot | Out-Null
function Need($n) { if (-not (Get-Command $n -ErrorAction SilentlyContinue)) { throw "Required tool not found: $n" } }
$files=Get-ChildItem $PackageRoot -File -Recurse | Where-Object { $_.Extension -notin '.sig','.bundle','.json' -and $_.Name -notmatch 'SHA256SUMS' }
$targets=[ordered]@{}
foreach($f in $files) { $rel=$f.FullName.Substring($PackageRoot.Length+1).Replace('\','/'); $h=(Get-FileHash $f.FullName -Algorithm SHA256).Hash.ToLowerInvariant(); $targets[$rel]=[ordered]@{length=$f.Length; hashes=@{sha256=$h}}; $f.FullName+'.sha256' | Out-Null; "$h  $rel" | Set-Content ($f.FullName+'.sha256') }
if (-not $ManifestOnly) { Need $Syft; Need $Cosign; if (-not $CosignKey) { throw 'CosignKey is required unless -ManifestOnly is used.' } }
foreach($f in $files) {
  if (-not $ManifestOnly) { & $Syft "file:$($f.FullName)" -o "spdx-json=$($f.FullName).sbom.spdx.json"; if($LASTEXITCODE -ne 0){throw 'syft failed.'}; & $Cosign sign-blob --offline --key $CosignKey --bundle ($f.FullName+'.bundle') --output-signature ($f.FullName+'.sig') $f.FullName; if($LASTEXITCODE -ne 0){throw 'cosign sign-blob failed.'} }
}
$now=[DateTime]::UtcNow; $expires=$now.AddDays(30).ToString('yyyy-MM-ddTHH:mm:ssZ'); $version=[int64]([DateTimeOffset]$now).ToUnixTimeSeconds()
$signed=[ordered]@{_type='targets';spec_version='1.0.31';version=$version;expires=$expires;targets=$targets}
$metadata=[ordered]@{signatures=@([ordered]@{keyid=$TufKeyId;sig=$TufSignature});signed=$signed}
$metadata | ConvertTo-Json -Depth 20 | Set-Content (Join-Path $MetadataRoot 'targets.json') -Encoding utf8
@{schema=1;generatedUtc=$now.ToString('o');packageRoot=$PackageRoot;cosign=if($ManifestOnly){'not-run'}else{'sign-blob --offline'};sbom='SPDX JSON';tufTargets='targets.json'} | ConvertTo-Json | Set-Content (Join-Path $MetadataRoot 'release-manifest.json') -Encoding utf8
Write-Output "Distribution manifest generated: $MetadataRoot"
