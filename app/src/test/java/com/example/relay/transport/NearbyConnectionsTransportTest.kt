package com.example.relay.transport

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyConnectionsTransportTest {
    @Test
    fun `platform discovery maps endpoint name to domain peer and loss removes it`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("local", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()

        platform.events.emit(NearbyPlatformEvent.EndpointFound("endpoint-1", "device-B"))
        runCurrent()
        assertEquals(listOf(Peer("device-B")), transport.discoveredPeers.value)

        platform.events.emit(NearbyPlatformEvent.EndpointLost("endpoint-1"))
        runCurrent()
        assertTrue(transport.discoveredPeers.value.isEmpty())
    }

    @Test
    fun `connection is accepted automatically and does not require user approval`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("local", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()
        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("endpoint-1", "device-B", "1234", true))
        runCurrent()

        assertEquals(listOf("endpoint-1"), platform.accepted)
        assertTrue(transport.state.value.pendingVerifications.isEmpty())

        transport.disconnect("device-B")
        assertFalse("device-B" in transport.state.value.pendingVerifications)
        assertFalse("device-B" in transport.state.value.connectedPeerIds)
    }

    @Test
    fun `disconnect updates connected peer state`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("local", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()
        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("endpoint-1", "device-B", "1234", true))
        runCurrent()
        transport.acceptConnection("device-B")
        platform.events.emit(NearbyPlatformEvent.ConnectionSucceeded("endpoint-1"))
        runCurrent()
        assertTrue("device-B" in transport.state.value.connectedPeerIds)

        platform.events.emit(NearbyPlatformEvent.Disconnected("endpoint-1"))
        runCurrent()
        assertFalse("device-B" in transport.state.value.connectedPeerIds)
    }

    @Test
    fun `send requires approved connected peer and waits for transfer success`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("local", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()
        assertTrue(transport.send("device-B", byteArrayOf(1)) is SendResult.Failed)
        assertTrue(platform.sent.isEmpty())

        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("endpoint-1", "device-B", "1234", true))
        runCurrent()
        transport.acceptConnection("device-B")
        platform.events.emit(NearbyPlatformEvent.ConnectionSucceeded("endpoint-1"))
        runCurrent()

        val result = async { transport.send("device-B", byteArrayOf(1, 2)) }
        runCurrent()
        assertEquals(1, platform.sent.size)
        platform.events.emit(NearbyPlatformEvent.PayloadTransferSucceeded("endpoint-1", 100L))
        runCurrent()
        assertEquals(SendResult.PayloadTransferCompleted, result.await())
    }

    @Test
    fun `transfer success delivered before send returns is not lost`() = runTest {
        val platform = FakeNearbyPlatform(completeDuringSend = true)
        val transport = NearbyConnectionsTransport("local", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()
        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("endpoint-1", "device-B", "1234", true))
        runCurrent()
        transport.acceptConnection("device-B")
        platform.events.emit(NearbyPlatformEvent.ConnectionSucceeded("endpoint-1"))
        runCurrent()

        val result = async { transport.send("device-B", byteArrayOf(1, 2)) }
        runCurrent()

        assertEquals(SendResult.PayloadTransferCompleted, result.await())
    }

    @Test
    fun `incoming transfer update is not reported as outgoing completion`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("local", platform, AllowedNearbyPermissionGate, backgroundScope)
        val observed = mutableListOf<TransportEvent>()
        backgroundScope.launch { transport.transportEvents.collect(observed::add) }
        transport.start()
        platform.events.emit(NearbyPlatformEvent.EndpointFound("endpoint-1", "device-B"))
        platform.events.emit(NearbyPlatformEvent.PayloadTransferSucceeded("endpoint-1", 999L))
        runCurrent()

        assertFalse(observed.any { it is TransportEvent.PayloadTransferCompleted })
    }

    @Test
    fun `double start is idempotent and missing permission never invokes platform`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("local", platform, DeniedNearbyPermissionGate, backgroundScope)
        transport.start()
        transport.start()
        assertEquals(0, platform.advertisingStarts)
        assertEquals(0, platform.discoveryStarts)

        val allowed = NearbyConnectionsTransport("local", platform, AllowedNearbyPermissionGate, backgroundScope)
        allowed.start()
        allowed.start()
        assertEquals(1, platform.advertisingStarts)
        assertEquals(1, platform.discoveryStarts)
    }
}

private class FakeNearbyPlatform(
    private val completeDuringSend: Boolean = false,
) : NearbyPlatform {
    override val events = MutableSharedFlow<NearbyPlatformEvent>(extraBufferCapacity = 32)
    var advertisingStarts = 0
    var discoveryStarts = 0
    val accepted = mutableListOf<String>()
    val rejected = mutableListOf<String>()
    val sent = mutableListOf<Pair<String, ByteArray>>()

    override suspend fun startAdvertising(localEndpointName: String) { advertisingStarts++ }
    override suspend fun startDiscovery() { discoveryStarts++ }
    override suspend fun requestConnection(localEndpointName: String, endpointId: String) = Unit
    override suspend fun acceptConnection(endpointId: String) { accepted += endpointId }
    override suspend fun rejectConnection(endpointId: String) { rejected += endpointId }
    override fun disconnect(endpointId: String) = Unit
    override suspend fun sendBytes(endpointId: String, bytes: ByteArray, onPayloadCreated: (Long) -> Unit): Long {
        sent += endpointId to bytes
        onPayloadCreated(100L)
        if (completeDuringSend) events.emit(NearbyPlatformEvent.PayloadTransferSucceeded(endpointId, 100L))
        return 100L
    }
    override fun stopAll() = Unit
}
