package com.example.relay.gateway

import com.example.relay.domain.DeliveryReceipt
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.MessageRepository
import com.example.relay.domain.OperatingMode
import com.example.relay.domain.RelayRuntimeSettings
import com.example.relay.domain.ReceiptType
import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GatewaySyncEngine(
    private val repository: MessageRepository,
    private val settingsStore: GatewaySettingsStoreContract,
    private val credentialStore: GatewayCredentialStoreContract,
    private val client: GatewayBridgeClient,
    private val policy: MessagePolicy,
    private val scope: CoroutineScope,
    private val pendingStore: GatewayPendingStoreContract,
    private val discovery: GatewayDiscovery? = null,
    private val localBridgeId: String = "",
) {
    private val mutex = Mutex()
    private val running = AtomicBoolean(false)
    private var job: Job? = null

    suspend fun start(runtime: RelayRuntimeSettings): Boolean {
        if (runtime.mode == OperatingMode.NORMAL) return false
        if (!running.compareAndSet(false, true)) return true
        if (settingsStore.load().automaticSync) job = scope.launch { loop() }
        return true
    }

    suspend fun stop() { running.set(false); job?.cancel(); job = null }

    suspend fun requestPair(code: String): Boolean {
        val settings = settingsStore.load()
        return runCatching { client.requestPair(settings, code) }.getOrDefault(false)
    }

    suspend fun syncOnce(): GatewaySyncResult = mutex.withLock {
        val settings = settingsStore.load()
        val token = credentialStore.load()
        if (settings.enabled && settings.host.isNotBlank() && settings.bridgeId.isNotBlank() && token.isNullOrBlank() && discovery == null) {
            return@withLock GatewaySyncResult.Deferred("gateway_token_missing")
        }
        val all = repository.all()
        pendingStore.add(all.map { it.messageId })
        val pendingIds = pendingStore.ids()
        val messages = all.asSequence().filter { it.messageId in pendingIds }.filter { policy.prepareForTransfer(it) != null }.sortedWith(compareByDescending<com.example.relay.domain.RelayMessage> { it.priority }.thenByDescending { it.createdAt }).take(128).mapNotNull(policy::prepareForTransfer).toList()
        if (messages.isEmpty()) return@withLock GatewaySyncResult.Completed(0, 0)
        return@withLock try {
            val push = if (settings.enabled && settings.host.isNotBlank() && settings.bridgeId.isNotBlank() && !token.isNullOrBlank()) {
                client.push(settings, token, messages)
            } else {
                val gateway = discovery?.discover() ?: return@withLock GatewaySyncResult.Deferred("gateway_not_found")
                if (localBridgeId.isBlank()) return@withLock GatewaySyncResult.Deferred("bridge_identity_missing")
                client.pushPublic(gateway, localBridgeId, "Relay Bridge", messages)
            }
            pendingStore.remove(push.response.acceptedMessageIds + push.response.duplicateMessageIds + push.response.rejected.map { it.messageId })
            val receipts = if (settings.enabled && settings.host.isNotBlank() && settings.bridgeId.isNotBlank() && !token.isNullOrBlank()) {
                client.pullReceipts(settings, token)
            } else {
                push.response.receipts.mapNotNull { receipt ->
                    when (receipt.receiptType) {
                        "GATEWAY_RECEIVED" -> DeliveryReceipt(receipt.receiptId, receipt.messageId, ReceiptType.GATEWAY_RECEIVED, receipt.actorId, receipt.recordedAt)
                        "GATEWAY_RECEIVED_UNVERIFIED" -> DeliveryReceipt(receipt.receiptId, receipt.messageId, ReceiptType.GATEWAY_RECEIVED_UNVERIFIED, receipt.actorId, receipt.recordedAt)
                        else -> null
                    }
                }
            }
            receipts.filter { it.receiptType == ReceiptType.GATEWAY_RECEIVED || it.receiptType == ReceiptType.GATEWAY_RECEIVED_UNVERIFIED }
                .filter { repository.find(it.messageId) != null }
                .forEach { repository.insertReceipt(it) }
            settingsStore.record("sent=${push.response.acceptedMessageIds.size}, duplicate=${push.response.duplicateMessageIds.size}, receipts=${receipts.size}")
            GatewaySyncResult.Completed(push.response.acceptedMessageIds.size + push.response.duplicateMessageIds.size, receipts.size)
        } catch (error: GatewayHttpException) {
            settingsStore.record("http_${error.status}")
            GatewaySyncResult.Failed("http_${error.status}", retryable = error.status >= 500 || error.status == 429)
        } catch (error: Exception) {
            settingsStore.record("network_error")
            GatewaySyncResult.Failed("network_error")
        }
    }

    private suspend fun loop() {
        var delayMs = 5_000L
        while (scope.coroutineContext.isActive && running.get()) {
            when (syncOnce()) {
                is GatewaySyncResult.Completed -> delayMs = 5_000L
                is GatewaySyncResult.Deferred -> delayMs = 30_000L
                is GatewaySyncResult.Failed -> delayMs = (delayMs * 2).coerceAtMost(15 * 60_000L)
            }
            delay(delayMs)
        }
    }
}

interface GatewayPendingStoreContract {
    fun ids(): Set<String>
    fun add(messageIds: List<String>)
    fun remove(messageIds: List<String>)
}

class SharedPreferencesGatewayPendingStore(context: Context) : GatewayPendingStoreContract {
    private val preferences = context.getSharedPreferences("relay_gateway_pending", Context.MODE_PRIVATE)
    @Synchronized override fun ids(): Set<String> = preferences.getStringSet("ids", emptySet()).orEmpty()
    @Synchronized override fun add(messageIds: List<String>) { preferences.edit().putStringSet("ids", ids() + messageIds).apply() }
    @Synchronized override fun remove(messageIds: List<String>) { preferences.edit().putStringSet("ids", ids() - messageIds.toSet()).apply() }
}
