package com.example.relay.sync

import com.example.relay.domain.Clock
import com.example.relay.domain.InsertResult
import com.example.relay.domain.MessageDelivery
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.MessageRepository
import com.example.relay.domain.DeliveryReceipt
import com.example.relay.domain.DeviceRole
import com.example.relay.domain.OperatingMode
import com.example.relay.domain.RelayRuntimeSettings
import com.example.relay.domain.ReceiptType
import com.example.relay.domain.ResourcePolicy
import com.example.relay.domain.PayloadTransfer
import com.example.relay.protocol.AckBody
import com.example.relay.protocol.DecodeResult
import com.example.relay.protocol.DecodedPacket
import com.example.relay.protocol.ErrorBody
import com.example.relay.protocol.HelloBody
import com.example.relay.protocol.AllowAllIncomingPayloads
import com.example.relay.protocol.IncomingPayloadPolicy
import com.example.relay.protocol.ManifestBody
import com.example.relay.protocol.MessageDataBody
import com.example.relay.protocol.MessageRequestBody
import com.example.relay.protocol.ReceiptDataBody
import com.example.relay.protocol.PacketCodec
import com.example.relay.transport.ConnectionEvent
import com.example.relay.transport.OfflineTransport
import com.example.relay.transport.SendResult
import java.util.LinkedHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

sealed interface SyncDebugEvent {
    data class Rejected(val peerId: String, val reason: String) : SyncDebugEvent
    data class PayloadTransferCompleted(val peerId: String, val itemId: String, val byteCount: Int) : SyncDebugEvent
    data class PeerAcknowledged(val peerId: String, val messageId: String) : SyncDebugEvent
    data class GatewayReceiptRecorded(val messageId: String, val actorId: String) : SyncDebugEvent
    data class MessageStored(val messageId: String, val duplicate: Boolean) : SyncDebugEvent
}

class SyncCoordinator(
    private val deviceId: String,
    private val transport: OfflineTransport,
    private val repository: MessageRepository,
    private val planner: SyncPlanner,
    private val policy: MessagePolicy,
    private val codec: PacketCodec,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val maxReplayEntries: Int = 1_024,
    private val incomingPayloadPolicy: IncomingPayloadPolicy = AllowAllIncomingPayloads,
    private val resourcePolicy: ResourcePolicy = ResourcePolicy(),
) : SyncSession {
    private val jobs = mutableListOf<Job>()
    private val replayCache = object : LinkedHashMap<String, Unit>(maxReplayEntries + 1, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > maxReplayEntries
    }
    private val dataPackets = mutableMapOf<Pair<String, String>, String>()
    private val payloadTransfers = mutableMapOf<Pair<String, String>, PayloadTransfer>()
    private val receivedItems = mutableMapOf<String, Int>()
    private val sentBytes = mutableMapOf<String, Long>()
    private val _debugEvents = kotlinx.coroutines.flow.MutableSharedFlow<SyncDebugEvent>(extraBufferCapacity = 64)
    override val debugEvents: kotlinx.coroutines.flow.SharedFlow<SyncDebugEvent> = _debugEvents
    private var started = false
    private var activeSettings = RelayRuntimeSettings()
    fun payloadTransfer(messageId: String, peerId: String): PayloadTransfer? = payloadTransfers[messageId to peerId]

    override suspend fun start(settings: RelayRuntimeSettings): Boolean {
        if (settings.mode == OperatingMode.NORMAL) {
            _debugEvents.tryEmit(SyncDebugEvent.Rejected("local", "communication requires DRILL or RELAY mode"))
            return false
        }
        if (started) return true
        activeSettings = settings
        jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.connectionEvents.collect { event ->
                if (event is ConnectionEvent.Connected) {
                    receivedItems[event.peer.peerId] = 0
                    sentBytes[event.peer.peerId] = 0
                    send(event.peer.peerId, HelloBody())
                    val messagePages = planner.manifest().chunked(128).ifEmpty { listOf(emptyList()) }
                    val receiptPages = repository.allReceipts().map { it.receiptId }.chunked(128).ifEmpty { listOf(emptyList()) }
                    repeat(maxOf(messagePages.size, receiptPages.size)) { index ->
                        send(event.peer.peerId, ManifestBody(messagePages.getOrElse(index) { emptyList() }, receiptPages.getOrElse(index) { emptyList() }))
                    }
                } else if (event is ConnectionEvent.Disconnected) {
                    receivedItems.remove(event.peerId)
                    sentBytes.remove(event.peerId)
                }
            }
        }
        jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.receivedPayloads.collect { incoming -> handle(incoming.peerId, incoming.bytes) }
        }
        return try {
            transport.start()
            if (!transport.state.value.started) {
                jobs.forEach { it.cancel() }
                jobs.clear()
                reject("local", transport.state.value.lastError ?: "transport did not start")
                return false
            }
            started = true
            true
        } catch (error: Exception) {
            jobs.forEach { it.cancel() }
            jobs.clear()
            reject("local", error.message ?: error.javaClass.simpleName)
            false
        }
    }

    override suspend fun stop() {
        transport.stop()
        jobs.forEach { it.cancel() }
        jobs.clear()
        started = false
    }

    private suspend fun handle(peerId: String, bytes: ByteArray) {
        if (bytes.size > resourcePolicy.maxPayloadBytes || !incomingPayloadPolicy.allow(peerId, bytes.size)) {
            reject(peerId, "payload limit")
            return
        }
        val count = (receivedItems[peerId] ?: 0) + 1
        if (count > resourcePolicy.maxReceivedItemsPerConnection) { reject(peerId, "received item limit"); return }
        receivedItems[peerId] = count
        val decoded = codec.decode(bytes)
        if (decoded !is DecodeResult.Success) {
            val reason = (decoded as? DecodeResult.Failure)?.error?.name ?: "decode failure"
            reject(peerId, reason)
            return
        }
        val packet = decoded.packet
        if (packet.envelope.senderDeviceId != peerId) return
        val replayKey = "$peerId:${packet.envelope.packetId}"
        if (replayCache.put(replayKey, Unit) != null) return
        when (val body = packet.body) {
            is HelloBody -> Unit
            is ManifestBody -> {
                val missing = planner.missingFromLocal(body.entries)
                val missingReceipts = planner.missingReceipts(body.receiptIds)
                val pages = maxOf((missing.size + 127) / 128, (missingReceipts.size + 127) / 128, 1)
                repeat(pages) { page -> send(peerId, MessageRequestBody(missing.drop(page * 128).take(128), missingReceipts.drop(page * 128).take(128))) }
            }
            is MessageRequestBody -> {
                planner.messagesToSend(peerId, body.messageIds).forEach { message ->
                val dataPacketId = java.util.UUID.randomUUID().toString()
                dataPackets[peerId to message.messageId] = dataPacketId
                send(peerId, MessageDataBody(message), dataPacketId)
                }
                planner.receiptsToSend(body.receiptIds).forEach { receipt -> send(peerId, ReceiptDataBody(receipt)) }
            }
            is MessageDataBody -> receiveData(peerId, packet)
            is ReceiptDataBody -> repository.insertReceipt(body.receipt)
            is AckBody -> receiveAck(peerId, body)
            is ErrorBody -> Unit
        }
    }

    private suspend fun receiveData(peerId: String, packet: DecodedPacket) {
        val body = packet.body as MessageDataBody
        val received = policy.receive(body.message) ?: return
        when (val insert = repository.insert(received)) {
            InsertResult.Inserted, InsertResult.Duplicate -> {
                _debugEvents.tryEmit(SyncDebugEvent.MessageStored(received.messageId, insert == InsertResult.Duplicate))
                val receipt = DeliveryReceipt(java.util.UUID.randomUUID().toString(), received.messageId, ReceiptType.PEER_RECEIVED, deviceId, clock.nowMillis())
                repository.insertReceipt(receipt)
                send(peerId, AckBody(received.messageId, packet.envelope.packetId, receipt))
                if (activeSettings.role == DeviceRole.GATEWAY) {
                    val gatewayReceipt = repository.receiptsFor(received.messageId).firstOrNull {
                        it.receiptType == ReceiptType.GATEWAY_RECEIVED && it.actorId == deviceId
                    } ?: DeliveryReceipt(
                        java.util.UUID.randomUUID().toString(),
                        received.messageId,
                        ReceiptType.GATEWAY_RECEIVED,
                        deviceId,
                        clock.nowMillis(),
                    )
                    when (repository.insertReceipt(gatewayReceipt)) {
                        InsertResult.Inserted, InsertResult.Duplicate -> {
                            _debugEvents.tryEmit(SyncDebugEvent.GatewayReceiptRecorded(received.messageId, deviceId))
                            send(peerId, ReceiptDataBody(gatewayReceipt))
                        }
                        InsertResult.Collision, is InsertResult.Rejected -> Unit
                    }
                }
            }
            InsertResult.Collision -> Unit
            is InsertResult.Rejected -> reject(peerId, "storage limit")
        }
    }

    private suspend fun receiveAck(peerId: String, body: AckBody) {
        val expectedPacket = dataPackets[peerId to body.messageId]
        if (expectedPacket != body.dataPacketId) return
        body.peerReceipt?.let { repository.insertReceipt(it) }
        if (repository.find(body.messageId) != null) {
            repository.markAcknowledged(MessageDelivery(body.messageId, peerId, clock.nowMillis(), body.dataPacketId))
            _debugEvents.tryEmit(SyncDebugEvent.PeerAcknowledged(peerId, body.messageId))
        }
    }

    private suspend fun send(
        peerId: String,
        body: com.example.relay.protocol.PacketBody,
        packetId: String = java.util.UUID.randomUUID().toString(),
    ): String {
        val encoded = codec.encode(deviceId, clock.nowMillis(), body, packetId)
        if (encoded.size > resourcePolicy.maxPayloadBytes) { reject(peerId, "outbound payload limit"); return packetId }
        val nextBytes = (sentBytes[peerId] ?: 0) + encoded.size
        if (nextBytes > resourcePolicy.maxSentBytesPerConnection) { reject(peerId, "sent byte limit"); return packetId }
        val transfer = try {
            transport.send(peerId, encoded)
        } catch (error: Exception) {
            SendResult.Failed(error.message ?: error.javaClass.simpleName)
        }
        sentBytes[peerId] = nextBytes
        if (transfer is SendResult.PayloadTransferCompleted) {
            if (body is MessageDataBody) {
                payloadTransfers[body.message.messageId to peerId] = PayloadTransfer(body.message.messageId, peerId, packetId, clock.nowMillis())
            }
            _debugEvents.tryEmit(SyncDebugEvent.PayloadTransferCompleted(peerId, when (body) {
                is MessageDataBody -> body.message.messageId
                is ReceiptDataBody -> body.receipt.receiptId
                else -> packetId
            }, encoded.size))
        } else if (transfer is SendResult.Failed) {
            reject(peerId, "transport transfer failed")
        }
        return packetId
    }

    suspend fun recordGatewayReceipt(messageId: String, settings: RelayRuntimeSettings): InsertResult {
        if (settings.role != DeviceRole.GATEWAY) return InsertResult.Rejected("gateway role required")
        return repository.insertReceipt(DeliveryReceipt(java.util.UUID.randomUUID().toString(), messageId, ReceiptType.GATEWAY_RECEIVED, deviceId, clock.nowMillis()))
    }

    private fun reject(peerId: String, reason: String) { _debugEvents.tryEmit(SyncDebugEvent.Rejected(peerId, reason)) }
}
