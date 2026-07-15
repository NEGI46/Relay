package com.example.relay.domain

import com.example.relay.NOW
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageUseCasesTest {
    @Test
    fun `safety and supply use cases create validated local messages`() = runBlocking {
        val clock = MutableClock(NOW)
        val repository = InMemoryMessageRepository()
        val policy = MessagePolicy(clock)
        val safety = CreateSafetyMessageUseCase(repository, policy, clock, "device-A", MessageIdGenerator { "safety-1" })
        val supply = CreateSupplyMessageUseCase(repository, policy, clock, "device-A", MessageIdGenerator { "supply-1" })

        assertEquals(MessageType.SAFETY, safety(SafetyState.SAFE, 2, " north ", " ok ").messageType)
        assertEquals(MessageType.SUPPLY, supply(SupplyKind.WATER, 10, "north", "needed").messageType)
        assertEquals(2, GetRegionalMessagesUseCase(repository, policy)().size)
        assertTrue(repository.all().all { it.hopCount == 0 && it.status == MessageStatus.CREATED })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid form input is rejected before insertion`() {
        runBlocking {
            val clock = MutableClock(NOW)
            val repository = InMemoryMessageRepository()
            val policy = MessagePolicy(clock)
            CreateSupplyMessageUseCase(repository, policy, clock, "device-A", MessageIdGenerator { "bad" })(
                SupplyKind.WATER, 0, "north", "needed",
            )
        }
    }
}
