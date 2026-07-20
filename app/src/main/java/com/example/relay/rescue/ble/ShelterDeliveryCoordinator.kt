package com.example.relay.rescue.ble

import android.content.Context
import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.ReceiptApplicationResult
import com.example.relay.rescue.RegionalShelterDirectoryResolver
import com.example.relay.rescue.RescueEnvelopeRepository
import com.example.relay.rescue.RescueRequestKey
import com.example.relay.rescue.RescueSubmissionStatus
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed interface ShelterDeliveryState {
    data object Idle : ShelterDeliveryState
    data object Scanning : ShelterDeliveryState
    data class Delivering(val shelterId: String) : ShelterDeliveryState
    data class WaitingToRetry(val reason: String) : ShelterDeliveryState
}

/**
 * Automatic courier delivery. It never reads rescue plaintext and it never
 * consumes a mesh hop: only a verified PC receipt changes submission state.
 */
class ShelterDeliveryCoordinator(
    private val client: ShelterBleClient,
    private val repository: RescueEnvelopeRepository,
    private val directoryResolver: RegionalShelterDirectoryResolver,
    private val carrierId: String,
private suspend fun deliverTo(advertisement: ShelterAdvertisement) {
    try {
        withTimeout(sessionDeadlineMillis) {
            client.connect(advertisement).use { session ->
                val manifest = resolveAndVerifyManifest(session) ?: return@withTimeout
                val candidate = findDeliverableCandidate(manifest) ?: return@withTimeout
                val encoded = encodeEnvelope(candidate.envelope)

                // The rest of the delivery logic continues here...
            }
        }
    } catch (e: TimeoutCancellationException) {
        _state.value = ShelterDeliveryState.Failure("delivery session timed out")
    } catch (e: Exception) {
        _state.value = ShelterDeliveryState.Failure(e.message ?: "unknown error")
    }
}

private suspend fun resolveAndVerifyManifest(session: ShelterSession): Manifest? {
    val identity = session.readIdentity()
    val manifest = directoryResolver.resolveBeaconIdentity(
        identity.shelterIdHash,
        identity.signedManifestFingerprint,
        clock(),
    ) ?: run {
        _state.value = ShelterDeliveryState.WaitingToRetry("untrusted shelter advertisement")
        return null
    }
    if (!directoryResolver.verifyAdvertisedManifest(manifest, clock())) {
        _state.value = ShelterDeliveryState.WaitingToRetry("untrusted shelter advertisement")
        return null
    }
    return manifest
}

private fun findDeliverableCandidate(manifest: Manifest): RescueRecord? =
    repository.all().firstOrNull { record ->
        record.state.submissionStatus in DELIVERABLE_STATUSES &&
            record.envelope.destinationShelterId == manifest.manifest.shelterId &&
            record.envelope.expiresAtEpochMillis > clock()
    }

private fun encodeEnvelope(envelope: EncryptedRescueEnvelope): ByteArray =
    json.encodeToString(EncryptedRescueEnvelope.serializer(), envelope)
        .encodeToByteArray()
                    if (encoded.size !in 1..maxEnvelopeBytes) {
                        _state.value = ShelterDeliveryState.WaitingToRetry("encrypted envelope exceeds BLE limit")
                        return@withTimeout
                    }
                    _state.value = ShelterDeliveryState.Delivering(manifest.manifest.shelterId)
                    val keys = directoryResolver.resolveForEnvelope(
                        manifest.regionId, manifest.manifest.shelterId, candidate.envelope.recipientKeyId, clock(),
                    ) ?: run {
                        _state.value = ShelterDeliveryState.WaitingToRetry("shelter key does not match envelope")
                        return@withTimeout
                    }
                    val receipt = when (val result = session.submit(
                        RescueBleSubmission(deliveryIds.idFor(candidate.key), carrierId, encoded),
                    )) {
                        is RescueBleSubmissionResult.Accepted -> result.receipt
                        is RescueBleSubmissionResult.Duplicate -> result.receipt
                        is RescueBleSubmissionResult.Rejected -> {
                            _state.value = ShelterDeliveryState.WaitingToRetry("shelter rejected delivery")
                            return@withTimeout
                        }
                    }
                    when (repository.applyReceipt(candidate.key, receipt, keys.receiptSigningPublicKey)) {
                        ReceiptApplicationResult.APPLIED, ReceiptApplicationResult.ALREADY_APPLIED -> {
                            deliveryIds.remove(candidate.key)
                            _state.value = ShelterDeliveryState.Scanning
                        }
                        else -> _state.value = ShelterDeliveryState.WaitingToRetry("invalid shelter receipt")
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            _state.value = ShelterDeliveryState.WaitingToRetry("BLE delivery timed out")
        } catch (_: Exception) {
            // Preserve the encrypted record and replay the same idempotency key next time.
            _state.value = ShelterDeliveryState.WaitingToRetry("BLE shelter unavailable")
        }
    }

    private companion object {
        const val MAX_ENVELOPE_BYTES = 16 * 1024
        val DELIVERABLE_STATUSES = setOf(
            RescueSubmissionStatus.PENDING,
            RescueSubmissionStatus.IN_TRANSIT,
            RescueSubmissionStatus.SHELTER_STORED,
            RescueSubmissionStatus.SHELTER_ACCEPTED,
            RescueSubmissionStatus.SHELTER_RESPONDING,
        )
    }
}

/** Durable per-request idempotency key, replayed after a disconnect or process restart. */
interface CourierDeliveryIdStore {
    fun idFor(key: RescueRequestKey): String
    fun remove(key: RescueRequestKey)
}

class SharedPreferencesCourierDeliveryIdStore(context: Context) : CourierDeliveryIdStore {
    private val preferences = context.applicationContext.getSharedPreferences("relay_rescue_delivery_ids", Context.MODE_PRIVATE)
    override fun idFor(key: RescueRequestKey): String {
        val storageKey = "${key.requestId}:${key.requestVersion}"
        return preferences.getString(storageKey, null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(storageKey, it).commit()
        }
    }
    override fun remove(key: RescueRequestKey) {
        preferences.edit().remove("${key.requestId}:${key.requestVersion}").apply()
    }
}
