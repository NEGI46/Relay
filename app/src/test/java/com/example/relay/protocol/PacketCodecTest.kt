package com.example.relay.protocol

import com.example.relay.NOW
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.MutableClock
import com.example.relay.message
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketCodecTest {
    private val codec = PacketCodec(MessagePolicy(MutableClock(NOW)))

    @Test
    fun `valid packet round trips`() {
        val decoded = codec.decode(codec.encode("device-A", NOW, MessageDataBody(message())))
        assertTrue(decoded is DecodeResult.Success)
        assertEquals("message-1", ((decoded as DecodeResult.Success).packet.body as MessageDataBody).message.messageId)
    }

    @Test
    fun `malformed packet is rejected`() {
        assertEquals(DecodeError.MALFORMED_JSON, (codec.decode("{".encodeToByteArray()) as DecodeResult.Failure).error)
    }

    @Test
    fun `oversized packet is rejected before parsing`() {
        val result = codec.decode(ByteArray(64 * 1024 + 1)) as DecodeResult.Failure
        assertEquals(DecodeError.PAYLOAD_TOO_LARGE, result.error)
    }

    @Test
    fun `unknown protocol version and packet type are safe failures`() {
        fun envelope(version: Int, type: String): ByteArray {
            val value = WireEnvelope(version, type, "packet", "device-A", NOW, buildJsonObject { put("x", 1) })
            return Json.encodeToString(WireEnvelope.serializer(), value).encodeToByteArray()
        }
        assertEquals(
            DecodeError.UNKNOWN_PROTOCOL_VERSION,
            (codec.decode(envelope(99, PacketTypes.HELLO)) as DecodeResult.Failure).error,
        )
        assertEquals(
            DecodeError.UNKNOWN_PACKET_TYPE,
            (codec.decode(envelope(1, "FUTURE_PACKET")) as DecodeResult.Failure).error,
        )
    }

    @Test
    fun `invalid message body and oversized manifest are rejected`() {
        val invalid = message(hopCount = -1)
        assertEquals(
            DecodeError.INVALID_BODY,
            (codec.decode(codec.encode("device-A", NOW, MessageDataBody(invalid))) as DecodeResult.Failure).error,
        )
        val entry = ManifestEntry("id", invalid.messageType, invalid.priority, NOW, NOW + 1, 0, 8, true)
        val manifestCodec = PacketCodec(
            MessagePolicy(MutableClock(NOW)),
            ProtocolLimits(maxPacketBytes = 256 * 1024),
        )
        assertEquals(
            DecodeError.INVALID_BODY,
            (manifestCodec.decode(manifestCodec.encode("device-A", NOW, ManifestBody(List(513) { entry.copy(messageId = "id-$it") }))) as DecodeResult.Failure).error,
        )
    }
}
