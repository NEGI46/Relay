package com.example.relay.ui.rescue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.relay.rescue.CourierRescuePresenter
import com.example.relay.rescue.RescueCreationResult
import com.example.relay.rescue.RescueEnvelopeRepository
import com.example.relay.rescue.RescueRequestCreator
import com.example.relay.rescue.RescueRequestDraft
import com.example.relay.rescue.RescueUrgency
import com.example.relay.rescue.ShelterPublicKeyProvider
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RescueViewModel(
    private val repository: RescueEnvelopeRepository,
    private val shelterKeyProvider: ShelterPublicKeyProvider,
    /** Starts persistent automatic transport after a request is stored. */
    private val onRescueAutomationRequired: () -> Unit = {},
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    /** Debug local-Gateway enrollment may still be in flight when a member opens the form. */
    private val shelterKeyWaitMillis: Long = 0,
) : ViewModel(), RescueCallbacks {
    private val creator = RescueRequestCreator(repository)
    private val presenter = CourierRescuePresenter(repository)
    private val _state = MutableStateFlow(RescueUiState(draft = newDraft()))
    val state: StateFlow<RescueUiState> = _state.asStateFlow()

    init { refreshCourierItems() }

    override fun onNavigate(screen: RescueScreen) {
        if (screen == RescueScreen.COURIER_INVENTORY) refreshCourierItems()
        _state.update { current -> current.copy(screen = screen, draft = current.draft ?: newDraft(), formMessage = null) }
    }

    override fun onDraftChange(draft: RescueRequestDraft) {
        _state.update { it.copy(draft = draft, formMessage = null) }
    }

    override fun onSubmitRequest() {
        val draft = _state.value.draft ?: return
        _state.update { it.copy(isRequestSubmitting = true, formMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val keys = awaitShelterKeys()
            if (keys == null || keys.shelterId != draft.destinationShelterId) {
                _state.update { it.copy(isRequestSubmitting = false, formMessage = "送信先避難所の確認情報を取得できません。") }
                return@launch
            }
            val result = runCatching { creator.create(draft, keys.recipientKey, UUID.randomUUID().toString()) }.getOrElse {
                _state.update { it.copy(isRequestSubmitting = false, formMessage = "救助要請を保存できませんでした。") }
                return@launch
            }
            _state.update { current -> when (result) {
                is RescueCreationResult.Stored -> current.copy(
                    screen = RescueScreen.BROADCASTING,
                    isRequestSubmitting = false,
                    draft = newDraft(keys.shelterId),
                    broadcast = RescueBroadcastUiState(
                        isActive = true,
                        transferCount = result.record.state.submissionCount,
                        statusMessage = "周囲に中継端末が来るまで発信しています。",
                    ),
                    courierAutomation = CourierAutomationUiState(isEnabled = true, statusMessage = "受信・提出は自動で行います"),
                )
                is RescueCreationResult.NotStored -> current.copy(
                    isRequestSubmitting = false,
                    formMessage = "保存できませんでした: ${result.reason.name}",
                )
            } }
            if (result is RescueCreationResult.Stored) onRescueAutomationRequired()
            refreshCourierItemsNow()
        }
    }

    override fun onStopBroadcasting() {
        _state.update { current -> current.copy(screen = RescueScreen.HOME, broadcast = current.broadcast.copy(isActive = false, statusMessage = "発信を停止しました")) }
    }

    private fun refreshCourierItems() {
        viewModelScope.launch(Dispatchers.IO) { refreshCourierItemsNow() }
    }

    private suspend fun awaitShelterKeys() = shelterKeyProvider.load() ?: run {
        val deadline = System.currentTimeMillis() + shelterKeyWaitMillis.coerceIn(0, 15_000)
        var keys: com.example.relay.rescue.ShelterPublicKeys?
        do {
            delay(250)
            keys = shelterKeyProvider.load()
        } while (keys == null && System.currentTimeMillis() < deadline)
        keys
    }

    private fun refreshCourierItemsNow() {
        val items = presenter.items()
        _state.update { current ->
            current.copy(
                courierItems = items,
                courierAutomation = current.courierAutomation.copy(isEnabled = current.courierAutomation.isEnabled || items.isNotEmpty()),
            )
        }
    }

    private fun newDraft(shelterId: String = shelterKeyProvider.load()?.shelterId.orEmpty()): RescueRequestDraft {
        val now = nowEpochMillis()
        return RescueRequestDraft(
            requestId = UUID.randomUUID().toString(),
            senderDeviceId = "request-${UUID.randomUUID()}",
            destinationShelterId = shelterId,
            createdAtEpochMillis = now,
            expiresAtEpochMillis = now + 3L * 24 * 60 * 60 * 1_000,
            urgency = RescueUrgency.URGENT,
        )
    }
}
