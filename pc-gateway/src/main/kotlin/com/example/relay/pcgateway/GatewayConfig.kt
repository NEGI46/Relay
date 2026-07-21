package com.example.relay.pcgateway

import com.example.relay.rescue.SignedShelterManifest
import com.example.relay.rescue.beaconFingerprintBytes
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

data class GatewayConfig(
    val version: String = System.getProperty("relay.version") ?: System.getenv("RELAY_VERSION") ?: "dev",
    val buildSha: String = System.getenv("GIT_COMMIT") ?: "unknown",
    /**
     * Bind on LAN by default. Operators must restrict exposure with the OS firewall
     * (Windows Private profile / macOS pf or Application Firewall).
     */
    val host: String = System.getenv("RELAY_GATEWAY_HOST") ?: "0.0.0.0",
    val port: Int = (System.getenv("RELAY_GATEWAY_PORT") ?: "8080").toIntOrNull() ?: 8080,
    // Packaged apps (Windows EXE / macOS app image) may start with a read-only CWD.
    // Keep the default database under the user's writable home profile.
    val dbPath: String = System.getenv("RELAY_GATEWAY_DB")
        ?: File(System.getProperty("user.home"), ".relay/relay-gateway.db").path,
    val gatewayId: String = System.getenv("RELAY_GATEWAY_ID") ?: "pc-gateway-local",
    val shelterId: String = System.getenv("RELAY_SHELTER_ID") ?: gatewayId,
    val rescueKeyPath: String = System.getenv("RELAY_RESCUE_KEY_FILE")
        ?: File(System.getProperty("user.home"), ".relay/rescue-keys.json").path,
    val offlineMapPath: String = System.getenv("RELAY_OFFLINE_MAP_DIR")
        ?: File(System.getProperty("user.home"), ".relay/maps/gsi-fuchu").path,
    val officialInfoCachePath: String = System.getenv("RELAY_OFFICIAL_INFO_CACHE")
        ?: File(System.getProperty("user.home"), ".relay/official/jma-warning-340000.json").path,
    /**
     * Public regional root used to verify the shelter's signed BLE identity.
     *
     * Keeping this optional preserves maintenance-only Gateway operation, but rescue BLE ingress
     * stays disabled until both this file and [rescueSignedManifestPath] verify successfully.
     */
    val rescueRegionalRootBundlePath: String? = System.getenv("RELAY_RESCUE_REGIONAL_ROOT_BUNDLE_FILE")
        ?.trim()
        ?.takeIf { it.isNotEmpty() },
    /** Root-signed public identity provisioned for this specific shelter PC. */
    val rescueSignedManifestPath: String? = System.getenv("RELAY_RESCUE_SIGNED_MANIFEST_FILE")
        ?.trim()
        ?.takeIf { it.isNotEmpty() },
    val rescueRecipientKeyId: String? = System.getenv("RELAY_RESCUE_RECIPIENT_KEY_ID")?.trim()?.takeIf { it.isNotEmpty() },
    val rescueManifestFingerprint: String? = System.getenv("RELAY_RESCUE_MANIFEST_FINGERPRINT")?.trim()?.takeIf { it.isNotEmpty() },
    /** Separate loopback-only listener used exclusively by the local Windows BLE sidecar. */
    val bleBridgeIngressHost: String = "127.0.0.1",
    val bleBridgeIngressPort: Int = (System.getenv("RELAY_BLE_BRIDGE_PORT") ?: "18081").toIntOrNull()
        ?.takeIf { it in 1..65_535 } ?: 18081,
    /** HMAC secret shared only with the locally installed BLE sidecar. */
    val bleBridgeSharedSecret: String = resolveBleBridgeSharedSecret(),
    val maxBleBridgeRequestBytes: Int = (System.getenv("RELAY_BLE_BRIDGE_MAX_REQUEST_BYTES") ?: "49152").toIntOrNull()
        ?.takeIf { it in 1_024..131_072 } ?: 49_152,
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
    /** Broker URL for cloud relay. Empty/null = Broker pull disabled. */
    val brokerUrl: String? = System.getenv("RELAY_BROKER_URL")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.also {
        require(it.startsWith("https://")) { "RELAY_BROKER_URL must use HTTPS (got: $it)" }
    },
    val brokerApiKey: String? = System.getenv("RELAY_BROKER_API_KEY")?.trim()?.takeIf { it.isNotEmpty() },
    val brokerPollIntervalMs: Long = (System.getenv("RELAY_BROKER_POLL_INTERVAL_MS") ?: "10000").toLongOrNull() ?: 10_000L,
)

/** Exact non-secret environment consumed by the Windows BLE bridge launcher. */
data class BleBridgeEnvironment(
    val signedManifestFingerprintBase64: String,
    val ingressPort: Int,
    val sharedSecretFile: String,
) {
    fun asEnvironmentValues(): Map<String, String> = linkedMapOf(
        "RELAY_BLE_SIGNED_MANIFEST_FINGERPRINT_BASE64" to signedManifestFingerprintBase64,
        "RELAY_BLE_BRIDGE_PORT" to ingressPort.toString(),
        "RELAY_BLE_BRIDGE_SECRET_FILE" to sharedSecretFile,
    )
}

/** Builds the bridge identity only from a verified signed manifest, never its legacy fingerprint. */
fun GatewayConfig.bleBridgeEnvironmentFor(manifest: SignedShelterManifest): BleBridgeEnvironment =
    BleBridgeEnvironment(
        signedManifestFingerprintBase64 = Base64.getEncoder().encodeToString(manifest.beaconFingerprintBytes()),
        ingressPort = bleBridgeIngressPort,
        sharedSecretFile = defaultBleBridgeSecretFile().absolutePath,
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

fun defaultBleBridgeSecretFile(): File =
    File(System.getenv("RELAY_BLE_BRIDGE_SECRET_FILE") ?: File(System.getProperty("user.home"), ".relay/ble-bridge.key").path)

/**
 * The BLE sidecar runs as the same interactive user and reads this secret locally.
 * It is never returned from an HTTP route or written to diagnostics.
 */
fun resolveBleBridgeSharedSecret(): String {
    val environment = System.getenv("RELAY_BLE_BRIDGE_SECRET")?.trim()
    if (!environment.isNullOrEmpty()) {
        require(environment.length >= 32) { "RELAY_BLE_BRIDGE_SECRET must be at least 32 characters" }
        return environment
    }
    val file = defaultBleBridgeSecretFile()
    if (file.isFile) {
        val existing = file.readText(Charsets.UTF_8).trim()
        require(existing.length >= 32) { "BLE bridge secret file is too short" }
        return existing
    }
    val generated = ByteArray(32).also(SecureRandom()::nextBytes)
    val value = Base64.getUrlEncoder().withoutPadding().encodeToString(generated)
    file.parentFile?.mkdirs()
    file.writeText(value, Charsets.UTF_8)
    return value
}
