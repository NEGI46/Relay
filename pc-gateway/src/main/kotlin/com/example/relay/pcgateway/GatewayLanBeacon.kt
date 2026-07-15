package com.example.relay.pcgateway

import com.example.relay.gateway.protocol.GATEWAY_PROTOCOL_VERSION
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class GatewayLanAnnouncement(
    val service: String = "relay-pc-gateway",
    val discoveryVersion: Int = 1,
    val protocolVersion: Int = GATEWAY_PROTOCOL_VERSION,
    val gatewayId: String,
    val apiPort: Int,
    val anonymousIngressPath: String = "/api/public/sync/messages",
    val receiptTrust: String = "UNVERIFIED",
)

/**
 * Dependency-free LAN hint. Clients use the UDP source address as the host and still validate
 * every HTTP response. This is discovery only, not authentication and not mDNS.
 */
class GatewayLanBeacon(
    private val config: GatewayConfig,
    private val broadcastAddress: String = "255.255.255.255",
) : AutoCloseable {
    @Volatile private var running = false
    private var worker: Thread? = null

    fun announcementBytes(): ByteArray = GatewayJson.encodeToString(
        GatewayLanAnnouncement(gatewayId = config.gatewayId, apiPort = config.port),
    ).encodeToByteArray()

    fun start() {
        if (running || !config.lanDiscoveryEnabled) return
        running = true
        worker = thread(name = "relay-gateway-lan-beacon", isDaemon = true) {
            runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val target = InetAddress.getByName(broadcastAddress)
                    while (running) {
                        val bytes = announcementBytes()
                        socket.send(DatagramPacket(bytes, bytes.size, target, config.lanDiscoveryPort))
                        try {
                            Thread.sleep(config.lanDiscoveryIntervalMs.coerceAtLeast(1_000))
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
            }.onFailure { error ->
                if (running) System.err.println("Relay LAN discovery stopped: ${error.message ?: error.javaClass.simpleName}")
            }
            running = false
        }
    }

    override fun close() {
        running = false
        worker?.interrupt()
        worker = null
    }
}
