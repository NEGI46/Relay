package com.example.relay.broker

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Purge interval: check for expired envelopes every 10 minutes. */
private const val PURGE_INTERVAL_MS = 10L * 60 * 1000

/**
 * Relay Broker entry point.
 *
 * TLS: In production, run behind a TLS-terminating reverse proxy (nginx, Caddy, etc.)
 * that forwards to this HTTP endpoint. The Broker itself listens on plain HTTP.
 * Android clients MUST connect via HTTPS (enforced client-side).
 *
 * Environment variables:
 * - RELAY_BROKER_PORT: HTTP listen port (default: 8443)
 * - RELAY_BROKER_DB_PATH: SQLite database path (default: ./data/broker.db)
 * - RELAY_BROKER_GATEWAY_API_KEY: Pre-shared key for Gateway authentication (optional in dev)
 */
fun main() {
    val port = System.getenv("RELAY_BROKER_PORT")?.toIntOrNull() ?: 8443
    val dbPath = System.getenv("RELAY_BROKER_DB_PATH") ?: "./data/broker.db"

    val store = BrokerStore(dbPath)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Periodic purge of expired envelopes
    scope.launch {
        while (isActive) {
            delay(PURGE_INTERVAL_MS)
            val purged = store.purgeExpired(System.currentTimeMillis())
            if (purged > 0) println("Broker purged $purged expired envelope(s)")
        }
    }

    println("Relay Broker starting on port $port")
    println("Database: $dbPath")
    val apiKey = System.getenv("RELAY_BROKER_GATEWAY_API_KEY")
    println("Gateway auth: ${if (apiKey != null) "enabled" else "DISABLED (development mode)"}")

    try {
        embeddedServer(Netty, host = "0.0.0.0", port = port) {
            brokerModule(store)
        }.start(wait = true)
    } finally {
        store.close()
    }
}
