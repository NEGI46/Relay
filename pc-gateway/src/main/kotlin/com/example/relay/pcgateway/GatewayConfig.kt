package com.example.relay.pcgateway

import java.io.File
import java.util.UUID

data class GatewayConfig(
    /** Bind on LAN by default; restrict exposure with the Windows Private-network firewall profile. */
    val host: String = System.getenv("RELAY_GATEWAY_HOST") ?: "0.0.0.0",
    val port: Int = (System.getenv("RELAY_GATEWAY_PORT") ?: "8080").toIntOrNull() ?: 8080,
    // The installed Windows application may start with a read-only working directory.
    // Keep the default database under the user's writable profile instead of beside the EXE.
    val dbPath: String = System.getenv("RELAY_GATEWAY_DB")
        ?: File(System.getProperty("user.home"), ".relay/relay-gateway.db").path,
    val gatewayId: String = System.getenv("RELAY_GATEWAY_ID") ?: "pc-gateway-local",
    /**
     * Operator key for pairing and dashboard APIs.
     * Resolution order: env RELAY_GATEWAY_ADMIN_KEY → ~/.relay/admin.key → generate+persist.
     */
    val adminKey: String = resolveAdminKey(),
    val maxPayloadBytes: Int = 64 * 1024,
    val maxMessagesPerRequest: Int = 128,
    val maxStoredMessages: Int = 50_000,
    val anonymousIngressEnabled: Boolean = (System.getenv("RELAY_GATEWAY_ANONYMOUS_INGRESS") ?: "true").toBooleanStrictOrNull() ?: true,
    val maxAnonymousRequestBytes: Int = 256 * 1024,
    val maxAnonymousMessagesPerRequest: Int = 32,
    val maxAnonymousRequestsPerMinute: Int = 30,
    val maxAnonymousMessagesPerMinute: Int = 256,
    val maxAnonymousBytesPerMinute: Int = 1024 * 1024,
    val lanDiscoveryEnabled: Boolean = (System.getenv("RELAY_GATEWAY_LAN_DISCOVERY") ?: "true").toBooleanStrictOrNull() ?: true,
    val lanDiscoveryPort: Int = (System.getenv("RELAY_GATEWAY_DISCOVERY_PORT") ?: "42888").toIntOrNull() ?: 42888,
    val lanDiscoveryIntervalMs: Long = 5_000,
)

fun defaultAdminKeyFile(): File =
    File(System.getenv("RELAY_GATEWAY_ADMIN_KEY_FILE") ?: File(System.getProperty("user.home"), ".relay/admin.key").path)

/**
 * Returns where the admin key came from (for console diagnostics; never prints the key).
 */
fun resolveAdminKeySource(): String {
    val env = System.getenv("RELAY_GATEWAY_ADMIN_KEY")
    if (!env.isNullOrBlank()) return "env:RELAY_GATEWAY_ADMIN_KEY"
    val file = defaultAdminKeyFile()
    return if (file.isFile) "file:${file.absolutePath}" else "generated-file:${file.absolutePath}"
}

fun resolveAdminKey(): String {
    val env = System.getenv("RELAY_GATEWAY_ADMIN_KEY")
    if (!env.isNullOrBlank()) return env.trim()
    val file = defaultAdminKeyFile()
    if (file.isFile) {
        val existing = file.readText(Charsets.UTF_8).trim()
        if (existing.isNotEmpty()) return existing
    }
    val generated = UUID.randomUUID().toString()
    file.parentFile?.mkdirs()
    file.writeText(generated, Charsets.UTF_8)
    return generated
}
