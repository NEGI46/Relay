package com.example.relay.rescue

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RescueShelterKeyStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearStore() {
        context.getSharedPreferences("relay_rescue_shelter_keys", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun onlyMatchingFingerprintCanBeSaved() {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val manifest = ShelterPublicKeyManifest(
            shelterId = "shelter-1",
            recipientPublicKey = recipient.publicKey,
            receiptSigningPublicKey = signer.publicKey,
            validFromEpochMillis = NOW - 1_000,
            validUntilEpochMillis = NOW + 1_000,
        )
        val store = RescueShelterKeyStore(context) { NOW }

        assertThrows(IllegalArgumentException::class.java) {
            store.saveVerifiedManifest(manifest, "0".repeat(64))
        }
        store.saveVerifiedManifest(manifest, manifest.fingerprint())

        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(manifest.shelterId, loaded?.shelterId)
        assertEquals(manifest.fingerprint(), loaded.manifestFingerprint)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
