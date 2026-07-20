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

function Reject-Text([string]$Text, [string]$Needle, [string]$Description) {
    if ($Text.IndexOf($Needle, [StringComparison]::Ordinal) -ge 0) {
        throw "Implementation contract failed: $Description"
    }
}

$versions = Read-Text 'gradle/libs.versions.toml'
$application = Read-Text 'app/src/main/java/com/example/relay/RelayApplication.kt'
$passphrase = Read-Text 'app/src/main/java/com/example/relay/data/local/SqlCipherPassphraseStore.kt'
$androidDatabaseTest = Read-Text 'app/src/androidTest/java/com/example/relay/data/local/SqlCipherPassphraseStoreTest.kt'
$migrationSource = Read-Text 'app/src/main/java/com/example/relay/data/local/PlaintextDatabaseMigration.kt'
$migrationTest = Read-Text 'app/src/androidTest/java/com/example/relay/data/local/PlaintextDatabaseMigrationTest.kt'
$manifest = Read-Text 'app/src/main/AndroidManifest.xml'
$workflow = Read-Text '.github/workflows/relay-ci.yml'
$backup = Read-Text 'scripts/backup-gateway.ps1'
$signing = Read-Text 'scripts/sign-artifacts.ps1'
$distributionVerify = Read-Text 'scripts/verify-distribution-signatures.ps1'
$tufVerify = Read-Text 'scripts/verify-tuf-metadata.ps1'
$pcGatewayPackage = Read-Text 'scripts/build-pc-gateway-exe.ps1'
$releaseWorkflow = Read-Text '.github/workflows/publish-release.yml'
$gatewayConfig = Read-Text 'pc-gateway/src/main/kotlin/com/example/relay/pcgateway/GatewayConfig.kt'

# At-rest data protection must remain fail-closed and tied to Android Keystore.
Require-Text $versions 'sqlcipher-android' 'SQLCipher dependency'
Require-Text $application 'SupportOpenHelperFactory' 'Room uses the SQLCipher open-helper factory'
Require-Text $application 'SqlCipherPassphraseStore' 'Room passphrase is obtained from the protected store'
Require-Text $passphrase 'AndroidKeyStore' 'SQLCipher key material is protected by Android Keystore'
Require-Text $passphrase 'AES/GCM/NoPadding' 'passphrase record uses authenticated encryption'
Require-Text $androidDatabaseTest 'DoesNotExposePlaintextSqliteHeader' 'instrumentation test checks the encrypted database header'
Require-Text $androidDatabaseTest 'SupportOpenHelperFactory' 'instrumentation test opens Room through SQLCipher'
Require-Text $migrationSource 'OPEN_READONLY' 'legacy database is opened read-only during migration'
Require-Text $migrationSource 'installEncryptedFile' 'legacy database is replaced only after encrypted copy succeeds'
Require-Text $migrationTest 'plaintextMessagesAreCopiedIntoEncryptedRoomDatabase' 'plaintext-to-encrypted migration instrumentation test'
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

# Windows releases must remain upgradeable and report the version that was packaged.
Require-Text $pcGatewayPackage '[string]$AppVersion' 'PC Gateway package version is an explicit input'
Require-Text $pcGatewayPackage '--app-version $AppVersion' 'jpackage receives the release version'
Require-Text $pcGatewayPackage '--name RelayPcGateway' 'Windows upgrade identity keeps the published app name'
Require-Text $pcGatewayPackage '--vendor Relay' 'Windows upgrade identity keeps the published vendor'
Require-Text $pcGatewayPackage '-Drelay.version=$AppVersion' 'packaged runtime receives the release version'
Reject-Text $pcGatewayPackage "`$appVersion = '0.1.0'" 'PC Gateway package version must not be hard-coded'
Require-Text $releaseWorkflow 'RELEASE_TAG: ${{ inputs.tag }}' 'release tag is passed to the Windows packaging step'
Require-Text $releaseWorkflow '-AppVersion $Matches.version' 'release tag drives the Windows package version'
Require-Text $gatewayConfig 'System.getProperty("relay.version")' 'Gateway health reports the packaged release version'

# Keep the contract itself in the required CI path; this prevents silent removal.
Require-Text $workflow 'verify-implementation-contracts.ps1' 'implementation contract CI step'

Write-Output 'Relay implementation contracts passed.'
