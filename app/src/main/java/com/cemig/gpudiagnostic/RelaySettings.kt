package com.cemig.gpudiagnostic

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-created session capabilities; no Hugging Face account credential is needed. */
class RelaySettings(context: Context) {
    // The replacement service has its own identity. Preserve legacy credentials as a backup.
    private val preferences = context.getSharedPreferences("relay-uploads-v2", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "relay-sessions-v2").apply { mkdirs() }
    val installationId: String = preferences.getString("installationId", null) ?: UUID.randomUUID().toString().also {
        check(preferences.edit().putString("installationId", it).commit())
    }
    var currentScope: String
        get() = preferences.getString("currentScope", "")!!
        set(value) { preferences.edit().putString("currentScope", value).commit() }

    @Synchronized fun sessionFor(runId: String, batch: Int, synthetic: Boolean): JSONObject {
        val identity = "$runId/$batch/${preferences.getLong("generation", 0)}"
        val scope = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        val session = readJson(file(scope)) ?: JSONObject().put("scope", scope).put("batch", batch)
            .put("installationId", installationId).put("purpose", if (synthetic) "synthetic-test" else "benchmark")
            .put("credential", encrypt(Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
            .also { writeJson(file(scope), it) }
        currentScope = scope
        return session
    }

    @Synchronized fun session(scope: String): JSONObject = checkNotNull(readJson(file(scope))) { "Saved upload session unavailable." }
    @Synchronized fun save(session: JSONObject) = writeJson(file(session.getString("scope")), session)
    @Synchronized fun newSession() {
        check(preferences.edit().putLong("generation", preferences.getLong("generation", 0) + 1).remove("currentScope").commit())
    }
    fun secret(session: JSONObject): String {
        val parts = session.getString("credential").split(':')
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }
    }
    private fun file(scope: String): File {
        require(scope.matches(Regex("[0-9a-f]{64}")))
        return File(directory, "$scope.json")
    }
    private fun encrypt(secret: String): String = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key())
        Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(doFinal(secret.toByteArray()), Base64.NO_WRAP)
    }
    private fun key(): SecretKey {
        val alias = "benchmark-relay-session"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
