package com.example.relay.rescue

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Wire model matching Broker's BrokerUploadRequest. */
@Serializable
data class BrokerUploadRequest(
    val envelope: EncryptedRescueEnvelope,
    val deviceKeyId: String,
    val uploadSignatureBase64: String,
)

/** Wire model matching Broker's BrokerUploadResponse. */
@Serializable
data class BrokerUploadResponse(
    val brokerReceiptId: String,
    val envelopeId: String,
    val status: String = "BROKER_STORED",
    val storedAtEpochMillis: Long,
)

/** Wire model matching Broker's BrokerReceiptBatch. */
@Serializable
data class BrokerReceiptBatch(
    val receipts: List<SignedShelterReceipt>,
)

/** Result of a Broker delivery attempt. */
sealed interface BrokerDeliveryResult {
    data class Stored(val response: BrokerUploadResponse) : BrokerDeliveryResult
    data object Offline : BrokerDeliveryResult
    data object Disabled : BrokerDeliveryResult
    data class Failed(val reason: String, val retryable: Boolean = true) : BrokerDeliveryResult
}

/**
 * Delivers encrypted rescue envelopes to the HTTPS Broker.
 * Does NOT increment hopCount — Broker is not a Store-Carry-Forward hop.
 * Runs independently of Nearby/BLE/LAN paths.
 */
class BrokerRescueDelivery(
    private val context: Context,
    private val endpoint: String,
    private val signingKeyStore: UploadSigningKeyStore,
    private val json: Json = Json { ignoreUnknownKeys = false; encodeDefaults = true },
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /**
     * Attempts to upload the envelope to the Broker.
     * The envelope is sent as-is (hopCount unchanged).
     */
    suspend fun deliver(envelope: EncryptedRescueEnvelope): BrokerDeliveryResult = withContext(Dispatchers.IO) {
        if (endpoint.isBlank()) return@withContext BrokerDeliveryResult.Disabled
        if (!isOnline()) return@withContext BrokerDeliveryResult.Offline

        val signature = try {
            signingKeyStore.sign(envelope)
        } catch (e: Exception) {
            return@withContext BrokerDeliveryResult.Failed("signing_failed:${e.message}", retryable = false)
        }

        val request = BrokerUploadRequest(
            envelope = envelope,
            deviceKeyId = signingKeyStore.keyId,
            uploadSignatureBase64 = signature,
        )

        runCatching {
            val connection = (URL("$endpoint/v1/rescue/upload").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json")
            }
            connection.outputStream.use { it.write(json.encodeToString(request).toByteArray()) }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()

            when {
                status == 201 || status == 200 -> {
                    val response = json.decodeFromString<BrokerUploadResponse>(body)
                    BrokerDeliveryResult.Stored(response)
                }
                status == 409 -> BrokerDeliveryResult.Failed("collision", retryable = false)
                status == 429 -> BrokerDeliveryResult.Failed("rate_limited", retryable = true)
                status in 400..499 -> BrokerDeliveryResult.Failed("client_error_$status", retryable = false)
                else -> BrokerDeliveryResult.Failed("server_error_$status", retryable = true)
            }
        }.getOrElse { error ->
            BrokerDeliveryResult.Failed("network:${error.message ?: "unknown"}", retryable = true)
        }
    }

    /**
     * Polls the Broker for signed shelter receipts addressed to this device.
     * The deviceKeyId acts as a capability token.
     */
    suspend fun pollReceipts(sinceEpochMillis: Long = 0): List<SignedShelterReceipt> = withContext(Dispatchers.IO) {
        if (endpoint.isBlank()) return@withContext emptyList()
        if (!isOnline()) return@withContext emptyList()

        runCatching {
            val url = "$endpoint/v1/devices/${signingKeyStore.keyId}/receipts?since=$sinceEpochMillis"
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
            }
            val status = connection.responseCode
            if (status !in 200..299) return@withContext emptyList()
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            json.decodeFromString<BrokerReceiptBatch>(body).receipts
        }.getOrDefault(emptyList())
    }

    private fun isOnline(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
