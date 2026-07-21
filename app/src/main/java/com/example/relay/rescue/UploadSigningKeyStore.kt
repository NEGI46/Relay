package com.example.relay.rescue

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/**
 * Android Keystore-backed ECDSA P-256 key for signing Broker uploads.
 * The key proves upload origin but is NOT identity-verified by the Broker.
 */
class UploadSigningKeyStore {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    val keyId: String
        get() = KEY_ALIAS

    /** Returns the public key for registration with the Broker (informational only). */
    fun publicKeyBase64(): String {
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
    }
}
