package com.example.relay.rescue

import kotlinx.serialization.Serializable

const val RESCUE_PROTOCOL_VERSION: Int = 1
const val RESCUE_MAX_CIPHERTEXT_BYTES: Int = 1_048_576

@Serializable
enum class RescueUrgency { ROUTINE, URGENT, IMMEDIATE }

@Serializable
enum class RescueSupportNeed { WATER, FOOD, MEDICINE, RESCUE_TEAM, TRANSPORT }

@Serializable
data class RescueLocation(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val description: String = "",
)

@Serializable
data class RescuePayload(
    val protocolVersion: Int = RESCUE_PROTOCOL_VERSION,
    val requestId: String,
    val requestVersion: Int = 1,
    val senderDeviceId: String,
    val destinationShelterId: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val urgency: RescueUrgency,
    val personCount: Int = 1,
    val injured: Boolean = false,
    val seriouslyInjured: Boolean = false,
    val mobilityImpaired: Boolean = false,
    val elderlyPresent: Boolean = false,
    val childrenPresent: Boolean = false,
    val pregnantPresent: Boolean = false,
    val medicalSupportRequired: Boolean = false,
    val trapped: Boolean = false,
    val fireOrCollapseRisk: Boolean = false,
    val supportNeeds: Set<RescueSupportNeed> = emptySet(),
    val location: RescueLocation? = null,
    val freeText: String = "",
)

/**
 * Store-carry-forward envelope. [hopCount] is intentionally mutable routing metadata and is not
 * authenticated as AAD. All other routing header fields are immutable and are bound to the
 * ciphertext by AES-GCM.
 */
@Serializable
data class EncryptedRescueEnvelope(
    val protocolVersion: Int = RESCUE_PROTOCOL_VERSION,
    val envelopeId: String,
    val requestId: String,
    val requestVersion: Int,
    val senderDeviceId: String,
    val destinationShelterId: String,
    val routingUrgency: RescueUrgency,
    val recipientKeyId: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val maxHopCount: Int = 8,
    val hopCount: Int = 0,
    val keyWrapAlgorithm: String = RescueAlgorithms.RSA_OAEP_SHA256,
    val contentEncryptionAlgorithm: String = RescueAlgorithms.AES_256_GCM,
    val ciphertextSizeBytes: Int,
    val wrappedContentKeyBase64: String,
    val nonceBase64: String,
    val ciphertextBase64: String,
    val ciphertextSha256Hex: String,
)

@Serializable
enum class ShelterReceiptStatus { STORED, ACCEPTED, REJECTED }

@Serializable
data class UnsignedShelterReceipt(
    val protocolVersion: Int = RESCUE_PROTOCOL_VERSION,
    val receiptId: String,
    val envelopeId: String,
    val requestId: String,
    val requestVersion: Int,
    val ciphertextSha256Hex: String,
    val shelterId: String,
    val receivedAtEpochMillis: Long,
    val status: ShelterReceiptStatus,
)

@Serializable
data class SignedShelterReceipt(
    val receipt: UnsignedShelterReceipt,
    val signerKeyId: String,
    val signatureAlgorithm: String = RescueAlgorithms.ECDSA_P256_SHA256,
    val signatureBase64: String,
)

/** Detached signature used exclusively by regional shelter trust documents. */
@Serializable
data class TrustDocumentSignature(
    val signerKeyId: String,
    val signatureAlgorithm: String = RescueAlgorithms.ECDSA_P256_SHA256,
    val signatureBase64: String,
)

/** Detached signature carried by an ordinary REPORT for tamper detection. */
@Serializable
data class ReportSignature(
    val signerKeyId: String,
    val signatureAlgorithm: String = RescueAlgorithms.ECDSA_P256_SHA256,
    val publicKey: RescuePublicKey,
    val signatureBase64: String,
)

object RescueAlgorithms {
    const val AES_256_GCM = "AES-256-GCM"
    const val RSA_OAEP_SHA256 = "RSA-OAEP-256"
    const val ECDSA_P256_SHA256 = "ECDSA-P256-SHA256"
}

@Serializable
enum class RescueKeyAlgorithm { RSA_OAEP_SHA256, ECDSA_P256_SHA256 }

/** X.509 SubjectPublicKeyInfo, Base64 encoded. */
@Serializable
data class RescuePublicKey(
    val keyId: String,
    val algorithm: RescueKeyAlgorithm,
    val encodedBase64: String,
)

/** PKCS#8 private key material. Never place this type in a courier workflow or wire model. */
data class RescuePrivateKey(
    val keyId: String,
    val algorithm: RescueKeyAlgorithm,
    val encodedBase64: String,
)

data class RescueKeyPair(
    val publicKey: RescuePublicKey,
    val privateKey: RescuePrivateKey,
)

sealed interface RescueValidationResult {
    data object Valid : RescueValidationResult
    data class Invalid(val reasons: List<String>) : RescueValidationResult
}

fun RescuePayload.validate(): RescueValidationResult = validationResult {
    require(protocolVersion == RESCUE_PROTOCOL_VERSION, "unsupported_protocol")
    requireIdentifier(requestId, "invalid_request_id")
    require(requestVersion in 1..1_000_000, "invalid_request_version")
    require(requestVersion in 1..1_000_000, "invalid_request_version")
    requireIdentifier(senderDeviceId, "invalid_sender_id")
    requireIdentifier(destinationShelterId, "invalid_shelter_id")
    require(createdAtEpochMillis > 0, "invalid_created_at")
    require(expiresAtEpochMillis > createdAtEpochMillis, "invalid_expiry")
    require(expiresAtEpochMillis - createdAtEpochMillis <= 7L * 24 * 60 * 60 * 1_000, "lifetime_too_long")
    require(personCount in 1..1_000, "invalid_person_count")
    require(freeText.length <= 2_000, "message_too_long")
    location?.let {
        require(it.description.length <= 256, "location_too_long")
        require(it.latitude == null || it.latitude in -90.0..90.0, "invalid_latitude")
        require(it.longitude == null || it.longitude in -180.0..180.0, "invalid_longitude")
        require(it.accuracyMeters == null || it.accuracyMeters in 0f..100_000f, "invalid_accuracy")
    }
}

fun EncryptedRescueEnvelope.validate(): RescueValidationResult = validationResult {
    require(protocolVersion == RESCUE_PROTOCOL_VERSION, "unsupported_protocol")
    requireIdentifier(envelopeId, "invalid_envelope_id")
    requireIdentifier(requestId, "invalid_request_id")
    require(requestVersion in 1..1_000_000, "invalid_request_version")
    requireIdentifier(senderDeviceId, "invalid_sender_id")
    requireIdentifier(destinationShelterId, "invalid_shelter_id")
    requireIdentifier(recipientKeyId, "invalid_recipient_key_id")
    require(createdAtEpochMillis > 0 && expiresAtEpochMillis > createdAtEpochMillis, "invalid_lifetime")
    require(expiresAtEpochMillis - createdAtEpochMillis <= 7L * 24 * 60 * 60 * 1_000, "lifetime_too_long")
    require(maxHopCount in 1..32 && hopCount in 0..maxHopCount, "invalid_hop")
    require(keyWrapAlgorithm == RescueAlgorithms.RSA_OAEP_SHA256, "unsupported_key_wrap")
    require(contentEncryptionAlgorithm == RescueAlgorithms.AES_256_GCM, "unsupported_content_encryption")
    require(ciphertextSizeBytes in 16..RESCUE_MAX_CIPHERTEXT_BYTES, "invalid_ciphertext_size")
    require(ciphertextSha256Hex.length == 64 && ciphertextSha256Hex.all(::isLowerHex), "invalid_ciphertext_hash")
    require(wrappedContentKeyBase64.length in 128..2_048, "invalid_wrapped_key")
    require(nonceBase64.length in 16..32, "invalid_nonce")
    require(ciphertextBase64.length in 24..((RESCUE_MAX_CIPHERTEXT_BYTES * 4 / 3) + 8), "invalid_ciphertext")
}

fun UnsignedShelterReceipt.validate(): RescueValidationResult = validationResult {
    require(protocolVersion == RESCUE_PROTOCOL_VERSION, "unsupported_protocol")
    requireIdentifier(receiptId, "invalid_receipt_id")
    requireIdentifier(envelopeId, "invalid_envelope_id")
    requireIdentifier(requestId, "invalid_request_id")
    requireIdentifier(shelterId, "invalid_shelter_id")
    require(receivedAtEpochMillis > 0, "invalid_received_at")
    require(ciphertextSha256Hex.length == 64 && ciphertextSha256Hex.all(::isLowerHex), "invalid_ciphertext_hash")
}

fun SignedShelterReceipt.validate(): RescueValidationResult = validationResult {
    require(receipt.validate() == RescueValidationResult.Valid, "invalid_receipt")
    requireIdentifier(signerKeyId, "invalid_signer_key_id")
    require(signatureAlgorithm == RescueAlgorithms.ECDSA_P256_SHA256, "unsupported_signature")
    require(signatureBase64.length in 64..256, "invalid_signature")
}

fun TrustDocumentSignature.validate(): RescueValidationResult = validationResult {
    requireIdentifier(signerKeyId, "invalid_signer_key_id")
    require(signatureAlgorithm == RescueAlgorithms.ECDSA_P256_SHA256, "unsupported_signature")
    require(signatureBase64.length in 64..256, "invalid_signature")
}

fun ReportSignature.validate(): RescueValidationResult = validationResult {
    requireIdentifier(signerKeyId, "invalid_signer_key_id")
    require(signatureAlgorithm == RescueAlgorithms.ECDSA_P256_SHA256, "unsupported_signature")
    require(publicKey.keyId == signerKeyId, "signer_key_mismatch")
    require(publicKey.algorithm == RescueKeyAlgorithm.ECDSA_P256_SHA256, "unsupported_key")
    require(signatureBase64.length in 64..256, "invalid_signature")
}

/** Canonical, length-prefixed AAD. Mutable hopCount and encrypted fields are deliberately absent. */
fun EncryptedRescueEnvelope.authenticatedHeaderBytes(): ByteArray = canonicalBytes(
    protocolVersion.toString(),
    envelopeId,
    requestId,
    requestVersion.toString(),
    senderDeviceId,
    destinationShelterId,
    routingUrgency.name,
    recipientKeyId,
    createdAtEpochMillis.toString(),
    expiresAtEpochMillis.toString(),
    maxHopCount.toString(),
    keyWrapAlgorithm,
    contentEncryptionAlgorithm,
    ciphertextSizeBytes.toString(),
)

fun EncryptedRescueEnvelope.duplicateKey(): String = "$requestId:$requestVersion"

internal fun UnsignedShelterReceipt.signingBytes(): ByteArray = canonicalBytes(
    protocolVersion.toString(),
    receiptId,
    envelopeId,
    requestId,
    requestVersion.toString(),
    ciphertextSha256Hex,
    shelterId,
    receivedAtEpochMillis.toString(),
    status.name,
)

private fun canonicalBytes(vararg fields: String): ByteArray = buildString {
    fields.forEach { field -> append(field.encodeToByteArray().size).append(':').append(field) }
}.encodeToByteArray()

private fun isLowerHex(character: Char): Boolean = character in '0'..'9' || character in 'a'..'f'

internal class ValidationCollector {
    val reasons = mutableListOf<String>()
    fun require(condition: Boolean, reason: String) { if (!condition) reasons += reason }
    fun requireIdentifier(value: String, reason: String) {
        require(value.length in 1..128 && value.all { it.isLetterOrDigit() || it in "-_.:" }, reason)
    }
}

internal inline fun validationResult(block: ValidationCollector.() -> Unit): RescueValidationResult {
    val collector = ValidationCollector().apply(block)
    return if (collector.reasons.isEmpty()) RescueValidationResult.Valid else RescueValidationResult.Invalid(collector.reasons)
}
