package com.example.relay.domain

import com.example.relay.NOW
import com.example.relay.message
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePolicyRepositoryTest {
    private val clock = MutableClock(NOW)
    private val policy = MessagePolicy(clock)

    @Test
    fun `same message id and body is deduplicated`() = runBlocking {
        val repository = InMemoryMessageRepository()
        assertEquals(InsertResult.Inserted, repository.insert(message()))
        assertEquals(InsertResult.Duplicate, repository.insert(message()))
        assertEquals(1, repository.all().size)
    }

    @Test
    fun `same id with different immutable body is rejected as collision`() = runBlocking {
        val repository = InMemoryMessageRepository()
        repository.insert(message(note = "first"))
        assertEquals(InsertResult.Collision, repository.insert(message(note = "forged")))
        assertEquals("first", (repository.find("message-1")!!.payload as SafetyPayload).note)
    }

    @Test
    fun `ttl boundary expires exactly at expiresAt`() {
        val value = message(expiresAt = NOW + 1_000)
        clock.currentMillis = value.expiresAt - 1
        clock.currentElapsedRealtimeMillis = value.expiresAt - 1
        assertTrue(policy.isActive(value))
        clock.currentMillis++
        clock.currentElapsedRealtimeMillis++
        assertFalse(policy.isActive(value))
    }

    @Test
    fun `receiving increments hop and hop limit prevents forwarding`() {
        val inbound = message(hopCount = 1, maxHopCount = 2)
        val received = policy.receive(inbound)!!
        assertEquals(2, received.hopCount)
        assertFalse(policy.canForward(received))
        assertNull(policy.receive(received))
    }

    @Test
    fun `acknowledgement is peer specific and idempotent`() = runBlocking {
        val repository = InMemoryMessageRepository()
        repository.insert(message())
        val first = MessageDelivery("message-1", "peer-B", NOW, "packet-1")
        repository.markAcknowledged(first)
        repository.markAcknowledged(first.copy(acknowledgedAt = NOW + 1))
        assertTrue(repository.wasAcknowledged("message-1", "peer-B"))
        assertFalse(repository.wasAcknowledged("message-1", "peer-C"))
        assertEquals(1, repository.deliveries().size)
    }
}
