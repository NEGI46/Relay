package com.example.relay.transport

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine

class GoogleNearbyPlatform(
    context: Context,
    private val serviceId: String,
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext),
) : NearbyPlatform {
    // Nearby callbacks are delivered from Play services threads and can burst while the
    // coroutine collector is decoding a payload. A bounded SharedFlow silently drops events
    // when full, which can strand a connection or lose a transfer completion. An unlimited
    // channel preserves callback ordering and applies backpressure only to the collector.
    private val _events = Channel<NearbyPlatformEvent>(Channel.UNLIMITED)
    override val events: Flow<NearbyPlatformEvent> = _events.receiveAsFlow()

    private fun emitEvent(event: NearbyPlatformEvent) {
        // The channel remains open for the lifetime of this platform. trySend keeps Play
        // services callbacks non-blocking while still retaining every event in the queue.
        _events.trySend(event)
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            emitEvent(
                NearbyPlatformEvent.ConnectionInitiated(
                    endpointId = endpointId,
                    endpointName = info.endpointName,
                    authenticationDigits = info.authenticationDigits,
                    incoming = info.isIncomingConnection,
                ),
            )
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                emitEvent(NearbyPlatformEvent.ConnectionSucceeded(endpointId))
            } else {
                emitEvent(
                    NearbyPlatformEvent.ConnectionFailed(
                        endpointId,
                        ConnectionsStatusCodes.getStatusCodeString(resolution.status.statusCode),
                    ),
                )
            }
        }

        override fun onDisconnected(endpointId: String) {
            emitEvent(NearbyPlatformEvent.Disconnected(endpointId))
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            emitEvent(NearbyPlatformEvent.EndpointFound(endpointId, info.endpointName))
        }

        override fun onEndpointLost(endpointId: String) {
            emitEvent(NearbyPlatformEvent.EndpointLost(endpointId))
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                payload.asBytes()?.let { emitEvent(NearbyPlatformEvent.BytesReceived(endpointId, it.copyOf())) }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> emitEvent(
                    NearbyPlatformEvent.PayloadTransferSucceeded(endpointId, update.payloadId),
                )
                PayloadTransferUpdate.Status.FAILURE -> emitEvent(
                    NearbyPlatformEvent.PayloadTransferFailed(endpointId, update.payloadId, "transfer failed"),
                )
                PayloadTransferUpdate.Status.CANCELED -> emitEvent(
                    NearbyPlatformEvent.PayloadTransferFailed(endpointId, update.payloadId, "transfer canceled"),
                )
            }
        }
    }

    override suspend fun startAdvertising(localEndpointName: String) {
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startAdvertising(localEndpointName, serviceId, lifecycleCallback, options).awaitUnit()
    }

    override suspend fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startDiscovery(serviceId, discoveryCallback, options).awaitUnit()
    }

    override suspend fun requestConnection(localEndpointName: String, endpointId: String) {
        client.requestConnection(localEndpointName, endpointId, lifecycleCallback).awaitUnit()
    }

    override suspend fun acceptConnection(endpointId: String) {
        client.acceptConnection(endpointId, payloadCallback).awaitUnit()
    }

    override suspend fun rejectConnection(endpointId: String) {
        client.rejectConnection(endpointId).awaitUnit()
    }

    override fun disconnect(endpointId: String) = client.disconnectFromEndpoint(endpointId)

    override suspend fun sendBytes(
        endpointId: String,
        bytes: ByteArray,
        onPayloadCreated: (Long) -> Unit,
    ): Long {
        require(bytes.size <= ConnectionsClient.MAX_BYTES_DATA_SIZE) { "payload exceeds Nearby BYTES limit" }
        val payload = Payload.fromBytes(bytes.copyOf())
        onPayloadCreated(payload.id)
        client.sendPayload(endpointId, payload).awaitUnit()
        return payload.id
    }

    override fun stopAll() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
    }
}

/** A cancelled Google Play services task is a failed Nearby operation, not cancellation of the
 * caller's long-lived transport coroutine. */
private class NearbyTaskCancelledException : Exception("Nearby operation was cancelled")

private suspend fun Task<Void>.awaitUnit(): Unit = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(Unit) }
    addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
    addOnCanceledListener {
        if (continuation.isActive) continuation.resumeWithException(NearbyTaskCancelledException())
    }
}
