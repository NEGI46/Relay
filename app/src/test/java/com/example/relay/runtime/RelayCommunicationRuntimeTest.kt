package com.example.relay.runtime

import com.example.relay.domain.OperatingMode
import com.example.relay.domain.RelayRuntimeSettings
import com.example.relay.sync.SyncDebugEvent
import com.example.relay.sync.SyncSession
import com.example.relay.transport.FakeNetwork
import com.example.relay.transport.FakeOfflineTransport
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayCommunicationRuntimeTest {
    @Test
    fun `normal mode cannot start and double start is idempotent`() = runTest {
        val session = FakeSyncSession()
        val runtime = RelayCommunicationRuntime(
            FakeOfflineTransport("local", FakeNetwork()),
            session,
            backgroundScope,
        )

        assertFalse(runtime.start(RelayRuntimeSettings(OperatingMode.NORMAL)))
        assertTrue(runtime.start(RelayRuntimeSettings(OperatingMode.DRILL)))
        assertTrue(runtime.start(RelayRuntimeSettings(OperatingMode.DRILL)))
        assertEquals(1, session.starts)
    }

    @Test
    fun `service stop seam stops sync and clears running state once`() = runTest {
        val session = FakeSyncSession()
        val runtime = RelayCommunicationRuntime(
            FakeOfflineTransport("local", FakeNetwork()),
            session,
            backgroundScope,
        )
        runtime.start(RelayRuntimeSettings(OperatingMode.RELAY))

        runtime.stop()
        runtime.stop()

        assertEquals(1, session.stops)
        assertFalse(runtime.state.value.running)
    }
}

private class FakeSyncSession : SyncSession {
    override val debugEvents = MutableSharedFlow<SyncDebugEvent>()
    var starts = 0
    var stops = 0
    override suspend fun start(settings: RelayRuntimeSettings): Boolean { starts++; return true }
    override suspend fun stop() { stops++ }
}
