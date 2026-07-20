package com.example.relay.ui.rescue

import com.example.relay.rescue.CourierRescueItem
import com.example.relay.rescue.InMemoryRescueEnvelopeRepository
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueSubmissionStatus
import com.example.relay.rescue.RescueSupportNeed
import com.example.relay.rescue.ShelterPublicKeyProvider
import com.example.relay.rescue.ShelterPublicKeys
import com.example.relay.location.FixedLocationProvider
import com.example.relay.location.GeoFix
import com.example.relay.rescue.RescueCondition
import com.example.relay.rescue.RescueUrgency
import java.lang.reflect.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RescueViewModelTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `request creation fails closed when shelter public key is unavailable`() = runBlocking {
        val repository = InMemoryRescueEnvelopeRepository()
        val viewModel = RescueViewModel(
            repository = repository,
            shelterKeyProvider = ShelterPublicKeyProvider { null },
            nowEpochMillis = { TEST_NOW },
        )
        viewModel.onNavigate(RescueScreen.REQUEST_FORM)
        viewModel.onDraftChange(
            requireNotNull(viewModel.state.value.draft).copy(
                destinationShelterId = "shelter-1",
                freeText = PRIVATE_NOTE,
            ),
        )

        viewModel.onSubmitRequest()

        val failed = withTimeout(ASYNC_TIMEOUT_MILLIS) {
            viewModel.state.first { !it.isRequestSubmitting && it.formMessage != null }
        }
        assertEquals(RescueScreen.REQUEST_FORM, failed.screen)
        assertTrue(failed.formMessage!!.isNotBlank())
        assertTrue(repository.all().isEmpty())
    }

    @Test
    fun `configured shelter key encrypts request before storage and enables automatic transport`() = runBlocking {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val repository = InMemoryRescueEnvelopeRepository()
        val keys = ShelterPublicKeys("shelter-1", recipient.publicKey, signer.publicKey)
        val viewModel = RescueViewModel(
            repository = repository,
            shelterKeyProvider = ShelterPublicKeyProvider { keys },
            nowEpochMillis = { TEST_NOW },
        )
        val draft = requireNotNull(viewModel.state.value.draft).copy(
            personCount = 3,
            injured = true,
            supportNeeds = setOf(RescueSupportNeed.WATER, RescueSupportNeed.MEDICINE),
            freeText = PRIVATE_NOTE,
        )
        viewModel.onNavigate(RescueScreen.REQUEST_FORM)
        viewModel.onDraftChange(draft)

        viewModel.onSubmitRequest()

        val broadcasting = withTimeout(ASYNC_TIMEOUT_MILLIS) {
            viewModel.state.first { it.screen == RescueScreen.BROADCASTING && !it.isRequestSubmitting }
        }
        val stored = repository.all().single()
        val decrypted = RescueCryptography.decrypt(stored.envelope, recipient.privateKey)
        assertTrue(broadcasting.broadcast.isActive)
        assertTrue(broadcasting.courierAutomation.isEnabled)
        assertTrue(broadcasting.broadcast.statusMessage.isNotBlank())
        assertEquals(3, decrypted.personCount)
        assertEquals(PRIVATE_NOTE, decrypted.freeText)
        assertNotEquals(PRIVATE_NOTE, stored.envelope.ciphertextBase64)
        assertFalse(stored.envelope.toString().contains(PRIVATE_NOTE))
    }

    @Test
    fun `two second SOS action stores life threat with unknown count and GPS`() = runBlocking {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val repository = InMemoryRescueEnvelopeRepository()
        val keys = ShelterPublicKeys("fuchu-area", recipient.publicKey, signer.publicKey)
        val viewModel = RescueViewModel(
            repository = repository,
            shelterKeyProvider = ShelterPublicKeyProvider { keys },
            locationProvider = FixedLocationProvider(GeoFix(34.392, 132.504, 7f, TEST_NOW)),
            nowEpochMillis = { TEST_NOW },
        )

        viewModel.onSendSos()

        withTimeout(ASYNC_TIMEOUT_MILLIS) {
            viewModel.state.first { it.screen == RescueScreen.BROADCASTING && !it.isRequestSubmitting }
        }
        val payload = RescueCryptography.decrypt(repository.all().single().envelope, recipient.privateKey)
        assertEquals(0, payload.personCount)
        assertEquals(RescueUrgency.IMMEDIATE, payload.urgency)
        assertEquals(setOf(RescueCondition.LIFE_THREATENING), payload.conditions)
        assertEquals(34.392, payload.location?.latitude ?: 0.0, 0.0)
        assertEquals(TEST_NOW, payload.location?.capturedAtEpochMillis ?: 0L)
    }

    @Test
    fun `rescue language starts in Japanese and toggles without changing the draft`() {
        val viewModel = RescueViewModel(
            repository = InMemoryRescueEnvelopeRepository(),
            shelterKeyProvider = ShelterPublicKeyProvider { null },
            nowEpochMillis = { TEST_NOW },
        )
        val draftId = requireNotNull(viewModel.state.value.draft).requestId

        assertEquals(RescueLanguage.JAPANESE, viewModel.state.value.language)
        viewModel.onToggleLanguage()
        assertEquals(RescueLanguage.ENGLISH, viewModel.state.value.language)
        assertEquals(draftId, viewModel.state.value.draft?.requestId)
        viewModel.onToggleLanguage()
        assertEquals(RescueLanguage.JAPANESE, viewModel.state.value.language)
    }

    @Test
    fun `courier state exposes only delivery metadata and never rescue plaintext`() = runBlocking {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val repository = InMemoryRescueEnvelopeRepository()
        val keys = ShelterPublicKeys("shelter-1", recipient.publicKey, signer.publicKey)
        val memberViewModel = RescueViewModel(
            repository = repository,
            shelterKeyProvider = ShelterPublicKeyProvider { keys },
            nowEpochMillis = { TEST_NOW },
        )
        memberViewModel.onDraftChange(
            requireNotNull(memberViewModel.state.value.draft).copy(
                injured = true,
                personCount = 4,
                freeText = PRIVATE_NOTE,
            ),
        )
        memberViewModel.onSubmitRequest()
        withTimeout(ASYNC_TIMEOUT_MILLIS) {
            memberViewModel.state.first { it.screen == RescueScreen.BROADCASTING }
        }

        val courierViewModel = RescueViewModel(
            repository = repository,
            shelterKeyProvider = ShelterPublicKeyProvider { null },
            nowEpochMillis = { TEST_NOW + 1_000 },
        )
        courierViewModel.onNavigate(RescueScreen.COURIER_INVENTORY)

        val item = withTimeout(ASYNC_TIMEOUT_MILLIS) {
            courierViewModel.state.first { it.courierItems.size == 1 }.courierItems.single()
        }
        assertEquals("shelter-1", item.destinationShelterId)
        assertEquals(RescueSubmissionStatus.PENDING, item.submissionStatus)
        assertFalse(item.toString().contains(PRIVATE_NOTE))
        assertFalse(item.toString().contains("member-device"))
        assertEquals(COURIER_METADATA_FIELDS, courierVisibleFields())
    }

    private fun courierVisibleFields(): Set<String> = CourierRescueItem::class.java.declaredFields
        .asSequence()
        .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
        .map { it.name }
        .toSet()

    private companion object {
        const val TEST_NOW = 1_700_000_000_000L
        const val ASYNC_TIMEOUT_MILLIS = 10_000L
        const val PRIVATE_NOTE = "玄関奥に重傷者。位置情報を含む秘密メモ"
        val COURIER_METADATA_FIELDS = setOf(
            "requestId",
            "requestVersion",
            "destinationShelterId",
            "receivedAtEpochMillis",
            "expiresAtEpochMillis",
            "submissionStatus",
            "submissionCount",
        )
    }
}
