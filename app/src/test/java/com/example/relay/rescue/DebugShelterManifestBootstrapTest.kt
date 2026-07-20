package com.example.relay.rescue

import com.example.relay.gateway.DiscoveredGateway
import com.example.relay.gateway.GatewayDiscovery
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugShelterManifestBootstrapTest {
    @Test
    companion object {
        private const val DEBUG_SHELTER = "debug-shelter"
    }

    fun `local test gateway manifest is saved with its computed fingerprint`() = runTest {
        val manifest = manifest(DEBUG_SHELTER)
        var saved: Pair<ShelterPublicKeyManifest, String>? = null
        val bootstrap = DebugShelterManifestBootstrap(
            discovery = discovery(),
            client = ShelterManifestClient { _, _ -> manifest },
            loadExisting = { null },
            saveManifest = { value, fingerprint -> saved = value to fingerprint },
        )

        assertTrue(bootstrap.enrollFromLocalTestGateway())
        assertEquals(manifest, saved?.first)
        assertEquals(manifest.fingerprint(), saved?.second)
    }

    @Test
    fun `rotated gateway manifest replaces an existing bundled manifest`() = runTest {
        val bundled = manifest("debug-shelter")
        val rotated = manifest("debug-shelter")
        val existing = ShelterPublicKeys(
            shelterId = bundled.shelterId,
            recipientKey = bundled.recipientPublicKey,
            receiptSigningKey = bundled.receiptSigningPublicKey,
            manifestFingerprint = bundled.fingerprint(),
            generation = bundled.generation,
        )
        var saved: Pair<ShelterPublicKeyManifest, String>? = null
        val bootstrap = DebugShelterManifestBootstrap(
            discovery = discovery(),
            client = ShelterManifestClient { _, _ -> rotated },
            loadExisting = { existing },
            saveManifest = { value, fingerprint -> saved = value to fingerprint },
        )

        assertTrue(bootstrap.enrollFromLocalTestGateway())
        assertEquals(rotated, saved?.first)
        assertEquals(rotated.fingerprint(), saved?.second)
    }

    private fun discovery() = object : GatewayDiscovery {
        override suspend fun discover(timeoutMs: Int): DiscoveredGateway =
            DiscoveredGateway("192.0.2.1", 8080, "debug-shelter")
    }

    private fun manifest(shelterId: String): ShelterPublicKeyManifest {
        val recipient = RescueCryptography.generateRecipientKeyPair().publicKey
        val receipt = RescueCryptography.generateShelterSigningKeyPair().publicKey
        return ShelterPublicKeyManifest(
            shelterId = shelterId,
            recipientPublicKey = recipient,
            receiptSigningPublicKey = receipt,
            validFromEpochMillis = 1,
            validUntilEpochMillis = Long.MAX_VALUE,
        )
    }
}