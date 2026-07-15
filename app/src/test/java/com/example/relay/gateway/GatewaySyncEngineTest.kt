package com.example.relay.gateway

import com.example.relay.NOW
import com.example.relay.domain.DeviceRole
import com.example.relay.domain.InMemoryMessageRepository
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.MutableClock
import com.example.relay.domain.OperatingMode
import com.example.relay.domain.RelayMessage
import com.example.relay.domain.RelayRuntimeSettings
import com.example.relay.gateway.protocol.GatewayReceipt
import com.example.relay.gateway.protocol.SyncMessagesResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewaySyncEngineTest {
    @Test fun `successful PC save response creates local Gateway Receipt and clears durable pending ids`() = runTest {
        val repository = InMemoryMessageRepository()
        val clock = MutableClock(NOW)
        repository.insert(com.example.relay.message())
        val settings = FakeSettings(GatewaySettings("127.0.0.1", 8080, "gateway", "bridge", enabled = true))
        val pending = FakePending()
        val client = FakeClient()
        val engine = GatewaySyncEngine(repository, settings, FakeCredentials("token"), client, MessagePolicy(clock), backgroundScope, pending)

        assertTrue(engine.start(RelayRuntimeSettings(OperatingMode.DRILL, DeviceRole.GATEWAY)))
        assertEquals(GatewaySyncResult.Completed(1, 1), engine.syncOnce())
        assertTrue(pending.removed.contains("message-1"))
        assertEquals(1, repository.allReceipts().size)
        assertEquals("GATEWAY_RECEIVED", repository.allReceipts().single().receiptType.name)
    }

    @Test fun `missing token never calls PC client`() = runTest {
        val client = FakeClient()
        val engine = GatewaySyncEngine(InMemoryMessageRepository(), FakeSettings(GatewaySettings("host", 8080, "gateway", "bridge", enabled = true)), FakeCredentials(null), client, MessagePolicy(MutableClock(NOW)), backgroundScope, FakePending())
        assertEquals(GatewaySyncResult.Deferred("gateway_token_missing"), engine.syncOnce())
        assertEquals(0, client.pushes)
    }

    @Test fun `unconfigured bridge uses discovered public gateway without token`() = runTest {
        val repository = InMemoryMessageRepository().also { it.insert(com.example.relay.message()) }
        val client = FakeClient()
        val engine = GatewaySyncEngine(
            repository,
            FakeSettings(GatewaySettings()),
            FakeCredentials(null),
            client,
            MessagePolicy(MutableClock(NOW)),
            backgroundScope,
            FakePending(),
            discovery = object : GatewayDiscovery {
                override suspend fun discover(timeoutMs: Int) = DiscoveredGateway("127.0.0.1", 8080, "gateway")
            },
            localBridgeId = "bridge-local",
        )
        assertEquals(GatewaySyncResult.Completed(1, 0), engine.syncOnce())
        assertEquals(1, client.publicPushes)
    }
}

private class FakeSettings(private var value: GatewaySettings) : GatewaySettingsStoreContract {
    override fun load() = value
    override fun save(settings: GatewaySettings) { value = settings }
    override fun record(result: String, connectedAt: Long) { value = value.copy(lastSyncResult = result, lastConnectedAt = connectedAt) }
}
private class FakeCredentials(private val value: String?) : GatewayCredentialStoreContract {
    override fun save(token: String) = Unit
    override fun load() = value
    override fun clear() = Unit
    override fun hasToken() = value != null
}
private class FakePending : GatewayPendingStoreContract {
    private val values = linkedSetOf<String>()
    val removed = mutableListOf<String>()
    override fun ids() = values.toSet()
    override fun add(messageIds: List<String>) { values += messageIds }
    override fun remove(messageIds: List<String>) { values -= messageIds.toSet(); removed += messageIds }
}
private class FakeClient : GatewayBridgeClient {
    var pushes = 0
    var publicPushes = 0
    override suspend fun requestPair(settings: GatewaySettings, code: String) = true
    override suspend fun push(settings: GatewaySettings, token: String, messages: List<RelayMessage>): GatewayPushResult { pushes++; return GatewayPushResult(SyncMessagesResponse(acceptedMessageIds = messages.map { it.messageId })) }
    override suspend fun pullReceipts(settings: GatewaySettings, token: String) = listOf(com.example.relay.domain.DeliveryReceipt("receipt-1", "message-1", com.example.relay.domain.ReceiptType.GATEWAY_RECEIVED, "gateway", NOW))
    override suspend fun pushPublic(gateway: DiscoveredGateway, bridgeId: String, bridgeName: String, messages: List<RelayMessage>): GatewayPushResult {
        publicPushes++
        return GatewayPushResult(SyncMessagesResponse(acceptedMessageIds = messages.map { it.messageId }))
    }
}
