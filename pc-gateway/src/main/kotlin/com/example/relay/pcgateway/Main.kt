package com.example.relay.pcgateway

import com.example.relay.pcgateway.rescue.RescueDeliveryIngress
import com.example.relay.pcgateway.rescue.RescueIntakeService
import com.example.relay.pcgateway.rescue.RescueKeyStore
import com.example.relay.pcgateway.rescue.BrokerPullAgent
import com.example.relay.pcgateway.rescue.ReceiptOutbox
import com.example.relay.pcgateway.rescue.SqliteRescuePersistence
import com.example.relay.pcgateway.rescue.provisioning.BleBridgeEnvironmentStore
import com.example.relay.pcgateway.rescue.provisioning.SignedShelterManifestStore
import com.example.relay.rescue.RegionalRootBundle
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

fun main() {
    val config = GatewayConfig()
    val store = GatewayStore(config)
    val rescueKeys = RescueKeyStore(Path.of(config.rescueKeyPath), config.shelterId).loadOrCreate()
    val verifiedBleManifest = loadVerifiedBleManifest(config, rescueKeys)
    val bleBridgeEnvironment = verifiedBleManifest?.let { signed ->
        config.bleBridgeEnvironmentFor(signed).also { environment ->
            BleBridgeEnvironmentStore.write(
                Path.of(config.rescueSignedManifestPath!!).resolveSibling("ble-bridge.env"),
                environment,
            )
        }
    }
    // Do not bind the sidecar ingress unless the advertised shelter identity is root-signed,
    // current, and matches the two locally held private keys. This prevents an unprovisioned PC
    // from accepting delivery traffic merely because its generated public manifest is reachable.
    var receiptOutboxRef: ReceiptOutbox? = null
    val rescueIntakeService = RescueIntakeService(
        shelterId = config.shelterId,
        recipientPrivateKey = rescueKeys.recipientPrivateKey,
        shelterSigningPrivateKey = rescueKeys.receiptSigningPrivateKey,
        persistence = store.rescuePersistence(),
        onReceiptIssued = { receipt -> receiptOutboxRef?.enqueue(receipt) },
    )
    rescueIntakeService.purgeExpiredDetails()
    val offlineMap = GsiTileCache(Path.of(config.offlineMapPath))
    val officialInformation = OfficialInformationService(Path.of(config.officialInfoCachePath))
    val rescueIngress = verifiedBleManifest?.let { RescueDeliveryIngress(rescueIntakeService) }
    val beacon = GatewayLanBeacon(config)
    val consoleHost = if (config.host in setOf("0.0.0.0", "::")) "127.0.0.1" else config.host
    println("Relay PC Gateway listening on http://${config.host}:${config.port}")
    println("Operator console: http://$consoleHost:${config.port}/")
    println("Admin key source: ${resolveAdminKeySource()} (value is not printed)")
    println("Database: ${config.dbPath}")
    println("Anonymous ingress: ${config.anonymousIngressEnabled}")
    println("Rescue shelter: ${config.shelterId}")
    if (verifiedBleManifest != null) {
        println("Rescue BLE trust: ready (root-signed shelter manifest verified)")
        println("Rescue maintenance manifest fingerprint: ${verifiedBleManifest.manifest.fingerprint()}")
        println("Rescue BLE signed-manifest fingerprint base64: ${bleBridgeEnvironment?.signedManifestFingerprintBase64}")
        println("BLE sidecar ingress: http://${config.bleBridgeIngressHost}:${config.bleBridgeIngressPort} (loopback only; secret is not printed)")
    } else {
        println("Rescue BLE trust: not ready; automatic rescue delivery is disabled (legacy manifest remains maintenance-only)")
    }
    if (config.lanDiscoveryEnabled && config.host !in setOf("127.0.0.1", "localhost", "::1")) {
        beacon.start()
        println("LAN discovery beacon: UDP ${config.lanDiscoveryPort} → /api/public/sync/messages")
    } else if (config.lanDiscoveryEnabled) {
        println("LAN discovery is inactive while the HTTP server is bound to loopback")
    }

    // Broker cloud relay: receipt outbox + pull agent (independent of LAN/BLE)
    // Startup order: Outbox MUST be ready before PullAgent starts (receipts from early pulls must not be lost)
    val brokerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var brokerHttpClient: HttpClient? = null
    var receiptOutbox: ReceiptOutbox? = null
    if (config.brokerUrl != null) {
        val client = HttpClient(CIO)
        brokerHttpClient = client

        // 1. Create ReceiptOutbox FIRST using the same DB connection as rescue persistence
        val rescuePersistence = store.rescuePersistence() as SqliteRescuePersistence
        val outbox = ReceiptOutbox(
            persistence = rescuePersistence,
            brokerUrl = config.brokerUrl,
            shelterId = config.shelterId,
            gatewayId = config.gatewayId,
            httpClient = client,
            gatewayApiKey = config.brokerApiKey,
        )
        receiptOutbox = outbox
        receiptOutboxRef = outbox
        brokerScope.launch { outbox.startFlusher(this) }

        // 2. Start PullAgent AFTER outbox is ready
        val cursorPath = Path.of(config.dbPath).resolveSibling("broker-pull-cursor.txt")
        val pullAgent = BrokerPullAgent(
            brokerUrl = config.brokerUrl,
            shelterId = config.shelterId,
            gatewayId = config.gatewayId,
            intakeService = rescueIntakeService,
            httpClient = client,
            gatewayApiKey = config.brokerApiKey,
            cursorPath = cursorPath,
            pollIntervalMs = config.brokerPollIntervalMs,
        )
        brokerScope.launch { pullAgent.start(this) }

        println("Broker cloud relay: ${config.brokerUrl} (poll every ${config.brokerPollIntervalMs}ms)")
    } else {
        println("Broker cloud relay: disabled (RELAY_BROKER_URL not set)")
    }
    if (config.host == "0.0.0.0") {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val firewallHint = when {
            os.contains("win") -> "Restrict with Windows Firewall (Private network only)."
            os.contains("mac") -> "Restrict with macOS Application Firewall / pf (scripts/setup-pc-gateway-macos.sh)."
            else -> "Restrict with the host firewall to the trusted LAN only."
        }
        println("Bound on all interfaces. $firewallHint")
    }
    try {
        val bleIngressServer = rescueIngress?.let { ingress ->
            embeddedServer(Netty, host = config.bleBridgeIngressHost, port = config.bleBridgeIngressPort) {
                bleBridgeIngressModule(config, ingress)
            }.start(wait = false)
        }
        try {
            embeddedServer(Netty, host = config.host, port = config.port) {
                // This unsigned route is retained only for explicitly marked maintenance tooling.
                // BLE delivery is gated above by the independently verified signed manifest.
                gatewayModule(
                    config,
                    store,
                    rescueManifest = rescueKeys.manifest,
                    rescueBleReady = verifiedBleManifest != null,
                    rescueIntakeService = rescueIntakeService,
                    offlineMap = offlineMap,
                    officialInformation = officialInformation,
                )
            }.start(wait = true)
        } finally {
            bleIngressServer?.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
        }
    } finally {
        beacon.close()
        offlineMap.close()
        brokerHttpClient?.close()
        store.close()
    }
}

/**
 * Startup boundary for automatic rescue delivery. It intentionally returns no usable identity on
 * any parse, signature, expiry, root, or local-key mismatch failure.
 */
private fun loadVerifiedBleManifest(
    config: GatewayConfig,
    localKeys: com.example.relay.pcgateway.rescue.RescueGatewayKeys,
): com.example.relay.rescue.SignedShelterManifest? = runCatching {
    val rootPath = requireNotNull(config.rescueRegionalRootBundlePath) {
        "RELAY_RESCUE_REGIONAL_ROOT_BUNDLE_FILE is not configured"
    }
    val signedManifestPath = requireNotNull(config.rescueSignedManifestPath) {
        "RELAY_RESCUE_SIGNED_MANIFEST_FILE is not configured"
    }
    val rootBundle = Json { ignoreUnknownKeys = false }
        .decodeFromString<RegionalRootBundle>(java.nio.file.Files.readString(Path.of(rootPath)))
    SignedShelterManifestStore(Path.of(signedManifestPath)).loadVerified(
        rootBundle = rootBundle,
        localKeys = localKeys,
        nowEpochMillis = System.currentTimeMillis(),
    )
}.onFailure { error ->
    // Do not expose key values, signatures, or file contents in logs or HTTP status.
    System.err.println("Rescue BLE trust verification failed; automatic rescue delivery remains disabled: ${error.message ?: "invalid trust configuration"}")
}.getOrNull()
