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
    private val connectionPolicy: NearbyConnectionPolicy = NearbyConnectionPolicy(),
    private val maxPayloadBytes: Int = 32 * 1024,
    private val transferTimeoutMs: Long = 30_000,
    private val connectionAttemptTimeoutMs: Long = 30_000,
    private val reconnectBaseDelayMs: Long = 1_000,
    private val reconnectMaxDelayMs: Long = 30_000,
) : OfflineTransport {
    private data class PendingTransfer(val peerId: String, val completion: CompletableDeferred<SendResult>)

    private val lifecycleMutex = Mutex()
    private val _state = MutableStateFlow(OfflineTransportState())
    private val _discoveredPeers = MutableStateFlow<List<Peer>>(emptyList())
    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 64)
    private val _receivedPayloads = MutableSharedFlow<ReceivedPayload>(extraBufferCapacity = 64)
    private val _transportEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 128)
    private val peerToEndpoint = linkedMapOf<String, String>()
    private val endpointToPeer = linkedMapOf<String, String>()
    private val pendingTransfers = ConcurrentHashMap<Long, PendingTransfer>()
    // Typed as MutableSet so member calls resolve through java.util.Set (API 1) rather than
    // ConcurrentHashMap.KeySetView (API 24); the backing set is still the concurrent key-set,
    // and newKeySet() itself is safe on minSdk 23 via core library desugaring.
    private val connectingPeerIds: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()
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
        var createdPayloadId: Long? = null
        return try {
            val completion = CompletableDeferred<SendResult>()
            val payloadId = platform.sendBytes(endpointId, payload.copyOf()) { createdId ->
                createdPayloadId = createdId
                pendingTransfers[createdId] = PendingTransfer(peerId, completion)
            }
            _transportEvents.emit(TransportEvent.PayloadSendRequested(peerId, payloadId, payload.size))
            withTimeoutOrNull(transferTimeoutMs) { completion.await() }
                ?: SendResult.Failed("payload transfer timed out").also { pendingTransfers.remove(payloadId) }
        } catch (error: Exception) {
            createdPayloadId?.let { pendingTransfers.remove(it)?.completion?.complete(SendResult.Failed(error.safeReason())) }
            SendResult.Failed(error.safeReason()).also { fail("send", error.safeReason()) }
        }
    }

    private suspend fun handlePlatformEvent(event: NearbyPlatformEvent) {
        when (event) {
            is NearbyPlatformEvent.EndpointFound -> addPeer(event.endpointId, event.endpointName)
            is NearbyPlatformEvent.EndpointLost -> removeDiscoveredEndpoint(event.endpointId)
            is NearbyPlatformEvent.ConnectionInitiated -> handleInitiated(event)
            is NearbyPlatformEvent.ConnectionSucceeded -> handleConnectionSucceeded(event.endpointId)
            is NearbyPlatformEvent.ConnectionFailed -> handleConnectionFailed(event)
            is NearbyPlatformEvent.Disconnected -> handleDisconnected(event.endpointId)
            is NearbyPlatformEvent.BytesReceived -> handleBytesReceived(event)
            is NearbyPlatformEvent.PayloadTransferSucceeded -> handleTransferSucceeded(event)
            is NearbyPlatformEvent.PayloadTransferFailed -> handleTransferFailed(event)
        }
    }

    private suspend fun handleConnectionSucceeded(endpointId: String) {
        val peerId = endpointToPeer[endpointId] ?: return
        if (!connectingPeerIds.remove(peerId)) {
            platform.disconnect(endpointId)
            return
        }
        cancelConnectionAttemptTimeout(peerId)
        cancelReconnect(peerId, resetAttempts = true)
        _state.value = _state.value.copy(
            connectedPeerIds = _state.value.connectedPeerIds + peerId,
            pendingVerifications = _state.value.pendingVerifications - peerId,
        )
        _connectionEvents.emit(ConnectionEvent.Connected(Peer(peerId)))
    }

    private suspend fun handleConnectionFailed(event: NearbyPlatformEvent.ConnectionFailed) {
        val peerId = endpointToPeer[event.endpointId] ?: return
        val wasActive = peerId in connectingPeerIds || peerId in _state.value.connectedPeerIds
        clearPeerConnection(peerId)
        if (!wasActive) return
        _connectionEvents.emit(ConnectionEvent.Failed(peerId, event.reason))
        scheduleReconnect(peerId)
    }

    private suspend fun handleDisconnected(endpointId: String) {
        val peerId = endpointToPeer[endpointId] ?: return
        val wasActive = peerId in connectingPeerIds || peerId in _state.value.connectedPeerIds
        clearPeerConnection(peerId)
        if (!wasActive) return
        _connectionEvents.emit(ConnectionEvent.Disconnected(peerId))
        scheduleReconnect(peerId)
    }

    private suspend fun handleBytesReceived(event: NearbyPlatformEvent.BytesReceived) {
        val peerId = endpointToPeer[event.endpointId] ?: return
        if (peerId !in _state.value.connectedPeerIds) return
        val bytes = event.bytes.copyOf()
        _receivedPayloads.emit(ReceivedPayload(peerId, bytes))
        _transportEvents.emit(TransportEvent.PayloadReceived(peerId, bytes.size))
    }

    private suspend fun handleTransferSucceeded(event: NearbyPlatformEvent.PayloadTransferSucceeded) {
        val outgoing = pendingTransfers.remove(event.payloadId) ?: return
        outgoing.completion.complete(SendResult.PayloadTransferCompleted)
        _transportEvents.emit(TransportEvent.PayloadTransferCompleted(outgoing.peerId, event.payloadId))
    }

    private suspend fun handleTransferFailed(event: NearbyPlatformEvent.PayloadTransferFailed) {
        val outgoing = pendingTransfers.remove(event.payloadId) ?: return
        outgoing.completion.complete(SendResult.Failed(event.reason))
        _transportEvents.emit(TransportEvent.PayloadTransferFailed(outgoing.peerId, event.payloadId, event.reason))
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
        // so both devices do not race to request the same connection. TRUSTED mode
        // additionally refuses to reach out to peers that are not on the allow-list.
        if (initiateConnection && isDeterministicInitiator(peerId) && connectionPolicy.allowsConnection(peerId)) {
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
        // TRUSTED mode fails closed: an inbound peer that is not on the allow-list is
        // rejected outright and is never retried, because the refusal is a policy
        // decision rather than a transient transport error.
        if (!connectionPolicy.allowsConnection(peerId)) {
            runCatching { platform.rejectConnection(event.endpointId) }
            clearPeerConnection(peerId)
            _connectionEvents.emit(ConnectionEvent.Failed(peerId, "peer is not trusted"))
            return
        }
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
        pendingTransfers.entries
            .filter { it.value.peerId == peerId }
            .forEach { entry ->
                if (pendingTransfers.remove(entry.key, entry.value)) {
                    entry.value.completion.complete(SendResult.Failed("peer disconnected"))
                }
            }
        cancelConnectionAttemptTimeout(peerId)
        connectingPeerIds -= peerId
        _state.value = _state.value.copy(
            connectedPeerIds = _state.value.connectedPeerIds - peerId,
            pendingVerifications = _state.value.pendingVerifications - peerId,
        )
    }

    private suspend fun requestPeerConnection(peerId: String) {
        val endpointId = peerToEndpoint[peerId] ?: return fail("connect", "unknown peer")
        // TRUSTED mode fails closed at the sole outbound chokepoint. The auto-initiate path in
        // addPeer() already skips untrusted peers, but an explicit connect() (e.g. UI-driven) would
        // otherwise bypass the allow-list; gate it here so every outbound path honours the policy.
        // A policy refusal is intentional, not transient, so it is never retried.
        if (!connectionPolicy.allowsConnection(peerId)) {
            _connectionEvents.emit(ConnectionEvent.Failed(peerId, "peer is not trusted"))
            return
        }
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
        if (!connectionPolicy.allowsConnection(peerId)) return
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
        pendingTransfers.values.forEach { it.completion.complete(SendResult.Failed("transport stopped")) }
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
