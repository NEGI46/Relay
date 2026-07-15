package com.example.relay.gateway

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class GatewayLanAnnouncement(
    val service: String = "relay-pc-gateway",
    val discoveryVersion: Int = 1,
    val protocolVersion: Int = 1,
    val gatewayId: String = "",
    val apiPort: Int = 8080,
    val anonymousIngressPath: String = "/api/public/sync/messages",
    val receiptTrust: String = "UNVERIFIED",
)

interface GatewayDiscovery {
    /**
     * Wait for a LAN beacon. Default timeout covers at least one PC beacon interval (5s).
     */
    suspend fun discover(timeoutMs: Int = 6_000): DiscoveredGateway?
}

/**
 * Dependency-free discovery matching the PC Gateway UDP beacon.
 *
 * The PC gateway sends announcements **to** [port] (default 42888). This client must bind
 * that same port to receive them; an ephemeral socket never sees the packets.
 */
class UdpGatewayDiscovery(
    private val port: Int = 42888,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : GatewayDiscovery {
    override suspend fun discover(timeoutMs: Int): DiscoveredGateway? = withContext(Dispatchers.IO) {
        runCatching {
            val waitMs = timeoutMs.coerceIn(1_000, 15_000)
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.broadcast = true
                socket.bind(InetSocketAddress(port))
                // Short per-packet timeout so we can loop until the overall deadline.
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
                        json.decodeFromString<GatewayLanAnnouncement>(
                            String(packet.data, 0, packet.length, Charsets.UTF_8),
                        )
                    }.getOrNull() ?: continue
                    if (announcement.service != "relay-pc-gateway" ||
                        announcement.discoveryVersion != 1 ||
                        announcement.apiPort !in 1..65_535 ||
                        announcement.gatewayId.isBlank() ||
                        announcement.anonymousIngressPath != "/api/public/sync/messages"
                    ) {
                        continue
                    }
                    val host = packet.address.hostAddress ?: continue
                    return@runCatching DiscoveredGateway(host, announcement.apiPort, announcement.gatewayId)
                }
                null
            }
        }.getOrNull()
    }
}
