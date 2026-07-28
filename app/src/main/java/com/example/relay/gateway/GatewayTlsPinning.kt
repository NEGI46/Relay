package com.example.relay.gateway

import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Opens Gateway HTTPS connections with both platform PKI validation and an out-of-band SPKI pin.
 *
 * The pin is the lowercase SHA-256 hex digest of a certificate public key's SubjectPublicKeyInfo.
 * Keeping the platform trust manager in the chain preserves hostname, validity, and CA checks; the
 * additional pin prevents a different otherwise-trusted certificate from impersonating a Gateway.
 */
internal object GatewayTlsPinning {
    fun open(url: URL, tlsSpkiSha256: String?): HttpURLConnection {
        val connection = url.openConnection() as HttpURLConnection
        if (connection !is HttpsURLConnection) return connection

        val pin = normalizePin(tlsSpkiSha256)
            ?: throw MissingGatewayTlsPinException()
        val platformTrustManager = platformTrustManager()
        val pinningTrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                platformTrustManager.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                platformTrustManager.checkServerTrusted(chain, authType)
                val certificates = chain.orEmpty()
                val pinned = certificates.any { certificate ->
                    MessageDigest.isEqual(
                        sha256(certificate.publicKey.encoded),
                        pin,
                    )
                }
                if (!pinned) throw CertificateException("Gateway TLS public-key pin mismatch")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> =
                platformTrustManager.acceptedIssuers
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(pinningTrustManager), SecureRandom())
        }
        connection.sslSocketFactory = context.socketFactory
        return connection
    }

    private fun platformTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().singleOrNull()
            ?: throw IllegalStateException("platform X509 trust manager unavailable")
    }

    private fun normalizePin(raw: String?): ByteArray? {
        val normalized = raw
            ?.filterNot { it == ':' || it == '-' || it.isWhitespace() }
            ?.lowercase()
            ?.takeIf { it.length == SHA256_HEX_LENGTH && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }
            ?: return null
        return normalized.chunked(2).map(String::toIntHex).map(Int::toByte).toByteArray()
    }

    private fun String.toIntHex(): Int = toInt(16)

    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)

    private const val SHA256_HEX_LENGTH = 64
}

class MissingGatewayTlsPinException :
    SecurityException("HTTPS Gateway connection requires an enrolled TLS SPKI pin")
