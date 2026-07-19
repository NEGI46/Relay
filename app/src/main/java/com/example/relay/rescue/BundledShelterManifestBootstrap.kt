package com.example.relay.rescue

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Seeds the public rescue key for the local Fuchu pilot without requiring Wi-Fi.
 *
 * Only public keys are bundled. The matching private keys remain in the PC Gateway's
 * owner-only key store. LAN discovery is still used to refresh a newer manifest when
 * connectivity is available.
 */
class BundledShelterManifestBootstrap(
    private val context: Context,
    private val saveManifest: (ShelterPublicKeyManifest, String) -> Unit,
    private val assetName: String = "fuchu-rescue-manifest.json",
    private val json: Json = Json { ignoreUnknownKeys = false },
) {
    fun seedIfMissing(loadExisting: () -> ShelterPublicKeys?): Boolean {
        if (loadExisting() != null) return true
        return runCatching {
            val manifest = context.assets.open(assetName).bufferedReader(Charsets.UTF_8).use {
                json.decodeFromString<ShelterPublicKeyManifest>(it.readText())
            }
            saveManifest(manifest, manifest.fingerprint())
            true
        }.getOrDefault(false)
    }
}
