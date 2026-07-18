package com.example.relay.pcgateway.rescue

import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescuePayload
import com.example.relay.rescue.RescueSupportNeed
import com.example.relay.rescue.RescueUrgency
import com.example.relay.rescue.ShelterReceiptStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RescueIntakeServiceTest {
    @Test
    fun pcDecryptsDeduplicatesCountsCarriersAndIssuesVerifiedReceipt() {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val service = RescueIntakeService(
            shelterId = "shelter-1",
            recipientPrivateKey = recipient.privateKey,
            shelterSigningPrivateKey = signer.privateKey,
            clock = RescueClock { 2_000 },
            idGenerator = RescueIdGenerator { "receipt-1" },
        )
        val envelope = RescueCryptography.encrypt(payload(1), recipient.publicKey, "envelope-1")

        val accepted = service.ingest(envelope, "courier-1") as RescueIngestResult.Accepted
        val duplicate = service.ingest(envelope, "courier-2") as RescueIngestResult.Duplicate

        assertEquals("private-note", accepted.request.payload.freeText)
        assertEquals(2, duplicate.request.uniqueCarrierCount)
        assertEquals(ShelterReceiptStatus.STORED, service.receipt("request-1")!!.receipt.status)
        assertTrue(RescueCryptography.verifyReceipt(service.receipt("request-1")!!, signer.publicKey))
    }

    @Test
    fun newerVersionBecomesLatestAndTamperedCiphertextIsRejected() {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val service = RescueIntakeService(
            "shelter-1",
            recipient.privateKey,
            signer.privateKey,
            clock = RescueClock { 2_000 },
        )
        service.ingest(RescueCryptography.encrypt(payload(1), recipient.publicKey, "envelope-1"), "courier-1")
        service.ingest(RescueCryptography.encrypt(payload(2), recipient.publicKey, "envelope-2"), "courier-2")
        val tampered = RescueCryptography.encrypt(payload(3), recipient.publicKey, "envelope-3").let {
            it.copy(ciphertextBase64 = it.ciphertextBase64.dropLast(2) + "AA")
        }

        assertEquals(2, service.detail("request-1")!!.key.requestVersion)
        assertEquals(
            RescueRejectionCode.CORRUPT_OR_UNDECRYPTABLE,
            (service.ingest(tampered, "courier-3") as RescueIngestResult.Rejected).code,
        )
    }

    private fun payload(version: Int) = RescuePayload(
        requestId = "request-1",
        requestVersion = version,
        senderDeviceId = "member-1",
        destinationShelterId = "shelter-1",
        createdAtEpochMillis = 1_000,
        expiresAtEpochMillis = 100_000,
        urgency = RescueUrgency.IMMEDIATE,
        personCount = 2,
        injured = true,
        supportNeeds = setOf(RescueSupportNeed.RESCUE_TEAM),
        freeText = "private-note",
    )
}
