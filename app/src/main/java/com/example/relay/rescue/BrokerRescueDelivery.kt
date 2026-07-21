package com.example.relay.rescue

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
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

/** Wire model matching Broker's BrokerDeviceRegisterRequest. */
@Serializable
data class BrokerDeviceRegisterRequest(
    val deviceKeyId: String,
    val publicKeyBase64: String,
)

/** Wire model matching Broker's BrokerDeviceRegisterResponse. */
@Serializable
data class BrokerDeviceRegisterResponse(
    val deviceKeyId: String,
    val capabilityToken: String,
)

/** Wire model matching Broker's BrokerReceiptBatch (with monotonic cursor). */
@Serializable
data class BrokerReceiptBatch(
    val receipts: List<SignedShelterReceipt>,
    val cursor: Long = 0,
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
 *
 * Security: HTTPS-only, no redirects, endpoint validation.
 */
class BrokerRescueDelivery(
    private val context: Context,
    private val endpoint: String,
    private val signingKeyStore: UploadSigningKeyStore,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    init {
        require(endpoint.isBlank() || endpoint.startsWith("https://")) {
            "Broker endpoint must use HTTPS (got: $endpoint)"
        }
    }

    /**
     * Registers this device with the Broker and stores the capability token.
     * Must be called before upload/receipt operations. Idempotent.
     */
    suspend fun ensureRegistered(): Boolean = withContext(Dispatchers.IO) {
        if (endpoint.isBlank()) return@withContext false
        if (signingKeyStore.capabilityToken != null) return@withContext true
        if (!isOnline()) return@withContext false

        runCatching {
            val request = BrokerDeviceRegisterRequest(
                deviceKeyId = signingKeyStore.keyId,
                publicKeyBase64 = signingKeyStore.publicKeyBase64(),
            )
            val connection = openSecureConnection("$endpoint/v1/devices/register", "POST")
            connection.outputStream.use { it.write(json.encodeToString(request).toByteArray()) }
            val status = connection.responseCode
            if (status !in 200..299) return@withContext false
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val response = json.decodeFromString<BrokerDeviceRegisterResponse>(body)
            signingKeyStore.capabilityToken = response.capabilityToken
            true
        }.getOrDefault(false)
    }

    /**
     * Attempts to upload the envelope to the Broker.
     * The envelope is sent as-is (hopCount unchanged).
     */
    suspend fun deliver(envelope: EncryptedRescueEnvelope): BrokerDeliveryResult = withContext(Dispatchers.IO) {
        if (endpoint.isBlank()) return@withContext BrokerDeliveryResult.Disabled
        if (!isOnline()) return@withContext BrokerDeliveryResult.Offline

        // Ensure device is registered before upload
        if (signingKeyStore.capabilityToken == null) {
            if (!ensureRegisteredSync()) {
                return@withContext BrokerDeliveryResult.Failed("registration_failed", retryable = true)
            }
        }

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
            val connection = openSecureConnection("$endpoint/v1/rescue/upload", "POST")
            connection.outputStream.use { it.write(json.encodeToString(request).toByteArray()) }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()

            when {
                status == 201 || status == 200 -> {
                    val response = json.decodeFromString<BrokerUploadResponse>(body)
                    BrokerDeliveryResult.Stored(response)
                }
                status == 401 -> BrokerDeliveryResult.Failed("unauthorized", retryable = true)
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
     * Polls the Broker for signed shelter receipts using the unguessable capability token.
     * Uses Broker monotonic seq cursor (not device time).
     */
    suspend fun pollReceipts(sinceSeq: Long = 0): BrokerReceiptBatch = withContext(Dispatchers.IO) {
        if (endpoint.isBlank()) return@withContext BrokerReceiptBatch(emptyList(), sinceSeq)
        if (!isOnline()) return@withContext BrokerReceiptBatch(emptyList(), sinceSeq)

        val token = signingKeyStore.capabilityToken
            ?: return@withContext BrokerReceiptBatch(emptyList(), sinceSeq)

        runCatching {
            val url = "$endpoint/v1/receipts?token=$token&sinceSeq=$sinceSeq"
            val connection = openSecureConnection(url, "GET")
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status !in 200..299) return@withContext BrokerReceiptBatch(emptyList(), sinceSeq)
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            json.decodeFromString<BrokerReceiptBatch>(body)
        }.getOrDefault(BrokerReceiptBatch(emptyList(), sinceSeq))
    }

    /**
     * Opens an HTTPS-only connection with redirects disabled.
     * Rejects non-HTTPS URLs at the connection level.
     */
    private fun openSecureConnection(url: String, method: String): HttpsURLConnection {
        require(url.startsWith("https://")) { "HTTPS required for Broker communication" }
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Content-Type", "application/json")
        return connection
    }

    private fun ensureRegisteredSync(): Boolean {
        if (signingKeyStore.capabilityToken != null) return true
        return runCatching {
            val request = BrokerDeviceRegisterRequest(
                deviceKeyId = signingKeyStore.keyId,
                publicKeyBase64 = signingKeyStore.publicKeyBase64(),
            )
            val connection = openSecureConnection("$endpoint/v1/devices/register", "POST")
            connection.outputStream.use { it.write(json.encodeToString(request).toByteArray()) }
            val status = connection.responseCode
            if (status !in 200..299) return false
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val response = json.decodeFromString<BrokerDeviceRegisterResponse>(body)
            signingKeyStore.capabilityToken = response.capabilityToken
            true
        }.getOrDefault(false)
    }

    private fun isOnline(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
