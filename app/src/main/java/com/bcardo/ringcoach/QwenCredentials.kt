package com.bcardo.ringcoach

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class QwenCredentials(
    context: Context,
    private val alias: String = "ringcoach.qianwen.api-key",
    preferenceName: String = "qianwen-credentials",
) {
    private val preferences = context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    @Synchronized
    fun save(value: String) {
        val clean = value.trim()
        require(clean.startsWith("sk-") && clean.none { it.isWhitespace() })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        val json = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        check(preferences.edit().putString("encrypted_key", json.toString()).commit())
    }

    @Synchronized
    fun read(): String? {
        val stored = preferences.getString("encrypted_key", null) ?: return null
        val json = JSONObject(stored)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = Base64.decode(json.getString("iv"), Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
        val bytes = cipher.doFinal(Base64.decode(json.getString("ciphertext"), Base64.NO_WRAP))
        return bytes.toString(Charsets.UTF_8)
    }

    @Synchronized
    fun clear() {
        check(preferences.edit().remove("encrypted_key").commit())
    }
}
