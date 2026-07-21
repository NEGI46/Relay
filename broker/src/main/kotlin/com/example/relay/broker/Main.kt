package com.example.relay.broker

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Purge interval: check for expired envelopes every 10 minutes. */
private const val PURGE_INTERVAL_MS = 10L * 60 * 1000

fun main() {
    val port = System.getenv("RELAY_BROKER_PORT")?.toIntOrNull() ?: 8443
    val dbPath = System.getenv("RELAY_BROKER_DB_PATH") ?: "./data/broker.db"
    val keystorePath = System.getenv("RELAY_BROKER_TLS_KEYSTORE")
    val keystorePassword = (System.getenv("RELAY_BROKER_TLS_PASSWORD") ?: "").toCharArray()
    val keyAlias = System.getenv("RELAY_BROKER_TLS_ALIAS") ?: "relay-broker"

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

    try {
        if (keystorePath != null && File(keystorePath).exists()) {
            // HTTPS with TLS
            val ks = KeyStore.getInstance("JKS").apply {
                load(File(keystorePath).inputStream(), keystorePassword)
            }
            println("TLS enabled (keystore: $keystorePath, alias: $keyAlias)")
            embeddedServer(Netty, applicationEngineEnvironment {
                sslConnector(ks, keystorePassword) {
                    this.keyAlias = keyAlias
                    this.port = port
                    this.keyStorePath = File(keystorePath)
                }
                module { brokerModule(store) }
            }).start(wait = true)
        } else {
            // Development mode: plain HTTP (production must use TLS)
            println("WARNING: TLS not configured. Running in plain HTTP mode (development only).")
            println("Set RELAY_BROKER_TLS_KEYSTORE and RELAY_BROKER_TLS_PASSWORD for production.")
            embeddedServer(Netty, host = "0.0.0.0", port = port) {
                brokerModule(store)
            }.start(wait = true)
        }
    } finally {
        store.close()
    }
}
