package com.example.relay.pcgateway

import com.example.relay.gateway.protocol.GatewayMessage
import com.example.relay.gateway.protocol.GatewayReceipt
import com.example.relay.gateway.protocol.UNVERIFIED_GATEWAY_RECEIPT_TYPE
import com.example.relay.gateway.protocol.VERIFIED_GATEWAY_RECEIPT_TYPE
import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class StoreOutcome(val disposition: String, val receipt: GatewayReceipt? = null, val reason: String? = null)
data class BridgeSummary(val bridgeId: String, val name: String, val paired: Boolean, val connected: Boolean, val lastSyncAt: Long?, val receivedCount: Long)
data class MessageSummary(val messageId: String, val messageType: String, val recordType: String, val priority: String, val status: String, val origin: String, val createdAt: Long, val receivedAt: Long, val hopCount: Int, val gatewayReceived: Boolean, val ingressTrust: String)

class GatewayStore(private val config: GatewayConfig, private val json: Json = GatewayJson) : AutoCloseable {
    private val lock = Any()
    private val connection: Connection

    init {
        File(config.dbPath).parentFile?.mkdirs()
        connection = DriverManager.getConnection("jdbc:sqlite:${config.dbPath}")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA busy_timeout=5000")
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA foreign_keys=ON")
            statement.execute("""
                CREATE TABLE IF NOT EXISTS messages(
                  message_id TEXT PRIMARY KEY, canonical_json TEXT NOT NULL, message_type TEXT NOT NULL,
                  record_type TEXT NOT NULL, priority TEXT NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL,
                  expires_at INTEGER NOT NULL, lifetime_ms INTEGER NOT NULL, accumulated_age_ms INTEGER NOT NULL,
                  hop_count INTEGER NOT NULL, hop_limit INTEGER NOT NULL, origin_id TEXT NOT NULL, received_at INTEGER NOT NULL,
                  ingress_trust TEXT NOT NULL DEFAULT 'VERIFIED'
                )
            """.trimIndent())
            val hasIngressTrust = statement.executeQuery("PRAGMA table_info(messages)").use { columns ->
                var found = false
                while (columns.next()) if (columns.getString("name") == "ingress_trust") found = true
                found
            }
            if (!hasIngressTrust) statement.execute("ALTER TABLE messages ADD COLUMN ingress_trust TEXT NOT NULL DEFAULT 'VERIFIED'")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_messages_created ON messages(priority, created_at, expires_at)")
            statement.execute("""
                CREATE TABLE IF NOT EXISTS receipts(
                  receipt_id TEXT PRIMARY KEY, message_id TEXT NOT NULL, receipt_type TEXT NOT NULL,
                  actor_id TEXT NOT NULL, recorded_at INTEGER NOT NULL,
                  UNIQUE(message_id, receipt_type, actor_id)
                )
            """.trimIndent())
            statement.execute("""
                CREATE TABLE IF NOT EXISTS bridges(
                  bridge_id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT, paired INTEGER NOT NULL DEFAULT 0,
                  connected INTEGER NOT NULL DEFAULT 0, last_sync_at INTEGER, received_count INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            statement.execute("""
                CREATE TABLE IF NOT EXISTS pairing_codes(code TEXT PRIMARY KEY, expires_at INTEGER NOT NULL, used INTEGER NOT NULL DEFAULT 0)
            """.trimIndent())
        }
    }

    fun createPairingCode(now: Long = System.currentTimeMillis()): String = synchronized(lock) {
        val code = (100000..999999).random().toString()
        connection.prepareStatement("INSERT INTO pairing_codes(code, expires_at) VALUES (?, ?)").use {
            it.setString(1, code); it.setLong(2, now + 5 * 60_000); it.executeUpdate()
        }
        code
    }

    fun requestPair(code: String, bridgeId: String, name: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        connection.prepareStatement("SELECT used, expires_at FROM pairing_codes WHERE code=?").use { ps ->
            ps.setString(1, code); ps.executeQuery().use { rs ->
                if (!rs.next() || rs.getInt("used") != 0 || rs.getLong("expires_at") < now) return false
            }
        }
        connection.prepareStatement("INSERT INTO bridges(bridge_id,name) VALUES(?,?) ON CONFLICT(bridge_id) DO UPDATE SET name=excluded.name").use {
            it.setString(1, bridgeId); it.setString(2, name.take(80)); it.executeUpdate()
        }
        true
    }

    fun approvePair(bridgeId: String, code: String, now: Long = System.currentTimeMillis()): String? = synchronized(lock) {
        if (!requestPair(code, bridgeId, bridgeId, now)) return null
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
        connection.prepareStatement("UPDATE bridges SET paired=1, token_hash=? WHERE bridge_id=?").use {
            it.setString(1, hash(token)); it.setString(2, bridgeId); it.executeUpdate()
        }
        connection.prepareStatement("UPDATE pairing_codes SET used=1 WHERE code=?").use { it.setString(1, code); it.executeUpdate() }
        token
    }

    fun authenticate(bridgeId: String, token: String): Boolean = synchronized(lock) {
        connection.prepareStatement("SELECT paired, token_hash FROM bridges WHERE bridge_id=?").use { ps ->
            ps.setString(1, bridgeId); ps.executeQuery().use { rs ->
                rs.next() && rs.getInt("paired") == 1 && constantTimeEquals(rs.getString("token_hash"), hash(token))
            }
        }
    }

    fun ingest(bridgeId: String, messages: List<GatewayMessage>, now: Long = System.currentTimeMillis()): List<StoreOutcome> = synchronized(lock) {
        connection.autoCommit = false
        try {
            val results = messages.map { message -> ingestOne(message, now, VERIFIED_GATEWAY_RECEIPT_TYPE) }
            connection.prepareStatement("UPDATE bridges SET connected=1,last_sync_at=?,received_count=received_count+? WHERE bridge_id=?").use {
                it.setLong(1, now); it.setInt(2, messages.size); it.setString(3, bridgeId); it.executeUpdate()
            }
            connection.commit(); results
        } catch (error: Exception) {
            connection.rollback(); throw error
        } finally { connection.autoCommit = true }
    }

    /** Unregistered LAN senders can store validated data but receive only an explicitly unverified receipt. */
    fun ingestUnregistered(messages: List<GatewayMessage>, now: Long = System.currentTimeMillis()): List<StoreOutcome> = synchronized(lock) {
        connection.autoCommit = false
        try {
            val results = messages.map { message -> ingestOne(message, now, UNVERIFIED_GATEWAY_RECEIPT_TYPE) }
            connection.commit(); results
        } catch (error: Exception) {
            connection.rollback(); throw error
        } finally { connection.autoCommit = true }
    }

    private fun ingestOne(message: GatewayMessage, now: Long, receiptType: String): StoreOutcome {
        val ingressTrust = if (receiptType == VERIFIED_GATEWAY_RECEIPT_TYPE) "VERIFIED" else "UNVERIFIED"
        if (message.messageId.isBlank() || message.messageId.length > 64 || message.originDeviceId.length !in 1..64) return StoreOutcome("REJECTED", reason = "invalid_identifier")
        if (message.lifetimeMs !in 1..604_800_000L || message.accumulatedAgeMs !in 0..message.lifetimeMs || message.accumulatedAgeMs >= message.lifetimeMs) return StoreOutcome("REJECTED", reason = "expired_or_invalid_ttl")
        if (message.hopLimit !in 1..32 || message.hopCount !in 0..message.hopLimit) return StoreOutcome("REJECTED", reason = "invalid_hop")
        if (message.recordType == "STATUS_CHANGE") {
            val target = message.payload.jsonObject["targetMessageId"]?.jsonPrimitive?.content ?: return StoreOutcome("REJECTED", reason = "invalid_status_change")
            val newStatus = message.payload.jsonObject["newStatus"]?.jsonPrimitive?.content ?: return StoreOutcome("REJECTED", reason = "invalid_status_change")
            if (newStatus !in setOf("ACTIVE", "RESOLVED", "RETRACTED")) return StoreOutcome("REJECTED", reason = "invalid_status_change")
            val exists = connection.prepareStatement("SELECT 1 FROM messages WHERE message_id=?").use { ps -> ps.setString(1, target); ps.executeQuery().use { it.next() } }
            if (!exists) return StoreOutcome("REJECTED", reason = "target_report_not_found")
        }
        val total = connection.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM messages").use { rs -> rs.next(); rs.getInt(1) } }
        if (total >= config.maxStoredMessages) return StoreOutcome("REJECTED", reason = "db_message_limit")
        val existing = connection.prepareStatement("SELECT canonical_json FROM messages WHERE message_id=?").use { ps ->
            ps.setString(1, message.messageId); ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
        if (existing != null) {
            val canonical = json.encodeToString(message)
            if (existing != canonical) return StoreOutcome("COLLISION", reason = "messageId collision")
            if (ingressTrust == "VERIFIED") {
                connection.prepareStatement("UPDATE messages SET ingress_trust='VERIFIED' WHERE message_id=?").use { ps ->
                    ps.setString(1, message.messageId); ps.executeUpdate()
                }
            }
            return StoreOutcome("DUPLICATE", receiptFor(message.messageId, now, receiptType))
        }
        connection.prepareStatement("""
            INSERT INTO messages(message_id,canonical_json,message_type,record_type,priority,status,created_at,expires_at,lifetime_ms,accumulated_age_ms,hop_count,hop_limit,origin_id,received_at,ingress_trust)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """.trimIndent()).use { ps ->
            ps.setString(1, message.messageId); ps.setString(2, json.encodeToString(message)); ps.setString(3, message.messageType)
            ps.setString(4, message.recordType); ps.setString(5, message.priority); ps.setString(6, message.status)
            ps.setLong(7, message.createdAt); ps.setLong(8, message.expiresAt); ps.setLong(9, message.lifetimeMs); ps.setLong(10, message.accumulatedAgeMs)
            ps.setInt(11, message.hopCount); ps.setInt(12, message.hopLimit); ps.setString(13, message.originDeviceId); ps.setLong(14, message.receivedAt)
            ps.setString(15, ingressTrust); ps.executeUpdate()
        }
        if (message.recordType == "STATUS_CHANGE") {
            val target = message.payload.jsonObject["targetMessageId"]!!.jsonPrimitive.content
            val newStatus = message.payload.jsonObject["newStatus"]!!.jsonPrimitive.content
            connection.prepareStatement("UPDATE messages SET status=? WHERE message_id=?").use { ps -> ps.setString(1, newStatus); ps.setString(2, target); ps.executeUpdate() }
        }
        return StoreOutcome("STORED", receiptFor(message.messageId, now, receiptType))
    }

    private fun receiptFor(messageId: String, now: Long, receiptType: String): GatewayReceipt {
        val existing = connection.prepareStatement("SELECT receipt_id, message_id, receipt_type, actor_id, recorded_at FROM receipts WHERE message_id=? AND receipt_type=? AND actor_id=?").use { ps ->
            ps.setString(1, messageId); ps.setString(2, receiptType); ps.setString(3, config.gatewayId); ps.executeQuery().use { rs -> if (rs.next()) GatewayReceipt(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)) else null }
        }
        if (existing != null) return existing
        val receipt = GatewayReceipt(UUID.randomUUID().toString(), messageId, receiptType, config.gatewayId, now)
        connection.prepareStatement("INSERT INTO receipts(receipt_id,message_id,receipt_type,actor_id,recorded_at) VALUES(?,?,?,?,?)").use { ps ->
            ps.setString(1, receipt.receiptId); ps.setString(2, receipt.messageId); ps.setString(3, receipt.receiptType); ps.setString(4, receipt.actorId); ps.setLong(5, receipt.recordedAt); ps.executeUpdate()
        }
        return receipt
    }

    fun receipts(): List<GatewayReceipt> = synchronized(lock) { connection.prepareStatement("SELECT receipt_id,message_id,receipt_type,actor_id,recorded_at FROM receipts ORDER BY recorded_at").use { ps -> ps.executeQuery().use { rs -> buildList { while (rs.next()) add(GatewayReceipt(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5))) } } } }

    fun summaries(): List<BridgeSummary> = synchronized(lock) { connection.prepareStatement("SELECT bridge_id,name,paired,connected,last_sync_at,received_count FROM bridges ORDER BY name").use { ps -> ps.executeQuery().use { rs -> buildList { while (rs.next()) add(BridgeSummary(rs.getString(1),rs.getString(2),rs.getInt(3)==1,rs.getInt(4)==1,rs.getLong(5).takeIf { !rs.wasNull() },rs.getLong(6))) } } } }

    fun counts(): Pair<Int, Int> = synchronized(lock) { connection.createStatement().use { st -> st.executeQuery("SELECT COUNT(*), COALESCE(SUM(CASE WHEN status='ACTIVE' THEN 1 ELSE 0 END),0) FROM messages").use { rs -> rs.next(); rs.getInt(1) to rs.getInt(2) } } }

    fun messages(type: String? = null, status: String? = null, query: String? = null): List<MessageSummary> = synchronized(lock) {
        val rows = connection.prepareStatement("SELECT m.message_id,m.message_type,m.record_type,m.priority,m.status,m.origin_id,m.created_at,m.received_at,m.hop_count,EXISTS(SELECT 1 FROM receipts r WHERE r.message_id=m.message_id AND r.receipt_type='GATEWAY_RECEIVED'),m.ingress_trust FROM messages m ORDER BY CASE priority WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3 WHEN 'NORMAL' THEN 2 ELSE 1 END DESC, created_at DESC").use { ps -> ps.executeQuery().use { rs -> buildList { while (rs.next()) add(MessageSummary(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getLong(7),rs.getLong(8),rs.getInt(9),rs.getInt(10)==1,rs.getString(11))) } } }
        rows.filter { (type == null || it.messageType == type) && (status == null || it.status == status) && (query.isNullOrBlank() || it.messageId.contains(query, true) || it.origin.contains(query, true)) }
    }

    override fun close() { synchronized(lock) { connection.close() } }
    private fun hash(value: String): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
    private fun constantTimeEquals(a: String?, b: String): Boolean = a != null && MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

val GatewayJson = Json { ignoreUnknownKeys = false; encodeDefaults = true; classDiscriminator = "payloadType" }
