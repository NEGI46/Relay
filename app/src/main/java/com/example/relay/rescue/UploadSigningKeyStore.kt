package com.example.relay.rescue

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64
import java.util.UUID

/**
 * Android Keystore-backed ECDSA P-256 key for signing Broker uploads.
 * Each device generates a unique keyId (UUID) on first launch.
 * The key proves upload origin; the Broker verifies signatures against the registered public key.
 */
class UploadSigningKeyStore(context: Context) {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private val prefs: SharedPreferences =
        context.getSharedPreferences("relay_device_identity", Context.MODE_PRIVATE)

    /**
     * Per-device unique key identifier. Generated once on first launch and persisted.
     * This is NOT the Keystore alias (which is constant) — it's a random UUID.
     */
    val keyId: String by lazy {
        prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            id
        }
    }

    /**
     * Capability token issued by the Broker on device registration.
     * Used for receipt polling instead of the public deviceKeyId.
     * Persisted across restarts.
     */
    var capabilityToken: String?
        get() = prefs.getString(KEY_CAPABILITY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_CAPABILITY_TOKEN, value).apply()

    /** Returns the public key for registration with the Broker. */
    fun publicKeyBase64(): String {
        ensureKeyExists()
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: return ""
        val publicKey = entry.certificate.publicKey
        return Base64.getEncoder().encodeToString(publicKey.encoded)
    }

    /**
     * Signs the canonical upload bytes: authenticatedHeaderBytes + ciphertextSha256Hex.
     * Returns Base64-encoded ECDSA signature.
     */
    fun sign(envelope: EncryptedRescueEnvelope): String {
        val privateKey = getOrCreatePrivateKey()
        val dataToSign = envelope.authenticatedHeaderBytes() + envelope.ciphertextSha256Hex.encodeToByteArray()
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(privateKey)
        signature.update(dataToSign)
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    private fun ensureKeyExists() {
        if (keyStore.containsAlias(KEY_ALIAS)) return
        getOrCreatePrivateKey()
    }

    private fun getOrCreatePrivateKey(): PrivateKey {
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) return existing.privateKey

        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE,
        )
        generator.initialize(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                .build(),
        )
        generator.generateKeyPair()
        return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry).privateKey
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "relay_broker_upload"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val KEY_DEVICE_ID = "device_key_id"
        private const val KEY_CAPABILITY_TOKEN = "capability_token"
    }
}
