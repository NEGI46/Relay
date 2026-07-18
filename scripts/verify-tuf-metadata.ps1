[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$MetadataPath,
    [Parameter(Mandatory)][string]$ArtifactPath,
    [string]$TargetName,
    [string]$TrustedRootPath,
    [switch]$ManifestOnly,
    [switch]$AllowExpired
)
$ErrorActionPreference = 'Stop'

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

function Get-TrustedKeys([object]$Root) {
    if ($Root.keys) { return $Root.keys }
    if ($Root.signed.keys) { return $Root.signed.keys }
    throw 'Trusted root has no keys object.'
}

if (-not (Test-Path -LiteralPath $MetadataPath -PathType Leaf)) { throw "Metadata not found: $MetadataPath" }
if (-not (Test-Path -LiteralPath $ArtifactPath -PathType Leaf)) { throw "Artifact not found: $ArtifactPath" }
$metadata = Get-Content -LiteralPath $MetadataPath -Raw | ConvertFrom-Json
if ($metadata.signed._type -ne 'targets' -or $null -eq $metadata.signed.targets) { throw 'Invalid TUF targets metadata.' }
$targetName = if ($TargetName) { $TargetName.Replace('\','/') } else { [IO.Path]::GetFileName($ArtifactPath) }
$target = $metadata.signed.targets.PSObject.Properties[$targetName].Value
if (-not $target) { throw "Target not found: $targetName" }
if (-not $AllowExpired -and [DateTime]::UtcNow -gt [DateTime]::Parse($metadata.signed.expires).ToUniversalTime()) { throw 'TUF metadata expired.' }
$hash = (Get-FileHash -LiteralPath $ArtifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($hash -ne ([string]$target.hashes.sha256).ToLowerInvariant()) { throw 'Target SHA-256 mismatch.' }
if ((Get-Item -LiteralPath $ArtifactPath).Length -ne [int64]$target.length) { throw 'Target length mismatch.' }

if ($ManifestOnly) {
    if ($metadata.signatures -and @($metadata.signatures).Count -gt 0) { throw 'ManifestOnly verification refuses attached signatures; use production verification.' }
    Write-Warning 'ManifestOnly: integrity verified only; no TUF signature or cosign authenticity was verified.'
    Write-Output "TUF target integrity verified (ManifestOnly): $targetName"
    exit 0
}
if (-not $TrustedRootPath) { throw 'TrustedRootPath is required for production TUF verification.' }
if (-not (Test-Path -LiteralPath $TrustedRootPath -PathType Leaf)) { throw "Trusted root not found: $TrustedRootPath" }
$root = Get-Content -LiteralPath $TrustedRootPath -Raw | ConvertFrom-Json
$keys = Get-TrustedKeys $root
$canonical = ConvertTo-CanonicalJson $metadata.signed
$bytes=[Text.Encoding]::UTF8.GetBytes($canonical); $valid=0
foreach($signature in @($metadata.signatures)) {
    if (-not $signature.keyid -or -not $signature.sig -or $signature.sig -match '^(REPLACE_WITH_|PLACEHOLDER|TODO|$)') { continue }
    $key=$keys.PSObject.Properties[$signature.keyid].Value
    if (-not $key) { continue }
    $public=[string]$key.keyval.public
    if (-not $public) { continue }
    try {
        $rsa=[System.Security.Cryptography.RSACryptoServiceProvider]::new(); $rsa.FromXmlString($public)
        if ($rsa.VerifyData($bytes,[Convert]::FromBase64String($signature.sig),(New-Object Security.Cryptography.SHA256CryptoServiceProvider))) { $valid++ }
    } catch { continue }
}
if ($valid -lt 1) { throw 'No valid TUF target signature matched the trusted root.' }
Write-Output "TUF target verified with $valid trusted signature(s): $targetName"
