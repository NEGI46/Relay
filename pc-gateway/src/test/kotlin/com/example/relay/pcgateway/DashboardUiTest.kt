package com.example.relay.pcgateway

import com.example.relay.gateway.protocol.GatewayMessage
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardUiTest {
    private fun message(id: String = "dash-1") = GatewayMessage(
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
        originDeviceId = "origin",
        payload = JsonPrimitive("safe"),
        receivedAt = 1_000,
    )

    @Test
    fun `dashboard and static console are available without admin key`() = testApplication {
        val config = GatewayConfig(
            dbPath = Files.createTempFile("relay-ui", ".db").toString(),
            adminKey = "admin-secret",
            gatewayId = "gw-ui",
        )
        GatewayStore(config).use { store ->
            store.ingestUnregistered(listOf(message()), 2_000)
            application { gatewayModule(config, store) }

            val dash = client.get("/api/dashboard")
            assertEquals(HttpStatusCode.OK, dash.status)
            val body = dash.bodyAsText()
            assertTrue(body.contains("\"gatewayId\":\"gw-ui\""))
            assertTrue(body.contains("\"unverifiedMessages\":1"))

            val index = client.get("/")
            assertEquals(HttpStatusCode.OK, index.status)
            assertTrue(index.bodyAsText().contains("Relay PC Gateway"))

            val css = client.get("/app.css")
            assertEquals(HttpStatusCode.OK, css.status)
            assertTrue(css.bodyAsText().contains("--bg"))
        }
    }

    @Test
    fun `message detail and csv require admin key`() = testApplication {
        val config = GatewayConfig(
            dbPath = Files.createTempFile("relay-ui2", ".db").toString(),
            adminKey = "admin-secret",
        )
        GatewayStore(config).use { store ->
            store.ingestUnregistered(listOf(message("detail-1")), 2_000)
            application { gatewayModule(config, store) }

            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/messages/detail-1").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/messages/export.csv").status)

            val detail = client.get("/api/messages/detail-1") {
                header("X-Admin-Key", "admin-secret")
            }
            assertEquals(HttpStatusCode.OK, detail.status)
            assertTrue(detail.bodyAsText().contains("detail-1"))

            val csv = client.get("/api/messages/export.csv") {
                header("X-Admin-Key", "admin-secret")
            }
            assertEquals(HttpStatusCode.OK, csv.status)
            assertTrue(csv.bodyAsText().contains("messageId"))
            assertTrue(csv.bodyAsText().contains("detail-1"))
        }
    }

    @Test
    fun `filtered messages endpoint accepts trust query`() = testApplication {
        val config = GatewayConfig(
            dbPath = Files.createTempFile("relay-ui3", ".db").toString(),
            adminKey = "admin-secret",
        )
        GatewayStore(config).use { store ->
            store.ingestUnregistered(listOf(message("u-1")), 2_000)
            application { gatewayModule(config, store) }
            val res = client.get("/api/messages?trust=UNVERIFIED") {
                header("X-Admin-Key", "admin-secret")
            }
            assertEquals(HttpStatusCode.OK, res.status)
            assertTrue(res.bodyAsText().contains("u-1"))
        }
    }
}
