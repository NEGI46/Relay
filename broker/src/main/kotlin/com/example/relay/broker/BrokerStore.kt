package com.example.relay.broker

import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.SignedShelterReceipt
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val brokerJson = Json { ignoreUnknownKeys = false; encodeDefaults = true }

/** Result of an upload attempt. */
sealed interface BrokerPutResult {
    data class Stored(val response: BrokerUploadResponse) : BrokerPutResult
    data object Duplicate : BrokerPutResult
    data class Collision(val existingEnvelopeId: String) : BrokerPutResult
}

/**
 * SQLite-backed Broker store. Never decrypts envelopes.
 * Provides: dedup, collision isolation, TTL purge, shelter-queue pull, receipt relay.
 */
class BrokerStore(dbPath: String) : AutoCloseable {
    private val lock = Any()
    private val connection: Connection

    init {
        File(dbPath).parentFile?.mkdirs()
        connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        connection.createStatement().use { st ->
            st.execute("PRAGMA busy_timeout=5000")
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA foreign_keys=ON")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS broker_envelopes(
                  envelope_id TEXT PRIMARY KEY,
                  request_id TEXT NOT NULL,
                  request_version INTEGER NOT NULL,
                  sender_device_id TEXT NOT NULL,
                  shelter_id TEXT NOT NULL,
                  expires_at INTEGER NOT NULL,
                  ciphertext_hash TEXT NOT NULL,
                  envelope_json TEXT NOT NULL,
                  device_key_id TEXT NOT NULL,
                  stored_at INTEGER NOT NULL,
                  UNIQUE(request_id, request_version, ciphertext_hash)
                )
                """.trimIndent(),
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS broker_receipts(
                  receipt_id TEXT PRIMARY KEY,
                  envelope_id TEXT NOT NULL,
                  shelter_id TEXT NOT NULL,
                  receipt_json TEXT NOT NULL,
                  uploaded_at INTEGER NOT NULL,
                  FOREIGN KEY(envelope_id) REFERENCES broker_envelopes(envelope_id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS broker_ledger(
                  envelope_id TEXT PRIMARY KEY,
                  status TEXT NOT NULL DEFAULT 'BROKER_STORED',
                  pulled_at INTEGER,
                  pulled_by TEXT,
                  FOREIGN KEY(envelope_id) REFERENCES broker_envelopes(envelope_id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS broker_quarantine(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  request_id TEXT NOT NULL,
                  request_version INTEGER NOT NULL,
                  incoming_envelope_id TEXT NOT NULL,
                  incoming_ciphertext_hash TEXT NOT NULL,
                  existing_envelope_id TEXT NOT NULL,
                  existing_ciphertext_hash TEXT NOT NULL,
                  device_key_id TEXT NOT NULL,
                  quarantined_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            st.execute("CREATE INDEX IF NOT EXISTS idx_broker_envelopes_shelter ON broker_envelopes(shelter_id, expires_at)")
            st.execute("CREATE INDEX IF NOT EXISTS idx_broker_receipts_device ON broker_receipts(shelter_id, uploaded_at)")
        }
    }

    /**
     * Idempotent store. Returns Stored on new insert, Duplicate if same hash exists,
     * Collision if same (requestId, requestVersion) has a different hash.
     */
    fun put(
        envelope: EncryptedRescueEnvelope,
        deviceKeyId: String,
        now: Long,
    ): BrokerPutResult = synchronized(lock) {
        // Check for existing entry with same requestId+requestVersion
        connection.prepareStatement(
            "SELECT envelope_id, ciphertext_hash FROM broker_envelopes WHERE request_id=? AND request_version=?",
        ).use { ps ->
            ps.setString(1, envelope.requestId)
            ps.setInt(2, envelope.requestVersion)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    val existingHash = rs.getString(2)
                    val existingId = rs.getString(1)
                    return if (existingHash == envelope.ciphertextSha256Hex) {
                        // Exact duplicate — return existing broker receipt id
                        BrokerPutResult.Duplicate
                    } else {
                        // Collision: same request key, different ciphertext
                        quarantine(envelope, existingId, existingHash, deviceKeyId, now)
                        BrokerPutResult.Collision(existingId)
                    }
                }
            }
        }
        val brokerReceiptId = UUID.randomUUID().toString()
        connection.prepareStatement(
            """INSERT OR IGNORE INTO broker_envelopes
               (envelope_id, request_id, request_version, sender_device_id, shelter_id, expires_at, ciphertext_hash, envelope_json, device_key_id, stored_at)
               VALUES(?,?,?,?,?,?,?,?,?,?)""",
        ).use { ps ->
            ps.setString(1, envelope.envelopeId)
            ps.setString(2, envelope.requestId)
            ps.setInt(3, envelope.requestVersion)
            ps.setString(4, envelope.senderDeviceId)
            ps.setString(5, envelope.destinationShelterId)
            ps.setLong(6, envelope.expiresAtEpochMillis)
            ps.setString(7, envelope.ciphertextSha256Hex)
            ps.setString(8, brokerJson.encodeToString(envelope))
            ps.setString(9, deviceKeyId)
            ps.setLong(10, now)
            ps.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT OR IGNORE INTO broker_ledger(envelope_id, status) VALUES(?, 'BROKER_STORED')",
        ).use { ps ->
            ps.setString(1, envelope.envelopeId)
            ps.executeUpdate()
        }
        BrokerPutResult.Stored(
            BrokerUploadResponse(
                brokerReceiptId = brokerReceiptId,
                envelopeId = envelope.envelopeId,
                storedAtEpochMillis = now,
            ),
        )
    }

    /** Look up an existing envelope's broker receipt id for duplicate responses. */
    fun existingBrokerReceiptId(envelopeId: String): String? = synchronized(lock) {
        connection.prepareStatement(
            "SELECT envelope_id FROM broker_envelopes WHERE envelope_id=?",
        ).use { ps ->
            ps.setString(1, envelopeId)
            ps.executeQuery().use { rs -> if (rs.next()) envelopeId else null }
        }
    }

    /**
     * Pull pending envelopes for a shelter. Cursor is the last seen stored_at timestamp.
     * Marks pulled envelopes in the ledger.
     */
    fun pendingForShelter(
        shelterId: String,
        now: Long,
        cursor: Long?,
        limit: Int,
        gatewayId: String,
    ): BrokerEnvelopeBatch = synchronized(lock) {
        val envelopes = mutableListOf<EncryptedRescueEnvelope>()
        var lastStoredAt = cursor ?: 0L
        connection.prepareStatement(
            """SELECT envelope_json, stored_at FROM broker_envelopes
               WHERE shelter_id=? AND expires_at>? AND stored_at>?
               ORDER BY stored_at ASC LIMIT ?""",
        ).use { ps ->
            ps.setString(1, shelterId)
            ps.setLong(2, now)
            ps.setLong(3, lastStoredAt)
            ps.setInt(4, limit)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    envelopes.add(brokerJson.decodeFromString(rs.getString(1)))
                    lastStoredAt = rs.getLong(2)
                }
            }
        }
        // Mark as pulled in ledger
        if (envelopes.isNotEmpty()) {
            connection.prepareStatement(
                "UPDATE broker_ledger SET status='GATEWAY_PULLED', pulled_at=?, pulled_by=? WHERE envelope_id=?",
            ).use { ps ->
                envelopes.forEach { envelope ->
                    ps.setLong(1, now)
                    ps.setString(2, gatewayId)
                    ps.setString(3, envelope.envelopeId)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
        val nextCursor = if (envelopes.size == limit) lastStoredAt.toString() else null
        BrokerEnvelopeBatch(envelopes, nextCursor)
    }

    /** Store a signed receipt from the Gateway for later device pickup. Idempotent by receipt_id. */
    fun saveReceipt(shelterId: String, receipt: SignedShelterReceipt, now: Long): Boolean = synchronized(lock) {
        // Verify the receipt's envelope exists and belongs to this shelter
        connection.prepareStatement(
            "SELECT envelope_id FROM broker_envelopes WHERE envelope_id=? AND shelter_id=?",
        ).use { ps ->
            ps.setString(1, receipt.receipt.envelopeId)
            ps.setString(2, shelterId)
            ps.executeQuery().use { rs -> if (!rs.next()) return false }
        }
        connection.prepareStatement(
            "INSERT OR IGNORE INTO broker_receipts(receipt_id, envelope_id, shelter_id, receipt_json, uploaded_at) VALUES(?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, receipt.receipt.receiptId)
            ps.setString(2, receipt.receipt.envelopeId)
            ps.setString(3, shelterId)
            ps.setString(4, brokerJson.encodeToString(receipt))
            ps.setLong(5, now)
            ps.executeUpdate() == 1
        }
    }

    /**
     * Retrieve receipts for a device. The deviceKeyId acts as a capability token:
     * only receipts for envelopes uploaded by that device key are returned.
     */
    fun receiptsForDevice(deviceKeyId: String, since: Long): List<SignedShelterReceipt> = synchronized(lock) {
        connection.prepareStatement(
            """SELECT r.receipt_json FROM broker_receipts r
               JOIN broker_envelopes e ON e.envelope_id = r.envelope_id
               WHERE e.device_key_id=? AND r.uploaded_at>?
               ORDER BY r.uploaded_at ASC LIMIT 100""",
        ).use { ps ->
            ps.setString(1, deviceKeyId)
            ps.setLong(2, since)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(brokerJson.decodeFromString(rs.getString(1))) }
            }
        }
    }

    /** Purge expired envelopes and their cascading receipts/ledger entries. */
    fun purgeExpired(now: Long): Int = synchronized(lock) {
        connection.prepareStatement("DELETE FROM broker_envelopes WHERE expires_at <= ?").use { ps ->
            ps.setLong(1, now)
            ps.executeUpdate()
        }
    }

    fun countPendingEnvelopes(now: Long): Int = synchronized(lock) {
        connection.prepareStatement("SELECT COUNT(*) FROM broker_envelopes WHERE expires_at > ?").use { ps ->
            ps.setLong(1, now)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    fun countPendingReceipts(): Int = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM broker_receipts").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    private fun quarantine(
        incoming: EncryptedRescueEnvelope,
        existingEnvelopeId: String,
        existingHash: String,
        deviceKeyId: String,
        now: Long,
    ) {
        connection.prepareStatement(
            """INSERT INTO broker_quarantine
               (request_id, request_version, incoming_envelope_id, incoming_ciphertext_hash,
                existing_envelope_id, existing_ciphertext_hash, device_key_id, quarantined_at)
               VALUES(?,?,?,?,?,?,?,?)""",
        ).use { ps ->
            ps.setString(1, incoming.requestId)
            ps.setInt(2, incoming.requestVersion)
            ps.setString(3, incoming.envelopeId)
            ps.setString(4, incoming.ciphertextSha256Hex)
            ps.setString(5, existingEnvelopeId)
            ps.setString(6, existingHash)
            ps.setString(7, deviceKeyId)
            ps.setLong(8, now)
            ps.executeUpdate()
        }
    }

    override fun close() = synchronized(lock) {
        if (!connection.isClosed) connection.close()
    }
}

/**
 * In-memory sliding-window rate limiter. Per-key (deviceKeyId or gatewayId).
 * Returns true if the request is allowed.
 */
class SlidingWindowRateLimiter(
    private val maxRequests: Int = 60,
    private val windowMillis: Long = 60_000,
) {
    private val windows = ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>>()

    fun allow(key: String, now: Long = System.currentTimeMillis()): Boolean {
        val deque = windows.computeIfAbsent(key) { ConcurrentLinkedDeque() }
        // Evict expired entries
        while (deque.peekFirst()?.let { it < now - windowMillis } == true) {
            deque.pollFirst()
        }
        if (deque.size >= maxRequests) return false
        deque.addLast(now)
        return true
    }
}
