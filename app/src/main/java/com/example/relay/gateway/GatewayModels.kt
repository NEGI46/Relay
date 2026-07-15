package com.example.relay.gateway

data class GatewaySettings(
    val host: String = "",
    val port: Int = 8080,
    val gatewayName: String = "",
    val bridgeId: String = "",
    val enabled: Boolean = false,
    val automaticSync: Boolean = false,
    val lastConnectedAt: Long? = null,
    val lastSyncResult: String? = null,
)

data class DiscoveredGateway(
    val host: String,
    val port: Int,
    val gatewayId: String,
)

sealed interface GatewaySyncResult {
    data class Completed(val sent: Int, val receipts: Int) : GatewaySyncResult
    data class Deferred(val reason: String) : GatewaySyncResult
    data class Failed(val reason: String, val retryable: Boolean = true) : GatewaySyncResult
}
