package com.example.relay.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface InsertResult {
    data object Inserted : InsertResult
    data object Duplicate : InsertResult
    data object Collision : InsertResult
    data class Rejected(val reason: String) : InsertResult
}

data class MessageDelivery(
    val messageId: String,
    val peerDeviceId: String,
    val acknowledgedAt: Long,
    val packetId: String,
)

interface MessageRepository {
    suspend fun insert(message: RelayMessage): InsertResult
    suspend fun find(messageId: String): RelayMessage?
    suspend fun all(): List<RelayMessage>
    suspend fun markAcknowledged(delivery: MessageDelivery)
    suspend fun wasAcknowledged(messageId: String, peerDeviceId: String): Boolean
    suspend fun deliveries(): List<MessageDelivery>
    suspend fun insertReceipt(receipt: DeliveryReceipt): InsertResult
    suspend fun allReceipts(): List<DeliveryReceipt>
    suspend fun receiptsFor(messageId: String): List<DeliveryReceipt>
}

class InMemoryMessageRepository(private val resourcePolicy: ResourcePolicy = ResourcePolicy()) : MessageRepository {
    private val mutex = Mutex()
    private val messages = linkedMapOf<String, RelayMessage>()
    private val deliveryMap = linkedMapOf<Pair<String, String>, MessageDelivery>()
    private val receipts = linkedMapOf<String, DeliveryReceipt>()

    override suspend fun insert(message: RelayMessage): InsertResult = mutex.withLock {
        val existing = messages[message.messageId]
        if (existing == null) {
            if (messages.size >= resourcePolicy.maxStoredMessages) return@withLock InsertResult.Rejected("max stored messages")
            if (messages.values.count { it.originDeviceId == message.originDeviceId } >= resourcePolicy.maxStoredMessagesPerOrigin) {
                return@withLock InsertResult.Rejected("max messages per origin")
            }
            messages[message.messageId] = message
            InsertResult.Inserted
        } else if (!sameCanonicalMessage(existing, message)) {
            InsertResult.Collision
        } else {
            if (message.hopCount < existing.hopCount) messages[message.messageId] = existing.copy(hopCount = message.hopCount)
            InsertResult.Duplicate
        }
    }

    override suspend fun find(messageId: String): RelayMessage? = mutex.withLock { messages[messageId] }
    override suspend fun all(): List<RelayMessage> = mutex.withLock { messages.values.toList() }

    override suspend fun markAcknowledged(delivery: MessageDelivery) = mutex.withLock {
        if (messages.containsKey(delivery.messageId)) {
            val key = delivery.messageId to delivery.peerDeviceId
            if (key !in deliveryMap) deliveryMap[key] = delivery
        }
    }

    override suspend fun wasAcknowledged(messageId: String, peerDeviceId: String): Boolean = mutex.withLock {
        deliveryMap.containsKey(messageId to peerDeviceId)
    }

    override suspend fun deliveries(): List<MessageDelivery> = mutex.withLock { deliveryMap.values.toList() }

    override suspend fun insertReceipt(receipt: DeliveryReceipt): InsertResult = mutex.withLock {
        val existing = receipts[receipt.receiptId]
        when {
            existing == null && receipts.values.any { it.messageId == receipt.messageId && it.receiptType == receipt.receiptType && it.actorId == receipt.actorId } -> InsertResult.Duplicate
            existing == null -> { receipts[receipt.receiptId] = receipt; InsertResult.Inserted }
            existing == receipt -> InsertResult.Duplicate
            else -> InsertResult.Collision
        }
    }

    override suspend fun allReceipts(): List<DeliveryReceipt> = mutex.withLock { receipts.values.toList() }
    override suspend fun receiptsFor(messageId: String): List<DeliveryReceipt> = mutex.withLock { receipts.values.filter { it.messageId == messageId } }

    private fun sameCanonicalMessage(a: RelayMessage, b: RelayMessage): Boolean =
        a.messageId == b.messageId && a.messageType == b.messageType && a.createdAt == b.createdAt &&
            a.expiresAt == b.expiresAt && a.priority == b.priority && a.originDeviceId == b.originDeviceId &&
            a.payload == b.payload && a.maxHopCount == b.maxHopCount
}
