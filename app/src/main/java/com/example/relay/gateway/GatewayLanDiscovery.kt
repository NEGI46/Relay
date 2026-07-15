package com.example.relay.gateway

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
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
    suspend fun discover(timeoutMs: Int = 900): DiscoveredGateway?
}

/** Dependency-free discovery matching the PC Gateway UDP beacon. */
class UdpGatewayDiscovery(
    private val port: Int = 42888,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : GatewayDiscovery {
    override suspend fun discover(timeoutMs: Int): DiscoveredGateway? = withContext(Dispatchers.IO) {
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = timeoutMs.coerceIn(100, 3_000)
                val packet = DatagramPacket(ByteArray(8192), 8192)
                socket.receive(packet)
                val announcement = json.decodeFromString<GatewayLanAnnouncement>(String(packet.data, 0, packet.length, Charsets.UTF_8))
                if (announcement.service != "relay-pc-gateway" || announcement.discoveryVersion != 1 ||
                    announcement.apiPort !in 1..65_535 || announcement.gatewayId.isBlank() ||
                    announcement.anonymousIngressPath != "/api/public/sync/messages"
                ) return@runCatching null
                DiscoveredGateway(packet.address.hostAddress ?: return@runCatching null, announcement.apiPort, announcement.gatewayId)
            }
        }.getOrNull()
    }
}
