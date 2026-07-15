package com.example.relay.pcgateway

import java.util.UUID
import java.io.File

data class GatewayConfig(
    /** Bind on LAN by default; restrict exposure with the Windows Private-network firewall profile. */
    val host: String = System.getenv("RELAY_GATEWAY_HOST") ?: "0.0.0.0",
    val port: Int = (System.getenv("RELAY_GATEWAY_PORT") ?: "8080").toIntOrNull() ?: 8080,
    // The installed Windows application may start with a read-only working directory.
    // Keep the default database under the user's writable profile instead of beside the EXE.
    val dbPath: String = System.getenv("RELAY_GATEWAY_DB")
        ?: File(System.getProperty("user.home"), ".relay/relay-gateway.db").path,
    val gatewayId: String = System.getenv("RELAY_GATEWAY_ID") ?: "pc-gateway-local",
    val adminKey: String = System.getenv("RELAY_GATEWAY_ADMIN_KEY") ?: UUID.randomUUID().toString(),
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
