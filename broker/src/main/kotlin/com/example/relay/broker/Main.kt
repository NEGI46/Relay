package com.example.relay.broker

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
 * - RELAY_BROKER_GATEWAY_API_KEY: Pre-shared key for Gateway authentication
 * - RELAY_BROKER_ALLOW_INSECURE_GATEWAY: Explicitly allow unauthenticated Gateway APIs in dev
 */
fun main() {
    val port = System.getenv("RELAY_BROKER_PORT")?.toIntOrNull()?.takeIf { it in 1..65_535 } ?: 8443
    val dbPath = System.getenv("RELAY_BROKER_DB_PATH") ?: "./data/broker.db"
    val apiKey = System.getenv("RELAY_BROKER_GATEWAY_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }
    val allowInsecureGateway = System.getenv("RELAY_BROKER_ALLOW_INSECURE_GATEWAY")
        ?.toBooleanStrictOrNull() == true
    require(apiKey != null || allowInsecureGateway) {
        "RELAY_BROKER_GATEWAY_API_KEY is required; set RELAY_BROKER_ALLOW_INSECURE_GATEWAY=true only for local development"
    }

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
    println("Gateway auth: ${if (apiKey != null) "enabled" else "DISABLED (explicit development mode)"}")

    try {
        embeddedServer(Netty, host = "0.0.0.0", port = port) {
            brokerModule(store, gatewayApiKey = apiKey)
        }.start(wait = true)
    } finally {
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        store.close()
    }
}
