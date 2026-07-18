[CmdletBinding()]
param(
    [string]$PackageRoot = (Join-Path $PSScriptRoot '..\distribution\Relay-User-Package'),
    [string]$MetadataRoot = (Join-Path $PSScriptRoot '..\distribution\metadata\tuf'),
    [string]$CosignKey,
    [string]$Cosign = 'cosign',
    [string]$Syft = 'syft',
    [string]$TufKeyId = 'relay-offline-root-1',
    [string]$TufPrivateKey,
    [string]$TufSignature,
    [switch]$ManifestOnly
)
$ErrorActionPreference='Stop'; $PackageRoot=(Resolve-Path $PackageRoot).Path; New-Item -ItemType Directory -Force $MetadataRoot | Out-Null
function Need($n) { if (-not (Get-Command $n -ErrorAction SilentlyContinue)) { throw "Required tool not found: $n" } }
function ConvertTo-CanonicalJson([object]$Value) {
    if ($null -eq $Value) { return 'null' }
    if ($Value -is [bool]) { return ($(if ($Value) { 'true' } else { 'false' })) }
    if ($Value -is [string]) { return ([System.Web.Script.Serialization.JavaScriptSerializer]::new().Serialize($Value)) }
    if ($Value -is [System.Collections.IDictionary]) {
        $parts=@(); foreach($key in ($Value.Keys | Sort-Object)) { $parts += (ConvertTo-CanonicalJson ([string]$key))+':'+(ConvertTo-CanonicalJson $Value[$key]) }; return '{'+($parts -join ',')+'}'
    }
    if ($Value -is [System.Collections.IEnumerable] -and -not ($Value -is [string])) { $parts=@(); foreach($item in $Value) { $parts += ConvertTo-CanonicalJson $item }; return '['+($parts -join ',')+']' }
    if ($Value -is [pscustomobject]) { $map=[ordered]@{}; foreach($p in ($Value.PSObject.Properties | Sort-Object Name)) { $map[$p.Name]=$p.Value }; return ConvertTo-CanonicalJson $map }
    if ($Value -is [int] -or $Value -is [long] -or $Value -is [decimal] -or $Value -is [double]) { return ([System.Convert]::ToString($Value,[Globalization.CultureInfo]::InvariantCulture)) }
    return ConvertTo-CanonicalJson ([string]$Value)
}
$files=Get-ChildItem $PackageRoot -File -Recurse | Where-Object { $_.Extension -notin '.sig','.bundle','.json' -and $_.Name -notmatch 'SHA256SUMS' }
$targets=[ordered]@{}
foreach($f in $files) { $rel=$f.FullName.Substring($PackageRoot.Length+1).Replace('\','/'); $h=(Get-FileHash $f.FullName -Algorithm SHA256).Hash.ToLowerInvariant(); $targets[$rel]=[ordered]@{length=$f.Length; hashes=@{sha256=$h}}; $f.FullName+'.sha256' | Out-Null; "$h  $rel" | Set-Content ($f.FullName+'.sha256') }
if (-not $ManifestOnly) { Need $Syft; Need $Cosign; if (-not $CosignKey) { throw 'CosignKey is required unless -ManifestOnly is used.' } }
foreach($f in $files) {
  if (-not $ManifestOnly) { & $Syft "file:$($f.FullName)" -o "spdx-json=$($f.FullName).sbom.spdx.json"; if($LASTEXITCODE -ne 0){throw 'syft failed.'}; & $Cosign sign-blob --offline --key $CosignKey --bundle ($f.FullName+'.bundle') --output-signature ($f.FullName+'.sig') $f.FullName; if($LASTEXITCODE -ne 0){throw 'cosign sign-blob failed.'} }
}
$now=[DateTime]::UtcNow; $expires=$now.AddDays(30).ToString('yyyy-MM-ddTHH:mm:ssZ'); $version=[int64]([DateTimeOffset]$now).ToUnixTimeSeconds()
$signed=[ordered]@{_type='targets';spec_version='1.0.31';version=$version;expires=$expires;targets=$targets}
$signature = $null
if ($ManifestOnly) {
  if ($TufSignature -and $TufSignature -notmatch '^REPLACE_WITH_|^PLACEHOLDER') { throw 'ManifestOnly cannot carry a production-looking TUF signature.' }
  $signature = $null
} else {
  if (-not $TufPrivateKey) { throw 'TufPrivateKey is required unless -ManifestOnly is used.' }
  if (-not (Test-Path -LiteralPath $TufPrivateKey -PathType Leaf)) { throw "TUF private key not found: $TufPrivateKey" }
  $rsa=[System.Security.Cryptography.RSACryptoServiceProvider]::new(); $rsa.FromXmlString((Get-Content -Raw -LiteralPath $TufPrivateKey));
  $bytes=[Text.Encoding]::UTF8.GetBytes((ConvertTo-CanonicalJson $signed)); $signature=[Convert]::ToBase64String($rsa.SignData($bytes,(New-Object Security.Cryptography.SHA256CryptoServiceProvider)))
}
$metadata=[ordered]@{signatures=@($(if($signature){[ordered]@{keyid=$TufKeyId;sig=$signature}}));signed=$signed}
$metadata | ConvertTo-Json -Depth 20 | Set-Content (Join-Path $MetadataRoot 'targets.json') -Encoding utf8
@{schema=1;generatedUtc=$now.ToString('o');packageRoot=$PackageRoot;mode=if($ManifestOnly){'ManifestOnly-integrity-only'}else{'production-signed'};cosign=if($ManifestOnly){'not-run'}else{'sign-blob --offline'};sbom='SPDX JSON';tufTargets='targets.json'} | ConvertTo-Json | Set-Content (Join-Path $MetadataRoot 'release-manifest.json') -Encoding utf8
Write-Output "Distribution manifest generated: $MetadataRoot"
