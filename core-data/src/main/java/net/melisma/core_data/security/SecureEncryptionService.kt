package net.melisma.core_data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for securely encrypting and decrypting sensitive data using Android Keystore.
 * Uses AES/GCM/NoPadding transformation for strong encryption with authentication.
 */
@Singleton
class SecureEncryptionService @Inject constructor() {

    companion object {
        private const val TAG = "SecureEncryptionService"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_ALIAS = "net.melisma.mail.encryption_key"
        private const val GCM_TAG_LENGTH = 128 // bits
        private const val GCM_IV_LENGTH = 12 // bytes
    }

    private val keyStore: KeyStore by lazy {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        keyStore
    }

    /**
     * Encrypts the provided data using a key from the Android Keystore.
     *
     * @param data The plain text data to encrypt.
     * @return Base64 encoded string containing IV + encrypted data, or null if encryption fails.
     */
    fun encrypt(data: String): String? {
        return try {
            // Get or create encryption key
            val key = getOrCreateKey()

            // Create encryption cipher with random IV
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)

            // Encrypt the data
            val encryptedBytes = cipher.doFinal(data.toByteArray(Charsets.UTF_8))

            // Prepend IV to the encrypted data (IV doesn't need to be secret, just unique)
            val ivAndEncryptedData = cipher.iv + encryptedBytes

            // Return Base64 encoded string (IV + encrypted data)
            Base64.encodeToString(ivAndEncryptedData, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            null
        }
    }

    /**
     * Decrypts the provided encrypted data using a key from the Android Keystore.
     *
     * @param encryptedData Base64 encoded string containing IV + encrypted data.
     * @return Decrypted plain text, or null if decryption fails.
     */
    fun decrypt(encryptedData: String): String? {
        return try {
            // Get the key from keystore
            val key = getKey() ?: return null

            // Get the raw bytes from the Base64 encoded string
            val encryptedBytes = Base64.decode(encryptedData, Base64.NO_WRAP)

            // First GCM_IV_LENGTH bytes are the IV
            val iv = encryptedBytes.copyOfRange(0, GCM_IV_LENGTH)
            val encrypted = encryptedBytes.copyOfRange(GCM_IV_LENGTH, encryptedBytes.size)

            // Create GCM specification with the extracted IV
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)

            // Initialize cipher for decryption
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)

            // Decrypt the data
            val decryptedBytes = cipher.doFinal(encrypted)

            // Return decrypted string
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            null
        }
    }

    /**
     * Gets the encryption key from the keystore or creates it if it doesn't exist.
     *
     * @return The secret key for encryption/decryption.
     */
    private fun getOrCreateKey(): SecretKey {
        val existingKey = getKey()
        if (existingKey != null) {
            return existingKey
        }

        // Key doesn't exist, generate a new one
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val keySpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256) // Use 256-bit key for stronger encryption
            .setUserAuthenticationRequired(false) // Can be set to true with biometric authentication
            .build()

        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }

    /**
     * Gets the encryption key from the keystore if it exists.
     *
     * @return The existing key, or null if it doesn't exist.
     */
    private fun getKey(): SecretKey? {
        return try {
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.getKey(KEY_ALIAS, null) as SecretKey
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving key", e)
            null
        }
    }

    /**
     * Checks if the encryption service is available and working properly.
     *
     * @return True if encryption/decryption functionality works as expected.
     */
    fun isAvailable(): Boolean {
        val testData = "test_encryption_service_availability"
        val encrypted = encrypt(testData)
        val decrypted = encrypted?.let { decrypt(it) }
        return testData == decrypted
    }
}