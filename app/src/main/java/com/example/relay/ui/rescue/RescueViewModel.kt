package com.example.relay.ui.rescue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.relay.location.LocationProvider
import com.example.relay.rescue.CourierRescuePresenter
import com.example.relay.rescue.RescueCondition
import com.example.relay.rescue.RescueCreationResult
import com.example.relay.rescue.RescueEnvelopeRepository
import com.example.relay.rescue.RescueLocation
import com.example.relay.rescue.RescueRequestAction
import com.example.relay.rescue.RescueRequestCreator
import com.example.relay.rescue.RescueRequestDraft
import com.example.relay.rescue.RescueSubmissionStatus
import com.example.relay.rescue.RescueUrgency
import com.example.relay.rescue.ShelterPublicKeyProvider
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RescueViewModel(
    private val repository: RescueEnvelopeRepository,
    private val shelterKeyProvider: ShelterPublicKeyProvider,
    /** Starts persistent automatic transport after a request is stored. */
    private val onRescueAutomationRequired: () -> Unit = {},
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    /** Non-null in production. A missing/disabled fix fails closed for rescue requests. */
    private val locationProvider: LocationProvider? = null,
    private val senderDeviceId: String = "request-${UUID.randomUUID()}",
    /** Debug local-Gateway enrollment may still be in flight when a member opens the form. */
    private val shelterKeyWaitMillis: Long = 0,
    private val trackingIntervalMillis: Long = 60_000,
) : ViewModel(), RescueCallbacks {
    private val creator = RescueRequestCreator(repository)
    private val presenter = CourierRescuePresenter(repository)
    private val _state = MutableStateFlow(RescueUiState(draft = newDraft()))
    val state: StateFlow<RescueUiState> = _state.asStateFlow()
    private var activeDraft: RescueRequestDraft? = null
    private var trackingJob: Job? = null

    init { refreshCourierItems() }

    override fun onNavigate(screen: RescueScreen) {
        if (screen == RescueScreen.COURIER_INVENTORY) refreshCourierItems()
        if (screen == RescueScreen.HOME || screen == RescueScreen.BROADCASTING) onRefreshStatus()
        _state.update { current ->
            current.copy(screen = screen, draft = current.draft ?: newDraft(), formMessage = null)
        }
    }

    override fun onDraftChange(draft: RescueRequestDraft) {
        _state.update { it.copy(draft = draft, formMessage = null) }
    }

    override fun onSubmitRequest() {
        val draft = (_state.value.draft ?: return).withInferredConditions()
        if (draft.personCount !in 1..1_000) {
            _state.update { it.copy(formMessage = "助けが必要な人数を入力してください。") }
            return
        }
        if (draft.conditions.isEmpty()) {
            _state.update { it.copy(formMessage = "現在の状態を1つ以上選んでください。") }
            return
        }
        submit(draft.withLegacyConditionFlags(), isSos = false)
    }

    override fun onSendSos() {
        val base = _state.value.draft ?: newDraft()
        submit(
            base.copy(
                urgency = RescueUrgency.IMMEDIATE,
                personCount = 0,
                conditions = setOf(RescueCondition.LIFE_THREATENING),
                seriouslyInjured = true,
                medicalSupportRequired = true,
                action = RescueRequestAction.ACTIVE,
            ),
            isSos = true,
        )
    }

    override fun onPrepareUpdate() {
        val current = activeDraft ?: return
        val now = nowEpochMillis()
        _state.update {
            it.copy(
                screen = RescueScreen.REQUEST_FORM,
                draft = current.copy(
                    requestVersion = current.requestVersion + 1,
                    createdAtEpochMillis = now,
                    expiresAtEpochMillis = now + REQUEST_LIFETIME_MILLIS,
                    action = RescueRequestAction.ACTIVE,
                ),
                formMessage = "変更内容を確認して送信してください。",
            )
        }
    }

    override fun onCancelRequest() {
        val current = activeDraft ?: return
        val now = nowEpochMillis()
        submit(
            current.copy(
                requestVersion = current.requestVersion + 1,
                createdAtEpochMillis = now,
                expiresAtEpochMillis = now + REQUEST_LIFETIME_MILLIS,
                action = RescueRequestAction.CANCELLED,
            ),
            isSos = current.urgency == RescueUrgency.IMMEDIATE,
        )
    }

    override fun onRefreshStatus() {
        val own = _state.value.ownRequest ?: return
        val latest = repository.all()
            .filter { it.envelope.requestId == own.requestId }
            .maxByOrNull { it.envelope.requestVersion }
            ?: return
        _state.update {
            it.copy(ownRequest = it.ownRequest?.copy(submissionStatus = latest.state.submissionStatus))
        }
    }

    override fun onStopBroadcasting() {
        _state.update { current -> current.copy(screen = RescueScreen.HOME) }
    }

    private fun submit(source: RescueRequestDraft, isSos: Boolean) {
        if (_state.value.isRequestSubmitting) return
        _state.update { it.copy(isRequestSubmitting = true, formMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val keys = awaitShelterKeys()
            if (keys == null) {
                failSubmission("府中町の救助受信情報を取得できません。通信状態を確認してください。")
                return@launch
            }
            val located = attachCurrentLocation(source.copy(destinationShelterId = keys.shelterId))
            if (located == null) {
                failSubmission("GPS位置を取得できません。位置情報をONにして、空が見える場所で再試行してください。")
                return@launch
            }
            val result = runCatching {
                creator.create(located, keys.recipientKey, UUID.randomUUID().toString())
            }.getOrElse {
                failSubmission("救助要請を端末に保存できませんでした。")
                return@launch
            }
            when (result) {
                is RescueCreationResult.Stored -> {
                    activeDraft = located
                    val record = result.record
                    _state.update { current ->
                        current.copy(
                            screen = RescueScreen.BROADCASTING,
                            isRequestSubmitting = false,
                            draft = newDraft(keys.shelterId),
                            ownRequest = located.toOwnRequest(record.state.submissionStatus),
                            broadcast = RescueBroadcastUiState(
                                isActive = located.action == RescueRequestAction.ACTIVE,
                                transferCount = record.state.submissionCount,
                                statusMessage = when {
                                    located.action == RescueRequestAction.CANCELLED -> "取消情報を自動で届けています。"
                                    isSos -> "命の危険があるSOSを最優先で自動送信しています。"
                                    else -> "救助要請を自動送信しています。操作は不要です。"
                                },
                            ),
                            courierAutomation = CourierAutomationUiState(
                                isEnabled = true,
                                statusMessage = "受信・中継・避難所への提出は自動です",
                            ),
                        )
                    }
                    onRescueAutomationRequired()
                    if (located.action == RescueRequestAction.CANCELLED) {
                        trackingJob?.cancel()
                    } else {
                        startLocationTracking()
                    }
                    refreshCourierItemsNow()
                }
                is RescueCreationResult.NotStored -> failSubmission("保存できませんでした: ${result.reason.name}")
            }
        }
    }

    private suspend fun attachCurrentLocation(draft: RescueRequestDraft): RescueRequestDraft? {
        val provider = locationProvider ?: return draft
        val fix = runCatching { provider.currentFix(8_000) }.getOrNull() ?: return null
        return draft.copy(
            location = RescueLocation(
                latitude = fix.latitude,
                longitude = fix.longitude,
                accuracyMeters = fix.accuracyMeters,
                description = draft.location?.description.orEmpty(),
                capturedAtEpochMillis = fix.capturedAtEpochMillis,
            ),
        )
    }

    private fun startLocationTracking() {
        if (locationProvider == null) return
        trackingJob?.cancel()
        trackingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(trackingIntervalMillis.coerceAtLeast(10_000))
                val current = activeDraft ?: break
                if (current.action == RescueRequestAction.CANCELLED) break
                val updated = attachCurrentLocation(current) ?: continue
                if (!locationChanged(current.location, updated.location)) continue
                val keys = awaitShelterKeys() ?: continue
                val now = nowEpochMillis()
                val next = updated.copy(
                    requestVersion = current.requestVersion + 1,
                    destinationShelterId = keys.shelterId,
                    createdAtEpochMillis = now,
                    expiresAtEpochMillis = now + REQUEST_LIFETIME_MILLIS,
                )
                val result = runCatching {
                    creator.create(next, keys.recipientKey, UUID.randomUUID().toString())
                }.getOrNull()
                if (result is RescueCreationResult.Stored) {
                    activeDraft = next
                    _state.update {
                        it.copy(
                            ownRequest = next.toOwnRequest(result.record.state.submissionStatus),
                            broadcast = it.broadcast.copy(statusMessage = "現在地を更新しながら自動送信しています。"),
                        )
                    }
                    onRescueAutomationRequired()
                }
            }
        }
    }

    private fun failSubmission(message: String) {
        _state.update { it.copy(isRequestSubmitting = false, formMessage = message) }
    }

    private fun refreshCourierItems() {
        viewModelScope.launch(Dispatchers.IO) { refreshCourierItemsNow() }
    }

    private suspend fun awaitShelterKeys() = shelterKeyProvider.load() ?: run {
        val deadline = nowEpochMillis() + shelterKeyWaitMillis.coerceIn(0, 15_000)
        var keys: com.example.relay.rescue.ShelterPublicKeys?
        do {
            delay(250)
            keys = shelterKeyProvider.load()
        } while (keys == null && nowEpochMillis() < deadline)
        keys
    }

    private fun refreshCourierItemsNow() {
        val items = presenter.items()
        _state.update { current ->
            current.copy(
                courierItems = items,
                courierAutomation = current.courierAutomation.copy(
                    isEnabled = current.courierAutomation.isEnabled || items.isNotEmpty(),
                ),
            )
        }
    }

    private fun newDraft(shelterId: String = shelterKeyProvider.load()?.shelterId.orEmpty()): RescueRequestDraft {
        val now = nowEpochMillis()
        return RescueRequestDraft(
            requestId = UUID.randomUUID().toString(),
            senderDeviceId = senderDeviceId,
            destinationShelterId = shelterId,
            createdAtEpochMillis = now,
            expiresAtEpochMillis = now + REQUEST_LIFETIME_MILLIS,
            urgency = RescueUrgency.URGENT,
        )
    }

    private fun RescueRequestDraft.withLegacyConditionFlags(): RescueRequestDraft = copy(
        urgency = if (RescueCondition.LIFE_THREATENING in conditions) RescueUrgency.IMMEDIATE else urgency,
        seriouslyInjured = seriouslyInjured || RescueCondition.LIFE_THREATENING in conditions,
        injured = injured || RescueCondition.INJURED_OR_UNWELL in conditions,
        mobilityImpaired = mobilityImpaired || RescueCondition.MOBILITY_IMPAIRED in conditions,
        medicalSupportRequired = medicalSupportRequired || RescueCondition.SUPPORT_NEEDED in conditions,
    )

    private fun RescueRequestDraft.withInferredConditions(): RescueRequestDraft {
        if (conditions.isNotEmpty()) return this
        val inferred = buildSet {
            if (seriouslyInjured) add(RescueCondition.LIFE_THREATENING)
            if (injured) add(RescueCondition.INJURED_OR_UNWELL)
            if (mobilityImpaired) add(RescueCondition.MOBILITY_IMPAIRED)
            if (medicalSupportRequired || supportNeeds.isNotEmpty()) add(RescueCondition.SUPPORT_NEEDED)
        }
        return copy(conditions = inferred)
    }

    private fun RescueRequestDraft.toOwnRequest(status: RescueSubmissionStatus): OwnRescueRequestUiState {
        val fix = location
        return OwnRescueRequestUiState(
            requestId = requestId,
            requestVersion = requestVersion,
            urgency = urgency,
            personCount = personCount,
            latitude = fix?.latitude ?: 0.0,
            longitude = fix?.longitude ?: 0.0,
            accuracyMeters = fix?.accuracyMeters,
            locationCapturedAtEpochMillis = fix?.capturedAtEpochMillis ?: createdAtEpochMillis,
            createdAtEpochMillis = createdAtEpochMillis,
            submissionStatus = status,
            isCancelled = action == RescueRequestAction.CANCELLED,
        )
    }

    private fun locationChanged(before: RescueLocation?, after: RescueLocation?): Boolean {
        val beforeLatitude = before?.latitude ?: return true
        val beforeLongitude = before.longitude ?: return true
        val afterLatitude = after?.latitude ?: return true
        val afterLongitude = after.longitude ?: return true
        val coordinateDelta = kotlin.math.abs(beforeLatitude - afterLatitude) +
            kotlin.math.abs(beforeLongitude - afterLongitude)
        val age = (after.capturedAtEpochMillis ?: 0) - (before.capturedAtEpochMillis ?: 0)
        return coordinateDelta >= 0.00005 || age >= 5 * 60_000
    }

    override fun onCleared() {
        trackingJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val REQUEST_LIFETIME_MILLIS = 3L * 24 * 60 * 60 * 1_000
    }
}
