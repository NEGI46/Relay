package com.example.relay.gateway

import android.content.Context

interface GatewaySettingsStoreContract {
    fun load(): GatewaySettings
    fun save(settings: GatewaySettings)
    fun record(result: String, connectedAt: Long = System.currentTimeMillis())
}

class GatewaySettingsStore(context: Context) : GatewaySettingsStoreContract {
    private val preferences = context.getSharedPreferences("relay_gateway_settings", Context.MODE_PRIVATE)
    override fun load() = GatewaySettings(
        host = preferences.getString("host", "") ?: "",
        port = preferences.getInt("port", 8080),
        gatewayName = preferences.getString("name", "") ?: "",
        bridgeId = preferences.getString("bridgeId", "") ?: "",
        enabled = preferences.getBoolean("enabled", false),
        automaticSync = preferences.getBoolean("automatic", false),
        lastConnectedAt = preferences.getLong("lastConnected", 0).takeIf { it > 0 },
        lastSyncResult = preferences.getString("lastResult", null),
    )
    override fun save(settings: GatewaySettings) { preferences.edit().putString("host", settings.host.trim()).putInt("port", settings.port).putString("name", settings.gatewayName.trim()).putString("bridgeId", settings.bridgeId.trim()).putBoolean("enabled", settings.enabled).putBoolean("automatic", settings.automaticSync).apply() }
    override fun record(result: String, connectedAt: Long) { preferences.edit().putString("lastResult", result.take(160)).putLong("lastConnected", connectedAt).apply() }
}
