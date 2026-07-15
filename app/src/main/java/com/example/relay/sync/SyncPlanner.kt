package com.example.relay.sync

import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.MessageRepository
import com.example.relay.domain.RelayMessage
import com.example.relay.protocol.ManifestEntry

class SyncPlanner(
    private val repository: MessageRepository,
    private val policy: MessagePolicy,
) {
    suspend fun manifest(): List<ManifestEntry> = repository.all()
        .filter(policy::isActive)
        .sortedBy { it.messageId }
        .map {
            ManifestEntry(
                it.messageId, it.messageType, it.priority, it.createdAt, it.expiresAt,
                it.hopCount, it.maxHopCount, policy.canForward(it), it.recordType, it.lifetimeMs, policy.effectiveAgeMs(it),
            )
        }

    suspend fun missingFromLocal(remote: List<ManifestEntry>): List<String> {
        val localIds = repository.all().asSequence().map { it.messageId }.toHashSet()
        return remote.asSequence()
            .filter { it.transferable && it.messageId !in localIds && it.accumulatedAgeMs < it.lifetimeMs }
            .sortedWith(compareByDescending<ManifestEntry> { it.recordType == com.example.relay.domain.RelayRecordType.STATUS_CHANGE }
                .thenByDescending { it.priority.ordinal }
                .thenByDescending { it.createdAt }
                .thenBy { it.messageId })
            .map { it.messageId }
            .toList()
    }

    suspend fun messagesToSend(peerId: String, requestedIds: Collection<String>): List<RelayMessage> {
        val requested = requestedIds.toHashSet()
        val pending = mutableListOf<RelayMessage>()
        for (message in repository.all()) {
            if (message.messageId in requested && policy.canForward(message) &&
                !repository.wasAcknowledged(message.messageId, peerId)
            ) {
                pending += message
            }
        }
        return pending.mapNotNull(policy::prepareForTransfer)
            .sortedWith(compareByDescending<RelayMessage> { it.recordType == com.example.relay.domain.RelayRecordType.STATUS_CHANGE }
                .thenByDescending { it.priority.ordinal }
                .thenByDescending { it.createdAt }
                .thenBy { it.messageId })
    }

    suspend fun missingReceipts(remoteReceiptIds: Collection<String>): List<String> {
        val local = repository.allReceipts().map { it.receiptId }.toHashSet()
        return remoteReceiptIds.filter { it !in local }.sorted()
    }

    suspend fun receiptsToSend(requestedIds: Collection<String>) = repository.allReceipts()
        .filter { it.receiptId in requestedIds }
        .sortedBy { it.receiptId }
}
