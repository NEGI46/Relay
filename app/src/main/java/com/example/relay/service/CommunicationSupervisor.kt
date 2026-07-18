package com.example.relay.service

import com.example.relay.domain.RelayRuntimeSettings
import com.example.relay.gateway.GatewaySyncEngine
import com.example.relay.runtime.CommunicationRuntimeState
import com.example.relay.runtime.RelayCommunicationRuntime
import kotlinx.coroutines.flow.StateFlow

/** Owns the paired Nearby and PC Gateway lifecycle; Android adapters only observe it. */
class CommunicationSupervisor(
    private val runtime: RelayCommunicationRuntime,
    private val gateway: GatewaySyncEngine,
) {
    val state: StateFlow<CommunicationRuntimeState> = runtime.state

    suspend fun start(settings: RelayRuntimeSettings): Boolean {
        if (!runtime.start(settings)) return false
        gateway.start(settings)
        return true
    }

    suspend fun stop() {
        gateway.stop()
        runtime.stop()
    }

    fun reportStartFailure(reason: String) = runtime.reportStartFailure(reason)
}
