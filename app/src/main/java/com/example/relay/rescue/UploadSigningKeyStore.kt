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
    private val identityLock = Any()

    /**
     * Per-device unique key identifier. Generated once on first launch and persisted.
     * This is NOT the Keystore alias (which is constant) — it's a random UUID.
     */
    val keyId: String
        get() = synchronized(identityLock) {
            ensureKeyExists()
            prefs.getString(KEY_DEVICE_ID, null) ?: newDeviceKeyId().also { id ->
                prefs.edit().putString(KEY_DEVICE_ID, id).commit()
            }
        }

    /**
     * Capability token issued by the Broker on device registration.
     * Used for receipt polling instead of the public deviceKeyId.
     * Persisted across restarts.
     */
    var capabilityToken: String?
        get() = synchronized(identityLock) {
            // A backup restore or Keystore reset can retain preferences but lose the private key.
            // Ensure the identity is rotated before a stale capability is used.
            ensureKeyExists()
            prefs.getString(KEY_CAPABILITY_TOKEN, null)
        }
        set(value) {
            synchronized(identityLock) {
                prefs.edit().putString(KEY_CAPABILITY_TOKEN, value).apply()
            }
        }

    /** Returns the public key for registration with the Broker. */
    fun publicKeyBase64(): String = synchronized(identityLock) {
        ensureKeyExists()
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: return@synchronized ""
        val publicKey = entry.certificate.publicKey
        Base64.getEncoder().encodeToString(publicKey.encoded)
    }

    /**
     * Signs the canonical upload bytes: authenticatedHeaderBytes + ciphertextSha256Hex.
     * Returns Base64-encoded ECDSA signature.
     */
    fun sign(envelope: EncryptedRescueEnvelope): String = synchronized(identityLock) {
        ensureKeyExists()
        val privateKey = getOrCreatePrivateKey()
        val dataToSign = envelope.authenticatedHeaderBytes() + envelope.ciphertextSha256Hex.encodeToByteArray()
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(privateKey)
        signature.update(dataToSign)
        Base64.getEncoder().encodeToString(signature.sign())
    }

    private fun ensureKeyExists() {
        if (keyStore.containsAlias(KEY_ALIAS)) return
        val hadPersistedIdentity = prefs.contains(KEY_DEVICE_ID)
        getOrCreatePrivateKey()
        val edit = prefs.edit().remove(KEY_CAPABILITY_TOKEN)
        if (hadPersistedIdentity) {
            // The key changed, so the Broker's registered public key and capability token are no
            // longer usable.  Give the replacement key a new opaque device identity instead of
            // trying to overwrite the old registration.
            edit.putString(KEY_DEVICE_ID, newDeviceKeyId())
        }
        edit.commit()
    }

    private fun newDeviceKeyId(): String = UUID.randomUUID().toString()

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
