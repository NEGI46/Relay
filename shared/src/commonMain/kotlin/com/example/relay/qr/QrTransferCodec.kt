package com.example.relay.qr

import com.example.relay.rescue.RescueCryptography
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val CURRENT_SCHEMA_VERSION = 1

/** A QR frame carries only an opaque, already encrypted Relay payload. */
@Serializable
data class QrTransferFrame(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val transferId: String,
    val frameIndex: Int,
    val totalFrames: Int,
    val payloadChunk: String,
    val payloadHash: String,
    val expiresAtEpochMillis: Long,
)

sealed interface QrTransferDecodeResult {
    data class Accepted(val payload: ByteArray, val transferId: String) : QrTransferDecodeResult
    data class Rejected(val reason: QrTransferRejection) : QrTransferDecodeResult
}

enum class QrTransferRejection {
    MALFORMED_FRAME,
    UNSUPPORTED_VERSION,
    INVALID_METADATA,
    EXPIRED,
    MIXED_TRANSFER,
    DUPLICATE_FRAME,
    MISSING_FRAME,
    HASH_MISMATCH,
}

/**
 * Deterministic codec for QR transport. The input must be an encrypted envelope
 * serialization or another opaque payload; this class never decodes rescue data.
 */
object QrTransferCodec {
    private const val MAX_FRAMES = 256
    private const val MAX_PAYLOAD_BYTES = 512 * 1024
    private const val MAX_CHUNK_HEX_CHARS = 4 * 1024
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun encode(
        transferId: String,
        encryptedPayload: ByteArray,
        expiresAtEpochMillis: Long,
        maxChunkBytes: Int = 1_024,
    ): List<QrTransferFrame> {
        require(transferId.isNotBlank() && transferId.length <= 128)
        require(encryptedPayload.isNotEmpty() && encryptedPayload.size <= MAX_PAYLOAD_BYTES)
        require(maxChunkBytes in 128..4_096)
        require(expiresAtEpochMillis > 0)
        val hash = RescueCryptography.sha256Hex(encryptedPayload)
        val chunks = encryptedPayload.asList().chunked(maxChunkBytes)
        require(chunks.size <= MAX_FRAMES)
        return chunks.mapIndexed { index, bytes ->
            QrTransferFrame(
                transferId = transferId,
                frameIndex = index,
                totalFrames = chunks.size,
                payloadChunk = bytes.toByteArray().toHex(),
                payloadHash = hash,
                expiresAtEpochMillis = expiresAtEpochMillis,
            )
        }
    }

    fun encodeForQr(frame: QrTransferFrame): ByteArray = json.encodeToString(QrTransferFrame.serializer(), frame).encodeToByteArray()

    fun decodeFrame(encoded: ByteArray): QrTransferFrame? = try {
        json.decodeFromString(QrTransferFrame.serializer(), encoded.decodeToString())
    } catch (_: Exception) {
        null
    }

    fun assemble(
        frames: Collection<QrTransferFrame>,
        nowEpochMillis: Long,
    ): QrTransferDecodeResult {
        validateEmptyFrames(frames)?.let { return it }
        val first = frames.first()
        validateSchemaVersion(first)?.let { return it }
        validateMetadata(first)?.let { return it }
        validateExpiry(nowEpochMillis, first)?.let { return it }
        validateFrameCount(frames, first)?.let { return it }
        validateHomogeneous(frames, first)?.let { return it }
        validateDuplicateFrames(frames)?.let { return it }
        validateFrameValidity(frames, first)?.let { return it }
        val ordered = frames.sortedBy { it.frameIndex }
        validateFrameOrdering(ordered, first)?.let { return it }
        val payload = ordered.flatMap { it.payloadChunk.fromHex().asList() }.toByteArray()
        validatePayloadSizeAndHash(payload, first)?.let { return it }
        return QrTransferDecodeResult.Accepted(payload, first.transferId)
    }

    private fun validateEmptyFrames(
        frames: Collection<QrTransferFrame>
    ): QrTransferDecodeResult.Rejected? =
        if (frames.isEmpty()) QrTransferDecodeResult.Rejected(QrTransferRejection.MISSING_FRAME)
        else null

    private fun validateSchemaVersion(
        frame: QrTransferFrame
    ): QrTransferDecodeResult.Rejected? =
        if (frame.schemaVersion != CURRENT_SCHEMA_VERSION) QrTransferDecodeResult.Rejected(QrTransferRejection.UNSUPPORTED_VERSION)
        else null

    private fun validateMetadata(
        frame: QrTransferFrame
    ): QrTransferDecodeResult.Rejected? =
        if (frame.transferId.isBlank() || frame.totalFrames !in 1..MAX_FRAMES || frame.expiresAtEpochMillis <= 0) {
            QrTransferDecodeResult.Rejected(QrTransferRejection.INVALID_METADATA)
        } else null

    private fun validateExpiry(
        nowEpochMillis: Long,
        frame: QrTransferFrame
    ): QrTransferDecodeResult.Rejected? =
        if (nowEpochMillis >= frame.expiresAtEpochMillis) QrTransferDecodeResult.Rejected(QrTransferRejection.EXPIRED)
        else null

    private fun validateFrameCount(
        frames: Collection<QrTransferFrame>,
        frame: QrTransferFrame
    ): QrTransferDecodeResult.Rejected? =
        if (frames.size != frame.totalFrames) QrTransferDecodeResult.Rejected(QrTransferRejection.MISSING_FRAME)
        else null

    private fun validateHomogeneous(
        frames: Collection<QrTransferFrame>,
        frame: QrTransferFrame
    ): QrTransferDecodeResult.Rejected? =
        if (frames.any {
                it.schemaVersion != frame.schemaVersion ||
                it.transferId != frame.transferId ||
                it.totalFrames != frame.totalFrames ||
                it.payloadHash != frame.payloadHash ||
                it.expiresAtEpochMillis != frame.expiresAtEpochMillis
            }) {
            QrTransferDecodeResult.Rejected(QrTransferRejection.MIXED_TRANSFER)
        } else null

    private fun validateDuplicateFrames(
        frames: Collection<QrTransferFrame>
    ): QrTransferDecodeResult.Rejected? =
        if (frames.map { it.frameIndex }.toSet().size != frames.size) {
            QrTransferDecodeResult.Rejected(QrTransferRejection.DUPLICATE_FRAME)
        } else null

    private fun validateFrameValidity(
        frames: Collection<QrTransferFrame>,
        frame: QrTransferFrame
    ): QrTransferDecodeResult.Rejected? =
        if (frames.any {
                it.frameIndex !in 0 until frame.totalFrames ||
                it.payloadChunk.length > MAX_CHUNK_HEX_CHARS ||
                !it.payloadChunk.isHex()
            }) {
            QrTransferDecodeResult.Rejected(QrTransferRejection.INVALID_METADATA)
        } else null

    private fun validateFrameOrdering(
        ordered: List<QrTransferFrame>,
        frame: QrTransferFrame
    ): QrTransferDecodeResult.Rejected? =
        if (ordered.map { it.frameIndex } != (0 until frame.totalFrames).toList()) {
            QrTransferDecodeResult.Rejected(QrTransferRejection.MISSING_FRAME)
        } else null

    private fun validatePayloadSizeAndHash(
        payload: ByteArray,
        frame: QrTransferFrame
    ): QrTransferDecodeResult.Rejected? =
        if (payload.isEmpty() || payload.size > MAX_PAYLOAD_BYTES || RescueCryptography.sha256Hex(payload) != frame.payloadHash) {
            QrTransferDecodeResult.Rejected(QrTransferRejection.HASH_MISMATCH)
        } else null
