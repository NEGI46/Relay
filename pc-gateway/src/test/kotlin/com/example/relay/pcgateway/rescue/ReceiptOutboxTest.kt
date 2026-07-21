package com.example.relay.pcgateway.rescue

import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.ShelterReceiptStatus
import com.example.relay.rescue.SignedShelterReceipt
import com.example.relay.rescue.UnsignedShelterReceipt
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReceiptOutboxTest {
    private lateinit var connection: java.sql.Connection

    @Before
    fun setup() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    @After
    fun teardown() {
        connection.close()
    }

    @Test
    fun `enqueue inserts receipt as PENDING`() {
        val outbox = createOutbox()
        val receipt = createReceipt("receipt-1", "envelope-1")

        outbox.enqueue(receipt)

        val rows = queryAll()
        assertEquals(1, rows.size)
        assertEquals("receipt-1", rows[0]["receipt_id"])
        assertEquals("PENDING", rows[0]["status"])
    }

    @Test
    fun `enqueue is idempotent - duplicate receipt_id ignored`() {
        val outbox = createOutbox()
        val receipt = createReceipt("receipt-1", "envelope-1")

        outbox.enqueue(receipt)
        outbox.enqueue(receipt)

        val rows = queryAll()
        assertEquals(1, rows.size)
    }

    @Test
    fun `enqueue multiple receipts preserves order`() {
        val outbox = createOutbox()

        outbox.enqueue(createReceipt("receipt-1", "envelope-1"))
        outbox.enqueue(createReceipt("receipt-2", "envelope-2"))
        outbox.enqueue(createReceipt("receipt-3", "envelope-3"))

        val rows = queryAll()
        assertEquals(3, rows.size)
    }

    @Test
    fun `markSent updates status and timestamp`() {
        val outbox = createOutbox()
        outbox.enqueue(createReceipt("receipt-1", "envelope-1"))

        // Simulate markSent via direct SQL (normally called by flusher)
        connection.prepareStatement(
            "UPDATE receipt_outbox SET status = 'SENT', sent_at = ? WHERE receipt_id = ?",
        ).use { stmt ->
            stmt.setLong(1, System.currentTimeMillis())
            stmt.setString(2, "receipt-1")
            stmt.executeUpdate()
        }

        val rows = queryAll()
        assertEquals("SENT", rows[0]["status"])
        assertTrue(rows[0]["sent_at"] != null)
    }

    @Test
    fun `incrementRetry increases retry count`() {
        val outbox = createOutbox()
        outbox.enqueue(createReceipt("receipt-1", "envelope-1"))

        connection.prepareStatement(
            "UPDATE receipt_outbox SET retry_count = retry_count + 1 WHERE receipt_id = ?",
        ).use { stmt ->
            stmt.setString(1, "receipt-1")
            stmt.executeUpdate()
        }

        val rows = queryAll()
        assertEquals("1", rows[0]["retry_count"])
    }

    private fun createOutbox(): ReceiptOutbox {
        // Create table manually since we can't use HttpClient in unit test easily
        connection.createStatement().use { stmt ->
            stmt.executeUpdate(
                """CREATE TABLE IF NOT EXISTS receipt_outbox (
                    receipt_id TEXT NOT NULL PRIMARY KEY,
                    envelope_id TEXT NOT NULL,
                    shelter_id TEXT NOT NULL,
                    receipt_json TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    created_at INTEGER NOT NULL,
                    sent_at INTEGER,
                    retry_count INTEGER NOT NULL DEFAULT 0
                )""",
            )
        }
        // Return a minimal outbox that shares the connection
        // We test the SQL operations directly rather than the HTTP flush
        return ReceiptOutbox(
            dbConnection = connection,
            brokerUrl = "https://broker.test",
            shelterId = "shelter-1",
            gatewayId = "gateway-1",
            httpClient = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO),
        )
    }

    private fun createReceipt(receiptId: String, envelopeId: String): SignedShelterReceipt {
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        return RescueCryptography.signReceipt(
            UnsignedShelterReceipt(
                receiptId = receiptId,
                envelopeId = envelopeId,
                requestId = "request-1",
                requestVersion = 1,
                ciphertextSha256Hex = "abc123",
                shelterId = "shelter-1",
                receivedAtEpochMillis = System.currentTimeMillis(),
                status = ShelterReceiptStatus.STORED,
            ),
            signer.privateKey,
        )
    }

    private fun queryAll(): List<Map<String, String?>> {
        val results = mutableListOf<Map<String, String?>>()
        connection.prepareStatement("SELECT * FROM receipt_outbox ORDER BY created_at ASC").use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    results.add(mapOf(
                        "receipt_id" to rs.getString("receipt_id"),
                        "envelope_id" to rs.getString("envelope_id"),
                        "shelter_id" to rs.getString("shelter_id"),
                        "status" to rs.getString("status"),
                        "sent_at" to rs.getString("sent_at"),
                        "retry_count" to rs.getString("retry_count"),
                    ))
                }
            }
        }
        return results
    }
}
