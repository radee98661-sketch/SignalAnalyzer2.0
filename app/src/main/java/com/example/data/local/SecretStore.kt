package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretStore {
    fun getProviderSecrets(provider: String): Map<String, String>
    fun getProviderSecretFieldNames(provider: String): List<String>
    fun putProviderSecrets(provider: String, updates: Map<String, String>)
    fun removeProviderSecretKeys(provider: String, keys: Collection<String>)
    fun deleteProviderSecrets(provider: String)

    fun getOrCreateLanBearerToken(): String
    fun rotateLanBearerToken(): String
    fun getOrCreateSessionSigningKey(): ByteArray
}

/**
 * Hardware/Keystore-backed AES-256-GCM encrypted store for provider API keys,
 * Angel One PIN/TOTP secrets, Google Apps Script tokens, and LAN bearer tokens.
 */
class AndroidKeystoreSecretStore(context: Context) : SecretStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(1 + iv.size + ciphertext.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(ciphertext, 0, combined, 1 + iv.size, ciphertext.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? {
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            val ivLen = combined[0].toInt() and 0xFF
            val iv = combined.copyOfRange(1, 1 + ivLen)
            val ciphertext = combined.copyOfRange(1 + ivLen, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun getProviderSecrets(provider: String): Map<String, String> {
        val enc = prefs.getString("provider_$provider", null) ?: return emptyMap()
        val raw = decrypt(enc) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val out = linkedMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optString(k, "")
                if (v.isNotEmpty()) out[k] = v
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    override fun getProviderSecretFieldNames(provider: String): List<String> =
        getProviderSecrets(provider).keys.toList()

    @Synchronized
    override fun putProviderSecrets(provider: String, updates: Map<String, String>) {
        val merged = getProviderSecrets(provider).toMutableMap()
        for ((k, v) in updates) {
            if (v.isNotEmpty()) merged[k] = v
        }
        val obj = JSONObject()
        for ((k, v) in merged) obj.put(k, v)
        prefs.edit().putString("provider_$provider", encrypt(obj.toString())).apply()
    }

    @Synchronized
    override fun removeProviderSecretKeys(provider: String, keys: Collection<String>) {
        if (keys.isEmpty()) return
        val merged = getProviderSecrets(provider).toMutableMap()
        var changed = false
        for (k in keys) {
            if (merged.remove(k) != null) changed = true
        }
        if (!changed) return
        if (merged.isEmpty()) {
            prefs.edit().remove("provider_$provider").apply()
        } else {
            val obj = JSONObject()
            for ((k, v) in merged) obj.put(k, v)
            prefs.edit().putString("provider_$provider", encrypt(obj.toString())).apply()
        }
    }

    @Synchronized
    override fun deleteProviderSecrets(provider: String) {
        prefs.edit().remove("provider_$provider").apply()
    }

    @Synchronized
    override fun getOrCreateLanBearerToken(): String {
        val enc = prefs.getString(KEY_LAN_TOKEN, null)
        if (enc != null) {
            val dec = decrypt(enc)
            if (!dec.isNullOrBlank()) return dec
        }
        return rotateLanBearerToken()
    }

    @Synchronized
    override fun rotateLanBearerToken(): String {
        val bytes = ByteArray(24)
        secureRandom.nextBytes(bytes)
        val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        prefs.edit().putString(KEY_LAN_TOKEN, encrypt(token)).apply()
        return token
    }

    @Synchronized
    override fun getOrCreateSessionSigningKey(): ByteArray {
        val enc = prefs.getString(KEY_SESSION_SIGNING, null)
        if (enc != null) {
            val dec = decrypt(enc)
            if (!dec.isNullOrBlank()) return Base64.decode(dec, Base64.NO_WRAP)
        }
        val keyBytes = ByteArray(32)
        secureRandom.nextBytes(keyBytes)
        prefs.edit().putString(KEY_SESSION_SIGNING, encrypt(Base64.encodeToString(keyBytes, Base64.NO_WRAP))).apply()
        return keyBytes
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "signal_analyzer_master_aes_gcm"
        private const val PREFS_NAME = "signal_analyzer_encrypted_secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_LAN_TOKEN = "lan_bearer_token"
        private const val KEY_SESSION_SIGNING = "lan_session_signing_key"
    }
}

class InMemorySecretStore : SecretStore {
    private val map = mutableMapOf<String, MutableMap<String, String>>()
    private var lanToken = "test-lan-token-1234567890abcdef"
    private val signingKey = ByteArray(32) { (it + 1).toByte() }

    override fun getProviderSecrets(provider: String): Map<String, String> = map[provider]?.toMap().orEmpty()

    override fun getProviderSecretFieldNames(provider: String): List<String> = getProviderSecrets(provider).keys.toList()

    override fun putProviderSecrets(provider: String, updates: Map<String, String>) {
        val m = map.getOrPut(provider) { linkedMapOf() }
        for ((k, v) in updates) {
            if (v.isNotEmpty()) m[k] = v
        }
    }

    override fun removeProviderSecretKeys(provider: String, keys: Collection<String>) {
        val m = map[provider] ?: return
        for (k in keys) m.remove(k)
        if (m.isEmpty()) map.remove(provider)
    }

    override fun deleteProviderSecrets(provider: String) {
        map.remove(provider)
    }

    override fun getOrCreateLanBearerToken(): String = lanToken

    override fun rotateLanBearerToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        lanToken = bytes.joinToString("") { "%02x".format(it) }
        return lanToken
    }

    override fun getOrCreateSessionSigningKey(): ByteArray = signingKey
}
