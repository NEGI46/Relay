package com.example.relay.pcgateway.rescue

import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class RescueKeyStoreTest {
    @Test
    fun generatedKeysAreReusedAndPublicManifestContainsNoPrivateMaterial() {
        val path = Files.createTempDirectory("relay-rescue-keys").resolve("keys.json")
        val clock = RescueClock { NOW }
        val first = RescueKeyStore(path, "shelter-1", clock).loadOrCreate()
        val second = RescueKeyStore(path, "shelter-1", clock).loadOrCreate()

        assertEquals(first.manifest, second.manifest)
        assertEquals(first.recipientPrivateKey, second.recipientPrivateKey)
        assertEquals(first.receiptSigningPrivateKey, second.receiptSigningPrivateKey)
        assertTrue(Files.size(path) in 1..32L * 1024)
        val publicText = kotlinx.serialization.json.Json.encodeToString(
            com.example.relay.rescue.ShelterPublicKeyManifest.serializer(),
            first.manifest,
        )
        assertFalse(publicText.contains(first.recipientPrivateKey.encodedBase64))
        assertFalse(publicText.contains(first.receiptSigningPrivateKey.encodedBase64))
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
