[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$MetadataPath,
    [Parameter(Mandatory)][string]$ArtifactPath,
    [switch]$AllowExpired
)
$ErrorActionPreference = 'Stop'
$metadata = Get-Content -LiteralPath $MetadataPath -Raw | ConvertFrom-Json
if ($metadata.signed._type -ne 'targets' -or -not $metadata.signed.targets) { throw 'Invalid TUF targets metadata.' }
$targetName = [IO.Path]::GetFileName($ArtifactPath)
$target = $metadata.signed.targets.PSObject.Properties[$targetName].Value
if (-not $target) { throw "Target not found: $targetName" }
if (-not $AllowExpired -and [DateTime]::UtcNow -gt [DateTime]::Parse($metadata.signed.expires).ToUniversalTime()) { throw 'TUF metadata expired.' }
$hash = (Get-FileHash -LiteralPath $ArtifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($hash -ne $target.hashes.sha256) { throw 'Target SHA-256 mismatch.' }
if ((Get-Item $ArtifactPath).Length -ne [int64]$target.length) { throw 'Target length mismatch.' }
Write-Output "TUF target verified: $targetName"
