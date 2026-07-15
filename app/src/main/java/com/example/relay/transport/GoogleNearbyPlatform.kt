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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine

class GoogleNearbyPlatform(
    context: Context,
    private val serviceId: String,
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext),
) : NearbyPlatform {
    private val _events = MutableSharedFlow<NearbyPlatformEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<NearbyPlatformEvent> = _events

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            _events.tryEmit(
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
                _events.tryEmit(NearbyPlatformEvent.ConnectionSucceeded(endpointId))
            } else {
                _events.tryEmit(
                    NearbyPlatformEvent.ConnectionFailed(
                        endpointId,
                        ConnectionsStatusCodes.getStatusCodeString(resolution.status.statusCode),
                    ),
                )
            }
        }

        override fun onDisconnected(endpointId: String) {
            _events.tryEmit(NearbyPlatformEvent.Disconnected(endpointId))
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            _events.tryEmit(NearbyPlatformEvent.EndpointFound(endpointId, info.endpointName))
        }

        override fun onEndpointLost(endpointId: String) {
            _events.tryEmit(NearbyPlatformEvent.EndpointLost(endpointId))
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                payload.asBytes()?.let { _events.tryEmit(NearbyPlatformEvent.BytesReceived(endpointId, it.copyOf())) }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> _events.tryEmit(NearbyPlatformEvent.PayloadTransferSucceeded(endpointId, update.payloadId))
                PayloadTransferUpdate.Status.FAILURE -> _events.tryEmit(NearbyPlatformEvent.PayloadTransferFailed(endpointId, update.payloadId, "transfer failed"))
                PayloadTransferUpdate.Status.CANCELED -> _events.tryEmit(NearbyPlatformEvent.PayloadTransferFailed(endpointId, update.payloadId, "transfer canceled"))
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

private suspend fun Task<Void>.awaitUnit(): Unit = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(Unit) }
    addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}
