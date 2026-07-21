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
import org.junit.Assert.assertNull
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
        store.put(testEnvelope(envelopeId = "env-expired", requestId = "req-exp", expiresAt = now - 1000), "dk1", now - 2000)
        store.put(testEnvelope(envelopeId = "env-active", requestId = "req-act"), "dk1", now)

        val batch = store.pendingForShelter("fuchu-01", now, null, 50, "gw-1")
        assertEquals(1, batch.envelopes.size)
        assertEquals("env-active", batch.envelopes[0].envelopeId)
    }

    @Test
    fun `pendingForShelter composite cursor pagination does not skip same-timestamp records`() {
        val now = System.currentTimeMillis()
        // Both stored at the same timestamp
        store.put(testEnvelope(envelopeId = "env-aaa"), "dk1", now)
        store.put(testEnvelope(envelopeId = "env-bbb", requestId = "req-002"), "dk1", now)

        val batch1 = store.pendingForShelter("fuchu-01", now + 200, null, 1, "gw-1")
        assertEquals(1, batch1.envelopes.size)
        assertEquals("env-aaa", batch1.envelopes[0].envelopeId)
        assertNotNull(batch1.cursor)

        // Composite cursor ensures env-bbb is not skipped
        val batch2 = store.pendingForShelter("fuchu-01", now + 200, batch1.cursor, 1, "gw-1")
        assertEquals(1, batch2.envelopes.size)
        assertEquals("env-bbb", batch2.envelopes[0].envelopeId)
    }

    @Test
    fun `pendingForShelter returns null cursor when no results`() {
        val batch = store.pendingForShelter("fuchu-01", System.currentTimeMillis(), null, 50, "gw-1")
        assertTrue(batch.envelopes.isEmpty())
        assertNull(batch.cursor)
    }

    @Test
    fun `device registration returns capability token`() {
        val result = store.registerDevice("device-uuid-1", "pubkey-base64", System.currentTimeMillis())
        assertEquals("device-uuid-1", result.deviceKeyId)
        assertTrue(result.capabilityToken.length >= 64) // 2x UUID without dashes
    }

    @Test
    fun `device registration is idempotent`() {
        val result1 = store.registerDevice("device-uuid-1", "pubkey-base64", System.currentTimeMillis())
        val result2 = store.registerDevice("device-uuid-1", "pubkey-base64", System.currentTimeMillis() + 1000)
        assertEquals(result1.capabilityToken, result2.capabilityToken)
    }

    @Test
    fun `capability token resolves to device`() {
        val result = store.registerDevice("device-uuid-1", "pubkey-base64", System.currentTimeMillis())
        val resolved = store.deviceForCapabilityToken(result.capabilityToken)
        assertEquals("device-uuid-1", resolved)
    }

    @Test
    fun `invalid capability token returns null`() {
        assertNull(store.deviceForCapabilityToken("nonexistent-token"))
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

    @Test(expected = IllegalStateException::class)
    fun `saveReceipt throws for unknown envelope`() {
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
        store.saveReceipt("fuchu-01", receipt, System.currentTimeMillis())
    }

    @Test
    fun `receiptsForDevice uses capability token and monotonic seq`() {
        val now = System.currentTimeMillis()
        val reg = store.registerDevice("dk1", "pubkey1", now)
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

        // Use capability token (not deviceKeyId directly)
        val batch = store.receiptsForDevice(reg.capabilityToken, 0)
        assertEquals(1, batch.receipts.size)
        assertEquals("rcpt-001", batch.receipts[0].receipt.receiptId)
        assertTrue(batch.cursor > 0)

        // Invalid token returns empty
        val emptyBatch = store.receiptsForDevice("invalid-token", 0)
        assertTrue(emptyBatch.receipts.isEmpty())
    }

    @Test
    fun `purgeExpired removes expired envelopes`() {
        val now = System.currentTimeMillis()
        store.put(testEnvelope(envelopeId = "env-expired", requestId = "req-exp", expiresAt = now - 1000), "dk1", now - 2000)
        store.put(testEnvelope(envelopeId = "env-active", requestId = "req-act"), "dk1", now)

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
