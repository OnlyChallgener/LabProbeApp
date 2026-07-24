package com.labprobe.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private class SecureStringStore(context: Context, prefsName: String, private val alias: String) {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun get(): String {
        val cipherText = prefs.getString("ciphertext", null) ?: return ""
        val iv = prefs.getString("iv", null) ?: return ""
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun set(value: String) {
        val clean = value.trim()
        if (clean.isBlank()) {
            prefs.edit().clear().apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }
}
/** Stores APP_TOKEN with a non-exportable Android Keystore key. */
class SecureTokenStore(context: Context) {
    private val delegate = SecureStringStore(context, "labprobe_secure", "labprobe_hub_client_token_v1")
    fun get(): String = delegate.get()
    fun set(value: String) = delegate.set(value)
}

/** Removes the deprecated APP-side HOOK_TOKEN copy left by build132. */
fun clearDeprecatedHookToken(context: Context) {
    context.applicationContext
        .getSharedPreferences("labprobe_secure_hook", Context.MODE_PRIVATE)
        .edit()
        .clear()
        .apply()
    runCatching {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias("labprobe_hook_token_v1")) store.deleteEntry("labprobe_hook_token_v1")
    }
}
