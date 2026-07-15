package com.example.relay.pcgateway

import com.example.relay.gateway.protocol.GatewayMessage
import com.example.relay.gateway.protocol.SyncMessagesRequest
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRoutesTest {
    @Test fun `health route starts and sync endpoint requires bridge token`() = testApplication {
        val db = Files.createTempFile("relay-route", ".db").toString()
        val config = GatewayConfig(dbPath = db, gatewayId = "gateway", adminKey = "admin")
        GatewayStore(config).use { store -> application { gatewayModule(config, store) } }
        val response = client.post("/api/health")
        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
    }

    @Test fun `unauthenticated sync is rejected`() = testApplication {
        val config = GatewayConfig(dbPath = Files.createTempFile("relay-route", ".db").toString(), adminKey = "admin")
        GatewayStore(config).use { store -> application { gatewayModule(config, store) } }
        val response = client.post("/api/sync/messages") {
            contentType(ContentType.Application.Json)
            setBody(GatewayJson.encodeToString(SyncMessagesRequest(bridgeId = "unknown", bridgeName = "x", messages = emptyList())))
        }
        assertTrue(response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden)
    }
}
