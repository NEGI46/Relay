package com.example.relay.broker

import com.example.relay.rescue.RescueValidationResult
import com.example.relay.rescue.brokerDeviceRegistrationBytes
import com.example.relay.rescue.validate
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Maximum raw request body size for upload (64 KiB envelope JSON). */
private const val MAX_UPLOAD_BODY_BYTES = 64 * 1024
private const val MAX_REGISTER_BODY_BYTES = 4 * 1024
private const val MAX_RECEIPT_BODY_BYTES = 64 * 1024

/** Maximum pull batch size a Gateway can request. */
private const val MAX_PULL_LIMIT = 100

/** Default pull batch size. */
private const val DEFAULT_PULL_LIMIT = 50

/**
 * Broker HTTP module.
 * @param gatewayApiKey Pre-shared key for Gateway authentication (Bearer token).
 *        If null, gateway endpoints are open (development only).
 */
fun Application.brokerModule(
    store: BrokerStore,
    gatewayApiKey: String? = System.getenv("RELAY_BROKER_GATEWAY_API_KEY")?.takeIf { it.isNotBlank() },
    uploadRateLimiter: SlidingWindowRateLimiter = SlidingWindowRateLimiter(maxRequests = 30, windowMillis = 60_000),
    pullRateLimiter: SlidingWindowRateLimiter = SlidingWindowRateLimiter(maxRequests = 120, windowMillis = 60_000),
) {
    install(ContentNegotiation) { json(brokerJson) }

    routing {
        /**
         * POST /v1/devices/register
         * Android registers its public key and receives an unguessable capability token.
         * Idempotent: re-registration returns existing token.
         */
        post("/v1/devices/register") {
            val contentLength = call.request.contentLength()
            if (contentLength == null) {
                call.respond(HttpStatusCode.LengthRequired, mapOf("reason" to "content_length_required"))
                return@post
            }
            if (contentLength > MAX_REGISTER_BODY_BYTES) {
                call.respond(HttpStatusCode.PayloadTooLarge, mapOf("reason" to "registration_too_large"))
                return@post
            }
            val request = try {
                call.receive<BrokerDeviceRegisterRequest>()
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("reason" to "malformed_request"))
                return@post
            }
            if (request.deviceKeyId.isBlank() || request.deviceKeyId.length > 128) {
                call.respond(HttpStatusCode.BadRequest, mapOf("reason" to "invalid_device_key_id"))
                return@post
            }
            val registrationKey = request.publicKeyBase64.decodeP256PublicKey()
            if (request.publicKeyBase64.isBlank() || request.publicKeyBase64.length > 1024 || registrationKey == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("reason" to "invalid_public_key"))
                return@post
            }
            if (!request.hasValidRegistrationProof(registrationKey)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("reason" to "invalid_registration_proof"))
                return@post
            }
            if (!uploadRateLimiter.allow("register:${request.deviceKeyId}")) {
                call.respond(HttpStatusCode.TooManyRequests, mapOf("reason" to "rate_limited"))
                return@post
            }
            val result = try {
                store.registerDevice(request.deviceKeyId, request.publicKeyBase64, System.currentTimeMillis())
            } catch (_: DeviceKeyConflictException) {
                // Never return an existing capability token to a caller presenting another key.
                call.respond(HttpStatusCode.Conflict, mapOf("reason" to "device_key_conflict"))
                return@post
            }
            call.respond(HttpStatusCode.OK, BrokerDeviceRegisterResponse(
                deviceKeyId = result.deviceKeyId,
                capabilityToken = result.capabilityToken,
            ))
        }

        /**
         * POST /v1/rescue/upload
         * Android uploads an encrypted envelope. Broker validates structure, verifies
         * upload signature against registered device key, deduplicates, and stores.
         * Never decrypts. hopCount is NOT modified.
         */
        post("/v1/rescue/upload") {
            // Size limit BEFORE reading body (check Content-Length header first)
            val contentLength = call.request.contentLength()
            if (contentLength == null) {
                call.respond(HttpStatusCode.LengthRequired, mapOf("reason" to "content_length_required"))
                return@post
            }
            if (contentLength > MAX_UPLOAD_BODY_BYTES) {
                call.respond(HttpStatusCode.PayloadTooLarge, mapOf("reason" to "envelope_too_large"))
                return@post
            }
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
            // Verify device is registered
            if (store.devicePublicKey(request.deviceKeyId) == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("reason" to "device_not_registered"))
                return@post
            }
            // Verify upload signature against registered public key
            if (!store.verifyUploadSignature(request.deviceKeyId, envelope, request.uploadSignatureBase64)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("reason" to "invalid_signature"))
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
                    // Idempotent: return the original stable acknowledgement, including its time.
                    call.respond(HttpStatusCode.OK, result.response)
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
         * PC Gateway pulls pending envelopes for its shelter. Requires Bearer auth.
         * Composite cursor (stored_at:envelope_id) prevents skip/dup.
         */
        get("/v1/gateways/{shelterId}/pull") {
            if (!authenticateGateway(gatewayApiKey)) return@get
            val shelterId = call.parameters["shelterId"]
            if (shelterId.isNullOrBlank() || shelterId.length > 128) {
                call.respond(HttpStatusCode.BadRequest, mapOf("reason" to "invalid_shelter_id"))
                return@get
            }
            if (!pullRateLimiter.allow("gateway:$shelterId")) {
                call.respond(HttpStatusCode.TooManyRequests, mapOf("reason" to "rate_limited"))
                return@get
            }
            val cursor = call.request.queryParameters["cursor"]
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_PULL_LIMIT)
                .coerceIn(1, MAX_PULL_LIMIT)
            val now = System.currentTimeMillis()
            val gatewayId = call.request.headers["X-Gateway-Id"] ?: shelterId
            val batch = store.pendingForShelter(shelterId, now, cursor, limit, gatewayId = gatewayId)
            call.respond(HttpStatusCode.OK, batch)
        }

        /**
         * POST /v1/gateways/{shelterId}/receipts
         * PC Gateway uploads a signed shelter receipt for relay back to the device.
         * Requires Bearer auth. Idempotent by receipt_id.
         * Returns 202 only when receipt is actually saved; 500 on save failure.
         */
        post("/v1/gateways/{shelterId}/receipts") {
            if (!authenticateGateway(gatewayApiKey)) return@post
            val contentLength = call.request.contentLength()
            if (contentLength == null) {
                call.respond(HttpStatusCode.LengthRequired, mapOf("reason" to "content_length_required"))
                return@post
            }
            if (contentLength > MAX_RECEIPT_BODY_BYTES) {
                call.respond(HttpStatusCode.PayloadTooLarge, mapOf("reason" to "receipt_too_large"))
                return@post
            }
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
            val saved = try {
                store.saveReceipt(shelterId, upload.receipt, now)
            } catch (e: IllegalStateException) {
                // Unknown envelope — Broker cannot store this receipt
                call.respond(HttpStatusCode.UnprocessableEntity, mapOf(
                    "reason" to (e.message ?: "save_failed"),
                ))
                return@post
            }
            call.respond(HttpStatusCode.Accepted, BrokerReceiptUploadResponse(
                accepted = true,
                reason = if (saved) null else "duplicate",
            ))
        }

        /**
         * GET /v1/receipts?sinceSeq=
         * Android polls for signed shelter receipts using an unguessable capability token.
         * The token is carried as Bearer auth so reverse-proxy access logs do not capture it.
         * Uses Broker monotonic seq cursor (not device time).
         */
        get("/v1/receipts") {
            val token = call.request.headers["Authorization"]
                ?.takeIf { it.startsWith("Bearer ") }
                ?.removePrefix("Bearer ")
                // Transitional fallback for already-deployed clients. New clients use the header.
                ?: call.request.queryParameters["token"]
            if (token.isNullOrBlank() || token.length > 128) {
                call.respond(HttpStatusCode.BadRequest, mapOf("reason" to "invalid_token"))
                return@get
            }
            if (!pullRateLimiter.allow("receipts:$token")) {
                call.respond(HttpStatusCode.TooManyRequests, mapOf("reason" to "rate_limited"))
                return@get
            }
            if (store.deviceForCapabilityToken(token) == null) {
                // Let the Android client distinguish an invalid/stale capability from a valid
                // account that simply has no receipts, so it can re-register and recover.
                call.respond(HttpStatusCode.Unauthorized, mapOf("reason" to "invalid_capability_token"))
                return@get
            }
            val sinceSeq = call.request.queryParameters["sinceSeq"]?.toLongOrNull() ?: 0L
            val batch = store.receiptsForDevice(token, sinceSeq)
            call.respond(HttpStatusCode.OK, batch)
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

private fun String.decodeP256PublicKey(): ECPublicKey? = runCatching {
    val key = KeyFactory.getInstance("EC").generatePublic(
        X509EncodedKeySpec(Base64.getDecoder().decode(this)),
    ) as ECPublicKey
    key.takeIf { it.params.matches(P256_PARAMETERS) }
}.getOrNull()

private val P256_PARAMETERS: ECParameterSpec by lazy {
    AlgorithmParameters.getInstance("EC").apply {
        init(ECGenParameterSpec("secp256r1"))
    }.getParameterSpec(ECParameterSpec::class.java)
}

private fun ECParameterSpec.matches(expected: ECParameterSpec): Boolean =
    curve.field == expected.curve.field &&
        curve.a == expected.curve.a &&
        curve.b == expected.curve.b &&
        generator == expected.generator &&
        order == expected.order &&
        cofactor == expected.cofactor

private fun BrokerDeviceRegisterRequest.hasValidRegistrationProof(publicKey: ECPublicKey): Boolean = runCatching {
    if (registrationSignatureBase64.isBlank() || registrationSignatureBase64.length > 1024) return@runCatching false
    val verifier = java.security.Signature.getInstance("SHA256withECDSA")
    verifier.initVerify(publicKey)
    verifier.update(brokerDeviceRegistrationBytes(deviceKeyId, publicKeyBase64))
    verifier.verify(Base64.getDecoder().decode(registrationSignatureBase64))
}.getOrDefault(false)

/**
 * Validates the Gateway Bearer token. Returns true if authenticated.
 * If gatewayApiKey is null (development mode), all requests pass.
 */
private suspend fun io.ktor.server.routing.RoutingContext.authenticateGateway(gatewayApiKey: String?): Boolean {
    if (gatewayApiKey == null) return true // development mode
    val authHeader = call.request.headers["Authorization"]
    if (authHeader != "Bearer $gatewayApiKey") {
        call.respond(HttpStatusCode.Unauthorized, mapOf("reason" to "invalid_gateway_credentials"))
        return false
    }
    return true
}
