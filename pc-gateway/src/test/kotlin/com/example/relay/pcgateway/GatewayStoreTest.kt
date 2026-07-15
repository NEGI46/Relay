package com.example.relay.pcgateway

import com.example.relay.gateway.protocol.GatewayMessage
import com.example.relay.gateway.protocol.UNVERIFIED_GATEWAY_RECEIPT_TYPE
import com.example.relay.gateway.protocol.VERIFIED_GATEWAY_RECEIPT_TYPE
import java.nio.file.Files
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayStoreTest {
    private fun store(): GatewayStore = GatewayStore(GatewayConfig(dbPath = Files.createTempFile("relay-gateway", ".db").toString(), gatewayId = "gateway-test"))

    private fun message(id: String = "m-1", age: Long = 0) = GatewayMessage(
        messageId = id, messageType = "SAFETY", recordType = "REPORT", priority = "HIGH", status = "RECEIVED",
        createdAt = 1_000, expiresAt = 86_401_000, lifetimeMs = 86_400_000, accumulatedAgeMs = age,
        hopCount = 1, hopLimit = 8, originDeviceId = "origin", payload = JsonPrimitive("safe"), receivedAt = 1_000,
    )

    @Test fun `saving creates gateway receipt and duplicate is idempotent`() {
        store().use { db ->
            val code = db.createPairingCode(1_000)
            assertEquals(true, db.requestPair(code, "bridge", "Bridge", 1_001))
            assertNotNull(db.approvePair("bridge", code, 1_002))
            val result = db.ingest("bridge", listOf(message()), now = 2_000)
            assertEquals("STORED", result.single().disposition)
            assertEquals(1, db.receipts().size)
            val duplicate = db.ingest("bridge", listOf(message()), now = 3_000)
            assertEquals("DUPLICATE", duplicate.single().disposition)
            assertEquals(1, db.receipts().size)
        }
    }

    @Test fun `invalid age is rejected before database save`() {
        store().use { db ->
            val code = db.createPairingCode(1_000)
            db.requestPair(code, "bridge", "Bridge", 1_001)
            db.approvePair("bridge", code, 1_002)
            val result = db.ingest("bridge", listOf(message(age = 86_400_000)), now = 2_000)
            assertEquals("REJECTED", result.single().disposition)
        }
    }

    @Test fun `unregistered save creates only unverified receipt until paired sync confirms it`() {
        store().use { db ->
            val anonymous = db.ingestUnregistered(listOf(message()), now = 2_000).single()
            assertEquals("STORED", anonymous.disposition)
            assertEquals(UNVERIFIED_GATEWAY_RECEIPT_TYPE, anonymous.receipt?.receiptType)
            assertEquals(listOf(UNVERIFIED_GATEWAY_RECEIPT_TYPE), db.receipts().map { it.receiptType })
            assertEquals("UNVERIFIED", db.messages().single().ingressTrust)

            val code = db.createPairingCode(3_000)
            db.requestPair(code, "bridge", "Bridge", 3_001)
            db.approvePair("bridge", code, 3_002)
            val paired = db.ingest("bridge", listOf(message()), now = 4_000).single()
            assertEquals("DUPLICATE", paired.disposition)
            assertEquals(VERIFIED_GATEWAY_RECEIPT_TYPE, paired.receipt?.receiptType)
            assertEquals(setOf(UNVERIFIED_GATEWAY_RECEIPT_TYPE, VERIFIED_GATEWAY_RECEIPT_TYPE), db.receipts().map { it.receiptType }.toSet())
            assertEquals("VERIFIED", db.messages().single().ingressTrust)
        }
    }

    @Test fun `expired pairing code cannot be used`() {
        store().use { db ->
            val code = db.createPairingCode(1_000)
            assertEquals(false, db.requestPair(code, "bridge", "Bridge", 1_000 + 5 * 60_001))
            assertNull(db.approvePair("bridge", code, 1_000 + 5 * 60_001))
        }
    }

    @Test fun `STATUS_CHANGE updates the target report status`() {
        store().use { db ->
            val code = db.createPairingCode(1_000); db.requestPair(code, "bridge", "Bridge", 1_001); db.approvePair("bridge", code, 1_002)
            db.ingest("bridge", listOf(message()), 2_000)
            val change = message("change-1").copy(
                recordType = "STATUS_CHANGE",
                priority = "CRITICAL",
                payload = buildJsonObject { put("eventId", "event-1"); put("targetMessageId", "m-1"); put("newStatus", "RESOLVED"); put("reason", "safe"); put("createdAt", 2_000); put("createdBy", "admin") },
            )
            assertEquals("STORED", db.ingest("bridge", listOf(change), 2_100).single().disposition)
            assertEquals("RESOLVED", db.messages().first { it.messageId == "m-1" }.status)
        }
    }

    @Test fun `reject pair revokes token and prevents authentication`() {
        store().use { db ->
            val code = db.createPairingCode(1_000)
            db.requestPair(code, "bridge-a", "Bridge A", 1_001)
            val token = db.approvePair("bridge-a", code, 1_002)!!
            assertEquals(true, db.authenticate("bridge-a", token))
            assertEquals(true, db.rejectPair("bridge-a", code = null, now = 1_003))
            assertEquals(false, db.authenticate("bridge-a", token))
        }
    }

    @Test fun `receipts for bridge are scoped to that bridge submissions`() {
        store().use { db ->
            val codeA = db.createPairingCode(1_000)
            db.requestPair(codeA, "bridge-a", "A", 1_001)
            db.approvePair("bridge-a", codeA, 1_002)
            val codeB = db.createPairingCode(1_010)
            db.requestPair(codeB, "bridge-b", "B", 1_011)
            db.approvePair("bridge-b", codeB, 1_012)
            db.ingest("bridge-a", listOf(message("from-a")), 2_000)
            db.ingest("bridge-b", listOf(message("from-b")), 2_100)
            assertEquals(listOf("from-a"), db.receiptsForBridge("bridge-a").map { it.messageId })
            assertEquals(listOf("from-b"), db.receiptsForBridge("bridge-b").map { it.messageId })
            assertEquals(2, db.receipts().size)
        }
    }

    @Test fun `unverified public messages show unverified receipt flag`() {
        store().use { db ->
            db.ingestUnregistered(listOf(message()), 2_000)
            val row = db.messages().single()
            assertEquals(true, row.gatewayReceivedUnverified)
            assertEquals(false, row.gatewayReceived)
            assertEquals("UNVERIFIED", row.ingressTrust)
            assertNull(row.sourceBridgeId)
        }
    }
}
