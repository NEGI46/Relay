package com.example.relay.pcgateway

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val config = GatewayConfig()
    val store = GatewayStore(config)
    val beacon = GatewayLanBeacon(config)
    val consoleHost = if (config.host in setOf("0.0.0.0", "::")) "127.0.0.1" else config.host
    println("Relay PC Gateway listening on http://${config.host}:${config.port}")
    println("Operator console: http://$consoleHost:${config.port}/")
    println("Admin key source: ${resolveAdminKeySource()} (value is not printed)")
    println("Database: ${config.dbPath}")
    println("Anonymous ingress: ${config.anonymousIngressEnabled}")
    if (config.lanDiscoveryEnabled && config.host !in setOf("127.0.0.1", "localhost", "::1")) {
        beacon.start()
        println("LAN discovery beacon: UDP ${config.lanDiscoveryPort} → /api/public/sync/messages")
    } else if (config.lanDiscoveryEnabled) {
        println("LAN discovery is inactive while the HTTP server is bound to loopback")
    }
    if (config.host == "0.0.0.0") {
        println("Bound on all interfaces. Restrict with Windows Firewall (Private network only).")
    }
    try {
        embeddedServer(Netty, host = config.host, port = config.port) { gatewayModule(config, store) }.start(wait = true)
    } finally {
        beacon.close()
        store.close()
    }
}
