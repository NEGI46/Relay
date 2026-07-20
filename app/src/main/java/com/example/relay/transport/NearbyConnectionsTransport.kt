package com.example.relay.transport

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class NearbyConnectionsTransport(
    private val localDeviceId: String,
    private val platform: NearbyPlatform,
    private val permissionGate: NearbyPermissionGate,
    private val scope: CoroutineScope,
    private val maxPayloadBytes: Int = 32 * 1024,
    private val transferTimeoutMs: Long = 30_000,
    private val connectionAttemptTimeoutMs: Long = 30_000,
    private val reconnectBaseDelayMs: Long = 1_000,
    private val reconnectMaxDelayMs: Long = 30_000,
) : OfflineTransport {
    private val lifecycleMutex = Mutex()
    private val _state = MutableStateFlow(OfflineTransportState())
    private val _discoveredPeers = MutableStateFlow<List<Peer>>(emptyList())
    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 64)
    private val _receivedPayloads = MutableSharedFlow<ReceivedPayload>(extraBufferCapacity = 64)
    private val _transportEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 128)
    private val peerToEndpoint = linkedMapOf<String, String>()
    private val endpointToPeer = linkedMapOf<String, String>()
    private val pendingTransfers = ConcurrentHashMap<Long, CompletableDeferred<SendResult>>()
    private val connectingPeerIds = ConcurrentHashMap.newKeySet<String>()
    private val connectionAttemptTimeoutJobs = ConcurrentHashMap<String, Job>()
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()
    private val reconnectJobs = ConcurrentHashMap<String, Job>()
    private var eventJob: Job? = null

    override val state: StateFlow<OfflineTransportState> = _state
    override val discoveredPeers: StateFlow<List<Peer>> = _discoveredPeers
    override val connectionEvents = _connectionEvents
    override val receivedPayloads = _receivedPayloads
    override val transportEvents = _transportEvents

    override suspend fun start() = lifecycleMutex.withLock {
        if (_state.value.started) return
        if (!permissionGate.canUseNearby()) {
            fail("start", "required Nearby permissions are missing")
            return
        }
        eventJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { platform.events.collect(::handlePlatformEvent) }
        try {
            platform.startAdvertising(localDeviceId)
            _state.value = _state.value.copy(started = true, advertising = true)
            _transportEvents.emit(TransportEvent.AdvertisingStarted)
            platform.startDiscovery()
            _state.value = _state.value.copy(discovering = true)
            _transportEvents.emit(TransportEvent.DiscoveryStarted)
        } catch (error: Exception) {
            runCatching { platform.stopAll() }
            cleanup(error.safeReason())
            _transportEvents.emit(TransportEvent.Error("start", error.safeReason()))
        }
    }

    override suspend fun stop() {
        lifecycleMutex.withLock {
            val stopFailure = runCatching { platform.stopAll() }.exceptionOrNull()
            cleanup(stopFailure?.safeReason())
            stopFailure?.let { _transportEvents.emit(TransportEvent.Error("stop", it.safeReason())) }
        }
    }

    override suspend fun connect(peerId: String) {
        requestPeerConnection(peerId)
    }

    override suspend fun acceptConnection(peerId: String) {
        val endpointId = peerToEndpoint[peerId] ?: return fail("accept", "unknown peer")
        if (peerId !in _state.value.pendingVerifications) return fail("accept", "verification is not pending")
        try {
            platform.acceptConnection(endpointId)
        } catch (error: Exception) {
            clearPeerConnection(peerId)
            fail("accept", error.safeReason())
            _connectionEvents.emit(ConnectionEvent.Failed(peerId, error.safeReason()))
        }
    }

    override suspend fun rejectConnection(peerId: String) {
        val endpointId = peerToEndpoint[peerId] ?: return
        try {
            platform.rejectConnection(endpointId)
        } finally {
            clearPeerConnection(peerId)
            _connectionEvents.emit(ConnectionEvent.Disconnected(peerId))
        }
    private suspend fun handlePlatformEvent(event: NearbyPlatformEvent) {
        when (event) {
            is NearbyPlatformEvent.EndpointFound -> addPeer(event.endpointId, event.endpointName)
            is NearbyPlatformEvent.EndpointLost -> removeDiscoveredEndpoint(event.endpointId)
            is NearbyPlatformEvent.ConnectionInitiated -> handleInitiated(event)
            is NearbyPlatformEvent.ConnectionSucceeded -> handleConnectionSucceeded(event)
            is NearbyPlatformEvent.ConnectionFailed -> handleConnectionFailed(event)
            is NearbyPlatformEvent.Disconnected -> handleDisconnected(event)
            is NearbyPlatformEvent.PayloadReceived -> handlePayloadReceived(event)
            is NearbyPlatformEvent.PayloadTransferUpdate -> handlePayloadTransferUpdate(event)
        }
    }

    private suspend fun handleConnectionSucceeded(event: NearbyPlatformEvent.ConnectionSucceeded) {
        endpointToPeer[event.endpointId]?.let { peerId ->
            if (!connectingPeerIds.remove(peerId)) {
                platform.disconnect(event.endpointId)
                return@let
            }
            cancelConnectionAttemptTimeout(peerId)
            cancelReconnect(peerId, resetAttempts = true)
            _state.value = _state.value.copy(
                connectedPeerIds = _state.value.connectedPeerIds + peerId,
                pendingVerifications = _state.value.pendingVerifications - peerId,
            )
            _connectionEvents.emit(ConnectionEvent.Connected(Peer(peerId)))
        }
    }

    private suspend fun handleConnectionFailed(event: NearbyPlatformEvent.ConnectionFailed) {
        endpointToPeer[event.endpointId]?.let { peerId ->
            val wasActive = peerId in connectingPeerIds || peerId in _state.value.connectedPeerIds
            clearPeerConnection(peerId)
            if (!wasActive) return@let
            _connectionEvents.emit(ConnectionEvent.Failed(peerId, event.reason))
            scheduleReconnect(peerId)
        }
    }

    private suspend fun handleDisconnected(event: NearbyPlatformEvent.Disconnected) {
        endpointToPeer[event.endpointId]?.let { peerId ->
            clearPeerConnection(peerId)
            _connectionEvents.emit(ConnectionEvent.Disconnected(peerId))
        }
    }

    private suspend fun handlePayloadReceived(event: NearbyPlatformEvent.PayloadReceived) {
        endpointToPeer[event.endpointId]?.let { peerId ->
            _transportEvents.emit(TransportEvent.PayloadReceived(peerId, event.payloadId, event.bytes))
        }
    }

    private suspend fun handlePayloadTransferUpdate(event: NearbyPlatformEvent.PayloadTransferUpdate) {
        endpointToPeer[event.endpointId]?.let { peerId ->
            handleTransferUpdate(peerId, event)
        }
    }
                val wasActive = peerId in connectingPeerIds || peerId in _state.value.connectedPeerIds
                clearPeerConnection(peerId)
                if (!wasActive) return@let
                _connectionEvents.emit(ConnectionEvent.Disconnected(peerId))
                scheduleReconnect(peerId)
            }
            is NearbyPlatformEvent.BytesReceived -> endpointToPeer[event.endpointId]?.let { peerId ->
                val bytes = event.bytes.copyOf()
                _receivedPayloads.emit(ReceivedPayload(peerId, bytes))
                _transportEvents.emit(TransportEvent.PayloadReceived(peerId, bytes.size))
            }
            is NearbyPlatformEvent.PayloadTransferSucceeded -> {
                val outgoing = pendingTransfers.remove(event.payloadId)
                outgoing?.complete(SendResult.PayloadTransferCompleted)
                if (outgoing != null) endpointToPeer[event.endpointId]?.let {
                    _transportEvents.emit(TransportEvent.PayloadTransferCompleted(it, event.payloadId))
                }
            }
            is NearbyPlatformEvent.PayloadTransferFailed -> {
                val outgoing = pendingTransfers.remove(event.payloadId)
                outgoing?.complete(SendResult.Failed(event.reason))
                if (outgoing != null) endpointToPeer[event.endpointId]?.let {
                    _transportEvents.emit(TransportEvent.PayloadTransferFailed(it, event.payloadId, event.reason))
                }
            }
        }
    }

    private suspend fun addPeer(endpointId: String, peerId: String, initiateConnection: Boolean = true) {
        if (peerId.isBlank() || peerId == localDeviceId) return
        val existing = peerToEndpoint[peerId]
        if (existing != null && existing != endpointId) {
            platform.rejectConnection(endpointId)
            return fail("discovery", "duplicate peer identity")
        }
        peerToEndpoint[peerId] = endpointId
        endpointToPeer[endpointId] = peerId
        _discoveredPeers.value = peerToEndpoint.keys.map(::Peer)
        _transportEvents.emit(TransportEvent.PeerFound(peerId))
        // Disaster mode is intentionally hands-off: use a deterministic initiator
        // so both devices do not race to request the same connection.
        if (initiateConnection && isDeterministicInitiator(peerId)) {
            requestPeerConnection(peerId)
        }
    }

    private suspend fun removeDiscoveredEndpoint(endpointId: String) {
        val peerId = endpointToPeer[endpointId] ?: return
        if (peerId in _state.value.connectedPeerIds || peerId in _state.value.pendingVerifications) return
        cancelReconnect(peerId, resetAttempts = true)
        cancelConnectionAttemptTimeout(peerId)
        connectingPeerIds -= peerId
        endpointToPeer.remove(endpointId)
        peerToEndpoint.remove(peerId)
        _discoveredPeers.value = peerToEndpoint.keys.map(::Peer)
        _transportEvents.emit(TransportEvent.PeerLost(peerId))
    }

    private suspend fun handleInitiated(event: NearbyPlatformEvent.ConnectionInitiated) {
        // A connection request is already in flight when this callback arrives.
        // Register the endpoint without issuing a second request.
        addPeer(event.endpointId, event.endpointName, initiateConnection = false)
        val peerId = endpointToPeer[event.endpointId] ?: return
        connectingPeerIds += peerId
        ensureConnectionAttemptTimeout(peerId)
        reconnectJobs.remove(peerId)?.cancel()
        val digits = event.authenticationDigits?.takeIf { it.isNotBlank() }
        if (digits == null) {
            platform.rejectConnection(event.endpointId)
            clearPeerConnection(peerId)
            _connectionEvents.emit(ConnectionEvent.Failed(peerId, "authentication code unavailable"))
            scheduleReconnect(peerId)
            return
        }
        // Nearby authentication digits remain available for diagnostics, but are
        // not presented as a user task. Incoming connections are accepted after
        // the transport-level code is available; application data is still
        // validated, size-limited, deduplicated, and marked unverified.
        try {
            platform.acceptConnection(event.endpointId)
        } catch (error: Exception) {
            clearPeerConnection(peerId)
            fail("accept", error.safeReason())
            _connectionEvents.emit(ConnectionEvent.Failed(peerId, error.safeReason()))
        }
    }

    private fun clearPeerConnection(peerId: String) {
        cancelConnectionAttemptTimeout(peerId)
        connectingPeerIds -= peerId
        _state.value = _state.value.copy(
            connectedPeerIds = _state.value.connectedPeerIds - peerId,
            pendingVerifications = _state.value.pendingVerifications - peerId,
        )
    }

    private suspend fun requestPeerConnection(peerId: String) {
        val endpointId = peerToEndpoint[peerId] ?: return fail("connect", "unknown peer")
        if (!_state.value.started || peerId in _state.value.connectedPeerIds || !connectingPeerIds.add(peerId)) return
        val timeoutJob = prepareConnectionAttemptTimeout(peerId)
        try {
            platform.requestConnection(localDeviceId, endpointId)
            timeoutJob.start()
        } catch (error: Exception) {
            cancelConnectionAttemptTimeout(peerId)
            connectingPeerIds -= peerId
            fail("connect", error.safeReason())
            _connectionEvents.emit(ConnectionEvent.Failed(peerId, error.safeReason()))
            scheduleReconnect(peerId)
        }
    }

    private fun prepareConnectionAttemptTimeout(peerId: String): Job {
        val timeoutJob = scope.launch(start = CoroutineStart.LAZY) {
            delay(connectionAttemptTimeoutMs)
            connectionAttemptTimeoutJobs.remove(peerId)
            if (!connectingPeerIds.remove(peerId) || !_state.value.started) return@launch
            _state.value = _state.value.copy(
                connectedPeerIds = _state.value.connectedPeerIds - peerId,
                pendingVerifications = _state.value.pendingVerifications - peerId,
            )
            peerToEndpoint[peerId]?.let { endpointId -> runCatching { platform.disconnect(endpointId) } }
            fail("connect", "connection attempt timed out")
            _connectionEvents.emit(ConnectionEvent.Failed(peerId, "connection attempt timed out"))
            scheduleReconnect(peerId)
        }
        connectionAttemptTimeoutJobs.put(peerId, timeoutJob)?.cancel()
        return timeoutJob
    }

    private fun ensureConnectionAttemptTimeout(peerId: String) {
        val timeoutJob = connectionAttemptTimeoutJobs[peerId]
            ?: prepareConnectionAttemptTimeout(peerId)
        timeoutJob.start()
    }

    private fun cancelConnectionAttemptTimeout(peerId: String) {
        connectionAttemptTimeoutJobs.remove(peerId)?.cancel()
    }

    private fun scheduleReconnect(peerId: String) {
        if (!_state.value.started || !isDeterministicInitiator(peerId) || peerId !in peerToEndpoint) return
        if (peerId in _state.value.connectedPeerIds || reconnectJobs[peerId]?.isActive == true) return
        val attempt = reconnectAttempts[peerId] ?: 0
        reconnectAttempts[peerId] = attempt + 1
        val multiplier = 1L shl attempt.coerceAtMost(30)
        val delayMs = if (reconnectBaseDelayMs > reconnectMaxDelayMs / multiplier) {
            reconnectMaxDelayMs
        } else {
            (reconnectBaseDelayMs * multiplier).coerceAtMost(reconnectMaxDelayMs)
        }
        reconnectJobs[peerId] = scope.launch {
            delay(delayMs)
            reconnectJobs.remove(peerId)
            requestPeerConnection(peerId)
        }
    }

    private fun cancelReconnect(peerId: String, resetAttempts: Boolean) {
        reconnectJobs.remove(peerId)?.cancel()
        if (resetAttempts) reconnectAttempts.remove(peerId)
    }

    private fun isDeterministicInitiator(peerId: String): Boolean = localDeviceId < peerId

    private suspend fun fail(operation: String, reason: String) {
        _state.value = _state.value.copy(lastError = reason)
        _transportEvents.emit(TransportEvent.Error(operation, reason))
    }

    private fun cleanup(lastError: String?) {
        eventJob?.cancel()
        eventJob = null
        pendingTransfers.values.forEach { it.complete(SendResult.Failed("transport stopped")) }
        pendingTransfers.clear()
        val scheduledReconnects = reconnectJobs.values.toList()
        reconnectJobs.clear()
        scheduledReconnects.forEach { it.cancel() }
        val connectionTimeouts = connectionAttemptTimeoutJobs.values.toList()
        connectionAttemptTimeoutJobs.clear()
        connectionTimeouts.forEach { it.cancel() }
        reconnectAttempts.clear()
        connectingPeerIds.clear()
        peerToEndpoint.clear()
        endpointToPeer.clear()
        _discoveredPeers.value = emptyList()
        _state.value = OfflineTransportState(lastError = lastError)
    }

    private fun Throwable.safeReason(): String = message?.take(160) ?: javaClass.simpleName
}
