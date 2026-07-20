package com.example.relay.rescue

import kotlinx.serialization.Serializable

const val REGIONAL_SHELTER_TRUST_PROTOCOL_VERSION: Int = 1
private const val MAX_DIRECTORY_LIFETIME_MS: Long = 31L * 24 * 60 * 60 * 1_000
private const val MAX_SHELTERS_PER_DIRECTORY: Int = 256
private const val MAX_DIRECTORY_SIGNED_BYTES: Int = 512 * 1_024

/**
 * A regional trust anchor shipped with the application. It deliberately contains public material
 * only; the matching private key belongs in an offline regional signing environment.
 */
@Serializable
data class RegionalRootBundle(
    val protocolVersion: Int = REGIONAL_SHELTER_TRUST_PROTOCOL_VERSION,
    val regionId: String,
    val rootSigningPublicKey: RescuePublicKey,
) {
    companion object {
        private const val UNSUPPORTED_ROOT_PROTOCOL = "unsupported_root_protocol"
        private const val INVALID_REGION_ID = "invalid_region_id"
        private const val INVALID_ROOT_KEY = "invalid_root_key"
        private const val INVALID_ROOT_KEY_ID = "invalid_root_key_id"
        private const val INVALID_ROOT_KEY_SIZE = "invalid_root_key_size"
    }
    fun validate(): RescueValidationResult = validationResult {
        require(protocolVersion == REGIONAL_SHELTER_TRUST_PROTOCOL_VERSION, UNSUPPORTED_ROOT_PROTOCOL)
        requireIdentifier(regionId, INVALID_REGION_ID)
        require(rootSigningPublicKey.algorithm == RescueKeyAlgorithm.ECDSA_P256_SHA256, INVALID_ROOT_KEY)
        requireIdentifier(rootSigningPublicKey.keyId, INVALID_ROOT_KEY_ID)
        require(rootSigningPublicKey.encodedBase64.length in 64..2_048, INVALID_ROOT_KEY_SIZE)
    }
}

/** A root-signed PC beacon document. [manifest] remains wire-compatible with the legacy form. */
@Serializable
data class SignedShelterManifest(
    val protocolVersion: Int = REGIONAL_SHELTER_TRUST_PROTOCOL_VERSION,
    val regionId: String,
    val manifest: ShelterPublicKeyManifest,
    val signerKeyId: String,
    val signatureAlgorithm: String = RescueAlgorithms.ECDSA_P256_SHA256,
    val signatureBase64: String,
) {
    fun validate(nowEpochMillis: Long? = null): RescueValidationResult = validationResult {
        require(protocolVersion == REGIONAL_SHELTER_TRUST_PROTOCOL_VERSION, "unsupported_signed_manifest_protocol")
        requireIdentifier(regionId, "invalid_region_id")
        require(manifest.validate(nowEpochMillis) == RescueValidationResult.Valid, "invalid_manifest")
        requireIdentifier(signerKeyId, "invalid_signer_key_id")
        require(signatureAlgorithm == RescueAlgorithms.ECDSA_P256_SHA256, "unsupported_signature")
        require(signatureBase64.length in 64..256, "invalid_signature")
    }

    internal fun signingBytes(): ByteArray = trustCanonicalBytes(
        protocolVersion.toString(),
        "shelter-manifest",
        regionId,
        manifest.protocolVersion.toString(),
        manifest.shelterId,
        manifest.recipientPublicKey.keyId,
        manifest.recipientPublicKey.algorithm.name,
        manifest.recipientPublicKey.encodedBase64,
        manifest.receiptSigningPublicKey.keyId,
        manifest.receiptSigningPublicKey.algorithm.name,
        manifest.receiptSigningPublicKey.encodedBase64,
        manifest.validFromEpochMillis.toString(),
        manifest.validUntilEpochMillis.toString(),
        manifest.generation.toString(),
    )

    /** Includes the manifest signature, so a directory signature binds the exact beacon document. */
    internal fun directoryEntryBytes(): ByteArray = trustCanonicalBytes(
        signingBytes().decodeToString(),
        signerKeyId,
        signatureAlgorithm,
        signatureBase64,
    )
}

/**
 * Stable SHA-256 identity for the complete signed manifest.
 *
 * This deliberately covers the root-signature payload *and* its signature.  A BLE peripheral
 * therefore cannot replace a signed document with a different key, expiry, or signature while
 * retaining the same advertised identity.  This is an identifier only, not a trust decision;
 * callers must resolve it through [RegionalShelterDirectoryResolver].
 */
fun SignedShelterManifest.signedManifestFingerprint(): String =
    RescueCryptography.sha256Hex(directoryEntryBytes())

/**
 * Raw SHA-256 fingerprint bytes used by the BLE bridge's
 * `RELAY_BLE_SIGNED_MANIFEST_FINGERPRINT_BASE64` provisioning value.
 *
 * The bridge advertises the first 16 bytes; keeping this conversion here ensures Android and the
 * PC derive the BLE value from exactly the same canonical signed-manifest identity.
 */
fun SignedShelterManifest.beaconFingerprintBytes(): ByteArray =
    signedManifestFingerprint().hexToBytes()

/**
 * Privacy-preserving, non-secret identity for the shelter portion of a BLE advertisement.
 * The raw shelter id is never required in the advertisement; Android compares only a prefix of
 * this SHA-256 value against the signed directory it has already accepted.
 */
fun SignedShelterManifest.shelterIdentityHash(): String =
    RescueCryptography.sha256Hex(manifest.shelterId.encodeToByteArray())

/** Raw SHA-256 bytes whose first eight bytes are the BLE shelter identity. */
fun SignedShelterManifest.shelterIdentityHashBytes(): ByteArray =
    shelterIdentityHash().hexToBytes()

@Serializable
data class UnsignedRegionalShelterDirectory(
    val protocolVersion: Int = REGIONAL_SHELTER_TRUST_PROTOCOL_VERSION,
    val regionId: String,
    /** Monotonic within [regionId]. A device never replaces a higher generation with a lower one. */
    val generation: Long,
    val issuedAtEpochMillis: Long,
    val validUntilEpochMillis: Long,
    val shelters: List<SignedShelterManifest>,
) {
    fun validate(nowEpochMillis: Long? = null): RescueValidationResult = validationResult {
        require(protocolVersion == REGIONAL_SHELTER_TRUST_PROTOCOL_VERSION, "unsupported_directory_protocol")
        requireIdentifier(regionId, "invalid_region_id")
        require(generation in 1..1_000_000L, "invalid_directory_generation")
        require(issuedAtEpochMillis > 0, "invalid_directory_issued_at")
        require(validUntilEpochMillis > issuedAtEpochMillis, "invalid_directory_expiry")
        require(validUntilEpochMillis - issuedAtEpochMillis <= MAX_DIRECTORY_LIFETIME_MS, "directory_lifetime_too_long")
        require(shelters.size in 1..MAX_SHELTERS_PER_DIRECTORY, "invalid_directory_size")
        nowEpochMillis?.let { require(it in issuedAtEpochMillis..validUntilEpochMillis, "directory_not_current") }
        shelters.forEach { signed ->
            require(signed.regionId == regionId, "manifest_region_mismatch")
            require(signed.validate(nowEpochMillis) == RescueValidationResult.Valid, "invalid_signed_manifest")
        }
        require(shelters.sumOf { it.directoryEntryBytes().size } <= MAX_DIRECTORY_SIGNED_BYTES, "directory_too_large")
        val ordering = shelters.map { listOf(it.manifest.shelterId, it.manifest.generation.toString().padStart(10, '0'), it.manifest.recipientPublicKey.keyId) }
        require(ordering == ordering.sortedWith(compareBy<List<String>> { it[0] }.thenBy { it[1] }.thenBy { it[2] }), "directory_not_canonical")
        require(ordering.distinct().size == ordering.size, "duplicate_directory_manifest")
    }

    internal fun signingBytes(): ByteArray = trustCanonicalBytes(
        protocolVersion.toString(),
        "regional-directory",
        regionId,
        generation.toString(),
        issuedAtEpochMillis.toString(),
        validUntilEpochMillis.toString(),
        shelters.size.toString(),
        *shelters.map { RescueCryptography.sha256Hex(it.directoryEntryBytes()) }.toTypedArray(),
    )
}

@Serializable
data class SignedRegionalShelterDirectory(
    val directory: UnsignedRegionalShelterDirectory,
    val signerKeyId: String,
    val signatureAlgorithm: String = RescueAlgorithms.ECDSA_P256_SHA256,
    val signatureBase64: String,
) {
    fun validate(nowEpochMillis: Long? = null): RescueValidationResult = validationResult {
        require(directory.validate(nowEpochMillis) == RescueValidationResult.Valid, "invalid_directory")
        requireIdentifier(signerKeyId, "invalid_signer_key_id")
        require(signatureAlgorithm == RescueAlgorithms.ECDSA_P256_SHA256, "unsupported_signature")
        require(signatureBase64.length in 64..256, "invalid_signature")
    }
}

/** Root-only provisioning helpers. The ECDSA primitive only accepts canonical trust bytes. */
fun signShelterManifest(
    regionId: String,
    manifest: ShelterPublicKeyManifest,
    regionalSigningPrivateKey: RescuePrivateKey,
): SignedShelterManifest {
    require(regionalSigningPrivateKey.algorithm == RescueKeyAlgorithm.ECDSA_P256_SHA256) { "invalid_root_signing_key" }
    val unsigned = SignedShelterManifest(regionId = regionId, manifest = manifest, signerKeyId = regionalSigningPrivateKey.keyId, signatureBase64 = "A".repeat(64))
    require(unsigned.validate() == RescueValidationResult.Valid) { "invalid_manifest" }
    val signature = RescueCryptography.signTrustDocument(unsigned.signingBytes(), regionalSigningPrivateKey)
    return unsigned.copy(signerKeyId = signature.signerKeyId, signatureBase64 = signature.signatureBase64)
}

fun verifyShelterManifest(
    signed: SignedShelterManifest,
    root: RegionalRootBundle,
    nowEpochMillis: Long,
): Boolean = verifyTrustSignature(
    signed.validate(nowEpochMillis) == RescueValidationResult.Valid && signed.regionId == root.regionId,
    signed.signerKeyId,
    signed.signatureBase64,
    signed.signingBytes(),
    root,
)

fun signRegionalShelterDirectory(
    directory: UnsignedRegionalShelterDirectory,
    regionalSigningPrivateKey: RescuePrivateKey,
): SignedRegionalShelterDirectory {
    require(regionalSigningPrivateKey.algorithm == RescueKeyAlgorithm.ECDSA_P256_SHA256) { "invalid_root_signing_key" }
    require(directory.validate() == RescueValidationResult.Valid) { "invalid_directory" }
    val signature = RescueCryptography.signTrustDocument(directory.signingBytes(), regionalSigningPrivateKey)
    return SignedRegionalShelterDirectory(directory, signature.signerKeyId, signatureBase64 = signature.signatureBase64)
}

fun verifyRegionalShelterDirectory(
    signed: SignedRegionalShelterDirectory,
    root: RegionalRootBundle,
    nowEpochMillis: Long,
): Boolean = verifyTrustSignature(
    signed.validate(nowEpochMillis) == RescueValidationResult.Valid && signed.directory.regionId == root.regionId,
    signed.signerKeyId,
    signed.signatureBase64,
    signed.directory.signingBytes(),
    root,
)

sealed interface DirectoryAcceptance {
    data class Accepted(val directory: SignedRegionalShelterDirectory) : DirectoryAcceptance
    data object Stale : DirectoryAcceptance
    data object Invalid : DirectoryAcceptance
}

data class ResolvedShelterKeys(
    val regionId: String,
    val shelterId: String,
    val recipientPublicKey: RescuePublicKey,
    val receiptSigningPublicKey: RescuePublicKey,
    val manifestGeneration: Int,
)

/** In-memory monotonic directory cache used by Android provisioning and delivery services. */
class RegionalShelterDirectoryResolver(rootBundles: Collection<RegionalRootBundle>) {
    private val roots = rootBundles.associateBy { it.regionId }.also { require(it.size == rootBundles.size) { "duplicate_root_region" } }
    private val accepted = mutableMapOf<String, SignedRegionalShelterDirectory>()

    fun accept(candidate: SignedRegionalShelterDirectory, nowEpochMillis: Long): DirectoryAcceptance {
        val root = roots[candidate.directory.regionId] ?: return DirectoryAcceptance.Invalid
        if (root.validate() != RescueValidationResult.Valid || !verifyRegionalShelterDirectory(candidate, root, nowEpochMillis)) return DirectoryAcceptance.Invalid
        val current = accepted[candidate.directory.regionId]
        if (current != null && candidate.directory.generation < current.directory.generation) return DirectoryAcceptance.Stale
        if (current != null && candidate.directory.generation == current.directory.generation && candidate.signatureBase64 != current.signatureBase64) return DirectoryAcceptance.Invalid
        accepted[candidate.directory.regionId] = candidate
        return DirectoryAcceptance.Accepted(candidate)
    }

    fun resolveForNewRequest(regionId: String, shelterId: String, nowEpochMillis: Long): ResolvedShelterKeys? =
        accepted[regionId]
            ?.takeIf { it.directory.validate(nowEpochMillis) == RescueValidationResult.Valid }
            ?.directory
            ?.shelters
            ?.asSequence()
            ?.filter { it.manifest.shelterId == shelterId && it.manifest.validate(nowEpochMillis) == RescueValidationResult.Valid }
            ?.maxWithOrNull(compareBy<SignedShelterManifest> { it.manifest.generation }.thenBy { it.manifest.recipientPublicKey.keyId })
            ?.toResolvedKeys()

    fun resolveForEnvelope(regionId: String, shelterId: String, recipientKeyId: String, nowEpochMillis: Long): ResolvedShelterKeys? =
        accepted[regionId]
            ?.takeIf { it.directory.validate(nowEpochMillis) == RescueValidationResult.Valid }
            ?.directory
            ?.shelters
            ?.firstOrNull {
                it.manifest.shelterId == shelterId &&
                    it.manifest.recipientPublicKey.keyId == recipientKeyId &&
                    it.manifest.validate(nowEpochMillis) == RescueValidationResult.Valid
            }
            ?.toResolvedKeys()

    fun verifyAdvertisedManifest(signed: SignedShelterManifest, nowEpochMillis: Long): Boolean {
        val root = roots[signed.regionId] ?: return false
        if (root.validate() != RescueValidationResult.Valid || !verifyShelterManifest(signed, root, nowEpochMillis)) return false
        val trusted = accepted[signed.regionId]?.directory?.shelters ?: return false
        return trusted.any { it == signed }
    }

    /**
     * Resolves the compact BLE identity without trusting data received over BLE.
     *
     * The fixed-size values are raw SHA-256 prefixes from [ShelterBleIdentity]: eight shelter-id
     * bytes and sixteen signed-manifest bytes. The resolver searches only directories whose root
     * signature was already accepted and whose directory and leaf are current. A collision or
     * any malformed identity returns null rather than choosing an arbitrary shelter.
     */
    fun resolveBeaconIdentity(
        shelterIdHashPrefix: ByteArray,
        signedManifestFingerprintPrefix: ByteArray,
        nowEpochMillis: Long,
    ): SignedShelterManifest? {
        if (shelterIdHashPrefix.size != BLE_SHELTER_ID_HASH_PREFIX_BYTES ||
            signedManifestFingerprintPrefix.size != BLE_SIGNED_MANIFEST_FINGERPRINT_PREFIX_BYTES
        ) return null
        val matches = accepted.values
            .asSequence()
            .filter { it.directory.validate(nowEpochMillis) == RescueValidationResult.Valid }
            .flatMap { it.directory.shelters.asSequence() }
            .filter { it.validate(nowEpochMillis) == RescueValidationResult.Valid }
            .filter { it.shelterIdentityHashBytes().copyOf(BLE_SHELTER_ID_HASH_PREFIX_BYTES).contentEquals(shelterIdHashPrefix) }
            .filter { it.beaconFingerprintBytes().copyOf(BLE_SIGNED_MANIFEST_FINGERPRINT_PREFIX_BYTES).contentEquals(signedManifestFingerprintPrefix) }
            .toList()
        return matches.singleOrNull()
    }

    private companion object {
        const val BLE_SHELTER_ID_HASH_PREFIX_BYTES = 8
        const val BLE_SIGNED_MANIFEST_FINGERPRINT_PREFIX_BYTES = 16
    }
}

private fun SignedShelterManifest.toResolvedKeys() = ResolvedShelterKeys(regionId, manifest.shelterId, manifest.recipientPublicKey, manifest.receiptSigningPublicKey, manifest.generation)

private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { index ->
    substring(index * 2, index * 2 + 2).toInt(16).toByte()
}

private fun verifyTrustSignature(valid: Boolean, signerKeyId: String, signatureBase64: String, canonicalBytes: ByteArray, root: RegionalRootBundle): Boolean {
    if (!valid || root.validate() != RescueValidationResult.Valid || signerKeyId != root.rootSigningPublicKey.keyId) return false
    return RescueCryptography.verifyTrustDocument(
        canonicalBytes,
        TrustDocumentSignature(signerKeyId, signatureBase64 = signatureBase64),
        root.rootSigningPublicKey,
    )
}

private fun trustCanonicalBytes(vararg fields: String): ByteArray = buildString {
    fields.forEach { field -> append(field.encodeToByteArray().size).append(':').append(field) }
}.encodeToByteArray()
