package com.example.relay.pcgateway

import com.example.relay.gateway.protocol.GATEWAY_PROTOCOL_VERSION
import com.example.relay.gateway.protocol.GatewayRejection
import com.example.relay.gateway.protocol.ReceiptResponse
import com.example.relay.gateway.protocol.SyncMessagesRequest
import com.example.relay.gateway.protocol.SyncMessagesResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class PairRequest(val code: String, val bridgeId: String, val bridgeName: String)
@Serializable data class PairApproveRequest(val code: String, val bridgeId: String)
@Serializable data class PairResponse(val paired: Boolean, val token: String? = null, val reason: String? = null)
@Serializable data class HealthResponse(val status: String, val gatewayId: String, val database: String)

fun Application.gatewayModule(
    config: GatewayConfig,
    store: GatewayStore,
    anonymousLimiter: AnonymousIngressRateLimiter = AnonymousIngressRateLimiter(
        config.maxAnonymousRequestsPerMinute,
        config.maxAnonymousMessagesPerMinute,
        config.maxAnonymousBytesPerMinute,
    ),
) {
    install(ContentNegotiation) { json(GatewayJson) }
    routing {
        get("/api/health") { call.respond(HealthResponse("ok", config.gatewayId, "ready")) }
        get("/api/pair/code") {
            if (call.request.headers["X-Admin-Key"] != config.adminKey) return@get call.respond(HttpStatusCode.Unauthorized)
            call.respond(mapOf("code" to store.createPairingCode()))
        }
        post("/api/pair/request") {
            val request = call.receive<PairRequest>()
            val accepted = store.requestPair(request.code, request.bridgeId, request.bridgeName)
            call.respond(if (accepted) PairResponse(true) else PairResponse(false, reason = "invalid_or_expired_code"))
        }
        post("/api/pair/approve") {
            if (call.request.headers["X-Admin-Key"] != config.adminKey) return@post call.respond(HttpStatusCode.Unauthorized)
            val request = call.receive<PairApproveRequest>()
            val token = store.approvePair(request.bridgeId, request.code)
            call.respond(if (token == null) PairResponse(false, reason = "pairing_failed") else PairResponse(true, token))
        }
        post("/api/pair/reject") {
            if (call.request.headers["X-Admin-Key"] != config.adminKey) return@post call.respond(HttpStatusCode.Unauthorized)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/api/sync/messages") {
            val contentLength = call.request.headers["Content-Length"]?.toLongOrNull()
            if (contentLength != null && contentLength > config.maxPayloadBytes.toLong() * config.maxMessagesPerRequest) return@post call.respond(HttpStatusCode.PayloadTooLarge)
            val request = call.receive<SyncMessagesRequest>()
            if (request.protocolVersion != GATEWAY_PROTOCOL_VERSION || request.messages.size > config.maxMessagesPerRequest) {
                return@post call.respond(HttpStatusCode.UnprocessableEntity, SyncMessagesResponse(rejected = request.messages.map { GatewayRejection(it.messageId, "unsupported_or_too_many") }))
            }
            val token = bearer(call.request.headers["Authorization"])
                ?: return@post call.respond(HttpStatusCode.Unauthorized)
            if (!store.authenticate(request.bridgeId, token)) return@post call.respond(HttpStatusCode.Forbidden)
            val now = System.currentTimeMillis()
            val outcomes = request.messages.map { message ->
                val reason = validate(message, config.maxPayloadBytes, now)
                if (reason != null) StoreOutcome("REJECTED", reason = reason) else null
            }
            val accepted = request.messages.zip(outcomes).filter { it.second == null }.map { it.first }
            val stored = runCatching { store.ingest(request.bridgeId, accepted, now) }.getOrElse { return@post call.respond(HttpStatusCode.ServiceUnavailable) }
            val all = request.messages.zip(outcomes)
            val rejected = all.filter { it.second != null }.map { GatewayRejection(it.first.messageId, it.second!!.reason!!) }
            call.respond(syncResponse(accepted, stored, rejected))
        }
        post("/api/public/sync/messages") {
            if (!config.anonymousIngressEnabled) return@post call.respond(HttpStatusCode.NotFound)
            val declaredLength = call.request.headers["Content-Length"]?.toLongOrNull()
            if (declaredLength != null && declaredLength > config.maxAnonymousRequestBytes) {
                return@post call.respond(HttpStatusCode.PayloadTooLarge)
            }
            val raw = call.receiveText()
            val rawBytes = raw.encodeToByteArray().size
            if (rawBytes > config.maxAnonymousRequestBytes) return@post call.respond(HttpStatusCode.PayloadTooLarge)
            val request = runCatching { GatewayJson.decodeFromString(SyncMessagesRequest.serializer(), raw) }.getOrElse {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "malformed_json"))
            }
            if (request.protocolVersion != GATEWAY_PROTOCOL_VERSION || request.bridgeId.length !in 1..64 ||
                request.messages.size > config.maxAnonymousMessagesPerRequest
            ) {
                return@post call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    SyncMessagesResponse(rejected = request.messages.map { GatewayRejection(it.messageId, "unsupported_or_too_many") }),
                )
            }
            // `local` is the immediate socket peer and deliberately ignores spoofable bridge/header identity.
            val source = call.request.local.remoteHost
            if (!anonymousLimiter.allow(source, request.messages.size, rawBytes)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "anonymous_rate_limit"))
            }
            val now = System.currentTimeMillis()
            val validation = request.messages.associateWith {
                if (it.recordType != "REPORT") "unregistered_status_change_not_allowed"
                else validate(it, config.maxPayloadBytes, now)
            }
            val accepted = validation.filterValues { it == null }.keys.toList()
            val rejected = validation.filterValues { it != null }.map { (message, reason) -> GatewayRejection(message.messageId, reason!!) }
            val stored = runCatching { store.ingestUnregistered(accepted, now) }.getOrElse {
                return@post call.respond(HttpStatusCode.ServiceUnavailable)
            }
            call.response.headers.append("X-Relay-Receipt-Trust", "unverified")
            call.respond(syncResponse(accepted, stored, rejected))
        }
        get("/api/sync/receipts") {
            val bridgeId = call.request.headers["X-Bridge-Id"] ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val token = bearer(call.request.headers["Authorization"]) ?: return@get call.respond(HttpStatusCode.Unauthorized)
            if (!store.authenticate(bridgeId, token)) return@get call.respond(HttpStatusCode.Forbidden)
            call.respond(ReceiptResponse(receipts = store.receipts()))
        }
        get("/api/bridges") {
            if (call.request.headers["X-Admin-Key"] != config.adminKey) return@get call.respond(HttpStatusCode.Unauthorized)
            call.respond(store.summaries())
        }
        get("/api/messages") {
            if (call.request.headers["X-Admin-Key"] != config.adminKey) return@get call.respond(HttpStatusCode.Unauthorized)
            val counts = store.counts()
            call.respond(mapOf("messages" to counts.first, "active" to counts.second, "items" to store.messages(call.request.queryParameters["type"], call.request.queryParameters["status"], call.request.queryParameters["q"])))
        }
        get("/") {
            val counts = store.counts()
            val bridges = store.summaries()
            val html = """
                <!doctype html><html><head><meta charset="utf-8"><title>Relay PC Gateway</title>
                <style>body{font-family:sans-serif;margin:2rem}table{border-collapse:collapse}td,th{padding:.4rem;border:1px solid #bbb}</style></head><body>
                <h1>Relay PC Gateway</h1><p>Gateway: ${config.gatewayId} / <a href="/api/health">health</a></p>
                <h2>Dashboard</h2><p>保存REPORT: ${counts.first} / ACTIVE: ${counts.second}</p>
                <h2>Messages</h2><table><tr><th>ID</th><th>種別</th><th>Record</th><th>Priority</th><th>Status</th><th>Trust</th><th>Origin</th><th>Created</th><th>Received</th><th>Hops</th></tr>
                ${store.messages().joinToString("") { "<tr><td>${safe(it.messageId.take(12))}</td><td>${safe(it.messageType)}</td><td>${safe(it.recordType)}</td><td>${safe(it.priority)}</td><td>${safe(it.status)}</td><td>${safe(it.ingressTrust)}</td><td>${safe(it.origin.take(12))}</td><td>${it.createdAt}</td><td>${it.receivedAt}</td><td>${it.hopCount}</td></tr>" }}</table>
                <h2>Bridges</h2><table><tr><th>ID</th><th>Name</th><th>Paired</th><th>Connected</th><th>Last sync</th><th>Received</th></tr>
                ${bridges.joinToString("") { "<tr><td>${safe(it.bridgeId)}</td><td>${safe(it.name)}</td><td>${it.paired}</td><td>${it.connected}</td><td>${it.lastSyncAt ?: "-"}</td><td>${it.receivedCount}</td></tr>" }}</table>
                <h2>Pairing</h2><label>Admin key <input id="admin" type="password"></label><button onclick="code()">Generate code</button><span id="code"></span>
                <form onsubmit="approve(event)"><input id="bridge" placeholder="Bridge ID"><input id="paircode" placeholder="Pairing code"><button>Approve bridge</button></form><pre id="result"></pre>
                <script>async function code(){let k=document.getElementById('admin').value;let r=await fetch('/api/pair/code',{headers:{'X-Admin-Key':k}});document.getElementById('code').textContent=await r.text()}async function approve(e){e.preventDefault();let k=document.getElementById('admin').value;let r=await fetch('/api/pair/approve',{method:'POST',headers:{'X-Admin-Key':k,'Content-Type':'application/json'},body:JSON.stringify({code:document.getElementById('paircode').value,bridgeId:document.getElementById('bridge').value})});document.getElementById('result').textContent=await r.text()}</script></body></html>
            """.trimIndent()
            call.respondText(html, ContentType.Text.Html)
        }
    }
}

private fun syncResponse(
    accepted: List<com.example.relay.gateway.protocol.GatewayMessage>,
    stored: List<StoreOutcome>,
    rejectedBeforeStore: List<GatewayRejection>,
): SyncMessagesResponse {
    val paired = accepted.zip(stored)
    val storeRejected = paired.filter { (_, outcome) -> outcome.disposition !in setOf("STORED", "DUPLICATE") }
        .map { (message, outcome) -> GatewayRejection(message.messageId, outcome.reason ?: outcome.disposition.lowercase()) }
    return SyncMessagesResponse(
        acceptedMessageIds = paired.filter { it.second.disposition == "STORED" }.map { it.first.messageId },
        duplicateMessageIds = paired.filter { it.second.disposition == "DUPLICATE" }.map { it.first.messageId },
        rejected = rejectedBeforeStore + storeRejected,
        receipts = paired.mapNotNull { it.second.receipt },
    )
}

private fun bearer(value: String?): String? = value?.removePrefix("Bearer ")?.takeIf { it != value && it.isNotBlank() }
private fun safe(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").take(80)
private fun validate(message: com.example.relay.gateway.protocol.GatewayMessage, maxBytes: Int, now: Long): String? {
    if (message.messageId.length !in 1..64 || message.originDeviceId.length !in 1..64) return "invalid_identifier"
    if (message.messageType !in setOf("SAFETY", "SUPPLY") || message.recordType !in setOf("REPORT", "STATUS_CHANGE")) return "invalid_type"
    if (message.priority !in setOf("LOW", "NORMAL", "HIGH", "CRITICAL") || message.status !in setOf("ACTIVE", "RESOLVED", "RETRACTED")) return "invalid_enum"
    if (message.lifetimeMs !in 1..604_800_000L || message.accumulatedAgeMs !in 0..message.lifetimeMs) return "invalid_ttl"
    if (message.hopCount !in 0..message.hopLimit || message.hopLimit !in 1..32) return "invalid_hop"
    if (message.accumulatedAgeMs >= message.lifetimeMs || message.createdAt > now + 86_400_000L) return "expired_or_future"
    if (message.payload.toString().toByteArray().size > maxBytes) return "payload_too_large"
    return null
}
