package com.example.relay.rescue

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

fun interface ShelterManifestClient {
    fun fetch(host: String, port: Int): ShelterPublicKeyManifest
}

class HttpShelterManifestClient(
    private val json: Json = Json { ignoreUnknownKeys = false },
) : ShelterManifestClient {
    override fun fetch(host: String, port: Int): ShelterPublicKeyManifest {
        require(isValidHost(host) && port in 1..65_535) { "invalid shelter address" }
        val connection = (URL("http", host, port, "/api/public/rescue/manifest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        return try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) { "manifest unavailable" }
            val declaredSize = connection.contentLengthLong
            require(declaredSize == -1L || declaredSize in 1..MAX_MANIFEST_BYTES.toLong()) { "manifest too large" }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(4_096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_MANIFEST_BYTES) { "manifest too large" }
                    output.write(buffer, 0, count)
                }
            }
            json.decodeFromString<ShelterPublicKeyManifest>(output.toByteArray().decodeToString())
        } finally {
            connection.disconnect()
        }
    }

    private fun isValidHost(value: String): Boolean = value.length in 1..253 &&
        value.none { it.isWhitespace() || it in "/\\@?#" } &&
        value.all { it.isLetterOrDigit() || it in ".-:" }

    private companion object {
        const val MAX_MANIFEST_BYTES = 24 * 1024
    }
}

class ShelterManifestEnrollment(
    private val client: ShelterManifestClient,
    private val keyStore: RescueShelterKeyStore,
) {
    fun enroll(host: String, port: Int, expectedFingerprint: String): ShelterPublicKeys {
        val manifest = client.fetch(host.trim(), port)
        keyStore.saveVerifiedManifest(manifest, normalizeFingerprint(expectedFingerprint))
        return requireNotNull(keyStore.load())
    }

    private fun normalizeFingerprint(value: String): String = value
        .filterNot { it == ':' || it == '-' || it.isWhitespace() }
        .lowercase()
}
