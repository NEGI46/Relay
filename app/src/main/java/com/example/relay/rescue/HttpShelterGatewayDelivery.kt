package com.example.relay.rescue

import com.example.relay.gateway.DiscoveredGateway
import com.example.relay.gateway.GatewayDiscovery
import com.example.relay.gateway.UdpGatewayDiscovery
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Sends only the already encrypted rescue envelope to a discovered local PC Gateway. */
class HttpShelterGatewayDelivery(
    private val discovery: GatewayDiscovery = UdpGatewayDiscovery(),
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
) {
    suspend fun deliver(
        envelope: EncryptedRescueEnvelope,
        carrierId: String,
        courierDeliveryId: String,
    ): SignedShelterReceipt? = withContext(Dispatchers.IO) {
        val gateway = discovery.discover() ?: return@withContext null
        val request = HttpRescueDeliveryRequest(envelope, carrierId, courierDeliveryId)
        runCatching {
            val connection = (URL("http://${gateway.host}:${gateway.port}/api/public/rescue/deliver")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            connection.outputStream.use { it.write(json.encodeToString(request).encodeToByteArray()) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) return@runCatching null
            json.decodeFromString<HttpRescueDeliveryResponse>(body).receipt
        }.getOrNull()
    }
}

@Serializable
private data class HttpRescueDeliveryRequest(
    val envelope: EncryptedRescueEnvelope,
    val carrierId: String,
    val courierDeliveryId: String,
)

@Serializable
private data class HttpRescueDeliveryResponse(
    val outcome: String,
    val reason: String? = null,
    val receipt: SignedShelterReceipt? = null,
)
