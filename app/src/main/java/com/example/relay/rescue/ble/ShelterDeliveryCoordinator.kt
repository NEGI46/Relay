package com.example.relay.rescue.ble

import android.content.Context
import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.ReceiptApplicationResult
import com.example.relay.rescue.RegionalShelterDirectoryResolver
import com.example.relay.rescue.RescueEnvelopeRepository
import com.example.relay.rescue.RescueRequestKey
import com.example.relay.rescue.RescueSubmissionStatus
import com.example.relay.rescue.ShelterReceiptStatus
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
    private val deliveryIds: CourierDeliveryIdStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { encodeDefaults = true },
    private val sessionDeadlineMillis: Long = 30_000,
    private val maxEnvelopeBytes: Int = MAX_ENVELOPE_BYTES,
    private val onRepositoryChanged: suspend () -> Unit = {},
) {
    private val mutex = Mutex()
    private var job: Job? = null
    private val _state = MutableStateFlow<ShelterDeliveryState>(ShelterDeliveryState.Idle)
    val state: StateFlow<ShelterDeliveryState> = _state

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = ShelterDeliveryState.Scanning
            client.advertisements.collect { advertisement -> mutex.withLock { deliverTo(advertisement) } }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = ShelterDeliveryState.Idle
    }

    private suspend fun deliverTo(advertisement: ShelterAdvertisement) {
        try {
            withTimeout(sessionDeadlineMillis) {
                client.connect(advertisement).use { session ->
                    // The BLE bridge has no signed manifest characteristic. It returns the
                    // exact same compact identity it advertised; resolve that pair only in
                    // the already verified, locally provisioned directory.
                    val identity = session.readIdentity()
                    val manifest = directoryResolver.resolveBeaconIdentity(
                        identity.signedManifestFingerprint,
                        clock(),
                    ) ?: run {
                        _state.value = ShelterDeliveryState.WaitingToRetry("untrusted shelter advertisement")
                        return@withTimeout
                    }
                    if (!directoryResolver.verifyAdvertisedManifest(manifest, clock())) {
                        _state.value = ShelterDeliveryState.WaitingToRetry("untrusted shelter advertisement")
                        return@withTimeout
                    }
                    val candidate = repository.all().firstOrNull { record ->
                        record.state.submissionStatus in DELIVERABLE_STATUSES &&
                            record.envelope.destinationShelterId == manifest.manifest.shelterId &&
                            record.envelope.expiresAtEpochMillis > clock()
                    } ?: return@withTimeout
                    val encoded = json.encodeToString(EncryptedRescueEnvelope.serializer(), candidate.envelope).encodeToByteArray()
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
                        ReceiptApplicationResult.APPLIED -> {
                            onRepositoryChanged()
                            if (receipt.receipt.status.isTerminalDeliveryReceipt()) deliveryIds.remove(candidate.key)
                            _state.value = ShelterDeliveryState.Scanning
                        }
                        ReceiptApplicationResult.ALREADY_APPLIED -> {
                            if (receipt.receipt.status.isTerminalDeliveryReceipt()) deliveryIds.remove(candidate.key)
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

internal fun ShelterReceiptStatus.isTerminalDeliveryReceipt(): Boolean = this in setOf(
    ShelterReceiptStatus.COMPLETED,
    ShelterReceiptStatus.CANCELLED,
    ShelterReceiptStatus.REJECTED,
)

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
