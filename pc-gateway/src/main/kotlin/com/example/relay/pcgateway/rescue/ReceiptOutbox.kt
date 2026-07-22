package com.example.relay.pcgateway.rescue

import com.example.relay.rescue.SignedShelterReceipt
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.ContentType.Application.Json
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Wire model matching Broker's BrokerReceiptUpload. */
@Serializable
data class BrokerReceiptUpload(
    val receipt: SignedShelterReceipt,
    val gatewayId: String,
)

/**
 * Transactional Receipt Outbox for the PC Gateway.
 * Uses the SAME database connection as SqliteRescuePersistence so that
 * receipt enqueue is atomic with rescue state changes (same transaction).
 *
 * Ensures signed receipts (STORED/ACCEPTED/RESPONDING/COMPLETED/REJECTED) are
 * reliably delivered to the Broker via at-least-once semantics.
 * Broker deduplicates by receipt_id (idempotent).
 *
 * The flusher only marks SENT after receiving 2xx from Broker. A 422 means the Broker has no
 * matching envelope (for example, a receipt from a LAN-only delivery), so it is terminal and is
 * not retried forever. Other failures remain pending for at-least-once retry.
 */
class ReceiptOutbox(
    private val persistence: SqliteRescuePersistence,
    private val brokerUrl: String,
    private val shelterId: String,
    private val gatewayId: String,
    private val httpClient: HttpClient,
    private val gatewayApiKey: String? = null,
    private val flushIntervalMs: Long = 5_000L,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    init {
        require(flushIntervalMs > 0) { "flushIntervalMs must be positive" }
        createTable()
    }

    private fun createTable() {
        persistence.withConnection { connection ->
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
        }
    }

    /**
     * Enqueues a signed receipt for delivery to the Broker.
     * Called within the same transaction as the status change (via onReceiptIssued callback
     * inside persistence.transaction{}), ensuring atomicity.
     */
    fun enqueue(receipt: SignedShelterReceipt) {
        persistence.withConnection { connection ->
            connection.prepareStatement(
                "INSERT OR IGNORE INTO receipt_outbox (receipt_id, envelope_id, shelter_id, receipt_json, status, created_at) VALUES (?, ?, ?, ?, 'PENDING', ?)",
            ).use { stmt ->
                stmt.setString(1, receipt.receipt.receiptId)
                stmt.setString(2, receipt.receipt.envelopeId)
                stmt.setString(3, receipt.receipt.shelterId)
                stmt.setString(4, json.encodeToString(receipt))
                stmt.setLong(5, System.currentTimeMillis())
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Starts the background flusher. Call from a coroutine scope.
     * POSTs pending receipts to Broker; marks SENT only after 2xx.
     */
    suspend fun startFlusher(scope: CoroutineScope) {
        while (scope.isActive) {
            try {
                flushPending()
            } catch (e: Exception) {
                System.err.println("[ReceiptOutbox] flush failed: ${e.message}")
            }
            delay(flushIntervalMs)
        }
    }

    /**
     * Flushes all pending receipts. Exposed for testing.
     * Returns number of receipts successfully sent.
     */
    suspend fun flushPending(): Int {
        val pending = loadPending()
        if (pending.isEmpty()) return 0

        var sent = 0
        for ((receiptId, receiptJson) in pending) {
            val upload = BrokerReceiptUpload(
                receipt = json.decodeFromString<SignedShelterReceipt>(receiptJson),
                gatewayId = gatewayId,
            )
            try {
                val response: HttpResponse = httpClient.post("$brokerUrl/v1/gateways/$shelterId/receipts") {
                    contentType(Json)
                    setBody(json.encodeToString(upload))
                    headers {
                        gatewayApiKey?.let { append(HttpHeaders.Authorization, "Bearer $it") }
                        append("X-Gateway-Id", gatewayId)
                    }
                }
                if (response.status.isSuccess()) {
                    markSent(receiptId)
                    sent++
                } else if (response.status == HttpStatusCode.UnprocessableEntity) {
                    markRejected(receiptId)
                } else {
                    incrementRetry(receiptId)
                }
            } catch (e: Exception) {
                incrementRetry(receiptId)
            }
        }
        return sent
    }

    private fun loadPending(): List<Pair<String, String>> {
        return persistence.withConnection { connection ->
            val results = mutableListOf<Pair<String, String>>()
            connection.prepareStatement(
                "SELECT receipt_id, receipt_json FROM receipt_outbox WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT 20",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        results.add(rs.getString("receipt_id") to rs.getString("receipt_json"))
                    }
                }
            }
            results
        }
    }

    private fun markSent(receiptId: String) {
        persistence.withConnection { connection ->
            connection.prepareStatement(
                "UPDATE receipt_outbox SET status = 'SENT', sent_at = ? WHERE receipt_id = ?",
            ).use { stmt ->
                stmt.setLong(1, System.currentTimeMillis())
                stmt.setString(2, receiptId)
                stmt.executeUpdate()
            }
        }
    }

    private fun incrementRetry(receiptId: String) {
        persistence.withConnection { connection ->
            connection.prepareStatement(
                "UPDATE receipt_outbox SET retry_count = retry_count + 1 WHERE receipt_id = ?",
            ).use { stmt ->
                stmt.setString(1, receiptId)
                stmt.executeUpdate()
            }
        }
    }

    private fun markRejected(receiptId: String) {
        persistence.withConnection { connection ->
            connection.prepareStatement(
                "UPDATE receipt_outbox SET status = 'REJECTED' WHERE receipt_id = ?",
            ).use { stmt ->
                stmt.setString(1, receiptId)
                stmt.executeUpdate()
            }
        }
    }
}
