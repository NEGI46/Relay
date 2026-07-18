package com.example.relay.data.repository

import com.example.relay.data.local.RelayDatabase
import com.example.relay.data.local.RescueEntity
import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.ReceiptApplicationResult
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueEnvelopeRepository
import com.example.relay.rescue.RescueEnvelopeState
import com.example.relay.rescue.RescuePublicKey
import com.example.relay.rescue.RescueRequestKey
import com.example.relay.rescue.RescueStoreRejection
import com.example.relay.rescue.RescueStoreResult
import com.example.relay.rescue.RescueSubmissionStatus
import com.example.relay.rescue.RescueValidationResult
import com.example.relay.rescue.ShelterReceiptStatus
import com.example.relay.rescue.SignedShelterReceipt
import com.example.relay.rescue.StoredRescueRecord
import com.example.relay.rescue.forwardRescueEnvelope
import com.example.relay.rescue.storageSizeBytes
import com.example.relay.rescue.validate
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Room-backed encrypted rescue store. Its API is synchronous to match [RescueEnvelopeRepository],
 * so every method must be called off Android's main thread.
 */
class RoomRescueEnvelopeRepository(
    private val database: RelayDatabase,
    private val maxRecordCount: Int = 256,
    private val maxStoredBytes: Long = 8L * 1_048_576,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
) : RescueEnvelopeRepository {
    private val dao = database.rescueDao()

    init {
        require(maxRecordCount > 0)
        require(maxStoredBytes > 0)
    }

    override fun store(
        envelope: EncryptedRescueEnvelope,
        receivedAtEpochMillis: Long,
    ): RescueStoreResult {
        if (envelope.validate() != RescueValidationResult.Valid ||
            !RescueCryptography.verifyEnvelopeFraming(envelope) ||
            receivedAtEpochMillis <= 0
        ) {
            return RescueStoreResult.Rejected(RescueStoreRejection.INVALID_ENVELOPE)
        }
        if (envelope.expiresAtEpochMillis <= receivedAtEpochMillis) {
            return RescueStoreResult.Rejected(RescueStoreRejection.EXPIRED)
        }
        val sizeBytes = envelope.storageSizeBytes()
        if (sizeBytes > maxStoredBytes) {
            return RescueStoreResult.Rejected(RescueStoreRejection.EXCEEDS_BYTE_LIMIT)
        }

        return database.runInTransaction<RescueStoreResult> {
            dao.deleteExpired(receivedAtEpochMillis)
            val key = RescueRequestKey(envelope.requestId, envelope.requestVersion)
            dao.find(key.requestId, key.requestVersion)?.let { existing ->
                return@runInTransaction RescueStoreResult.Rejected(
                    if (existing.ciphertextSha256Hex == envelope.ciphertextSha256Hex) {
                        RescueStoreRejection.DUPLICATE
                    } else {
                        RescueStoreRejection.COLLISION
                    },
                )
            }
            if (dao.hasNewerVersion(key.requestId, key.requestVersion)) {
                return@runInTransaction RescueStoreResult.Rejected(
                    RescueStoreRejection.SUPERSEDED_BY_NEWER_VERSION,
                )
            }

            val superseded = dao.olderVersions(key.requestId, key.requestVersion).map { it.key() }
            dao.deleteOlderVersions(key.requestId, key.requestVersion)
            val record = StoredRescueRecord(envelope, RescueEnvelopeState(receivedAtEpochMillis))
            dao.insert(record.toEntity(sizeBytes))
            val pruned = pruneForCapacity(key)
            RescueStoreResult.Stored(record, superseded + pruned)
        }
    }

    override fun get(key: RescueRequestKey): StoredRescueRecord? =
        dao.find(key.requestId, key.requestVersion)?.toRecordOrNull()

    override fun all(): List<StoredRescueRecord> = dao.all().mapNotNull { it.toRecordOrNull() }

    override fun prepareForExport(
        key: RescueRequestKey,
        nowEpochMillis: Long,
    ): EncryptedRescueEnvelope? = database.runInTransaction<EncryptedRescueEnvelope?> {
        val current = dao.find(key.requestId, key.requestVersion)?.toRecordOrNull()
            ?: return@runInTransaction null
        if (nowEpochMillis >= current.envelope.expiresAtEpochMillis) return@runInTransaction null
        forwardRescueEnvelope(current.envelope)
    }

    override fun recordSuccessfulExport(key: RescueRequestKey, exportedHopCount: Int): Boolean =
        database.runInTransaction<Boolean> {
            val entity = dao.find(key.requestId, key.requestVersion) ?: return@runInTransaction false
            val current = entity.toRecordOrNull() ?: return@runInTransaction false
            if (exportedHopCount != current.envelope.hopCount + 1 ||
                exportedHopCount > current.envelope.maxHopCount
            ) {
                return@runInTransaction false
            }
            val updated = current.copy(
                envelope = current.envelope.copy(hopCount = exportedHopCount),
                state = current.state.copy(
                    submissionStatus = RescueSubmissionStatus.IN_TRANSIT,
                    submissionCount = current.state.submissionCount + 1,
                ),
            )
            dao.update(updated.toEntity(updated.envelope.storageSizeBytes())) == 1
        }

    override fun applyReceipt(
        key: RescueRequestKey,
        signedReceipt: SignedShelterReceipt,
        shelterSigningPublicKey: RescuePublicKey,
    ): ReceiptApplicationResult = database.runInTransaction<ReceiptApplicationResult> {
        val entity = dao.find(key.requestId, key.requestVersion)
            ?: return@runInTransaction ReceiptApplicationResult.RECORD_NOT_FOUND
        val current = entity.toRecordOrNull()
            ?: return@runInTransaction ReceiptApplicationResult.RECORD_NOT_FOUND
        if (current.state.signedReceipt != null) {
            return@runInTransaction ReceiptApplicationResult.ALREADY_APPLIED
        }
        if (!RescueCryptography.verifyReceipt(signedReceipt, shelterSigningPublicKey)) {
            return@runInTransaction ReceiptApplicationResult.INVALID_SIGNATURE
        }
        val receipt = signedReceipt.receipt
        val envelope = current.envelope
        if (receipt.envelopeId != envelope.envelopeId ||
            receipt.requestId != envelope.requestId ||
            receipt.requestVersion != envelope.requestVersion ||
            receipt.ciphertextSha256Hex != envelope.ciphertextSha256Hex ||
            receipt.shelterId != envelope.destinationShelterId
        ) {
            return@runInTransaction ReceiptApplicationResult.RECEIPT_MISMATCH
        }
        val status = when (receipt.status) {
            ShelterReceiptStatus.STORED -> RescueSubmissionStatus.SHELTER_STORED
            ShelterReceiptStatus.ACCEPTED -> RescueSubmissionStatus.SHELTER_ACCEPTED
            ShelterReceiptStatus.REJECTED -> RescueSubmissionStatus.SHELTER_REJECTED
        }
        val updated = current.copy(
            state = current.state.copy(submissionStatus = status, signedReceipt = signedReceipt),
        )
        if (dao.update(updated.toEntity(updated.envelope.storageSizeBytes())) == 1) {
            ReceiptApplicationResult.APPLIED
        } else {
            ReceiptApplicationResult.RECORD_NOT_FOUND
        }
    }

    private fun pruneForCapacity(protectedKey: RescueRequestKey): List<RescueRequestKey> {
        val pruned = mutableListOf<RescueRequestKey>()
        while (dao.count() > maxRecordCount || dao.totalStorageSizeBytes() > maxStoredBytes) {
            val victim = dao.pruningCandidate(protectedKey.requestId, protectedKey.requestVersion)
                ?: error("Incoming rescue envelope was checked against capacity before insertion")
            dao.delete(victim.requestId, victim.requestVersion)
            pruned += victim.key()
        }
        return pruned
    }

    private fun StoredRescueRecord.toEntity(sizeBytes: Long) = RescueEntity(
        requestId = envelope.requestId,
        requestVersion = envelope.requestVersion,
        envelopeId = envelope.envelopeId,
        ciphertextSha256Hex = envelope.ciphertextSha256Hex,
        createdAtEpochMillis = envelope.createdAtEpochMillis,
        expiresAtEpochMillis = envelope.expiresAtEpochMillis,
        storageSizeBytes = sizeBytes,
        envelopeJson = json.encodeToString(envelope),
        receivedAtEpochMillis = state.receivedAtEpochMillis,
        submissionStatus = state.submissionStatus.name,
        submissionCount = state.submissionCount,
        signedReceiptJson = state.signedReceipt?.let { json.encodeToString(it) },
    )

    private fun RescueEntity.toRecordOrNull(): StoredRescueRecord? = runCatching {
        val envelope = json.decodeFromString<EncryptedRescueEnvelope>(envelopeJson)
        check(envelope.requestId == requestId && envelope.requestVersion == requestVersion)
        check(envelope.envelopeId == envelopeId && envelope.ciphertextSha256Hex == ciphertextSha256Hex)
        check(envelope.createdAtEpochMillis == createdAtEpochMillis)
        check(envelope.expiresAtEpochMillis == expiresAtEpochMillis)
        check(envelope.storageSizeBytes() == storageSizeBytes)
        check(envelope.validate() == RescueValidationResult.Valid)
        check(RescueCryptography.verifyEnvelopeFraming(envelope))
        check(receivedAtEpochMillis > 0 && submissionCount >= 0)
        val receipt = signedReceiptJson?.let { json.decodeFromString<SignedShelterReceipt>(it) }
        check(receipt == null || receipt.validate() == RescueValidationResult.Valid)
        check(
            receipt == null ||
                receipt.receipt.envelopeId == envelope.envelopeId &&
                receipt.receipt.requestId == envelope.requestId &&
                receipt.receipt.requestVersion == envelope.requestVersion &&
                receipt.receipt.ciphertextSha256Hex == envelope.ciphertextSha256Hex &&
                receipt.receipt.shelterId == envelope.destinationShelterId,
        )
        val status = RescueSubmissionStatus.valueOf(submissionStatus)
        val receiptStatus = receipt?.receipt?.status?.let {
            when (it) {
                ShelterReceiptStatus.STORED -> RescueSubmissionStatus.SHELTER_STORED
                ShelterReceiptStatus.ACCEPTED -> RescueSubmissionStatus.SHELTER_ACCEPTED
                ShelterReceiptStatus.REJECTED -> RescueSubmissionStatus.SHELTER_REJECTED
            }
        }
        check(receiptStatus == null || receiptStatus == status)
        check(receipt != null || status !in receiptOnlyStatuses)
        StoredRescueRecord(
            envelope = envelope,
            state = RescueEnvelopeState(
                receivedAtEpochMillis = receivedAtEpochMillis,
                submissionStatus = status,
                submissionCount = submissionCount,
                signedReceipt = receipt,
            ),
        )
    }.getOrNull()

    private fun RescueEntity.key() = RescueRequestKey(requestId, requestVersion)

    private companion object {
        val receiptOnlyStatuses = setOf(
            RescueSubmissionStatus.SHELTER_STORED,
            RescueSubmissionStatus.SHELTER_ACCEPTED,
            RescueSubmissionStatus.SHELTER_REJECTED,
        )
    }
}
