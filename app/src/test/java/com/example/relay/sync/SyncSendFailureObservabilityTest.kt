package com.example.relay.sync

import com.example.relay.NOW
import com.example.relay.domain.InMemoryMessageRepository
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.MutableClock
import com.example.relay.domain.OperatingMode
import com.example.relay.domain.RelayRuntimeSettings
import com.example.relay.message
import com.example.relay.protocol.PacketCodec
import com.example.relay.transport.FakeNetwork
import com.example.relay.transport.FakeOfflineTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DEVICE_A = "device-A"
private const val DEVICE_B = "device-B"

/**
 * Failed transport sends must surface as SendFailed/Rejected — never as PayloadTransferCompleted.
 * Exercises the real [SyncCoordinator] + [FakeOfflineTransport] path.
 */
class SyncSendFailureObservabilityTest {
    @Test
    fun `transport send failure emits SendFailed and does not record payload transfer`() = runBlocking {
        val network = FakeNetwork()
        val clock = MutableClock(NOW)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val aRepo = InMemoryMessageRepository().also { it.insert(message(id = "message-fail-1")) }
        val bRepo = InMemoryMessageRepository()
        val aTransport = FakeOfflineTransport(DEVICE_A, network)
        val bTransport = FakeOfflineTransport(DEVICE_B, network)
        val aPolicy = MessagePolicy(clock)
        val bPolicy = MessagePolicy(clock)
        val a = SyncCoordinator(
            DEVICE_A,
            aTransport,
            aRepo,
            SyncPlanner(aRepo, aPolicy),
            aPolicy,
            PacketCodec(aPolicy),
            clock,
            scope,
        )
        val b = SyncCoordinator(
            DEVICE_B,
            bTransport,
            bRepo,
            SyncPlanner(bRepo, bPolicy),
            bPolicy,
            PacketCodec(bPolicy),
            clock,
            scope,
        )

        val observed = mutableListOf<SyncDebugEvent>()
        val collector = scope.launch {
            a.debugEvents.collect { observed += it }
        }

        a.start(RelayRuntimeSettings(OperatingMode.DRILL))
        b.start(RelayRuntimeSettings(OperatingMode.DRILL))
        aTransport.sendFailureReason = "link dropped mid-transfer"
        aTransport.connect("device-B")

        withTimeout(5_000) {
            while (observed.none { it is SyncDebugEvent.SendFailed }) {
                kotlinx.coroutines.yield()
            }
        }

        val failed = observed.filterIsInstance<SyncDebugEvent.SendFailed>()
        assertTrue("expected SendFailed events, got $observed", failed.isNotEmpty())
        assertTrue(failed.any { it.reason.contains("link dropped") })
        assertFalse(
            "must not claim transfer completed after failure",
            observed.any { it is SyncDebugEvent.PayloadTransferCompleted },
        )
        assertNull(a.payloadTransfer("message-fail-1", "device-B"))
        assertEquals(0, observed.filterIsInstance<SyncDebugEvent.PayloadTransferCompleted>().size)

        collector.cancel()
        scope.cancel()
    }

    @Test
    fun `successful send after failure can complete without prior false completion`() = runBlocking {
        val network = FakeNetwork()
        val clock = MutableClock(NOW)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val aRepo = InMemoryMessageRepository().also { it.insert(message(id = MESSAGE_RECOVER_ID)) }
        val bRepo = InMemoryMessageRepository()
        val aTransport = FakeOfflineTransport("device-A", network)
        val bTransport = FakeOfflineTransport("device-B", network)
        val aPolicy = MessagePolicy(clock)
        val bPolicy = MessagePolicy(clock)
        val a = SyncCoordinator(
            "device-A", aTransport, aRepo, SyncPlanner(aRepo, aPolicy), aPolicy,
            PacketCodec(aPolicy), clock, scope,
        )
        val b = SyncCoordinator(
            "device-B", bTransport, bRepo, SyncPlanner(bRepo, bPolicy), bPolicy,
            PacketCodec(bPolicy), clock, scope,
        )

        a.start(RelayRuntimeSettings(OperatingMode.DRILL))
        b.start(RelayRuntimeSettings(OperatingMode.DRILL))
        aTransport.sendFailureReason = "temporary radio error"
        aTransport.connect("device-B")

        withTimeout(5_000) {
            a.debugEvents.first { it is SyncDebugEvent.SendFailed }
        }
        assertNull(a.payloadTransfer("message-recover", "device-B"))

        // Clear forced failures and reconnect so real SyncCoordinator path can deliver.
        aTransport.clearSendFailure()
        aTransport.disconnect("device-B")
        aTransport.connect("device-B")

        withTimeout(5_000) {
            while (bRepo.find("message-recover") == null) {
                kotlinx.coroutines.yield()
            }
        }
        assertTrue(bRepo.find("message-recover") != null)

        scope.cancel()
    }
}
