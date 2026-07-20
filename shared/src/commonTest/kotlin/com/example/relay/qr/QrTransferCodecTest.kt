package com.example.relay.qr

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class QrTransferCodecTest {
    private val payload = ByteArray(300) { ((it % 251) + 1).toByte() }

    companion object {
        private const val TRANSFER_ID = "transfer-1"
    }

    @Test
    fun roundTripAcceptsShuffledFrames() {
        val frames = QrTransferCodec.encode(TRANSFER_ID, payload, 10_000, maxChunkBytes = 128)
        val accepted = assertIs<QrTransferDecodeResult.Accepted>(QrTransferCodec.assemble(frames.shuffled(), 1_000))
        assertEquals(TRANSFER_ID, accepted.transferId)
        assertContentEquals(payload, accepted.payload)
    }
    fun missingFrameIsRejected() {
        val frames = QrTransferCodec.encode("transfer-1", payload, 10_000, maxChunkBytes = 128)
        assertEquals(QrTransferRejection.MISSING_FRAME, (QrTransferCodec.assemble(frames.drop(1), 1_000) as QrTransferDecodeResult.Rejected).reason)
    }

    @Test
    fun duplicateFrameIsRejected() {
        val frames = QrTransferCodec.encode("transfer-1", payload + payload, 10_000, maxChunkBytes = 128)
        val duplicate = frames.drop(1) + frames[1]
        assertEquals(QrTransferRejection.DUPLICATE_FRAME, (QrTransferCodec.assemble(duplicate, 1_000) as QrTransferDecodeResult.Rejected).reason)
    }

    @Test
    fun hashMismatchIsRejected() {
        val frames = QrTransferCodec.encode("transfer-1", payload, 10_000, maxChunkBytes = 128)
        val tampered = frames.toMutableList().also { it[0] = it[0].copy(payloadChunk = "00" + it[0].payloadChunk.drop(2)) }
        assertEquals(QrTransferRejection.HASH_MISMATCH, (QrTransferCodec.assemble(tampered, 1_000) as QrTransferDecodeResult.Rejected).reason)
    }
}
