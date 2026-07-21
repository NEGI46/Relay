package com.example.relay.broker

import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.RescueUrgency
import com.example.relay.rescue.SignedShelterReceipt
import com.example.relay.rescue.UnsignedShelterReceipt
import com.example.relay.rescue.ShelterReceiptStatus
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BrokerStoreTest {
    private lateinit var store: BrokerStore
    private lateinit var dbFile: File

    @Before
    fun setup() {
        dbFile = File.createTempFile("broker-test", ".db")
        dbFile.deleteOnExit()
        store = BrokerStore(dbFile.absolutePath)
    }

    @After
    fun teardown() {
        store.close()
        dbFile.delete()
    }

    private fun testEnvelope(
        envelopeId: String = "env-001",
        requestId: String = "req-001",
        requestVersion: Int = 1,
        shelterId: String = "fuchu-01",
        ciphertextHash: String = "a".repeat(64),
        expiresAt: Long = System.currentTimeMillis() + 3_600_000,
    ) = EncryptedRescueEnvelope(
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

    @Test
    fun `put stores new envelope`() {
        val envelope = testEnvelope()
        val result = store.put(envelope, "device-key-1", System.currentTimeMillis())
        assertTrue(result is BrokerPutResult.Stored)
        val stored = result as BrokerPutResult.Stored
        assertEquals(envelope.envelopeId, stored.response.envelopeId)
        assertEquals("BROKER_STORED", stored.response.status)
    }

    @Test
    fun `put returns Duplicate for same envelope`() {
        val envelope = testEnvelope()
        val now = System.currentTimeMillis()
        store.put(envelope, "device-key-1", now)
        val result = store.put(envelope, "device-key-1", now + 1000)
        assertTrue(result is BrokerPutResult.Duplicate)
    }

    @Test
    fun `put returns Collision for same request key different hash`() {
        val envelope1 = testEnvelope(ciphertextHash = "a".repeat(64))
        val envelope2 = testEnvelope(envelopeId = "env-002", ciphertextHash = "b".repeat(64))
        val now = System.currentTimeMillis()
        store.put(envelope1, "device-key-1", now)
        val result = store.put(envelope2, "device-key-2", now + 1000)
        assertTrue(result is BrokerPutResult.Collision)
        assertEquals("env-001", (result as BrokerPutResult.Collision).existingEnvelopeId)
    }

    @Test
    fun `pendingForShelter returns envelopes for correct shelter`() {
        val now = System.currentTimeMillis()
        store.put(testEnvelope(envelopeId = "env-001", shelterId = "fuchu-01"), "dk1", now)
        store.put(testEnvelope(envelopeId = "env-002", requestId = "req-002", shelterId = "other-01"), "dk2", now)

        val batch = store.pendingForShelter("fuchu-01", now, null, 50, "gw-1")
        assertEquals(1, batch.envelopes.size)
        assertEquals("env-001", batch.envelopes[0].envelopeId)
    }

    @Test
    fun `pendingForShelter excludes expired envelopes`() {
        val now = System.currentTimeMillis()
        store.put(testEnvelope(envelopeId = "env-expired", expiresAt = now - 1000), "dk1", now - 2000)
        store.put(testEnvelope(envelopeId = "env-active"), "dk1", now)

        val batch = store.pendingForShelter("fuchu-01", now, null, 50, "gw-1")
        assertEquals(1, batch.envelopes.size)
        assertEquals("env-active", batch.envelopes[0].envelopeId)
    }

    @Test
    fun `pendingForShelter respects cursor pagination`() {
        val now = System.currentTimeMillis()
        store.put(testEnvelope(envelopeId = "env-001"), "dk1", now)
        store.put(testEnvelope(envelopeId = "env-002", requestId = "req-002"), "dk1", now + 100)

        val batch1 = store.pendingForShelter("fuchu-01", now + 200, null, 1, "gw-1")
        assertEquals(1, batch1.envelopes.size)
        assertNotNull(batch1.cursor)

        val batch2 = store.pendingForShelter("fuchu-01", now + 200, batch1.cursor!!.toLong(), 1, "gw-1")
        assertEquals(1, batch2.envelopes.size)
        assertEquals("env-002", batch2.envelopes[0].envelopeId)
    }

    @Test
    fun `saveReceipt stores receipt for existing envelope`() {
        val now = System.currentTimeMillis()
        store.put(testEnvelope(envelopeId = "env-001"), "dk1", now)

        val receipt = SignedShelterReceipt(
            receipt = UnsignedShelterReceipt(
                receiptId = "rcpt-001",
                envelopeId = "env-001",
                requestId = "req-001",
                requestVersion = 1,
                ciphertextSha256Hex = "a".repeat(64),
                shelterId = "fuchu-01",
                receivedAtEpochMillis = now,
                status = ShelterReceiptStatus.ACCEPTED,
            ),
            signerKeyId = "signer-001",
            signatureBase64 = "D".repeat(88),
        )
        assertTrue(store.saveReceipt("fuchu-01", receipt, now))
    }

    @Test
    fun `saveReceipt rejects receipt for unknown envelope`() {
        val receipt = SignedShelterReceipt(
            receipt = UnsignedShelterReceipt(
                receiptId = "rcpt-001",
                envelopeId = "nonexistent",
                requestId = "req-001",
                requestVersion = 1,
                ciphertextSha256Hex = "a".repeat(64),
                shelterId = "fuchu-01",
                receivedAtEpochMillis = System.currentTimeMillis(),
                status = ShelterReceiptStatus.ACCEPTED,
            ),
            signerKeyId = "signer-001",
            signatureBase64 = "D".repeat(88),
        )
        assertFalse(store.saveReceipt("fuchu-01", receipt, System.currentTimeMillis()))
    }

    @Test
    fun `receiptsForDevice returns only receipts for matching device key`() {
        val now = System.currentTimeMillis()
        store.put(testEnvelope(envelopeId = "env-001"), "dk1", now)
        store.put(testEnvelope(envelopeId = "env-002", requestId = "req-002"), "dk2", now)

        val receipt1 = SignedShelterReceipt(
            receipt = UnsignedShelterReceipt(
                receiptId = "rcpt-001", envelopeId = "env-001", requestId = "req-001",
                requestVersion = 1, ciphertextSha256Hex = "a".repeat(64),
                shelterId = "fuchu-01", receivedAtEpochMillis = now, status = ShelterReceiptStatus.ACCEPTED,
            ),
            signerKeyId = "signer-001", signatureBase64 = "D".repeat(88),
        )
        store.saveReceipt("fuchu-01", receipt1, now)

        val deviceReceipts = store.receiptsForDevice("dk1", 0)
        assertEquals(1, deviceReceipts.size)
        assertEquals("rcpt-001", deviceReceipts[0].receipt.receiptId)

        val otherReceipts = store.receiptsForDevice("dk2", 0)
        assertEquals(0, otherReceipts.size)
    }

    @Test
    fun `purgeExpired removes expired envelopes`() {
        val now = System.currentTimeMillis()
        store.put(testEnvelope(envelopeId = "env-expired", expiresAt = now - 1000), "dk1", now - 2000)
        store.put(testEnvelope(envelopeId = "env-active"), "dk1", now)

        val purged = store.purgeExpired(now)
        assertEquals(1, purged)
        assertEquals(1, store.countPendingEnvelopes(now))
    }

    @Test
    fun `rate limiter allows within limit and blocks over limit`() {
        val limiter = SlidingWindowRateLimiter(maxRequests = 3, windowMillis = 60_000)
        val now = System.currentTimeMillis()
        assertTrue(limiter.allow("key1", now))
        assertTrue(limiter.allow("key1", now + 100))
        assertTrue(limiter.allow("key1", now + 200))
        assertFalse(limiter.allow("key1", now + 300))
        // Different key is independent
        assertTrue(limiter.allow("key2", now + 300))
    }
}
