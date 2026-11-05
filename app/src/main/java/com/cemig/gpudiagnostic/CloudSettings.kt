package com.cemig.gpudiagnostic

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The repository is ordinary configuration; the credential is encrypted with an Android Keystore key. */
class CloudSettings(context: Context) {
    private val preferences = context.getSharedPreferences("hugging-face-uploads", Context.MODE_PRIVATE)
    val repository get() = preferences.getString("repository", "")!!
    val enabled get() = preferences.getBoolean("enabled", false)
    val installationId: String = preferences.getString("installationId", null) ?: UUID.randomUUID().toString().also {
        preferences.edit().putString("installationId", it).commit()
    }
    private fun key(): SecretKey {
        val alias = "benchmark-hf-upload"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun token(): String? = runCatching {
        val saved = preferences.getString("credential", null) ?: return null
        val parts = saved.split(':')
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }
    }.getOrNull()
    fun save(repository: String, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(token.toByteArray()), Base64.NO_WRAP)
        check(preferences.edit().putString("repository", repository).putString("credential", encrypted).putBoolean("enabled", true).commit())
    }
    fun disable() { preferences.edit().putBoolean("enabled", false).remove("credential").commit() }
}
