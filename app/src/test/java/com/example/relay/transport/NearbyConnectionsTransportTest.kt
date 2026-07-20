package com.example.relay.transport

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyConnectionsTransportTest {
    @Test
    companion object {
        private const val ENDPOINT_ID = "endpoint-1"
    }

    fun `platform discovery maps endpoint name to domain peer and loss removes it`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("local", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()

        platform.events.emit(NearbyPlatformEvent.EndpointFound(ENDPOINT_ID, "device-B"))
        runCurrent()
        assertEquals(listOf(Peer("device-B")), transport.discoveredPeers.value)

        platform.events.emit(NearbyPlatformEvent.EndpointLost(ENDPOINT_ID))
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
    private companion object {
        private const val ENDPOINT_ID = "endpoint-1"
        private const val DEVICE_B = "device-B"
    }
    fun `outgoing connection request is not duplicated when initiation callback arrives`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("device-A", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()

        platform.events.emit(NearbyPlatformEvent.EndpointFound(ENDPOINT_ID, DEVICE_B))
        runCurrent()
        assertEquals(listOf(ENDPOINT_ID), platform.requested)

        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated(ENDPOINT_ID, DEVICE_B, "1234", false))
        runCurrent()

        assertEquals(listOf(ENDPOINT_ID), platform.requested)
        assertEquals(listOf(ENDPOINT_ID), platform.accepted)
    }

    @Test
    private companion object {
        private const val ENDPOINT_ID = "endpoint-1"
        private const val FAILURE_TYPE = "transient"
    }

    fun `failed deterministic initiator retries with exponential backoff`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("device-A", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()
        platform.events.emit(NearbyPlatformEvent.EndpointFound(ENDPOINT_ID, "device-B"))
        runCurrent()
        assertEquals(1, platform.requested.size)

        platform.events.emit(NearbyPlatformEvent.ConnectionFailed(ENDPOINT_ID, FAILURE_TYPE))
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, platform.requested.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, platform.requested.size)

        platform.events.emit(NearbyPlatformEvent.ConnectionFailed(ENDPOINT_ID, FAILURE_TYPE))
        runCurrent()
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(2, platform.requested.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, platform.requested.size)
    }

    @Test
    fun `missing connection result times out and retries instead of remaining connecting forever`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport(
            "device-A",
            platform,
            AllowedNearbyPermissionGate,
            backgroundScope,
            reconnectBaseDelayMs = 1_000,
            reconnectMaxDelayMs = 1_000,
        )
        val connectionEvents = mutableListOf<ConnectionEvent>()
        backgroundScope.launch { transport.connectionEvents.collect(connectionEvents::add) }
        transport.start()

        platform.events.emit(NearbyPlatformEvent.EndpointFound("endpoint-1", "device-B"))
        runCurrent()
        assertEquals(1, platform.requested.size)

        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(1, platform.requested.size)
        assertFalse(connectionEvents.any { it is ConnectionEvent.Failed })

        advanceTimeBy(1)
        runCurrent()
        assertTrue(
            connectionEvents.any {
                it is ConnectionEvent.Failed &&
                    it.peerId == "device-B" &&
                    it.reason == "connection attempt timed out"
            },
        )

        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, platform.requested.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, platform.requested.size)
    }

    @Test
    fun `successful connection cancels the connection attempt timeout`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport(
            "device-A",
            platform,
            AllowedNearbyPermissionGate,
            backgroundScope,
            reconnectBaseDelayMs = 1_000,
            reconnectMaxDelayMs = 1_000,
        )
        val connectionEvents = mutableListOf<ConnectionEvent>()
        backgroundScope.launch { transport.connectionEvents.collect(connectionEvents::add) }
        transport.start()

        platform.events.emit(NearbyPlatformEvent.EndpointFound("endpoint-1", "device-B"))
        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("endpoint-1", "device-B", "1234", false))
        platform.events.emit(NearbyPlatformEvent.ConnectionSucceeded("endpoint-1"))
        runCurrent()

        advanceTimeBy(31_000)
        runCurrent()

        assertEquals(1, platform.requested.size)
        assertFalse(connectionEvents.any { it is ConnectionEvent.Failed })
        assertTrue("device-B" in transport.state.value.connectedPeerIds)
    }

    @Test
    fun `incoming connection also times out when no result callback arrives`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("device-B", platform, AllowedNearbyPermissionGate, backgroundScope)
        val connectionEvents = mutableListOf<ConnectionEvent>()
        backgroundScope.launch { transport.connectionEvents.collect(connectionEvents::add) }
        transport.start()

        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("endpoint-1", "device-A", "1234", true))
        runCurrent()
        assertEquals(listOf("endpoint-1"), platform.accepted)

        advanceTimeBy(30_000)
        runCurrent()

        assertTrue(
            connectionEvents.any {
                it is ConnectionEvent.Failed &&
                    it.peerId == "device-A" &&
                    it.reason == "connection attempt timed out"
            },
        )
        assertTrue(platform.requested.isEmpty())
    }

    @Test
    fun `late success after connection timeout cannot resurrect the peer`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("device-A", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()
        platform.events.emit(NearbyPlatformEvent.EndpointFound("endpoint-1", "device-B"))
        runCurrent()

        advanceTimeBy(30_000)
        runCurrent()
        platform.events.emit(NearbyPlatformEvent.ConnectionSucceeded("endpoint-1"))
        runCurrent()

        assertFalse("device-B" in transport.state.value.connectedPeerIds)
        assertTrue("endpoint-1" in platform.disconnected)
    }

    @Test
    fun `peer loss cancels a scheduled reconnect`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("device-A", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()
        platform.events.emit(NearbyPlatformEvent.EndpointFound("endpoint-1", "device-B"))
        platform.events.emit(NearbyPlatformEvent.ConnectionFailed("endpoint-1", "transient"))
        runCurrent()

        platform.events.emit(NearbyPlatformEvent.EndpointLost("endpoint-1"))
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(listOf("endpoint-1"), platform.requested)
    }

    @Test
    fun `transport stop cancels a scheduled reconnect`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("device-A", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()
        platform.events.emit(NearbyPlatformEvent.EndpointFound("endpoint-1", "device-B"))
        platform.events.emit(NearbyPlatformEvent.ConnectionFailed("endpoint-1", "transient"))
        runCurrent()

        transport.stop()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(listOf("endpoint-1"), platform.requested)
    }

    @Test
    fun `non initiator accepts incoming connection but never schedules reconnect`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("device-B", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()
        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("endpoint-1", "device-A", "1234", true))
        platform.events.emit(NearbyPlatformEvent.ConnectionSucceeded("endpoint-1"))
        platform.events.emit(NearbyPlatformEvent.Disconnected("endpoint-1"))
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        assertTrue(platform.requested.isEmpty())
        assertEquals(listOf("endpoint-1"), platform.accepted)
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
    val requested = mutableListOf<String>()
    val disconnected = mutableListOf<String>()
    val sent = mutableListOf<Pair<String, ByteArray>>()

    override suspend fun startAdvertising(localEndpointName: String) { advertisingStarts++ }
    override suspend fun startDiscovery() { discoveryStarts++ }
    override suspend fun requestConnection(localEndpointName: String, endpointId: String) { requested += endpointId }
    override suspend fun acceptConnection(endpointId: String) { accepted += endpointId }
    override suspend fun rejectConnection(endpointId: String) { rejected += endpointId }
    override fun disconnect(endpointId: String) { disconnected += endpointId }
    override suspend fun sendBytes(endpointId: String, bytes: ByteArray, onPayloadCreated: (Long) -> Unit): Long {
        sent += endpointId to bytes
        onPayloadCreated(100L)
        if (completeDuringSend) events.emit(NearbyPlatformEvent.PayloadTransferSucceeded(endpointId, 100L))
        return 100L
    }
    override fun stopAll() = Unit
}
