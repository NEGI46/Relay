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
        if (loadExisting() != null) return true
        val gateway = discovery.discover() ?: return false
        val manifest = runCatching { client.fetch(gateway.host, gateway.port) }.getOrNull() ?: return false
        return runCatching {
            // DEBUG-only trust-on-first-use. Release builds use a regionally signed directory.
            saveManifest(manifest, manifest.fingerprint())
            true
        }.getOrDefault(false)
    }
}
