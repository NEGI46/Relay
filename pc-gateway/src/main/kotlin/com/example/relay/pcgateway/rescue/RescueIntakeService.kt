package com.example.relay.pcgateway.rescue

import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueKeyAlgorithm
import com.example.relay.rescue.RescuePrivateKey
import com.example.relay.rescue.RescueCondition
import com.example.relay.rescue.RescueRequestAction
import com.example.relay.rescue.RescueValidationResult
import com.example.relay.rescue.ShelterReceiptStatus
import com.example.relay.rescue.SignedShelterReceipt
import com.example.relay.rescue.UnsignedShelterReceipt
import com.example.relay.rescue.validate
import java.util.UUID

fun interface RescueClock {
    fun nowEpochMillis(): Long
}

fun interface RescueIdGenerator {
    fun nextId(): String
}

class RescueIntakeService(
    private val shelterId: String,
    private val recipientPrivateKey: RescuePrivateKey,
    private val shelterSigningPrivateKey: RescuePrivateKey,
    private val persistence: RescuePersistence = InMemoryRescuePersistence(),
    private val clock: RescueClock = RescueClock(System::currentTimeMillis),
    private val idGenerator: RescueIdGenerator = RescueIdGenerator { UUID.randomUUID().toString() },
) {
    init {
        require(isIdentifier(shelterId)) { "invalid shelter id" }
        require(recipientPrivateKey.algorithm == RescueKeyAlgorithm.RSA_OAEP_SHA256) {
            "recipient key must be RSA-OAEP-SHA256"
        }
        require(shelterSigningPrivateKey.algorithm == RescueKeyAlgorithm.ECDSA_P256_SHA256) {
            "signing key must be ECDSA-P256-SHA256"
        }
        RescueCryptography.importPrivateKey(
            recipientPrivateKey.keyId,
            recipientPrivateKey.algorithm,
            recipientPrivateKey.encodedBase64,
        )
        RescueCryptography.importPrivateKey(
            shelterSigningPrivateKey.keyId,
            shelterSigningPrivateKey.algorithm,
            shelterSigningPrivateKey.encodedBase64,
        )
    }

    /**
     * [carrierId] must come from the authenticated transport principal when HTTP wiring is added.
     * This method deliberately does not log envelopes or decrypted payloads.
     */
    @Synchronized
    fun ingest(
        envelope: EncryptedRescueEnvelope,
        carrierId: String,
        courierDeliveryId: String = "legacy:${envelope.envelopeId}:$carrierId",
    ): RescueIngestResult {
        if (!isIdentifier(carrierId)) return RescueIngestResult.Rejected(RescueRejectionCode.INVALID_CARRIER)
        if (!isDeliveryIdentifier(courierDeliveryId)) return RescueIngestResult.Rejected(RescueRejectionCode.INVALID_DELIVERY_ID)
        if (envelope.validate() != RescueValidationResult.Valid) {
            return RescueIngestResult.Rejected(RescueRejectionCode.INVALID_ENVELOPE)
        }
        if (envelope.destinationShelterId != shelterId) {
            return RescueIngestResult.Rejected(RescueRejectionCode.WRONG_SHELTER)
        }
        if (envelope.recipientKeyId != recipientPrivateKey.keyId) {
            return RescueIngestResult.Rejected(RescueRejectionCode.WRONG_RECIPIENT_KEY)
        }

        val now = clock.nowEpochMillis()
        if (envelope.expiresAtEpochMillis <= now) {
            return RescueIngestResult.Rejected(RescueRejectionCode.EXPIRED)
        }

        val payload = try {
            RescueCryptography.decrypt(envelope, recipientPrivateKey)
        } catch (_: Exception) {
            return RescueIngestResult.Rejected(RescueRejectionCode.CORRUPT_OR_UNDECRYPTABLE)
        }

        val key = RescueRequestKey(envelope.requestId, envelope.requestVersion)
        return persistence.transaction {
            val existing = find(key)
            if (existing != null && existing.envelopeHash != envelope.ciphertextSha256Hex) {
                val collision = QuarantinedRescueEnvelope(
                    key = key,
                    envelopeId = envelope.envelopeId,
                    claimedEnvelopeHash = envelope.ciphertextSha256Hex,
                    existingEnvelopeHash = existing.envelopeHash,
                    carrierId = carrierId,
                    quarantinedAtEpochMillis = now,
                )
                quarantine(collision)
                return@transaction RescueIngestResult.Quarantined(collision)
            }

            if (existing != null) {
                val carrierWasNew = carrierId !in existing.carrierIds
                val deliveryWasNew = courierDeliveryId !in existing.deliveryIds
                val updated = if (carrierWasNew || deliveryWasNew) {
                    existing.copy(
                        carrierIds = existing.carrierIds + carrierId,
                        deliveryIds = existing.deliveryIds + courierDeliveryId,
                    ).also(::replace)
                } else existing
                return@transaction RescueIngestResult.Duplicate(updated, carrierWasNew, deliveryWasNew)
            }

            val request = StoredRescueRequest(
                key = key,
                envelopeHash = envelope.ciphertextSha256Hex,
                envelope = envelope,
                payload = payload,
                receivedAtEpochMillis = now,
                responseStatus = if (payload.action == RescueRequestAction.CANCELLED) {
                    RescueResponseStatus.COMPLETED
                } else {
                    RescueResponseStatus.UNCONFIRMED
                },
                carrierIds = setOf(carrierId),
                deliveryIds = setOf(courierDeliveryId),
                receipt = signReceipt(envelope, now),
                statusUpdatedAtEpochMillis = now,
                terminalAtEpochMillis = now.takeIf { payload.action == RescueRequestAction.CANCELLED },
            )
            insert(request)
            RescueIngestResult.Accepted(request)
        }
    }

    @Synchronized
    fun list(latestOnly: Boolean = true): List<RescueRequestSummary> {
        val all = persistence.transaction { listAll() }
        val latestVersions = all.groupBy { it.key.requestId }
            .mapValues { (_, versions) -> versions.maxOf { it.key.requestVersion } }
        return all.asSequence()
            .filter { !latestOnly || latestVersions[it.key.requestId] == it.key.requestVersion }
            .sortedWith(compareByDescending<StoredRescueRequest> { it.receivedAtEpochMillis }
                .thenByDescending { it.key.requestVersion })
            .map { request ->
                RescueRequestSummary(
                    requestId = request.key.requestId,
                    requestVersion = request.key.requestVersion,
                    urgency = request.payload.urgency,
                    personCount = request.payload.personCount,
                    receivedAtEpochMillis = request.receivedAtEpochMillis,
                    expiresAtEpochMillis = request.payload.expiresAtEpochMillis,
                    responseStatus = request.responseStatus,
                    uniqueCarrierCount = request.uniqueCarrierCount,
                    isLatestVersion = latestVersions[request.key.requestId] == request.key.requestVersion,
                    assignedNodeId = request.assignedNodeId,
                    statusUpdatedAtEpochMillis = request.statusUpdatedAtEpochMillis,
                    isLifeThreatening = request.payload.urgency == com.example.relay.rescue.RescueUrgency.IMMEDIATE ||
                        RescueCondition.LIFE_THREATENING in request.payload.conditions,
                    isCancelled = request.payload.action == RescueRequestAction.CANCELLED,
                )
            }
            .toList()
    }

    @Synchronized
    fun detail(requestId: String, requestVersion: Int? = null): StoredRescueRequest? {
        return persistence.transaction {
            val version = requestVersion ?: latestVersion(requestId) ?: return@transaction null
            find(RescueRequestKey(requestId, version))
        }
    }

    @Synchronized
    fun updateStatus(
        requestId: String,
        status: RescueResponseStatus,
        operatorNodeId: String = shelterId,
        requestVersion: Int? = null,
    ): RescueStatusUpdateResult {
        if (!isIdentifier(operatorNodeId)) return RescueStatusUpdateResult.NotFound
        return persistence.transaction {
            val version = requestVersion ?: latestVersion(requestId)
                ?: return@transaction RescueStatusUpdateResult.NotFound
            val current = find(RescueRequestKey(requestId, version))
                ?: return@transaction RescueStatusUpdateResult.NotFound
            if (current.assignedNodeId != null && current.assignedNodeId != operatorNodeId) {
                return@transaction RescueStatusUpdateResult.AssignedElsewhere(current.assignedNodeId)
            }
            if (status != current.responseStatus && status !in allowedNextStatuses.getValue(current.responseStatus)) {
                return@transaction RescueStatusUpdateResult.InvalidTransition(current.responseStatus, status)
            }
            val now = clock.nowEpochMillis()
            val updated = if (status == current.responseStatus && current.assignedNodeId != null) {
                current
            } else {
                current.copy(
                    responseStatus = status,
                    assignedNodeId = current.assignedNodeId ?: operatorNodeId.takeIf {
                        status != RescueResponseStatus.UNCONFIRMED
                    },
                    statusUpdatedAtEpochMillis = now,
                    terminalAtEpochMillis = now.takeIf { status in terminalStatuses },
                ).also(::replace)
            }
            RescueStatusUpdateResult.Updated(updated)
        }
    }

    /** Deletes terminal request plaintext after the v1 30-day retention period. */
    @Synchronized
    fun purgeExpiredDetails(retentionMillis: Long = 30L * 24 * 60 * 60 * 1_000): Int =
        persistence.transaction { deleteTerminalBefore(clock.nowEpochMillis() - retentionMillis) }

    @Synchronized
    fun receipt(requestId: String, requestVersion: Int? = null): SignedShelterReceipt? =
        detail(requestId, requestVersion)?.receipt

    @Synchronized
    fun listQuarantined(): List<QuarantinedRescueEnvelope> = persistence.transaction { listQuarantined() }

    private fun signReceipt(envelope: EncryptedRescueEnvelope, receivedAt: Long): SignedShelterReceipt =
        RescueCryptography.signReceipt(
            UnsignedShelterReceipt(
                receiptId = idGenerator.nextId(),
                envelopeId = envelope.envelopeId,
                requestId = envelope.requestId,
                requestVersion = envelope.requestVersion,
                ciphertextSha256Hex = envelope.ciphertextSha256Hex,
                shelterId = shelterId,
                receivedAtEpochMillis = receivedAt,
                status = ShelterReceiptStatus.STORED,
            ),
            shelterSigningPrivateKey,
        )

    private fun isIdentifier(value: String): Boolean = value.length in 1..128 &&
        value.all { it.isLetterOrDigit() || it in "-_.:" }

    private fun isDeliveryIdentifier(value: String): Boolean = value.length in 1..256 &&
        value.all { it.isLetterOrDigit() || it in "-_.:" }

    private companion object {
        val terminalStatuses = setOf(
            RescueResponseStatus.COMPLETED,
            RescueResponseStatus.UNABLE,
            RescueResponseStatus.DUPLICATE,
        )
        val allowedNextStatuses = mapOf(
            RescueResponseStatus.UNCONFIRMED to setOf(
                RescueResponseStatus.CONFIRMED,
                RescueResponseStatus.UNABLE,
                RescueResponseStatus.DUPLICATE,
            ),
            RescueResponseStatus.CONFIRMED to setOf(
                RescueResponseStatus.PREPARING,
                RescueResponseStatus.RESCUE_REQUESTED,
                RescueResponseStatus.UNABLE,
                RescueResponseStatus.DUPLICATE,
            ),
            RescueResponseStatus.PREPARING to setOf(
                RescueResponseStatus.RESCUE_REQUESTED,
                RescueResponseStatus.RESPONDING,
                RescueResponseStatus.UNABLE,
            ),
            RescueResponseStatus.RESCUE_REQUESTED to setOf(
                RescueResponseStatus.RESPONDING,
                RescueResponseStatus.UNABLE,
            ),
            RescueResponseStatus.RESPONDING to setOf(
                RescueResponseStatus.COMPLETED,
                RescueResponseStatus.UNABLE,
            ),
            RescueResponseStatus.COMPLETED to emptySet(),
            RescueResponseStatus.UNABLE to emptySet(),
            RescueResponseStatus.DUPLICATE to emptySet(),
        )
    }
}
