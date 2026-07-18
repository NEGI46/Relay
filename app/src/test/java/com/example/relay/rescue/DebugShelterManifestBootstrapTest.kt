package com.example.relay.rescue

import com.example.relay.gateway.DiscoveredGateway
import com.example.relay.gateway.GatewayDiscovery
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugShelterManifestBootstrapTest {
    @Test
    fun `local test gateway manifest is saved with its computed fingerprint`() = runTest {
        val recipient = RescueCryptography.generateRecipientKeyPair().publicKey
        val receipt = RescueCryptography.generateShelterSigningKeyPair().publicKey
        val manifest = ShelterPublicKeyManifest(
            shelterId = "debug-shelter",
            recipientPublicKey = recipient,
            receiptSigningPublicKey = receipt,
            validFromEpochMillis = 1,
            validUntilEpochMillis = Long.MAX_VALUE,
        )
        var saved: Pair<ShelterPublicKeyManifest, String>? = null
        val bootstrap = DebugShelterManifestBootstrap(
            discovery = object : GatewayDiscovery {
                override suspend fun discover(timeoutMs: Int): DiscoveredGateway =
                    DiscoveredGateway("192.0.2.1", 8080, "debug-shelter")
            },
            client = ShelterManifestClient { _, _ -> manifest },
            loadExisting = { null },
            saveManifest = { value, fingerprint -> saved = value to fingerprint },
        )

        assertTrue(bootstrap.enrollFromLocalTestGateway())
        assertEquals(manifest, saved?.first)
        assertEquals(manifest.fingerprint(), saved?.second)
    }
}
