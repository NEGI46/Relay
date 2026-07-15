package com.example.relay.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.relay.data.repository.RoomMessageRepository
import com.example.relay.domain.MessagePriority
import com.example.relay.domain.CreateSafetyMessageUseCase
import com.example.relay.domain.CreateSupplyMessageUseCase
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.RelayMessage
import com.example.relay.domain.SafetyState
import com.example.relay.domain.SupplyKind
import com.example.relay.domain.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import com.example.relay.domain.DeliveryPresentation
import com.example.relay.domain.OperatingMode
import com.example.relay.domain.DeviceRole
import com.example.relay.domain.DeviceRoleStore
import com.example.relay.runtime.RelayCommunicationRuntime
import com.example.relay.runtime.RuntimePeer
import com.example.relay.gateway.GatewayCredentialStore
import com.example.relay.gateway.GatewaySettings
import com.example.relay.gateway.GatewaySettingsStore
import com.example.relay.gateway.GatewaySyncEngine
import com.example.relay.gateway.GatewaySyncResult
import kotlinx.coroutines.launch

enum class RelayScreen { HOME, SAFETY_FORM, SUPPLY_FORM, REGIONAL, SETTINGS, PEERS, DEBUG, GATEWAY }

data class RelayUiState(
    val screen: RelayScreen = RelayScreen.HOME,
    val transportRunning: Boolean = false,
    val discoveredPeers: Int = 0,
    val connectedPeers: Int = 0,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    val mode: OperatingMode = OperatingMode.NORMAL,
    val role: DeviceRole = DeviceRole.MEMBER,
    val advertising: Boolean = false,
    val discovering: Boolean = false,
    val transportName: String = "未設定",
    val peers: Map<String, RuntimePeer> = emptyMap(),
    val debugEvents: List<String> = emptyList(),
)

class RelayViewModel(
    private val repository: RoomMessageRepository,
    private val deviceId: String,
    private val deviceRoleStore: DeviceRoleStore,
    private val communicationRuntime: RelayCommunicationRuntime,
    private val gatewaySettingsStore: GatewaySettingsStore,
    private val gatewayCredentialStore: GatewayCredentialStore,
    private val gatewaySyncEngine: GatewaySyncEngine,
) : ViewModel() {
    private val policy = MessagePolicy(SystemClock)
    private val createSafetyMessage = CreateSafetyMessageUseCase(repository, policy, SystemClock, deviceId)
    private val createSupplyMessage = CreateSupplyMessageUseCase(repository, policy, SystemClock, deviceId)
    private val _uiState = MutableStateFlow(RelayUiState(role = deviceRoleStore.load()))
    private val _gatewaySettings = MutableStateFlow(gatewaySettingsStore.load())
    val gatewaySettings: StateFlow<GatewaySettings> = _gatewaySettings.asStateFlow()
    private val _gatewayResult = MutableStateFlow<GatewaySyncResult?>(null)
    val gatewayResult: StateFlow<GatewaySyncResult?> = _gatewayResult.asStateFlow()
    val uiState: StateFlow<RelayUiState> = _uiState.asStateFlow()
    val messages: StateFlow<List<RelayMessage>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val deliveryStates: StateFlow<Map<String, DeliveryPresentation>> = combine(
        messages,
        repository.observeReceipts(),
    ) { currentMessages, receipts ->
        currentMessages.associate { message ->
            message.messageId to repository.deliveryPresentation(receipts.filter { it.messageId == message.messageId })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        viewModelScope.launch {
            communicationRuntime.state.collect { runtime ->
                val current = _uiState.value
                _uiState.value = current.copy(
                    transportRunning = runtime.running,
                    discoveredPeers = runtime.peers.values.count { it.status != com.example.relay.runtime.RuntimePeerStatus.CONNECTED },
                    connectedPeers = runtime.transport.connectedPeerIds.size,
                    lastSyncAt = runtime.lastSyncAt,
                    lastError = runtime.lastError,
                    mode = if (runtime.running) runtime.mode else current.mode,
                    role = if (runtime.running) runtime.role else current.role,
                    advertising = runtime.transport.advertising,
                    discovering = runtime.transport.discovering,
                    transportName = runtime.transportName,
                    peers = runtime.peers,
                    debugEvents = runtime.debugEvents,
                )
            }
        }
    }

    fun navigate(screen: RelayScreen) { _uiState.value = _uiState.value.copy(screen = screen) }

    fun setMode(mode: OperatingMode) { _uiState.value = _uiState.value.copy(mode = mode) }
    fun setRole(role: DeviceRole) {
        deviceRoleStore.save(role)
        _uiState.value = _uiState.value.copy(role = role)
    }

    fun reportError(reason: String) { _uiState.value = _uiState.value.copy(lastError = reason.take(160)) }
    fun connect(peerId: String) = viewModelScope.launch { communicationRuntime.connect(peerId) }
    fun accept(peerId: String) = viewModelScope.launch { communicationRuntime.accept(peerId) }
    fun reject(peerId: String) = viewModelScope.launch { communicationRuntime.reject(peerId) }
    fun disconnect(peerId: String) = viewModelScope.launch { communicationRuntime.disconnect(peerId) }

    fun createSafety(
        state: SafetyState,
        companions: Int,
        location: String,
        note: String,
    ) = viewModelScope.launch {
        createSafetyMessage(state, companions, location, note, MessagePriority.HIGH)
        navigate(RelayScreen.HOME)
    }

    fun createSupply(
        kind: SupplyKind,
        count: Int,
        location: String,
        note: String,
        otherLabel: String?,
    ) = viewModelScope.launch {
        createSupplyMessage(kind, count, location, note, otherLabel, MessagePriority.CRITICAL)
        navigate(RelayScreen.HOME)
    }

    fun clearAll() = viewModelScope.launch { repository.clearAll() }

    fun saveGateway(settings: GatewaySettings, token: String?) {
        gatewaySettingsStore.save(settings)
        if (!token.isNullOrBlank()) gatewayCredentialStore.save(token)
        _gatewaySettings.value = settings
    }
    fun clearGateway() { gatewayCredentialStore.clear(); gatewaySettingsStore.save(GatewaySettings()); _gatewaySettings.value = GatewaySettings() }
    fun pairGateway(code: String) = viewModelScope.launch {
        val settings = _gatewaySettings.value
        val ok = gatewaySyncEngine.requestPair(code)
        reportError(if (ok) "PC Gatewayへのペアリング要求を送信しました。PC側で承認してください" else "ペアリング要求に失敗しました")
    }
    fun syncGateway() = viewModelScope.launch { _gatewayResult.value = gatewaySyncEngine.syncOnce() }


}
