package com.example.relay.broker

import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.RescueUrgency
import com.example.relay.rescue.authenticatedHeaderBytes
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BrokerRoutesTest {
    private lateinit var store: BrokerStore
    private lateinit var dbFile: File
    private lateinit var deviceKeyPair: java.security.KeyPair
    private val deviceKeyId = "test-device-uuid"

    @Before
    fun setup() {
        dbFile = File.createTempFile("broker-routes-test", ".db")
        dbFile.deleteOnExit()
        store = BrokerStore(dbFile.absolutePath)
        // Generate a real EC P-256 key pair for signing
        deviceKeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
    }

    @After
    fun teardown() {
        store.close()
        dbFile.delete()
    }

    private fun registerDevice() {
        val publicKeyBase64 = Base64.getEncoder().encodeToString(deviceKeyPair.public.encoded)
        store.registerDevice(deviceKeyId, publicKeyBase64, System.currentTimeMillis())
    }

    private fun signEnvelope(envelope: EncryptedRescueEnvelope): String {
        val dataToSign = envelope.authenticatedHeaderBytes() + envelope.ciphertextSha256Hex.encodeToByteArray()
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(deviceKeyPair.private)
        sig.update(dataToSign)
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    private fun testEnvelope(
        envelopeId: String = "env-001",
        requestId: String = "req-001",
        requestVersion: Int = 1,
        shelterId: String = "fuchu-01",
        ciphertextHash: String = "a".repeat(64),
        createdAt: Long = System.currentTimeMillis(),
        expiresAt: Long = System.currentTimeMillis() + 3_600_000,
    ) = EncryptedRescueEnvelope(
        envelopeId = envelopeId,
        requestId = requestId,
        requestVersion = requestVersion,
        senderDeviceId = "device-001",
        destinationShelterId = shelterId,
        routingUrgency = RescueUrgency.IMMEDIATE,
        recipientKeyId = "key-001",
        createdAtEpochMillis = createdAt,
        expiresAtEpochMillis = expiresAt,
        ciphertextSizeBytes = 256,
        wrappedContentKeyBase64 = "A".repeat(172),
        nonceBase64 = "B".repeat(24),
        ciphertextBase64 = "C".repeat(344),
        ciphertextSha256Hex = ciphertextHash,
    )

    private fun uploadJson(envelope: EncryptedRescueEnvelope): String {
        val request = BrokerUploadRequest(envelope, deviceKeyId, signEnvelope(envelope))
        return brokerJson.encodeToString(BrokerUploadRequest.serializer(), request)
    }

    @Test
    fun `upload returns 201 for valid envelope`() = testApplication {
        application { brokerModule(store) }
        registerDevice()
        val response = client.post("/v1/rescue/upload") {
            contentType(ContentType.Application.Json)
            setBody(uploadJson(testEnvelope()))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("BROKER_STORED"))
        assertTrue(body.contains("env-001"))
    }

    @Test
    fun `upload returns 401 for unregistered device`() = testApplication {
        application { brokerModule(store) }
        // Do NOT register device
        val response = client.post("/v1/rescue/upload") {
            contentType(ContentType.Application.Json)
            setBody(uploadJson(testEnvelope()))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("device_not_registered"))
    }

    @Test
    fun `upload returns 200 for duplicate envelope`() = testApplication {
        application { brokerModule(store) }
        registerDevice()
        val body = uploadJson(testEnvelope())
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
        registerDevice()
        client.post("/v1/rescue/upload") {
            contentType(ContentType.Application.Json)
            setBody(uploadJson(testEnvelope(ciphertextHash = "a".repeat(64))))
        }
        val response = client.post("/v1/rescue/upload") {
            contentType(ContentType.Application.Json)
            setBody(uploadJson(testEnvelope(envelopeId = "env-002", ciphertextHash = "b".repeat(64))))
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("ciphertext_collision"))
    }

    @Test
    fun `upload returns 400 for expired envelope`() = testApplication {
        application { brokerModule(store) }
        registerDevice()
        // Both createdAt and expiresAt in the past, but expiresAt > createdAt (valid lifetime)
        val pastCreated = System.currentTimeMillis() - 7_200_000 // 2 hours ago
        val pastExpired = System.currentTimeMillis() - 1_000 // 1 second ago
        val response = client.post("/v1/rescue/upload") {
            contentType(ContentType.Application.Json)
            setBody(uploadJson(testEnvelope(createdAt = pastCreated, expiresAt = pastExpired)))
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
        application { brokerModule(store, gatewayApiKey = null) } // no auth in test
        registerDevice()
        client.post("/v1/rescue/upload") {
            contentType(ContentType.Application.Json)
            setBody(uploadJson(testEnvelope()))
        }
        val response = client.get("/v1/gateways/fuchu-01/pull")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("env-001"))
    }

    @Test
    fun `pull returns empty for unknown shelter`() = testApplication {
        application { brokerModule(store, gatewayApiKey = null) }
        val response = client.get("/v1/gateways/unknown-shelter/pull")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"envelopes\":[]"))
    }

    @Test
    fun `pull requires auth when api key is set`() = testApplication {
        application { brokerModule(store, gatewayApiKey = "secret-key") }
        val response = client.get("/v1/gateways/fuchu-01/pull")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `health returns status`() = testApplication {
        application { brokerModule(store) }
        val response = client.get("/v1/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"ok\""))
    }

    @Test
    fun `receipts returns empty for invalid token`() = testApplication {
        application { brokerModule(store) }
        val response = client.get("/v1/receipts?token=invalid-token&sinceSeq=0")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"receipts\":[]"))
    }

    @Test
    fun `device registration returns capability token`() = testApplication {
        application { brokerModule(store) }
        val publicKeyBase64 = Base64.getEncoder().encodeToString(deviceKeyPair.public.encoded)
        val registerBody = brokerJson.encodeToString(
            BrokerDeviceRegisterRequest.serializer(),
            BrokerDeviceRegisterRequest(deviceKeyId, publicKeyBase64),
        )
        val response = client.post("/v1/devices/register") {
            contentType(ContentType.Application.Json)
            setBody(registerBody)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("capabilityToken"))
        assertTrue(body.contains(deviceKeyId))
    }
}
