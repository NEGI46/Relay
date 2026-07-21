package com.example.relay.pcgateway

import com.example.relay.rescue.SignedShelterManifest
import com.example.relay.rescue.beaconFingerprintBytes
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Runtime posture is intentionally explicit.  Production is the default so an omitted
 * environment variable cannot silently turn a pilot Gateway into a LAN-wide anonymous service.
 */
enum class GatewayProfile {
    DEVELOPMENT,
    LAB,
    PRODUCTION;

    companion object {
        fun fromEnvironment(raw: String? = System.getenv("RELAY_PROFILE")): GatewayProfile = when (raw?.trim()?.lowercase()) {
            "development", "dev" -> DEVELOPMENT
            "lab" -> LAB
            null, "", "production", "prod" -> PRODUCTION
            else -> throw IllegalArgumentException("RELAY_PROFILE must be development, lab, or production")
        }
    }
}

/** How a non-loopback listener is isolated from untrusted networks. */
enum class GatewayLanMode {
    DISABLED,
    CLOSED_NETWORK,
    TLS_REVERSE_PROXY;

    companion object {
        fun fromEnvironment(raw: String? = System.getenv("RELAY_GATEWAY_LAN_MODE")): GatewayLanMode = when (raw?.trim()?.lowercase()) {
            null, "", "disabled" -> DISABLED
            "closed-network", "closed_network", "private-network", "private_network" -> CLOSED_NETWORK
            "tls-reverse-proxy", "tls_reverse_proxy", "tls" -> TLS_REVERSE_PROXY
            else -> throw IllegalArgumentException(
                "RELAY_GATEWAY_LAN_MODE must be disabled, closed-network, or tls-reverse-proxy",
            )
        }
    }
}

data class GatewayConfig(
    val profile: GatewayProfile = GatewayProfile.fromEnvironment(),
    val lanMode: GatewayLanMode = GatewayLanMode.fromEnvironment(),
    val version: String = System.getProperty("relay.version") ?: System.getenv("RELAY_VERSION") ?: "dev",
    val buildSha: String = System.getenv("GIT_COMMIT") ?: "unknown",
    /**
     * Production and lab bind to loopback unless a deliberate LAN topology is configured.
     * Development keeps the historic LAN behavior solely for local compatibility work.
     */
    val host: String = System.getenv("RELAY_GATEWAY_HOST") ?: if (profile == GatewayProfile.DEVELOPMENT) "0.0.0.0" else "127.0.0.1",
    val port: Int = (System.getenv("RELAY_GATEWAY_PORT") ?: "8080").toIntOrNull() ?: 8080,
    /** External endpoint advertised by a TLS reverse proxy, never a secret. */
    val publicScheme: String = (System.getenv("RELAY_GATEWAY_PUBLIC_SCHEME")
        ?: if (lanMode == GatewayLanMode.TLS_REVERSE_PROXY) "https" else "http").lowercase(),
    val publicPort: Int = (System.getenv("RELAY_GATEWAY_PUBLIC_PORT") ?: System.getenv("RELAY_GATEWAY_PORT") ?: "8080")
        .toIntOrNull() ?: 8080,
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
    val rescueKeyExpiryWarningMillis: Long = (System.getenv("RELAY_RESCUE_KEY_EXPIRY_WARNING_DAYS") ?: "30")
        .toLongOrNull()?.coerceIn(1, 365)?.times(24L * 60 * 60 * 1_000) ?: 30L * 24 * 60 * 60 * 1_000,
    /** Separate loopback-only listener used exclusively by the local Windows BLE sidecar. */
    val bleBridgeIngressHost: String = "127.0.0.1",
    val bleBridgeIngressPort: Int = (System.getenv("RELAY_BLE_BRIDGE_PORT") ?: "18081").toIntOrNull()
        ?.takeIf { it in 1..65_535 } ?: 18081,
    /** HMAC secret shared only with the locally installed BLE sidecar. */
    val bleBridgeSharedSecret: String = resolveBleBridgeSharedSecret(),
    val maxBleBridgeRequestBytes: Int = (System.getenv("RELAY_BLE_BRIDGE_MAX_REQUEST_BYTES") ?: "49152").toIntOrNull()
        ?.takeIf { it in 1_024..131_072 } ?: 49_152,
    /**
     * Legacy shared management key. It exists solely to migrate development tooling and is
     * deliberately unavailable in lab/production profiles.
     */
    val legacyAdminKeyEnabled: Boolean = profile == GatewayProfile.DEVELOPMENT &&
        environmentBoolean("RELAY_GATEWAY_ENABLE_LEGACY_ADMIN_KEY", default = true),
    val adminKey: String? = if (legacyAdminKeyEnabled) resolveAdminKey() else null,
    /** First-admin bootstrap inputs. No default username or password is ever generated. */
    val bootstrapUsername: String? = System.getenv("RELAY_GATEWAY_BOOTSTRAP_USERNAME")?.trim()?.takeIf { it.isNotEmpty() },
    val bootstrapSecret: String? = System.getenv("RELAY_GATEWAY_BOOTSTRAP_SECRET")?.takeIf { it.isNotBlank() },
    val sessionTtlMillis: Long = (System.getenv("RELAY_GATEWAY_SESSION_TTL_MINUTES") ?: "480")
        .toLongOrNull()?.coerceIn(5, 24 * 60)?.times(60_000) ?: 8L * 60 * 60 * 1_000,
    /** Secure cookies are mandatory when a TLS reverse proxy fronts remote management. */
    val sessionCookieSecure: Boolean = environmentBoolean(
        "RELAY_GATEWAY_SESSION_COOKIE_SECURE",
        default = lanMode == GatewayLanMode.TLS_REVERSE_PROXY,
    ),
    /** Browser/admin access from a non-loopback peer requires an explicit topology acknowledgement. */
    val remoteManagementEnabled: Boolean = environmentBoolean("RELAY_GATEWAY_REMOTE_MANAGEMENT", default = false),
    val maxPayloadBytes: Int = 64 * 1024,
    val maxMessagesPerRequest: Int = 128,
    val maxStoredMessages: Int = 50_000,
    /** Anonymous ingress is a development compatibility default only. */
    val anonymousIngressEnabled: Boolean = environmentBoolean(
        "RELAY_GATEWAY_ANONYMOUS_INGRESS",
        default = profile == GatewayProfile.DEVELOPMENT,
    ),
    val maxAnonymousRequestBytes: Int = 256 * 1024,
    val maxAnonymousMessagesPerRequest: Int = 32,
    val maxAnonymousRequestsPerMinute: Int = 30,
    val maxAnonymousMessagesPerMinute: Int = 256,
    val maxAnonymousBytesPerMinute: Int = 1024 * 1024,
    /** LAN discovery is not a trust mechanism and is disabled outside explicit LAN operation. */
    val lanDiscoveryEnabled: Boolean = environmentBoolean(
        "RELAY_GATEWAY_LAN_DISCOVERY",
        default = profile == GatewayProfile.DEVELOPMENT,
    ),
    val lanDiscoveryPort: Int = (System.getenv("RELAY_GATEWAY_DISCOVERY_PORT") ?: "42888").toIntOrNull() ?: 42888,
    val lanDiscoveryIntervalMs: Long = 5_000,
    /** Broker URL for cloud relay. Empty/null = Broker pull disabled. */
    val brokerUrl: String? = System.getenv("RELAY_BROKER_URL")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.also {
        require(isSafeHttpsEndpoint(it)) { "RELAY_BROKER_URL must be an HTTPS endpoint without embedded credentials" }
    },
    /** Per-Gateway, per-shelter Broker credential. It is never written to health/audit/log output. */
    val brokerCredential: String? = System.getenv("RELAY_BROKER_CREDENTIAL")?.trim()?.takeIf { it.isNotEmpty() },
    /** Deprecated shared Broker key; accepted only by development-profile compatibility wiring. */
    val brokerLegacyApiKey: String? = System.getenv("RELAY_BROKER_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv("RELAY_BROKER_GATEWAY_API_KEY")?.trim()?.takeIf { it.isNotEmpty() },
    val brokerPollIntervalMs: Long = (System.getenv("RELAY_BROKER_POLL_INTERVAL_MS") ?: "10000").toLongOrNull() ?: 10_000L,
) {
    /** Non-sensitive diagnostics surfaced by health; never contains host paths, keys, or tokens. */
    val configurationWarnings: List<String> = buildList {
        if (profile == GatewayProfile.DEVELOPMENT) add("development_profile_compatibility_enabled")
        if (profile == GatewayProfile.LAB) add("lab_profile_not_for_production_operation")
        if (profile != GatewayProfile.DEVELOPMENT && legacyAdminKeyMaterialConfigured()) {
            // Deliberately report only that obsolete material exists, never its value or path.
            add("legacy_admin_key_material_ignored_remove_from_host")
        }
        if (lanMode == GatewayLanMode.CLOSED_NETWORK) add("closed_network_boundary_must_be_verified_by_operator")
        if (profile != GatewayProfile.DEVELOPMENT && lanDiscoveryEnabled && publicScheme != "https") {
            add("lan_discovery_http_not_usable_by_android_release_clients")
        }
        if (brokerUrl != null && brokerCredential == null && profile == GatewayProfile.DEVELOPMENT) {
            add("broker_credential_missing_development_only")
        }
    }

    /** Non-secret configuration snapshot allowed in an operator audit record. */
    val auditConfigurationTarget: String = listOf(
        "profile=${profile.name.lowercase()}",
        "lan=${lanMode.name.lowercase()}",
        "anonymous=$anonymousIngressEnabled",
        "discovery=$lanDiscoveryEnabled",
        "remoteManagement=$remoteManagementEnabled",
    ).joinToString(";")

    init {
        require(port in 1..65_535) { "RELAY_GATEWAY_PORT must be a valid TCP port" }
        require(publicPort in 1..65_535) { "RELAY_GATEWAY_PUBLIC_PORT must be a valid TCP port" }
        require(lanDiscoveryPort in 1..65_535) { "RELAY_GATEWAY_DISCOVERY_PORT must be a valid UDP port" }
        require(publicScheme in setOf("http", "https")) { "RELAY_GATEWAY_PUBLIC_SCHEME must be http or https" }
        require(!(lanMode == GatewayLanMode.TLS_REVERSE_PROXY && publicScheme != "https")) {
            "TLS reverse-proxy mode requires RELAY_GATEWAY_PUBLIC_SCHEME=https"
        }
        require(!(lanMode == GatewayLanMode.TLS_REVERSE_PROXY && !isLoopbackHost(host))) {
            "TLS reverse-proxy mode must bind the Gateway to loopback; expose only the proxy"
        }
        val lanListener = !isLoopbackHost(host)
        require(!(profile != GatewayProfile.DEVELOPMENT && lanListener && lanMode == GatewayLanMode.DISABLED)) {
            "non-loopback Gateway binding requires RELAY_GATEWAY_LAN_MODE=closed-network or tls-reverse-proxy"
        }
        require(!(profile != GatewayProfile.DEVELOPMENT && (anonymousIngressEnabled || lanDiscoveryEnabled) && lanMode == GatewayLanMode.DISABLED)) {
            "anonymous ingress or LAN discovery requires an explicit LAN topology outside development"
        }
        require(!(lanDiscoveryEnabled && !anonymousIngressEnabled)) {
            "LAN discovery advertises anonymous sync; enable anonymous ingress too or disable discovery"
        }
        require(!(remoteManagementEnabled && lanMode != GatewayLanMode.TLS_REVERSE_PROXY)) {
            "remote management requires RELAY_GATEWAY_LAN_MODE=tls-reverse-proxy"
        }
        require(!(remoteManagementEnabled && !sessionCookieSecure)) {
            "remote management requires RELAY_GATEWAY_SESSION_COOKIE_SECURE=true"
        }
        require(!(profile != GatewayProfile.DEVELOPMENT && legacyAdminKeyEnabled)) {
            "X-Admin-Key compatibility is permitted only in the development profile"
        }
        require(!(profile != GatewayProfile.DEVELOPMENT && brokerLegacyApiKey != null)) {
            "RELAY_BROKER_API_KEY / RELAY_BROKER_GATEWAY_API_KEY are development-only legacy credentials; use RELAY_BROKER_CREDENTIAL"
        }
        require(!(profile == GatewayProfile.PRODUCTION && brokerUrl != null && brokerCredential == null)) {
            "production Broker usage requires RELAY_BROKER_CREDENTIAL"
        }
        require((bootstrapUsername == null) == (bootstrapSecret == null)) {
            "set both RELAY_GATEWAY_BOOTSTRAP_USERNAME and RELAY_GATEWAY_BOOTSTRAP_SECRET, or neither"
        }
        if (bootstrapSecret != null) require(bootstrapSecret.length >= 16) {
            "RELAY_GATEWAY_BOOTSTRAP_SECRET must contain at least 16 characters"
        }
    }

    /**
     * In TLS-reverse-proxy mode the backend peer is normally loopback even for a remote browser.
     * Therefore loopback alone is not treated as local operator access in that mode: the explicit
     * remote-management switch is the boundary. Use `disabled` LAN mode for a local-only console.
     */
    fun managementSourceAllowed(remoteHost: String?): Boolean = when (lanMode) {
        GatewayLanMode.TLS_REVERSE_PROXY -> remoteManagementEnabled
        else -> remoteManagementEnabled || isLoopbackHost(remoteHost)
    }

    companion object {
        fun isLoopbackHost(value: String?): Boolean {
            val normalized = value?.trim()?.removePrefix("[")?.removeSuffix("]")?.lowercase() ?: return false
            if (normalized == "localhost" || normalized == "::1" || normalized == "0:0:0:0:0:0:0:1") return true
            if (normalized.startsWith("127.")) return true
            return runCatching { InetAddress.getByName(normalized).isLoopbackAddress }.getOrDefault(false)
        }

        fun isSafeHttpsEndpoint(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null &&
                (uri.port == -1 || uri.port in 1..65_535)
        }.getOrDefault(false)
    }
}

private fun environmentBoolean(name: String, default: Boolean): Boolean =
    System.getenv(name)?.trim()?.toBooleanStrictOrNull() ?: default

private fun legacyAdminKeyMaterialConfigured(): Boolean =
    !System.getenv("RELAY_GATEWAY_ADMIN_KEY").isNullOrBlank() || defaultAdminKeyFile().isFile

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

/** Returns only a source descriptor; callers must never print the legacy key itself. */
fun resolveAdminKeySource(): String {
    val env = System.getenv("RELAY_GATEWAY_ADMIN_KEY")
    if (!env.isNullOrBlank()) return "env:RELAY_GATEWAY_ADMIN_KEY"
    val file = defaultAdminKeyFile()
    return if (file.isFile) "file:${file.absolutePath}" else "generated-file:${file.absolutePath}"
}

/** Development-only migration compatibility. Production never calls this function. */
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
