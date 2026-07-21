package com.example.relay.broker

import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.SignedShelterReceipt
import com.example.relay.rescue.authenticatedHeaderBytes
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
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

/** Result of device registration. */
data class DeviceRegistrationResult(
    val deviceKeyId: String,
    val capabilityToken: String,
)

/** Returned exactly once by the local issuance command. The raw token is never persisted. */
data class IssuedGatewayCredential(
    val credentialId: String,
    val gatewayId: String,
    val shelterId: String,
    val expiresAtEpochMillis: Long,
    val token: String,
)

/** Authenticated scope used by pull and receipt endpoints. */
data class BrokerGatewayPrincipal(
    val credentialId: String,
    val gatewayId: String,
    val shelterId: String,
    val expiresAtEpochMillis: Long,
)

/**
 * SQLite-backed Broker store. Never decrypts envelopes.
 * Provides: dedup, collision isolation, TTL purge, shelter-queue pull, receipt relay.
 * Composite cursor (stored_at, envelope_id) prevents skip/dup on same-timestamp records.
 * Receipts use a monotonic seq for reliable device polling.
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
                  seq INTEGER PRIMARY KEY AUTOINCREMENT,
                  receipt_id TEXT NOT NULL UNIQUE,
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
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS broker_devices(
                  device_key_id TEXT PRIMARY KEY,
                  public_key_base64 TEXT NOT NULL,
                  capability_token TEXT NOT NULL UNIQUE,
                  registered_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS broker_gateway_credentials(
                  credential_id TEXT PRIMARY KEY,
                  token_hash TEXT NOT NULL UNIQUE,
                  gateway_id TEXT NOT NULL,
                  shelter_id TEXT NOT NULL,
                  issued_at INTEGER NOT NULL,
                  expires_at INTEGER NOT NULL,
                  revoked_at INTEGER
                )
                """.trimIndent(),
            )
            st.execute("CREATE INDEX IF NOT EXISTS idx_broker_envelopes_shelter ON broker_envelopes(shelter_id, expires_at)")
            st.execute("CREATE INDEX IF NOT EXISTS idx_broker_envelopes_cursor ON broker_envelopes(shelter_id, stored_at, envelope_id)")
            st.execute("CREATE INDEX IF NOT EXISTS idx_broker_receipts_device ON broker_receipts(shelter_id, uploaded_at)")
            st.execute("CREATE INDEX IF NOT EXISTS idx_broker_receipts_seq ON broker_receipts(seq)")
            st.execute("CREATE INDEX IF NOT EXISTS idx_broker_gateway_credential_scope ON broker_gateway_credentials(gateway_id, shelter_id, expires_at)")
        }
    }

    /**
     * Issues a high-entropy credential scoped to exactly one Gateway and shelter.  SQLite stores
     * only a SHA-256 digest; this is suitable for a generated 256-bit bearer token and avoids a
     * reversible shared secret in backups or database inspection.
     */
    fun issueGatewayCredential(
        gatewayId: String,
        shelterId: String,
        expiresAtEpochMillis: Long,
        now: Long = System.currentTimeMillis(),
    ): IssuedGatewayCredential = synchronized(lock) {
        require(isScopeIdentifier(gatewayId)) { "invalid gateway id" }
        require(isScopeIdentifier(shelterId)) { "invalid shelter id" }
        require(expiresAtEpochMillis > now) { "credential expiry must be in the future" }
        val credentialId = UUID.randomUUID().toString().replace("-", "")
        val rawSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
        val token = "rgc_${credentialId}_$rawSecret"
        connection.prepareStatement(
            """INSERT INTO broker_gateway_credentials
               (credential_id,token_hash,gateway_id,shelter_id,issued_at,expires_at,revoked_at)
               VALUES(?,?,?,?,?,?,NULL)""",
        ).use { ps ->
            ps.setString(1, credentialId)
            ps.setString(2, gatewayTokenHash(token))
            ps.setString(3, gatewayId)
            ps.setString(4, shelterId)
            ps.setLong(5, now)
            ps.setLong(6, expiresAtEpochMillis)
            ps.executeUpdate()
        }
        IssuedGatewayCredential(credentialId, gatewayId, shelterId, expiresAtEpochMillis, token)
    }

    fun revokeGatewayCredential(credentialId: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        if (credentialId.length !in 16..80) return false
        connection.prepareStatement(
            "UPDATE broker_gateway_credentials SET revoked_at=? WHERE credential_id=? AND revoked_at IS NULL",
        ).use { ps ->
            ps.setLong(1, now)
            ps.setString(2, credentialId)
            ps.executeUpdate() == 1
        }
    }

    /** Returns no scope at all for malformed, expired, revoked, or unknown credentials. */
    fun authenticateGatewayCredential(token: String?, now: Long = System.currentTimeMillis()): BrokerGatewayPrincipal? = synchronized(lock) {
        if (token.isNullOrBlank() || token.length !in 48..256) return null
        val digest = gatewayTokenHash(token)
        connection.prepareStatement(
            """SELECT credential_id,token_hash,gateway_id,shelter_id,expires_at,revoked_at
               FROM broker_gateway_credentials WHERE token_hash=?""",
        ).use { ps ->
            ps.setString(1, digest)
            ps.executeQuery().use { rs ->
                if (!rs.next() || rs.getObject("revoked_at") != null || rs.getLong("expires_at") <= now) return null
                val storedHash = rs.getString("token_hash")
                if (!MessageDigest.isEqual(storedHash.toByteArray(Charsets.UTF_8), digest.toByteArray(Charsets.UTF_8))) return null
                BrokerGatewayPrincipal(
                    credentialId = rs.getString("credential_id"),
                    gatewayId = rs.getString("gateway_id"),
                    shelterId = rs.getString("shelter_id"),
                    expiresAtEpochMillis = rs.getLong("expires_at"),
                )
            }
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

    // ─── Device Registration & Capability Tokens ───────────────────────────────────

    /**
     * Registers a device public key and returns a capability token.
     * Idempotent: re-registration with same key returns existing token.
     */
    fun registerDevice(deviceKeyId: String, publicKeyBase64: String, now: Long): DeviceRegistrationResult = synchronized(lock) {
        // Check if already registered
        connection.prepareStatement(
            "SELECT capability_token FROM broker_devices WHERE device_key_id=?",
        ).use { ps ->
            ps.setString(1, deviceKeyId)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    return DeviceRegistrationResult(deviceKeyId, rs.getString(1))
                }
            }
        }
        val capabilityToken = UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", "")
        connection.prepareStatement(
            "INSERT OR IGNORE INTO broker_devices(device_key_id, public_key_base64, capability_token, registered_at) VALUES(?,?,?,?)",
        ).use { ps ->
            ps.setString(1, deviceKeyId)
            ps.setString(2, publicKeyBase64)
            ps.setString(3, capabilityToken)
            ps.setLong(4, now)
            ps.executeUpdate()
        }
        DeviceRegistrationResult(deviceKeyId, capabilityToken)
    }

    /** Returns the registered public key for a device, or null if unregistered. */
    fun devicePublicKey(deviceKeyId: String): String? = synchronized(lock) {
        connection.prepareStatement(
            "SELECT public_key_base64 FROM broker_devices WHERE device_key_id=?",
        ).use { ps ->
            ps.setString(1, deviceKeyId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    /** Resolves a capability token to a device key ID, or null if invalid. */
    fun deviceForCapabilityToken(token: String): String? = synchronized(lock) {
        connection.prepareStatement(
            "SELECT device_key_id FROM broker_devices WHERE capability_token=?",
        ).use { ps ->
            ps.setString(1, token)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    /**
     * Verifies the upload signature against the device's registered public key.
     * Returns true if valid, false otherwise.
     */
    fun verifyUploadSignature(
        deviceKeyId: String,
        envelope: EncryptedRescueEnvelope,
        signatureBase64: String,
    ): Boolean = synchronized(lock) {
        val publicKeyBase64 = devicePublicKey(deviceKeyId) ?: return false
        try {
            val keyBytes = Base64.getDecoder().decode(publicKeyBase64)
            val keySpec = X509EncodedKeySpec(keyBytes)
            val publicKey = KeyFactory.getInstance("EC").generatePublic(keySpec)
            val dataToVerify = envelope.authenticatedHeaderBytes() +
                envelope.ciphertextSha256Hex.encodeToByteArray()
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(publicKey)
            sig.update(dataToVerify)
            sig.verify(Base64.getDecoder().decode(signatureBase64))
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Pull pending envelopes for a shelter using a composite cursor (stored_at, envelope_id).
     * This prevents skipping records with identical timestamps and never wraps to the start.
     * Cursor format: "{stored_at}:{envelope_id}" (opaque to callers).
     */
    fun pendingForShelter(
        shelterId: String,
        now: Long,
        cursor: String?,
        limit: Int,
        gatewayId: String,
    ): BrokerEnvelopeBatch = synchronized(lock) {
        val envelopes = mutableListOf<EncryptedRescueEnvelope>()
        var lastStoredAt = 0L
        var lastEnvelopeId = ""

        // Parse composite cursor
        if (cursor != null) {
            val parts = cursor.split(":", limit = 2)
            if (parts.size == 2) {
                lastStoredAt = parts[0].toLongOrNull() ?: 0L
                lastEnvelopeId = parts[1]
            }
        }

        // Composite cursor query: (stored_at, envelope_id) > (cursorStoredAt, cursorEnvelopeId)
        connection.prepareStatement(
            """SELECT envelope_json, stored_at, envelope_id FROM broker_envelopes
               WHERE shelter_id=? AND expires_at>?
               AND (stored_at > ? OR (stored_at = ? AND envelope_id > ?))
               ORDER BY stored_at ASC, envelope_id ASC LIMIT ?""",
        ).use { ps ->
            ps.setString(1, shelterId)
            ps.setLong(2, now)
            ps.setLong(3, lastStoredAt)
            ps.setLong(4, lastStoredAt)
            ps.setString(5, lastEnvelopeId)
            ps.setInt(6, limit)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    envelopes.add(brokerJson.decodeFromString(rs.getString(1)))
                    lastStoredAt = rs.getLong(2)
                    lastEnvelopeId = rs.getString(3)
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
        // Always return cursor if we got results (even partial page) to avoid re-scanning
        val nextCursor = if (envelopes.isNotEmpty()) "$lastStoredAt:$lastEnvelopeId" else null
        BrokerEnvelopeBatch(envelopes, nextCursor)
    }

    /**
     * Store a signed receipt from the Gateway for later device pickup.
     * Idempotent by receipt_id. Returns true if stored (new), false if duplicate.
     * Throws if the envelope is unknown (save failure → caller must NOT return success).
     */
    fun saveReceipt(shelterId: String, receipt: SignedShelterReceipt, now: Long): Boolean = synchronized(lock) {
        // Verify the receipt's envelope exists and belongs to this shelter
        connection.prepareStatement(
            "SELECT envelope_id FROM broker_envelopes WHERE envelope_id=? AND shelter_id=?",
        ).use { ps ->
            ps.setString(1, receipt.receipt.envelopeId)
            ps.setString(2, shelterId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) throw IllegalStateException("unknown_envelope:${receipt.receipt.envelopeId}")
            }
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
     * Retrieve receipts for a device using Broker-assigned monotonic seq cursor.
     * The capabilityToken resolves to a deviceKeyId; only that device's receipts are returned.
     * Returns receipts with seq > sinceSeq, plus the max seq seen (for next cursor).
     */
    fun receiptsForDevice(capabilityToken: String, sinceSeq: Long): BrokerReceiptBatch = synchronized(lock) {
        val deviceKeyId = deviceForCapabilityToken(capabilityToken)
            ?: return BrokerReceiptBatch(emptyList(), sinceSeq)
        var maxSeq = sinceSeq
        val receipts = connection.prepareStatement(
            """SELECT r.receipt_json, r.seq FROM broker_receipts r
               JOIN broker_envelopes e ON e.envelope_id = r.envelope_id
               WHERE e.device_key_id=? AND r.seq>?
               ORDER BY r.seq ASC LIMIT 100""",
        ).use { ps ->
            ps.setString(1, deviceKeyId)
            ps.setLong(2, sinceSeq)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(brokerJson.decodeFromString<SignedShelterReceipt>(rs.getString(1)))
                        val seq = rs.getLong(2)
                        if (seq > maxSeq) maxSeq = seq
                    }
                }
            }
        }
        BrokerReceiptBatch(receipts, maxSeq)
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

    private fun isScopeIdentifier(value: String): Boolean = value.length in 1..128 &&
        value.all { it.isLetterOrDigit() || it in "-_.:" }

    /** A digest is sufficient only because [issueGatewayCredential] creates 256-bit random tokens. */
    private fun gatewayTokenHash(token: String): String = Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)),
    )

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
