package com.example.relay.pcgateway

import com.example.relay.gateway.protocol.GATEWAY_PROTOCOL_VERSION
import com.example.relay.gateway.protocol.GatewayRejection
import com.example.relay.gateway.protocol.ReceiptResponse
import com.example.relay.gateway.protocol.SyncMessagesRequest
import com.example.relay.gateway.protocol.SyncMessagesResponse
import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.ShelterPublicKeyManifest
import com.example.relay.pcgateway.rescue.RescueIntakeService
import com.example.relay.pcgateway.rescue.RescueDeliveryIngress
import com.example.relay.pcgateway.rescue.RescueIngestResult
import com.example.relay.pcgateway.rescue.RescueOperatorListResponse
import com.example.relay.pcgateway.rescue.RescueStatusChangeRequest
import com.example.relay.pcgateway.rescue.RescueStatusChangeResponse
import com.example.relay.pcgateway.rescue.RescueStatusUpdateResult
import com.example.relay.pcgateway.rescue.toOperatorRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import io.ktor.server.routing.routing

@Serializable data class PairRequest(val code: String, val bridgeId: String, val bridgeName: String)
@Serializable data class PairApproveRequest(val code: String, val bridgeId: String)
@Serializable data class PairRejectRequest(val bridgeId: String, val code: String? = null)
@Serializable data class PairResponse(val paired: Boolean, val token: String? = null, val reason: String? = null)
@Serializable data class HealthResponse(
    val status: String,
    val gatewayId: String,
    val database: String,
    val anonymousIngress: Boolean = true,
    val lanDiscoveryPort: Int = 42888,
    /** Fail-closed until an authenticated local BLE bridge heartbeat is wired. */
    val bleBridgeStatus: String = "unavailable",
    val version: String = "unknown",
    val buildSha: String = "unknown",
    val shelterId: String = "unknown",
    val recipientKeyId: String? = null,
    val manifestFingerprint: String? = null,
    val rescueIngressReady: Boolean = false,
    val rescueKeyPath: String? = null,
    val runtimeUser: String = System.getProperty("user.name", "unknown"),
)

@Serializable
data class MessagesListResponse(
    val messages: Int,
    val active: Int,
    /** Legacy aliases; both fields count content verification, not route authentication. */
    val verified: Int,
    val unverified: Int,
    val contentVerified: Int,
    val contentUnverified: Int,
    val authenticatedRoute: Int,
    val anonymousRoute: Int,
    val items: List<MessageSummary>,
)

fun Application.gatewayModule(
    config: GatewayConfig,
    store: GatewayStore,
    /** Maintenance compatibility only. General-user rescue delivery never trusts this unsigned document. */
    rescueManifest: ShelterPublicKeyManifest? = null,
    /** True only after startup verified a root-signed manifest against this PC's local keys. */
    rescueBleReady: Boolean = false,
    rescueIntakeService: RescueIntakeService? = null,
    offlineMap: GsiTileCache? = null,
    officialInformation: OfficialInformationService? = null,
    anonymousLimiter: AnonymousIngressRateLimiter = AnonymousIngressRateLimiter(
        config.maxAnonymousRequestsPerMinute,
        config.maxAnonymousMessagesPerMinute,
        config.maxAnonymousBytesPerMinute,
    ),
) {
    install(ContentNegotiation) { json(GatewayJson) }
    routing {
        healthRoutes(config)
    }
}

private fun Route.healthRoutes(config: GatewayConfig) {
    get("/api/health") {
        call.respond(
            HealthResponse(
                status = "ok",
                gatewayId = config.gatewayId,
                database = "ready",
                anonymousIngress = config.anonymousIngressEnabled,
                lanDiscoveryPort = config.lanDiscoveryPort,
            )
        )
    }
}
                    bleBridgeStatus = if (rescueBleReady) "awaiting_sidecar" else "not_ready",
                    version = config.version,
                    buildSha = config.buildSha,
                    shelterId = config.shelterId,
                    recipientKeyId = config.rescueRecipientKeyId,
                    manifestFingerprint = config.rescueManifestFingerprint,
                    rescueIngressReady = rescueIntakeService != null,
                    rescueKeyPath = config.rescueKeyPath,
                ),
            )
        }
        get("/api/public/rescue/manifest") {
            val manifest = rescueManifest ?: return@get call.respond(HttpStatusCode.NotFound)
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respond(manifest)
        }
        get("/api/rescue/requests") {
            if (call.request.headers["X-Admin-Key"] != config.adminKey) {
                return@get call.respond(HttpStatusCode.Unauthorized)
            }
            val service = rescueIntakeService ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
            service.purgeExpiredDetails()
            val items = service.list(latestOnly = true).mapNotNull { summary ->
                service.detail(summary.requestId, summary.requestVersion)?.toOperatorRequest()
            }.sortedWith(
                compareByDescending<com.example.relay.pcgateway.rescue.RescueOperatorRequest> {
                    it.responseStatus == com.example.relay.pcgateway.rescue.RescueResponseStatus.UNCONFIRMED &&
                        it.urgency == "IMMEDIATE" && it.action != "CANCELLED"
                }.thenByDescending { it.urgency == "IMMEDIATE" }
                    .thenByDescending { it.receivedAtEpochMillis },
            )
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respond(RescueOperatorListResponse(System.currentTimeMillis(), items = items))
        }
        get("/api/rescue/requests/{id}") {
            if (call.request.headers["X-Admin-Key"] != config.adminKey) {
                return@get call.respond(HttpStatusCode.Unauthorized)
            }
            val service = rescueIntakeService ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val version = call.request.queryParameters["version"]?.toIntOrNull()
            val detail = service.detail(id, version) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respond(detail.toOperatorRequest())
        }
        post("/api/rescue/requests/{id}/status") {
            if (call.request.headers["X-Admin-Key"] != config.adminKey) {
                return@post call.respond(HttpStatusCode.Unauthorized)
            }
            val service = rescueIntakeService ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val request = call.receive<RescueStatusChangeRequest>()
            when (val result = service.updateStatus(id, request.status, request.operatorNodeId)) {
                is RescueStatusUpdateResult.Updated -> call.respond(
                    RescueStatusChangeResponse(
                        updated = true,
                        status = result.request.responseStatus,
                        assignedNodeId = result.request.assignedNodeId,
                    ),
                )
                RescueStatusUpdateResult.NotFound -> call.respond(
                    HttpStatusCode.NotFound,
                    RescueStatusChangeResponse(false, reason = "not_found"),
                )
                is RescueStatusUpdateResult.InvalidTransition -> call.respond(
                    HttpStatusCode.Conflict,
                    RescueStatusChangeResponse(false, result.current, reason = "invalid_transition"),
                )
                is RescueStatusUpdateResult.AssignedElsewhere -> call.respond(
                    HttpStatusCode.Conflict,
                    RescueStatusChangeResponse(false, assignedNodeId = result.assignedNodeId, reason = "assigned_elsewhere"),
                )
            }
        }
        get("/api/map/status") {
            if (call.request.headers["X-Admin-Key"] != config.adminKey) {
                return@get call.respond(HttpStatusCode.Unauthorized)
            }
            val map = offlineMap ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
            call.respond(map.status())
        }
        post("/api/map/prepare") {
            if (call.request.headers["X-Admin-Key"] != config.adminKey) {
                return@post call.respond(HttpStatusCode.Unauthorized)
            }
            val map = offlineMap ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            map.prepare()
            call.respond(HttpStatusCode.Accepted, map.status())
        }
        get("/api/map/tiles/{z}/{x}/{y}") {
            val map = offlineMap ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
            val z = call.parameters["z"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
            val x = call.parameters["x"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
            val y = call.parameters["y"]?.removeSuffix(".png")?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest)
            val bytes = map.tile(z, x, y) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=86400")
            call.respondBytes(bytes, ContentType.Image.PNG)
        }
        get("/api/official-info") {
            val information = officialInformation ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respond(information.current())
        }
        get("/api/pair/code") {
            if (call.request.headers["X-Admin-Key"] != config.adminKey) {
                return@get call.respond(HttpStatusCode.Unauthorized)
            }
            call.respond(mapOf("code" to store.createPairingCode()))
        }
        post("/api/pair/request") {
            val request = call.receive<PairRequest>()
            val accepted = store.requestPair(request.code, request.bridgeId, request.bridgeName)
            call.respond(if (accepted) PairResponse(true) else PairResponse(false, reason = "invalid_or_expired_code"))
        }
        post("/api/pair/approve") {
            if (call.request.headers["X-Admin-Key"] != config.adminKey) {
                return@post call.respond(HttpStatusCode.Unauthorized)
            }
            val request = call.receive<PairApproveRequest>()
            val token = store.approvePair(request.bridgeId, request.code)
            call.respond(if (token == null) PairResponse(false, reason = "pairing_failed") else PairResponse(true, token))
        }
        post("/api/pair/reject") {
            if (call.request.headers["X-Admin-Key"] != config.adminKey) {
                return@post call.respond(HttpStatusCode.Unauthorized)
            }
            val request = call.receive<PairRejectRequest>()
            if (request.bridgeId.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bridgeId_required"))
            }
            val ok = store.rejectPair(request.bridgeId, request.code)
            call.respond(if (ok) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
        }
        post("/api/sync/messages") {
            val contentLength = call.request.headers["Content-Length"]?.toLongOrNull()
            if (contentLength != null && contentLength > config.maxPayloadBytes.toLong() * config.maxMessagesPerRequest) {
                return@post call.respond(HttpStatusCode.PayloadTooLarge)
            }
            val request = call.receive<SyncMessagesRequest>()
            if (request.protocolVersion != GATEWAY_PROTOCOL_VERSION || request.messages.size > config.maxMessagesPerRequest) {
                return@post call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    SyncMessagesResponse(
                        rejected = request.messages.map { GatewayRejection(it.messageId, "unsupported_or_too_many") },
                    ),
                )
            }
            val token = bearer(call.request.headers["Authorization"])
                ?: return@post call.respond(HttpStatusCode.Unauthorized)
            if (!store.authenticate(request.bridgeId, token)) return@post call.respond(HttpStatusCode.Forbidden)
            val now = System.currentTimeMillis()
            val outcomes = request.messages.map { message ->
                val reason = validate(message, config.maxPayloadBytes, now)
                if (reason != null) StoreOutcome(message.messageId, "REJECTED", reason = reason) else null
            }
            val accepted = request.messages.zip(outcomes).filter { it.second == null }.map { it.first }
            val stored = runCatching { store.ingest(request.bridgeId, accepted, now) }
                .getOrElse { return@post call.respond(HttpStatusCode.ServiceUnavailable) }
            val all = request.messages.zip(outcomes)
            val rejected = all.filter { it.second != null }.map { GatewayRejection(it.first.messageId, it.second!!.reason!!) }
            // Authentication covers the Bridge transport only. A REPORT signature, when carried,
            // is informational until an issuer registry verifies its origin binding.
            call.response.headers.append("X-Relay-Route-Authentication", "authenticated_bridge")
            call.response.headers.append(
                "X-Relay-Content-Verification",
                if (accepted.any { it.reportSignature != null }) "signed_unverified" else "unverified",
            )
            call.response.headers.append("X-Relay-Receipt-Semantics", "gateway_saved")
            call.respond(syncResponse(stored, rejected))
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
            val request = runCatching {
                GatewayJson.decodeFromString(SyncMessagesRequest.serializer(), raw)
            }.getOrElse {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "malformed_json"))
            }
            if (request.protocolVersion != GATEWAY_PROTOCOL_VERSION || request.bridgeId.length !in 1..64 ||
                request.messages.size > config.maxAnonymousMessagesPerRequest
            ) {
                return@post call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    SyncMessagesResponse(
                        rejected = request.messages.map { GatewayRejection(it.messageId, "unsupported_or_too_many") },
                    ),
                )
            }
            // Socket peer only — ignore spoofable bridge/header identity for rate limits.
            val source = call.request.local.remoteHost
            if (!anonymousLimiter.allow(source, request.messages.size, rawBytes)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "anonymous_rate_limit"))
            }
            val now = System.currentTimeMillis()
            val validation = request.messages.associateWith { validate(it, config.maxPayloadBytes, now) }
            val accepted = validation.filterValues { it == null }.keys.toList()
            val rejected = validation.filterValues { it != null }.map { (message, reason) ->
                GatewayRejection(message.messageId, reason!!)
            }
            val stored = runCatching { store.ingestUnregistered(accepted, now) }
                .getOrElse { return@post call.respond(HttpStatusCode.ServiceUnavailable) }
            // Keep the legacy header for old clients, but expose the independent axes explicitly.
            call.response.headers.append("X-Relay-Receipt-Trust", "unverified")
            call.response.headers.append("X-Relay-Route-Authentication", "anonymous_lan")
            call.response.headers.append(
                "X-Relay-Content-Verification",
                if (accepted.any { it.reportSignature != null }) "signed_unverified" else "unverified",
            )
            call.response.headers.append("X-Relay-Receipt-Semantics", "gateway_saved")
            call.respond(syncResponse(stored, rejected))
        }
        post("/api/public/rescue/deliver") {
            if (!config.anonymousIngressEnabled) return@post call.respond(HttpStatusCode.NotFound)
            val service = rescueIntakeService ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            val raw = call.receiveText()
            if (raw.encodeToByteArray().size > config.maxAnonymousRequestBytes) {
                return@post call.respond(HttpStatusCode.PayloadTooLarge)
            }
            val request = runCatching {
                GatewayJson.decodeFromString(PublicRescueDeliveryRequest.serializer(), raw)
            }.getOrElse {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "malformed_json"))
            }
            val source = call.request.local.remoteHost
            if (!anonymousLimiter.allow(source, 1, raw.encodeToByteArray().size)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "anonymous_rate_limit"))
            }
            val ingress = RescueDeliveryIngress(service)
            when (val result = ingress.ingest(
                GatewayJson.encodeToString(EncryptedRescueEnvelope.serializer(), request.envelope).encodeToByteArray(),
                request.carrierId,
                request.courierDeliveryId,
            )) {
                is RescueIngestResult.Accepted -> call.respond(HttpStatusCode.OK, PublicRescueDeliveryResponse("accepted", receipt = result.request.receipt))
                is RescueIngestResult.Duplicate -> call.respond(HttpStatusCode.OK, PublicRescueDeliveryResponse("duplicate", receipt = result.request.receipt))
                is RescueIngestResult.Rejected -> call.respond(HttpStatusCode.UnprocessableEntity, PublicRescueDeliveryResponse("rejected", result.code.name))
                is RescueIngestResult.Quarantined -> call.respond(HttpStatusCode.Conflict, PublicRescueDeliveryResponse("quarantined"))
            }
        }
        get("/api/sync/receipts") {
            val bridgeId = call.request.headers["X-Bridge-Id"]
                ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val token = bearer(call.request.headers["Authorization"])
                ?: return@get call.respond(HttpStatusCode.Unauthorized)
            if (!store.authenticate(bridgeId, token)) return@get call.respond(HttpStatusCode.Forbidden)
            // Scope to this bridge's submissions — do not leak other bridges' receipts.
            call.respond(ReceiptResponse(receipts = store.receiptsForBridge(bridgeId)))
        }
        get("/api/bridges") {
            if (call.request.headers["X-Admin-Key"] != config.adminKey) {
                return@get call.respond(HttpStatusCode.Unauthorized)
            }
            call.respond(store.summaries())
        }
        /** Public local snapshot for the operator console (no payload bodies). */
        get("/api/dashboard") {
            call.respond(store.dashboard(config))
        }
        get("/api/messages") {
            if (call.request.headers["X-Admin-Key"] != config.adminKey) {
                return@get call.respond(HttpStatusCode.Unauthorized)
            }
            val counts = store.counts()
            val trust = store.trustCounts()
            val routes = store.routeAuthenticationCounts()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 300
            call.respond(
                MessagesListResponse(
                    messages = counts.first,
                    active = counts.second,
                    verified = trust.first,
                    unverified = trust.second,
                    contentVerified = trust.first,
                    contentUnverified = trust.second,
                    authenticatedRoute = routes.authenticatedBridge,
                    anonymousRoute = routes.anonymousLan,
                    items = store.messages(
                        type = call.request.queryParameters["type"],
                        status = call.request.queryParameters["status"],
                        query = call.request.queryParameters["q"],
                        trust = call.request.queryParameters["trust"],
                        routeAuthentication = call.request.queryParameters["routeAuthentication"],
                        limit = limit,
                    ),
                ),
            )
        }
        get("/api/messages/export.csv") {
            if (call.request.headers["X-Admin-Key"] != config.adminKey) {
                return@get call.respond(HttpStatusCode.Unauthorized)
            }
            val csv = store.exportCsv()
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"relay-messages.csv\"")
            call.respondText(csv, ContentType.Text.CSV)
        }
        get("/api/messages/{id}") {
            if (call.request.headers["X-Admin-Key"] != config.adminKey) {
                return@get call.respond(HttpStatusCode.Unauthorized)
            }
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val detail = store.messageDetail(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(detail)
        }
        // Static operator console (classpath: web/)
        staticResources("/", "web") {
            default("index.html")
        }
        get("/legacy") {
            // Minimal fallback if static resources fail to load in some packaging setups.
            val bytes = this::class.java.classLoader.getResourceAsStream("web/index.html")?.readBytes()
            if (bytes == null) {
                call.respondText("Relay PC Gateway UI missing", status = HttpStatusCode.ServiceUnavailable)
            } else {
                call.respondBytes(bytes, ContentType.Text.Html)
            }
        }
    }
}

@Serializable
private data class PublicRescueDeliveryRequest(
    val envelope: EncryptedRescueEnvelope,
    val carrierId: String,
    val courierDeliveryId: String,
)

@Serializable
private data class PublicRescueDeliveryResponse(
    val outcome: String,
    val reason: String? = null,
    val receipt: com.example.relay.rescue.SignedShelterReceipt? = null,
)

private fun syncResponse(
    stored: List<StoreOutcome>,
    rejectedBeforeStore: List<GatewayRejection>,
): SyncMessagesResponse {
    val storeRejected = stored.filter { it.disposition !in setOf("STORED", "DUPLICATE") }
        .map { outcome -> GatewayRejection(outcome.messageId, outcome.reason ?: outcome.disposition.lowercase()) }
    return SyncMessagesResponse(
        acceptedMessageIds = stored.filter { it.disposition == "STORED" }.map { it.messageId },
        duplicateMessageIds = stored.filter { it.disposition == "DUPLICATE" }.map { it.messageId },
        rejected = rejectedBeforeStore + storeRejected,
        receipts = stored.mapNotNull { it.receipt },
    )
}

private fun bearer(value: String?): String? =
    value?.removePrefix("Bearer ")?.takeIf { it != value && it.isNotBlank() }

private fun validate(
    message: com.example.relay.gateway.protocol.GatewayMessage,
    maxBytes: Int,
    now: Long,
): String? {
    if (message.messageId.length !in 1..64 || message.originDeviceId.length !in 1..64) return "invalid_identifier"
    if (message.messageType !in setOf("SAFETY", "SUPPLY") ||
        message.recordType !in setOf("REPORT", "STATUS_CHANGE")
    ) {
        return "invalid_type"
    }
    if (message.priority !in setOf("LOW", "NORMAL", "HIGH", "CRITICAL") ||
        message.status !in setOf("ACTIVE", "RESOLVED", "RETRACTED")
    ) {
        return "invalid_enum"
    }
    if (message.lifetimeMs !in 1..604_800_000L || message.accumulatedAgeMs !in 0..message.lifetimeMs) return "invalid_ttl"
    if (message.hopCount !in 0..message.hopLimit || message.hopLimit !in 1..32) return "invalid_hop"
    if (message.accumulatedAgeMs >= message.lifetimeMs || message.createdAt > now + 86_400_000L) return "expired_or_future"
    if (message.payload.toString().toByteArray().size > maxBytes) return "payload_too_large"
    return null
}
