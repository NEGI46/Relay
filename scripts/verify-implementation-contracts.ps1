[CmdletBinding()]
param(
    [string]$RepositoryRoot = '.'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path

function Read-Text([string]$RelativePath) {
    $path = Join-Path $root $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required file is missing: $RelativePath"
    }
    return Get-Content -LiteralPath $path -Raw -Encoding utf8
}

function Require-Text([string]$Text, [string]$Needle, [string]$Description) {
    if ($Text.IndexOf($Needle, [StringComparison]::Ordinal) -lt 0) {
        throw "Implementation contract failed: $Description"
    }
}

$versions = Read-Text 'gradle/libs.versions.toml'
$application = Read-Text 'app/src/main/java/com/example/relay/RelayApplication.kt'
$passphrase = Read-Text 'app/src/main/java/com/example/relay/data/local/SqlCipherPassphraseStore.kt'
$manifest = Read-Text 'app/src/main/AndroidManifest.xml'
$workflow = Read-Text '.github/workflows/relay-ci.yml'
$backup = Read-Text 'scripts/backup-gateway.ps1'
$signing = Read-Text 'scripts/sign-artifacts.ps1'
$distributionVerify = Read-Text 'scripts/verify-distribution-signatures.ps1'
$tufVerify = Read-Text 'scripts/verify-tuf-metadata.ps1'

# At-rest data protection must remain fail-closed and tied to Android Keystore.
Require-Text $versions 'sqlcipher-android' 'SQLCipher dependency'
Require-Text $application 'SupportOpenHelperFactory' 'Room uses the SQLCipher open-helper factory'
Require-Text $application 'SqlCipherPassphraseStore' 'Room passphrase is obtained from the protected store'
Require-Text $passphrase 'AndroidKeyStore' 'SQLCipher key material is protected by Android Keystore'
Require-Text $passphrase 'AES/GCM/NoPadding' 'passphrase record uses authenticated encryption'
Require-Text $manifest 'android:allowBackup="false"' 'Android backup is disabled for encrypted application data'

# Runtime communication must have an explicit foreground-service and permission boundary.
Require-Text $manifest 'FOREGROUND_SERVICE_CONNECTED_DEVICE' 'connected-device foreground-service permission'
Require-Text $manifest 'android:foregroundServiceType="connectedDevice"' 'connected-device foreground-service type'
Require-Text $manifest 'BLUETOOTH_SCAN' 'Nearby scan permission'
Require-Text $manifest 'NEARBY_WIFI_DEVICES' 'Nearby Wi-Fi permission'

# Release artifacts require both integrity metadata and an explicit production verifier.
Require-Text $backup 'age' 'Gateway backup encryption boundary'
Require-Text $backup 'sha256' 'Gateway backup checksum verification'
Require-Text $signing 'TufPrivateKey' 'TUF signing key input'
Require-Text $signing 'cosign sign-blob' 'cosign artifact signing'
Require-Text $distributionVerify 'RequireBundles' 'cosign bundle verification gate'
Require-Text $tufVerify 'TrustedRootPath' 'trusted-root TUF verification gate'

# Keep the contract itself in the required CI path; this prevents silent removal.
Require-Text $workflow 'verify-implementation-contracts.ps1' 'implementation contract CI step'

Write-Output 'Relay implementation contracts passed.'
