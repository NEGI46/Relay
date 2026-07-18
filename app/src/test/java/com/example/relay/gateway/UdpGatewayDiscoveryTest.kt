package com.example.relay.gateway

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UdpGatewayDiscoveryTest {
    @Test
    fun `receives announcement on bound discovery port`() = runBlocking {
        val port = freeUdpPort()
        val discovery = UdpGatewayDiscovery(port = port)
        val waiter = async { discovery.discover(timeoutMs = 3_000) }
        delay(150)
        val payload = """
            {"service":"relay-pc-gateway","discoveryVersion":1,"protocolVersion":1,
             "gatewayId":"gw-test","apiPort":8080,
             "anonymousIngressPath":"/api/public/sync/messages","receiptTrust":"UNVERIFIED"}
        """.trimIndent().toByteArray(Charsets.UTF_8)
        DatagramSocket().use { sender ->
            sender.broadcast = true
            val packet = DatagramPacket(payload, payload.size, InetAddress.getByName("127.0.0.1"), port)
            sender.send(packet)
        }
        val found = waiter.await()
        assertNotNull(found)
        assertEquals(8080, found!!.port)
        assertEquals("gw-test", found.gatewayId)
    }

    @Test
    fun `ignores malformed or wrong-service packets and times out cleanly`() = runBlocking {
        val port = freeUdpPort()
        val discovery = UdpGatewayDiscovery(port = port)
        val waiter = async { discovery.discover(timeoutMs = 1_200) }
        delay(100)
        DatagramSocket().use { sender ->
            val bad = """{"service":"other","discoveryVersion":1,"gatewayId":"x","apiPort":1}"""
                .toByteArray(Charsets.UTF_8)
            sender.send(DatagramPacket(bad, bad.size, InetAddress.getByName("127.0.0.1"), port))
        }
        assertNull(waiter.await())
    }

    private fun freeUdpPort(): Int {
        return DatagramSocket(0).use { it.localPort }
    }
}
