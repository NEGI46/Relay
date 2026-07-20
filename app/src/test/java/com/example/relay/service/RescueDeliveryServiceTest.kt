package com.example.relay.service

import com.example.relay.rescue.InMemoryRescueEnvelopeRepository
import com.example.relay.rescue.RescueCreationResult
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueRequestCreator
import com.example.relay.rescue.RescueRequestDraft
import com.example.relay.rescue.RescueSubmissionStatus
import com.example.relay.rescue.RescueUrgency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RescueDeliveryServiceTest {
    @Test
    fun `LAN delivery keeps polling after accepted until a terminal receipt arrives`() {
        val record = storedRecord()

        listOf(
            RescueSubmissionStatus.SHELTER_STORED,
            RescueSubmissionStatus.SHELTER_ACCEPTED,
            RescueSubmissionStatus.SHELTER_RESPONDING,
        ).forEach { status ->
            assertEquals(
                status,
                selectLocalGatewayCandidate(
                    listOf(record.copy(state = record.state.copy(submissionStatus = status))),
                    NOW,
                )?.state?.submissionStatus,
            )
        }
    }

    @Test
    fun `LAN delivery stops polling terminal rescue states`() {
        val record = storedRecord()

        listOf(
            RescueSubmissionStatus.SHELTER_COMPLETED,
            RescueSubmissionStatus.CANCELLED,
            RescueSubmissionStatus.SHELTER_REJECTED,
        ).forEach { status ->
            assertNull(
                selectLocalGatewayCandidate(
                    listOf(record.copy(state = record.state.copy(submissionStatus = status))),
                    NOW,
                ),
            )
        }
    }

    private fun storedRecord() = (RescueRequestCreator(InMemoryRescueEnvelopeRepository()).create(
        RescueRequestDraft(
            requestId = "request-lan-status",
            senderDeviceId = "phone-a",
            destinationShelterId = "shelter-1",
            createdAtEpochMillis = NOW - 1_000,
            expiresAtEpochMillis = NOW + 60_000,
            urgency = RescueUrgency.URGENT,
        ),
        RescueCryptography.generateRecipientKeyPair().publicKey,
        envelopeId = "envelope-lan-status",
    ) as RescueCreationResult.Stored).record

    private companion object {
        const val NOW = 10_000L
    }
}
