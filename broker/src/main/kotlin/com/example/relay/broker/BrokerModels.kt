package com.example.relay.broker

import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.SignedShelterReceipt
import kotlinx.serialization.Serializable

/** Upload request from an Android device. The envelope is already encrypted; Broker never decrypts. */
@Serializable
data class BrokerUploadRequest(
    val envelope: EncryptedRescueEnvelope,
    /** Android Keystore install key identifier. Proves upload origin but is NOT identity-verified. */
    val deviceKeyId: String,
    /** ECDSA-P256 signature over envelope authenticatedHeaderBytes + ciphertextSha256Hex. */
    val uploadSignatureBase64: String,
)

/** Broker acknowledgement that the ciphertext is stored. This is NOT a shelter receipt. */
@Serializable
data class BrokerUploadResponse(
    val brokerReceiptId: String,
    val envelopeId: String,
    val status: String = "BROKER_STORED",
    val storedAtEpochMillis: Long,
)

/** Batch of pending envelopes for a Gateway to pull. Cursor-based pagination. */
@Serializable
data class BrokerEnvelopeBatch(
    val envelopes: List<EncryptedRescueEnvelope>,
    /** Opaque cursor for the next page. Null when no more results. */
    val cursor: String? = null,
)

/** A signed shelter receipt uploaded by the PC Gateway for relay back to the device. */
@Serializable
data class BrokerReceiptUpload(
    val receipt: SignedShelterReceipt,
    val gatewayId: String,
)

@Serializable
data class BrokerReceiptUploadResponse(
    val accepted: Boolean,
    val reason: String? = null,
)

/** Receipts available for a device to poll. Capability token is the deviceKeyId path parameter. */
@Serializable
data class BrokerReceiptBatch(
    val receipts: List<SignedShelterReceipt>,
)

@Serializable
data class BrokerHealthResponse(
    val status: String = "ok",
    val pendingEnvelopes: Int,
    val pendingReceipts: Int,
    val version: String = "1.0.0",
)

/** Internal ledger status for Broker-side tracking. Separate from shelter receipt statuses. */
enum class BrokerLedgerStatus {
    BROKER_STORED,
    GATEWAY_PULLED,
}
