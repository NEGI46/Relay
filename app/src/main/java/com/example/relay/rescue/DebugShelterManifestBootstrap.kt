package com.example.relay.rescue

import com.example.relay.gateway.GatewayDiscovery

/** Debug-only automatic enrollment from a locally announced test PC Gateway. */
class DebugShelterManifestBootstrap(
    private val discovery: GatewayDiscovery,
    private val client: ShelterManifestClient,
    private val loadExisting: () -> ShelterPublicKeys?,
    private val saveManifest: (ShelterPublicKeyManifest, String) -> Unit,
) {
    suspend fun enrollFromLocalTestGateway(): Boolean {
        val gateway = discovery.discover() ?: return false
        val manifest = runCatching { client.fetch(gateway.host, gateway.port) }.getOrNull() ?: return false
        val existing = loadExisting()
        val gateway = discovery.discover() ?: return existing != null
        val manifest = runCatching { client.fetch(gateway.host, gateway.port) }.getOrNull()
            ?: return existing != null
        val fingerprint = manifest.fingerprint()
        if (existing?.manifestFingerprint == fingerprint) return true
        return runCatching {
            // DEBUG-only trust-on-first-use. Always refresh when the local test Gateway rotates keys.
            saveManifest(manifest, fingerprint)
            true
        }.getOrDefault(false)
    }
}