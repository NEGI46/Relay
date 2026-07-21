package com.example.relay.gateway

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class AndroidGatewayLanAnnouncement(
    val service: String = "relay-pc-gateway",
    val discoveryVersion: Int = 1,
    val protocolVersion: Int = 1,
    val gatewayId: String = "",
    val apiPort: Int = 8080,
    val apiScheme: String = "http",
    val anonymousIngressPath: String = "/api/public/sync/messages",
    val receiptTrust: String = "UNVERIFIED",
)

data class GatewayDiscoveryDiagnostic(val gatewayIp: String?, val result: String)

interface GatewayDiscovery {
    suspend fun discover(timeoutMs: Int = 6_000): DiscoveredGateway?
}

class UdpGatewayDiscovery(
    private val context: Context? = null,
    private val port: Int = 42888,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val multicastLockFactory: (() -> AutoCloseable?)? = null,
    private val onDiagnostic: (GatewayDiscoveryDiagnostic) -> Unit = {},
) : GatewayDiscovery {
    override suspend fun discover(timeoutMs: Int): DiscoveredGateway? = withContext(Dispatchers.IO) {
        val waitMs = timeoutMs.coerceIn(1_000, 15_000)
        val lock = runCatching { multicastLockFactory?.invoke() ?: acquireWifiMulticastLock() }.getOrNull()
        try {
            val result = runCatching {
                DatagramSocket(null).use { socket ->
                    socket.reuseAddress = true
                    socket.broadcast = true
                    socket.bind(InetSocketAddress(port))
                    socket.soTimeout = minOf(2_000, waitMs)
                    val deadline = System.currentTimeMillis() + waitMs
                    val buffer = ByteArray(8192)
                    while (System.currentTimeMillis() < deadline) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        val announcement = runCatching {
                            json.decodeFromString<AndroidGatewayLanAnnouncement>(
                                String(packet.data, 0, packet.length, Charsets.UTF_8),
                            )
                        }.getOrNull() ?: continue
                        if (announcement.service != "relay-pc-gateway" ||
                            announcement.discoveryVersion != 1 ||
                            announcement.apiPort !in 1..65_535 ||
                            announcement.apiScheme !in setOf("http", "https") ||
                            announcement.gatewayId.isBlank() ||
                            announcement.anonymousIngressPath != "/api/public/sync/messages"
                        ) continue
                        val host = packet.address.hostAddress ?: continue
                        return@runCatching DiscoveredGateway(host, announcement.apiPort, announcement.gatewayId, announcement.apiScheme)
                    }
                    null
                }
            }
            result.onSuccess { gateway ->
                onDiagnostic(GatewayDiscoveryDiagnostic(gateway?.host, if (gateway == null) "udp_timeout" else "beacon_received"))
            }.onFailure {
                onDiagnostic(GatewayDiscoveryDiagnostic(null, "udp_error"))
            }
            result.getOrNull()
        } finally {
            runCatching { lock?.close() }
        }
    }

    private fun acquireWifiMulticastLock(): AutoCloseable? {
        val wifi = context?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val lock = wifi.createMulticastLock("relay-gateway-discovery").apply { setReferenceCounted(false); acquire() }
        return AutoCloseable { runCatching { if (lock.isHeld) lock.release() } }
    }
}
