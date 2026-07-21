package com.example.relay.broker

import com.example.relay.rescue.RescueValidationResult
import com.example.relay.rescue.validate
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/** Maximum raw request body size for upload (64 KiB envelope JSON). */
private const val MAX_UPLOAD_BODY_BYTES = 64 * 1024

/** Maximum pull batch size a Gateway can request. */
private const val MAX_PULL_LIMIT = 100

/** Default pull batch size. */
private const val DEFAULT_PULL_LIMIT = 50

fun Application.brokerModule(
    store: BrokerStore,
    uploadRateLimiter: SlidingWindowRateLimiter = SlidingWindowRateLimiter(maxRequests = 30, windowMillis = 60_000),
    pullRateLimiter: SlidingWindowRateLimiter = SlidingWindowRateLimiter(maxRequests = 120, windowMillis = 60_000),
) {
    install(ContentNegotiation) { json(brokerJson) }

    routing {
        /**
         * POST /v1/rescue/upload
         * Android uploads an encrypted envelope. Broker validates structure, deduplicates,
         * and stores. Never decrypts. hopCount is NOT modified.
         */
        post("/v1/rescue/upload") {
            val rawBody = call.receiveText()
            if (rawBody.toByteArray().size > MAX_UPLOAD_BODY_BYTES) {
                call.respond(HttpStatusCode.PayloadTooLarge, mapOf("reason" to "envelope_too_large"))
                return@post
            }
            val request = try {
                brokerJson.decodeFromString<BrokerUploadRequest>(rawBody)
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("reason" to "malformed_request"))
                return@post
            }
            val envelope = request.envelope
            // Validate envelope structure (shared validation logic)
            if (envelope.validate() != RescueValidationResult.Valid) {
                call.respond(HttpStatusCode.BadRequest, mapOf("reason" to "invalid_envelope"))
                return@post
            }
            // Validate deviceKeyId format
            if (request.deviceKeyId.isBlank() || request.deviceKeyId.length > 128) {
                call.respond(HttpStatusCode.BadRequest, mapOf("reason" to "invalid_device_key_id"))
                return@post
            }
            // Rate limit per device key
            if (!uploadRateLimiter.allow(request.deviceKeyId)) {
                call.respond(HttpStatusCode.TooManyRequests, mapOf("reason" to "rate_limited"))
                return@post
            }
            // Reject expired envelopes
            val now = System.currentTimeMillis()
            if (envelope.expiresAtEpochMillis <= now) {
                call.respond(HttpStatusCode.BadRequest, mapOf("reason" to "envelope_expired"))
                return@post
            }
            when (val result = store.put(envelope, request.deviceKeyId, now)) {
                is BrokerPutResult.Stored -> call.respond(HttpStatusCode.Created, result.response)
                is BrokerPutResult.Duplicate -> {
                    // Idempotent: return 200 with existing info
                    call.respond(HttpStatusCode.OK, BrokerUploadResponse(
                        brokerReceiptId = store.existingBrokerReceiptId(envelope.envelopeId) ?: envelope.envelopeId,
                        envelopeId = envelope.envelopeId,
                        storedAtEpochMillis = now,
                    ))
                }
                is BrokerPutResult.Collision -> {
                    call.respond(HttpStatusCode.Conflict, mapOf(
                        "reason" to "ciphertext_collision",
                        "existing_envelope_id" to result.existingEnvelopeId,
                    ))
                }
            }
        }

        /**
         * GET /v1/gateways/{shelterId}/pull?cursor=&limit=
         * PC Gateway pulls pending envelopes for its shelter. Outbound from Gateway perspective.
         * At-least-once: pull does not delete; Gateway acknowledges via receipt upload.
         */
        get("/v1/gateways/{shelterId}/pull") {
            val shelterId = call.parameters["shelterId"]
            if (shelterId.isNullOrBlank() || shelterId.length > 128) {
                call.respond(HttpStatusCode.BadRequest, mapOf("reason" to "invalid_shelter_id"))
                return@get
            }
            if (!pullRateLimiter.allow("gateway:$shelterId")) {
                call.respond(HttpStatusCode.TooManyRequests, mapOf("reason" to "rate_limited"))
                return@get
            }
            val cursor = call.request.queryParameters["cursor"]?.toLongOrNull()
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_PULL_LIMIT)
                .coerceIn(1, MAX_PULL_LIMIT)
            val now = System.currentTimeMillis()
            val batch = store.pendingForShelter(shelterId, now, cursor, limit, gatewayId = shelterId)
            call.respond(HttpStatusCode.OK, batch)
        }

        /**
         * POST /v1/gateways/{shelterId}/receipts
         * PC Gateway uploads a signed shelter receipt for relay back to the device.
         * Idempotent by receipt_id.
         */
        post("/v1/gateways/{shelterId}/receipts") {
            val shelterId = call.parameters["shelterId"]
            if (shelterId.isNullOrBlank() || shelterId.length > 128) {
                call.respond(HttpStatusCode.BadRequest, mapOf("reason" to "invalid_shelter_id"))
                return@post
            }
            if (!pullRateLimiter.allow("receipt:$shelterId")) {
                call.respond(HttpStatusCode.TooManyRequests, mapOf("reason" to "rate_limited"))
                return@post
            }
            val upload = try {
                call.receive<BrokerReceiptUpload>()
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("reason" to "malformed_receipt"))
                return@post
            }
            // Validate receipt structure
            if (upload.receipt.validate() != RescueValidationResult.Valid) {
                call.respond(HttpStatusCode.BadRequest, mapOf("reason" to "invalid_receipt"))
                return@post
            }
            // Receipt shelterId must match the path parameter
            if (upload.receipt.receipt.shelterId != shelterId) {
                call.respond(HttpStatusCode.BadRequest, mapOf("reason" to "shelter_mismatch"))
                return@post
            }
            val now = System.currentTimeMillis()
            val saved = store.saveReceipt(shelterId, upload.receipt, now)
            call.respond(HttpStatusCode.Accepted, BrokerReceiptUploadResponse(
                accepted = true,
                reason = if (saved) null else "duplicate_or_unknown_envelope",
            ))
        }

        /**
         * GET /v1/devices/{deviceKeyId}/receipts?since=
         * Android polls for signed shelter receipts. deviceKeyId is the capability token:
         * only receipts for envelopes uploaded by this key are returned.
         */
        get("/v1/devices/{deviceKeyId}/receipts") {
            val deviceKeyId = call.parameters["deviceKeyId"]
            if (deviceKeyId.isNullOrBlank() || deviceKeyId.length > 128) {
                call.respond(HttpStatusCode.BadRequest, mapOf("reason" to "invalid_device_key_id"))
                return@get
            }
            if (!pullRateLimiter.allow("device:$deviceKeyId")) {
                call.respond(HttpStatusCode.TooManyRequests, mapOf("reason" to "rate_limited"))
                return@get
            }
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
            val receipts = store.receiptsForDevice(deviceKeyId, since)
            call.respond(HttpStatusCode.OK, BrokerReceiptBatch(receipts))
        }

        /** GET /v1/health — operational status. */
        get("/v1/health") {
            val now = System.currentTimeMillis()
            call.respond(HttpStatusCode.OK, BrokerHealthResponse(
                pendingEnvelopes = store.countPendingEnvelopes(now),
                pendingReceipts = store.countPendingReceipts(),
            ))
        }
    }
}
