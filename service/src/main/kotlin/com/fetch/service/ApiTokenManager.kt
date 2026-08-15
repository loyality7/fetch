package com.fetch.service

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Generates and securely stores the localhost API Bearer token using the Android KeyStore.
 */
public class ApiTokenManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    public fun getOrCreateToken(): String {
        val existing = getToken()
        if (existing != null) return existing

        val newToken = generateRandomToken()
        storeToken(newToken)
        return newToken
    }

    public fun getToken(): String? {
        val encryptedBase64 = prefs.getString(KEY_ENCRYPTED_TOKEN, null) ?: return null
        val ivBase64 = prefs.getString(KEY_IV, null) ?: return null

        return try {
            val encryptedBytes = Base64.getDecoder().decode(encryptedBase64)
            val iv = Base64.getDecoder().decode(ivBase64)

            val secretKey = getSecretKey()
            val cipher = Cipher.getInstance(AES_MODE)
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    public fun rotateToken(): String {
        val newToken = generateRandomToken()
        storeToken(newToken)
        return newToken
    }

    private fun storeToken(token: String) {
        val secretKey = getSecretKey()
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)

        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(token.toByteArray(Charsets.UTF_8))

        val encryptedBase64 = Base64.getEncoder().encodeToString(encryptedBytes)
        val ivBase64 = Base64.getEncoder().encodeToString(iv)

        prefs.edit()
            .putString(KEY_ENCRYPTED_TOKEN, encryptedBase64)
            .putString(KEY_IV, ivBase64)
            .apply()
    }

    private fun generateRandomToken(): String {
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )

            val builder = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)

            keyGenerator.init(builder.build())
            return keyGenerator.generateKey()
        }

        val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
        return entry.secretKey
    }

    private companion object {
        private const val PREFS_NAME = "fetch_service_prefs"
        private const val KEY_ENCRYPTED_TOKEN = "encrypted_token"
        private const val KEY_IV = "token_iv"
        private const val KEY_ALIAS = "fetch_api_token_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_MODE = "AES/GCM/NoPadding"
    }
}
