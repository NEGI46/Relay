package com.example.relay.transport

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
        val endpointId = peerToEndpoint[peerId] ?: return fail("connect", "unknown peer")
        try {
            platform.requestConnection(localDeviceId, endpointId)
        } catch (error: Exception) {
            fail("connect", error.safeReason())
            _connectionEvents.emit(ConnectionEvent.Failed(peerId, error.safeReason()))
        }
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
    }

    override suspend fun disconnect(peerId: String) {
        peerToEndpoint[peerId]?.let(platform::disconnect)
        clearPeerConnection(peerId)
        _connectionEvents.emit(ConnectionEvent.Disconnected(peerId))
    }

    override suspend fun send(peerId: String, payload: ByteArray): SendResult {
        if (payload.size > maxPayloadBytes) return SendResult.Failed("payload exceeds Nearby BYTES limit")
        if (peerId !in _state.value.connectedPeerIds) return SendResult.Failed("peer is not connected")
        val endpointId = peerToEndpoint[peerId] ?: return SendResult.Failed("peer endpoint is unavailable")
        return try {
            val completion = CompletableDeferred<SendResult>()
            val payloadId = platform.sendBytes(endpointId, payload.copyOf()) { createdId ->
                pendingTransfers[createdId] = completion
            }
            _transportEvents.emit(TransportEvent.PayloadSendRequested(peerId, payloadId, payload.size))
            withTimeoutOrNull(transferTimeoutMs) { completion.await() }
                ?: SendResult.Failed("payload transfer timed out").also { pendingTransfers.remove(payloadId) }
        } catch (error: Exception) {
            SendResult.Failed(error.safeReason()).also { fail("send", error.safeReason()) }
        }
    }

    private suspend fun handlePlatformEvent(event: NearbyPlatformEvent) {
        when (event) {
            is NearbyPlatformEvent.EndpointFound -> addPeer(event.endpointId, event.endpointName)
            is NearbyPlatformEvent.EndpointLost -> removeDiscoveredEndpoint(event.endpointId)
            is NearbyPlatformEvent.ConnectionInitiated -> handleInitiated(event)
            is NearbyPlatformEvent.ConnectionSucceeded -> endpointToPeer[event.endpointId]?.let { peerId ->
                _state.value = _state.value.copy(
                    connectedPeerIds = _state.value.connectedPeerIds + peerId,
                    pendingVerifications = _state.value.pendingVerifications - peerId,
                )
                _connectionEvents.emit(ConnectionEvent.Connected(Peer(peerId)))
            }
            is NearbyPlatformEvent.ConnectionFailed -> endpointToPeer[event.endpointId]?.let { peerId ->
                clearPeerConnection(peerId)
                _connectionEvents.emit(ConnectionEvent.Failed(peerId, event.reason))
            }
            is NearbyPlatformEvent.Disconnected -> endpointToPeer[event.endpointId]?.let { peerId ->
                clearPeerConnection(peerId)
                _connectionEvents.emit(ConnectionEvent.Disconnected(peerId))
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

    private suspend fun addPeer(endpointId: String, peerId: String) {
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
        if (localDeviceId < peerId && peerId !in _state.value.connectedPeerIds && peerId !in _state.value.pendingVerifications) {
            try {
                platform.requestConnection(localDeviceId, endpointId)
            } catch (error: Exception) {
                fail("connect", error.safeReason())
                _connectionEvents.emit(ConnectionEvent.Failed(peerId, error.safeReason()))
            }
        }
    }

    private suspend fun removeDiscoveredEndpoint(endpointId: String) {
        val peerId = endpointToPeer[endpointId] ?: return
        if (peerId in _state.value.connectedPeerIds || peerId in _state.value.pendingVerifications) return
        endpointToPeer.remove(endpointId)
        peerToEndpoint.remove(peerId)
        _discoveredPeers.value = peerToEndpoint.keys.map(::Peer)
        _transportEvents.emit(TransportEvent.PeerLost(peerId))
    }

    private suspend fun handleInitiated(event: NearbyPlatformEvent.ConnectionInitiated) {
        addPeer(event.endpointId, event.endpointName)
        val peerId = endpointToPeer[event.endpointId] ?: return
        val digits = event.authenticationDigits?.takeIf { it.isNotBlank() }
        if (digits == null) {
            platform.rejectConnection(event.endpointId)
            _connectionEvents.emit(ConnectionEvent.Failed(peerId, "authentication code unavailable"))
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
        _state.value = _state.value.copy(
            connectedPeerIds = _state.value.connectedPeerIds - peerId,
            pendingVerifications = _state.value.pendingVerifications - peerId,
        )
    }

    private suspend fun fail(operation: String, reason: String) {
        _state.value = _state.value.copy(lastError = reason)
        _transportEvents.emit(TransportEvent.Error(operation, reason))
    }

    private fun cleanup(lastError: String?) {
        eventJob?.cancel()
        eventJob = null
        pendingTransfers.values.forEach { it.complete(SendResult.Failed("transport stopped")) }
        pendingTransfers.clear()
        peerToEndpoint.clear()
        endpointToPeer.clear()
        _discoveredPeers.value = emptyList()
        _state.value = OfflineTransportState(lastError = lastError)
    }

    private fun Throwable.safeReason(): String = message?.take(160) ?: javaClass.simpleName
}
