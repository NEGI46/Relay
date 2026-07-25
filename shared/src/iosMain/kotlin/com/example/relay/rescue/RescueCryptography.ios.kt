package com.example.relay.rescue

import com.example.relay.domain.RelayMessage
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

/**
 * iOS actual for RescueCryptography.
 *
 * sha256Hex is fully implemented using CommonCrypto.
 * All other cryptographic operations throw [RescueCryptoException] with code
 * "ios_not_yet_implemented" — they are not exercised by the current iOS smoke
 * build which only renders the Compose UI.
 *
 * Full Apple Security framework implementation is planned for a future PR.
 */
@OptIn(ExperimentalForeignApi::class)
actual object RescueCryptography {

    private fun notImplemented(): Nothing =
        throw RescueCryptoException("ios_not_yet_implemented")

    actual fun generateRecipientKeyPair(): RescueKeyPair = notImplemented()

    actual fun generateShelterSigningKeyPair(): RescueKeyPair = notImplemented()

    actual fun generateReportSigningKeyPair(): RescueKeyPair = notImplemented()

    actual fun importPublicKey(
        keyId: String,
        algorithm: RescueKeyAlgorithm,
        encodedBase64: String,
    ): RescuePublicKey = notImplemented()

    actual fun importPrivateKey(
        keyId: String,
        algorithm: RescueKeyAlgorithm,
        encodedBase64: String,
    ): RescuePrivateKey = notImplemented()

    actual fun encrypt(
        payload: RescuePayload,
        recipientPublicKey: RescuePublicKey,
        envelopeId: String,
        maxHopCount: Int,
    ): EncryptedRescueEnvelope = notImplemented()

    actual fun decrypt(
        envelope: EncryptedRescueEnvelope,
        recipientPrivateKey: RescuePrivateKey,
    ): RescuePayload = notImplemented()

    actual fun verifyEnvelopeFraming(envelope: EncryptedRescueEnvelope): Boolean = notImplemented()

    @OptIn(ExperimentalForeignApi::class)
    actual fun sha256Hex(bytes: ByteArray): String {
        val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
        bytes.usePinned { pinned ->
            digest.usePinned { digestPinned ->
                CC_SHA256(
                    pinned.addressOf(0),
                    bytes.size.convert(),
                    digestPinned.addressOf(0),
                )
            }
        }
        return digest.joinToString("") { it.toString(16).padStart(2, '0') }
    }

    actual fun signReceipt(
        receipt: UnsignedShelterReceipt,
        shelterPrivateKey: RescuePrivateKey,
    ): SignedShelterReceipt = notImplemented()

    actual fun verifyReceipt(
        receipt: SignedShelterReceipt,
        shelterPublicKey: RescuePublicKey,
    ): Boolean = notImplemented()

    actual fun signTrustDocument(
        canonicalBytes: ByteArray,
        regionalSigningPrivateKey: RescuePrivateKey,
    ): TrustDocumentSignature = notImplemented()

    actual fun verifyTrustDocument(
        canonicalBytes: ByteArray,
        signature: TrustDocumentSignature,
        regionalSigningPublicKey: RescuePublicKey,
    ): Boolean = notImplemented()

    actual fun signReport(
        message: RelayMessage,
        signingKeyPair: RescueKeyPair,
    ): RelayMessage = notImplemented()

    actual fun verifyReport(message: RelayMessage): Boolean = notImplemented()
}
