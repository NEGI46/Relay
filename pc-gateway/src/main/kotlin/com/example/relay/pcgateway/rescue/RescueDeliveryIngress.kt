package com.example.relay.pcgateway.rescue

import com.example.relay.pcgateway.GatewayJson
import com.example.relay.rescue.EncryptedRescueEnvelope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString

/**
 * Transport-neutral entry point for courier deliveries.
 *
 * BLE, USB, and a future local HTTP adapter pass only opaque bytes here.  The byte
 * cap and serialization boundary live before decryption, while [RescueIntakeService]
 * owns all semantic validation and the durable-commit-before-receipt guarantee.
 */
class RescueDeliveryIngress(
    private val intakeService: RescueIntakeService,
    private val maxEnvelopeBytes: Int = DEFAULT_MAX_ENVELOPE_BYTES,
) {
    init {
        require(maxEnvelopeBytes in 1..MAX_SUPPORTED_ENVELOPE_BYTES) { "invalid rescue envelope byte cap" }
    }

    fun ingest(
        envelopeBytes: ByteArray,
        carrierId: String,
        courierDeliveryId: String,
    ): RescueIngestResult {
        if (envelopeBytes.size > maxEnvelopeBytes) {
            return RescueIngestResult.Rejected(RescueRejectionCode.PAYLOAD_TOO_LARGE)
        }
        val envelope = try {
            GatewayJson.decodeFromString<EncryptedRescueEnvelope>(envelopeBytes.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            return RescueIngestResult.Rejected(RescueRejectionCode.MALFORMED_SERIALIZATION)
        } catch (_: IllegalArgumentException) {
            return RescueIngestResult.Rejected(RescueRejectionCode.MALFORMED_SERIALIZATION)
        }
        return intakeService.ingest(envelope, carrierId, courierDeliveryId)
    }

    companion object {
        const val DEFAULT_MAX_ENVELOPE_BYTES = 32 * 1024
        const val MAX_SUPPORTED_ENVELOPE_BYTES = 64 * 1024
    }
}
