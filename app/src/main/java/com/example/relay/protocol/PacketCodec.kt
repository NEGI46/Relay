package com.example.relay.protocol

import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.MessageValidation
import com.example.relay.domain.ReceiptType
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

class PacketCodec(
    private val messagePolicy: MessagePolicy,
    private val limits: ProtocolLimits = ProtocolLimits(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "payloadType"
    },
) {
    fun encode(
        senderDeviceId: String,
        sentAt: Long,
        body: PacketBody,
        packetId: String = UUID.randomUUID().toString(),
    ): ByteArray {
        val bodyElement = when (body) {
            is HelloBody -> json.encodeToJsonElement(HelloBody.serializer(), body)
            is ManifestBody -> json.encodeToJsonElement(ManifestBody.serializer(), body)
            is MessageRequestBody -> json.encodeToJsonElement(MessageRequestBody.serializer(), body)
            is MessageDataBody -> json.encodeToJsonElement(MessageDataBody.serializer(), body)
            is ReceiptDataBody -> json.encodeToJsonElement(ReceiptDataBody.serializer(), body)
            is AckBody -> json.encodeToJsonElement(AckBody.serializer(), body)
            is ErrorBody -> json.encodeToJsonElement(ErrorBody.serializer(), body)
        }
        return json.encodeToString(
            WireEnvelope.serializer(),
            WireEnvelope(CURRENT_PROTOCOL_VERSION, body.type, packetId, senderDeviceId, sentAt, bodyElement),
        ).encodeToByteArray()
    }

    fun decode(bytes: ByteArray): DecodeResult {
        checkPayloadSize(bytes)?.let { return it }
        val envelope = parseEnvelope(bytes) ?: return DecodeResult.Failure(DecodeError.MALFORMED_JSON)
        if (!isProtocolVersionValid(envelope)) return DecodeResult.Failure(DecodeError.UNKNOWN_PROTOCOL_VERSION)
        if (!isEnvelopeValid(envelope)) return DecodeResult.Failure(DecodeError.INVALID_ENVELOPE)
        val serializer = getSerializer(envelope.packetType) ?: return DecodeResult.Failure(DecodeError.UNKNOWN_PACKET_TYPE)
        val body = parseBody(envelope, serializer) ?: return DecodeResult.Failure(DecodeError.INVALID_BODY)
        val invalidReason = validateBody(body, envelope.senderDeviceId)
        return if (invalidReason == null) DecodeResult.Success(DecodedPacket(envelope, body))
        else DecodeResult.Failure(DecodeError.INVALID_BODY, invalidReason)
    }

    private fun checkPayloadSize(bytes: ByteArray): DecodeResult.Failure? {
        return if (bytes.size > limits.maxPacketBytes) DecodeResult.Failure(DecodeError.PAYLOAD_TOO_LARGE) else null
    }

    private fun parseEnvelope(bytes: ByteArray): WireEnvelope? {
        return try {
            json.decodeFromString(WireEnvelope.serializer(), bytes.decodeToString())
        } catch (_: Exception) {
            null
        }
    }

    private fun isProtocolVersionValid(envelope: WireEnvelope): Boolean {
        return envelope.protocolVersion == CURRENT_PROTOCOL_VERSION
    }

    private fun isEnvelopeValid(envelope: WireEnvelope): Boolean {
        return validId(envelope.packetId) && validId(envelope.senderDeviceId) && envelope.sentAt >= 0
    }

    private fun <T> parseBody(envelope: WireEnvelope, serializer: KSerializer<T>): T? {
        return try {
            json.decodeFromJsonElement(serializer, envelope.body)
        } catch (_: Exception) {
            null
        }
    }

    private fun getSerializer(packetType: PacketTypes): KSerializer<*>? {
        return when (packetType) {
            PacketTypes.HELLO -> HelloBody.serializer()
            PacketTypes.MANIFEST -> ManifestBody.serializer()
            PacketTypes.MESSAGE_REQUEST -> MessageRequestBody.serializer()
            PacketTypes.MESSAGE_DATA -> MessageDataBody.serializer()
            PacketTypes.RECEIPT_DATA -> ReceiptDataBody.serializer()
            PacketTypes.ACK -> AckBody.serializer()
            PacketTypes.ERROR -> ErrorBody.serializer()
            else -> null
        }
    }

    private fun validateBody(body: PacketBody, senderDeviceId: String): String? = when (body) {
        is HelloBody -> if (body.displayName.length <= 64) null else "display name too long"
        is ManifestBody -> when {
            body.entries.size > limits.maxManifestEntries -> "manifest too large"
            body.receiptIds.size > limits.maxManifestEntries -> "receipt manifest too large"
            body.entries.any { !validId(it.messageId) || it.hopCount < 0 || it.maxHopCount < 1 || it.hopCount > it.maxHopCount } -> "invalid manifest entry"
            else -> null
        }
        is MessageRequestBody -> when {
            body.messageIds.size > limits.maxRequestEntries -> "request too large"
            body.messageIds.distinct().size != body.messageIds.size -> "duplicate request id"
            body.messageIds.any { !validId(it) } -> "invalid request id"
            body.receiptIds.any { !validId(it) } -> "invalid receipt request id"
            else -> null
        }
        is MessageDataBody -> when (val validation = messagePolicy.validate(body.message)) {
            MessageValidation.Valid -> null
            is MessageValidation.Invalid -> validation.reason
        }
        is ReceiptDataBody -> if (validId(body.receipt.receiptId) && validId(body.receipt.messageId) &&
            validId(body.receipt.actorId) && body.receipt.recordedAt >= 0
        ) null else "invalid receipt"
        is AckBody -> when {
            !validId(body.messageId) || !validId(body.dataPacketId) -> "invalid ack"
            body.peerReceipt == null -> "peer receipt required"
            !validId(body.peerReceipt.receiptId) || !validId(body.peerReceipt.actorId) -> "invalid peer receipt"
            body.peerReceipt.receiptType != ReceiptType.PEER_RECEIVED -> "peer receipt type required"
            body.peerReceipt.messageId != body.messageId -> "peer receipt message mismatch"
            body.peerReceipt.actorId != senderDeviceId -> "peer receipt actor mismatch"
            body.peerReceipt.recordedAt < 0 -> "invalid peer receipt time"
            else -> null
        }
        is ErrorBody -> if (body.code.length in 1..32 && body.detail.length <= limits.maxErrorDetailChars) null else "invalid error"
    }

    private fun validId(value: String): Boolean = value.length in 1..limits.maxIdentifierChars
}
