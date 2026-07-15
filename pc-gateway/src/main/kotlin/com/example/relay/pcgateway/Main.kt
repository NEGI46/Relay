package com.example.relay.pcgateway

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val config = GatewayConfig()
    val store = GatewayStore(config)
    val beacon = GatewayLanBeacon(config)
    println("Relay PC Gateway listening on http://${config.host}:${config.port}")
    println("Admin key is available only in this console: ${config.adminKey}")
    if (config.lanDiscoveryEnabled && config.host !in setOf("127.0.0.1", "localhost", "::1")) {
        beacon.start()
        println("LAN discovery beacon: UDP ${config.lanDiscoveryPort}")
    } else if (config.lanDiscoveryEnabled) {
        println("LAN discovery is inactive while the HTTP server is bound to loopback")
    }
    try {
        embeddedServer(Netty, host = config.host, port = config.port) { gatewayModule(config, store) }.start(wait = true)
    } finally {
        beacon.close()
        store.close()
    }
}
