package com.example.relay.ui.rescue

import com.example.relay.rescue.CourierRescueItem
import com.example.relay.rescue.RescueRequestDraft

/** Screens available in the ordinary rescue flow.
 *
 * A courier never has to discover a shelter PC, select a network, submit data,
 * retry a transfer, or enrol a key.  Those actions are intentionally absent
 * from this contract so they cannot accidentally return through a UI change.
 */
enum class RescueScreen {
    HOME,
    REQUEST_FORM,
    BROADCASTING,
    COURIER_INVENTORY,
    SAFETY_PRIVACY,
}

data class RescueBroadcastUiState(
    val isActive: Boolean = false,
    val nearbyDeviceCount: Int = 0,
    val transferCount: Int = 0,
    val statusMessage: String = "周囲のRelay端末を待っています",
)

/** Metadata-only, passive status shown to a courier. */
data class CourierAutomationUiState(
    val isEnabled: Boolean = false,
    val statusMessage: String = "受信した情報は自動で避難所へ届けます",
    val lastDeliveredAtEpochMillis: Long? = null,
)

data class RescueUiState(
    val screen: RescueScreen = RescueScreen.HOME,
    val draft: RescueRequestDraft? = null,
    val broadcast: RescueBroadcastUiState = RescueBroadcastUiState(),
    val courierItems: List<CourierRescueItem> = emptyList(),
    val courierAutomation: CourierAutomationUiState = CourierAutomationUiState(),
    val isRequestSubmitting: Boolean = false,
    val formMessage: String? = null,
)

/** Parent-owned actions. RescueFlow performs no networking or persistence. */
interface RescueCallbacks {
    fun onNavigate(screen: RescueScreen)
    fun onDraftChange(draft: RescueRequestDraft)
    fun onSubmitRequest()
    fun onStopBroadcasting()
}
