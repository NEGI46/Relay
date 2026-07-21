package com.example.relay.broker

import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.RescueUrgency
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BrokerRoutesTest {
    private lateinit var store: BrokerStore
    private lateinit var dbFile: File

    @Before
    fun setup() {
        dbFile = File.createTempFile("broker-routes-test", ".db")
        dbFile.deleteOnExit()
        store = BrokerStore(dbFile.absolutePath)
    }

    @After
    fun teardown() {
        store.close()
        dbFile.delete()
    }

    private fun uploadJson(
        envelopeId: String = "env-001",
        requestId: String = "req-001",
        requestVersion: Int = 1,
        shelterId: String = "fuchu-01",
        ciphertextHash: String = "a".repeat(64),
        expiresAt: Long = System.currentTimeMillis() + 3_600_000,
    ): String {
        val envelope = EncryptedRescueEnvelope(
            envelopeId = envelopeId,
            requestId = requestId,
            requestVersion = requestVersion,
            senderDeviceId = "device-001",
            destinationShelterId = shelterId,
            routingUrgency = RescueUrgency.IMMEDIATE,
            recipientKeyId = "key-001",
            createdAtEpochMillis = System.currentTimeMillis(),
            expiresAtEpochMillis = expiresAt,
            ciphertextSizeBytes = 256,
            wrappedContentKeyBase64 = "A".repeat(172),
            nonceBase64 = "B".repeat(24),
            ciphertextBase64 = "C".repeat(344),
            ciphertextSha256Hex = ciphertextHash,
        )
        val request = BrokerUploadRequest(envelope, "device-key-1", "sig".repeat(30))
        return brokerJson.encodeToString(BrokerUploadRequest.serializer(), request)
    }

    @Test
    fun `upload returns 201 for valid envelope`() = testApplication {
        application { brokerModule(store) }
        val response = client.post("/v1/rescue/upload") {
            contentType(ContentType.Application.Json)
            setBody(uploadJson())
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("BROKER_STORED"))
        assertTrue(body.contains("env-001"))
    }

    @Test
    fun `upload returns 200 for duplicate envelope`() = testApplication {
        application { brokerModule(store) }
        val body = uploadJson()
        client.post("/v1/rescue/upload") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val response = client.post("/v1/rescue/upload") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `upload returns 409 for collision`() = testApplication {
        application { brokerModule(store) }
        client.post("/v1/rescue/upload") {
            contentType(ContentType.Application.Json)
            setBody(uploadJson(ciphertextHash = "a".repeat(64)))
        }
        val response = client.post("/v1/rescue/upload") {
            contentType(ContentType.Application.Json)
            setBody(uploadJson(envelopeId = "env-002", ciphertextHash = "b".repeat(64)))
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("ciphertext_collision"))
    }

    @Test
    fun `upload returns 400 for expired envelope`() = testApplication {
        application { brokerModule(store) }
        val response = client.post("/v1/rescue/upload") {
            contentType(ContentType.Application.Json)
            setBody(uploadJson(expiresAt = System.currentTimeMillis() - 1000))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("envelope_expired"))
    }

    @Test
    fun `upload returns 400 for malformed body`() = testApplication {
        application { brokerModule(store) }
        val response = client.post("/v1/rescue/upload") {
            contentType(ContentType.Application.Json)
            setBody("{invalid json")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `pull returns envelopes for shelter`() = testApplication {
        application { brokerModule(store) }
        client.post("/v1/rescue/upload") {
            contentType(ContentType.Application.Json)
            setBody(uploadJson())
        }
        val response = client.get("/v1/gateways/fuchu-01/pull")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("env-001"))
    }

    @Test
    fun `pull returns empty for unknown shelter`() = testApplication {
        application { brokerModule(store) }
        val response = client.get("/v1/gateways/unknown-shelter/pull")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"envelopes\":[]"))
    }

    @Test
    fun `health returns status`() = testApplication {
        application { brokerModule(store) }
        val response = client.get("/v1/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"ok\""))
    }

    @Test
    fun `device receipts returns empty for unknown device`() = testApplication {
        application { brokerModule(store) }
        val response = client.get("/v1/devices/unknown-key/receipts")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"receipts\":[]"))
    }
}
