package com.example.relay.pcgateway

import com.example.relay.gateway.protocol.GatewayMessage
import com.example.relay.gateway.protocol.SyncMessagesRequest
import com.example.relay.gateway.protocol.SyncMessagesResponse
import com.example.relay.gateway.protocol.UNVERIFIED_GATEWAY_RECEIPT_TYPE
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnonymousIngressTest {
    private fun message(id: String = "anonymous-1") = GatewayMessage(
        messageId = id,
        messageType = "SAFETY",
        recordType = "REPORT",
        priority = "HIGH",
        status = "ACTIVE",
        createdAt = 1_000,
        expiresAt = 86_401_000,
        lifetimeMs = 86_400_000,
        accumulatedAgeMs = 0,
        hopCount = 1,
        hopLimit = 8,
        originDeviceId = "anonymous-device",
        payload = JsonPrimitive("safe"),
        receivedAt = 1_000,
    )

    @Test fun `public ingress stores without token but returns only unverified receipt and admin remains protected`() = testApplication {
        val config = GatewayConfig(
            dbPath = Files.createTempFile("relay-anonymous", ".db").toString(),
            gatewayId = "gateway-test",
            adminKey = "admin-secret",
        )
        val store = GatewayStore(config)
        application { gatewayModule(config, store) }
        try {
            val response = client.post("/api/public/sync/messages") {
                contentType(ContentType.Application.Json)
                setBody(GatewayJson.encodeToString(SyncMessagesRequest(bridgeId = "unregistered", bridgeName = "phone", messages = listOf(message()))))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("unverified", response.headers["X-Relay-Receipt-Trust"])
            val body = GatewayJson.decodeFromString(SyncMessagesResponse.serializer(), response.bodyAsText())
            assertEquals(listOf("anonymous-1"), body.acceptedMessageIds)
            assertEquals(UNVERIFIED_GATEWAY_RECEIPT_TYPE, body.receipts.single().receiptType)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/messages").status)
        } finally {
            store.close()
        }
    }

    @Test fun `public ingress enforces per source request limit`() = testApplication {
        val config = GatewayConfig(
            dbPath = Files.createTempFile("relay-anonymous-limit", ".db").toString(),
            maxAnonymousRequestsPerMinute = 1,
        )
        val store = GatewayStore(config)
        application { gatewayModule(config, store) }
        val body = GatewayJson.encodeToString(SyncMessagesRequest(bridgeId = "unregistered", bridgeName = "phone", messages = emptyList()))
        try {
            val first = client.post("/api/public/sync/messages") { contentType(ContentType.Application.Json); setBody(body) }
            val second = client.post("/api/public/sync/messages") { contentType(ContentType.Application.Json); setBody(body) }
            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(HttpStatusCode.TooManyRequests, second.status)
        } finally {
            store.close()
        }
    }

    @Test fun `public ingress rejects status change from unregistered source`() = testApplication {
        val config = GatewayConfig(dbPath = Files.createTempFile("relay-anonymous-status", ".db").toString())
        val store = GatewayStore(config)
        application { gatewayModule(config, store) }
        val change = message("change-1").copy(recordType = "STATUS_CHANGE")
        try {
            val response = client.post("/api/public/sync/messages") {
                contentType(ContentType.Application.Json)
                setBody(GatewayJson.encodeToString(SyncMessagesRequest(bridgeId = "unregistered", bridgeName = "phone", messages = listOf(change))))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val decoded = GatewayJson.decodeFromString(SyncMessagesResponse.serializer(), response.bodyAsText())
            assertEquals("unregistered_status_change_not_allowed", decoded.rejected.single().reason)
            assertTrue(store.messages().isEmpty())
        } finally {
            store.close()
        }
    }

    @Test fun `limiter resets window and accounts for bytes and messages`() {
        var now = 1_000L
        val limiter = AnonymousIngressRateLimiter(2, 2, 10, windowMs = 1_000) { now }
        assertTrue(limiter.allow("source", 1, 5))
        assertFalse(limiter.allow("source", 2, 1))
        assertTrue(limiter.allow("other", 2, 10))
        now += 1_000
        assertTrue(limiter.allow("source", 2, 10))
    }

    @Test fun `LAN announcement contains discovery data but no admin secret`() {
        val config = GatewayConfig(gatewayId = "gateway-test", adminKey = "do-not-advertise", port = 9080)
        val text = GatewayLanBeacon(config).announcementBytes().decodeToString()
        val announcement = GatewayJson.decodeFromString(GatewayLanAnnouncement.serializer(), text)
        assertEquals("gateway-test", announcement.gatewayId)
        assertEquals(9080, announcement.apiPort)
        assertEquals("UNVERIFIED", announcement.receiptTrust)
        assertFalse(text.contains("do-not-advertise"))
    }
}
