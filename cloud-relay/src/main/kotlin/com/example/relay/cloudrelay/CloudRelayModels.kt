package com.example.relay.cloudrelay

import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.SignedShelterReceipt
import kotlinx.serialization.Serializable

@Serializable
data class CloudStoredReceipt(
    val receiptId: String, val envelopeId: String, val requestId: String, val requestVersion: Int,
    val ciphertextSha256Hex: String, val shelterId: String, val storedAtEpochMillis: Long,
    val status: String = "CLOUD_STORED",
)
@Serializable data class EnvelopeUploadResponse(val receipt: CloudStoredReceipt)
@Serializable data class GatewayEnvelopeBatch(val envelopes: List<EncryptedRescueEnvelope>)
@Serializable data class GatewayReceiptRequest(val receipt: SignedShelterReceipt)
