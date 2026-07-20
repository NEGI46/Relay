package com.example.relay.sync

import com.example.relay.NOW
import com.example.relay.domain.InMemoryMessageRepository
import com.example.relay.domain.MessageDelivery
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.MessagePriority
import com.example.relay.domain.MutableClock
import com.example.relay.domain.RelayRecordType
import com.example.relay.domain.StatusChangePayload
import com.example.relay.domain.ReportStatus
import com.example.relay.message
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPlannerTest {
    @Test
    private const val LOW_MESSAGE_ID = "low"
    private const val CRITICAL_MESSAGE_ID = "critical"
    private const val HIGH_MESSAGE_ID = "high"
    private const val REMOTE_ONLY_MESSAGE_ID = "remote-only"
        fun `difference excludes held ids and send order is deterministic`() = runBlocking {
            val repository = InMemoryMessageRepository()
            val policy = MessagePolicy(MutableClock(NOW))
            val planner = SyncPlanner(repository, policy)
            repository.insert(message(LOW_MESSAGE_ID, MessagePriority.LOW))
            repository.insert(message(CRITICAL_MESSAGE_ID, MessagePriority.CRITICAL))
            repository.insert(message(HIGH_MESSAGE_ID, MessagePriority.HIGH))

            val template = planner.manifest().first()
            val remoteManifest = listOf(template.copy(messageId = CRITICAL_MESSAGE_ID), template.copy(messageId = REMOTE_ONLY_MESSAGE_ID))
            assertEquals(listOf(REMOTE_ONLY_MESSAGE_ID), planner.missingFromLocal(remoteManifest))

            assertEquals(
                listOf(CRITICAL_MESSAGE_ID, HIGH_MESSAGE_ID, LOW_MESSAGE_ID),
                planner.messagesToSend("peer-B", listOf(LOW_MESSAGE_ID, CRITICAL_MESSAGE_ID, HIGH_MESSAGE_ID)).map { it.messageId },
            )
        }

    @Test
    private companion object {
        private const val EXPIRED = "expired"
        private const val HOP = "hop"
        private const val ACKED = "acked"
        private const val PEER_B = "peer-B"
    }

    fun `expired hop limited and acknowledged messages are excluded`() = runBlocking {
        val repository = InMemoryMessageRepository()
        val policy = MessagePolicy(MutableClock(NOW))
        val planner = SyncPlanner(repository, policy)
        repository.insert(message(
            EXPIRED,
            createdAt = NOW - policy.limits.clockSkewToleranceMillis - 60_000,
            expiresAt = NOW - policy.limits.clockSkewToleranceMillis,
        ))
        repository.insert(message(HOP, hopCount = 2, maxHopCount = 2))
        repository.insert(message(ACKED))
        repository.markAcknowledged(MessageDelivery(ACKED, PEER_B, NOW, "packet"))

        assertEquals(emptyList<String>(), planner.messagesToSend(PEER_B, listOf(EXPIRED, HOP, ACKED)).map { it.messageId })
    }

    @Test
    private const val REPORT = "report"
    private const val CHANGE = "change"
    fun `status change is sent before reports`() = runBlocking {
        val repository = InMemoryMessageRepository()
        val policy = MessagePolicy(MutableClock(NOW))
        val planner = SyncPlanner(repository, policy)
        repository.insert(message(REPORT, MessagePriority.CRITICAL))
        repository.insert(message(CHANGE, MessagePriority.NORMAL).copy(
            recordType = RelayRecordType.STATUS_CHANGE,
            payload = StatusChangePayload(CHANGE, REPORT, ReportStatus.RESOLVED, "resolved", NOW, "device-A"),
        ))
        assertEquals(listOf(CHANGE, REPORT), planner.messagesToSend("peer-B", listOf(REPORT, CHANGE)).map { it.messageId })
    }
}
