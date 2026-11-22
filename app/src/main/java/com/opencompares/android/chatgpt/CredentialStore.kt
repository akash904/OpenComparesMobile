package com.opencompares.android.chatgpt

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * This install's ChatGPT registration and session.
 *
 * [hostId] and [clientId] outlive sign-out: OpenAI expects the same host to reuse
 * them. Tokens are cleared on sign-out.
 */
data class StoredCredentials(
    val hostId: String,
    val clientId: String? = null,
    val subject: String? = null,
    val email: String? = null,
    val name: String? = null,
    val idToken: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val accessExpiresAtMillis: Long = 0,
    val scopes: Set<String> = emptySet(),
    val welcomeShown: Boolean = false,
    val preferredModel: String? = null
) {
    val isSignedIn: Boolean get() = accessToken != null && refreshToken != null && clientId != null
    val hasPlanUsage: Boolean get() = SiwcConfig.PLAN_USAGE_SCOPE in scopes

    fun signedOut() = copy(
        idToken = null, accessToken = null, refreshToken = null,
        accessExpiresAtMillis = 0, scopes = emptySet()
    )

    fun toJson(): JSONObject = JSONObject()
        .put("hostId", hostId)
        .putOpt("clientId", clientId)
        .putOpt("subject", subject)
        .putOpt("email", email)
        .putOpt("name", name)
        .putOpt("idToken", idToken)
        .putOpt("accessToken", accessToken)
        .putOpt("refreshToken", refreshToken)
        .put("accessExpiresAtMillis", accessExpiresAtMillis)
        .put("scopes", JSONArray(scopes.toList()))
        .put("welcomeShown", welcomeShown)
        .putOpt("preferredModel", preferredModel)

    companion object {
        fun fromJson(json: JSONObject): StoredCredentials {
            fun opt(key: String) = json.optString(key).ifEmpty { null }
            val scopes = json.optJSONArray("scopes")
            return StoredCredentials(
                hostId = json.getString("hostId"),
                clientId = opt("clientId"),
                subject = opt("subject"),
                email = opt("email"),
                name = opt("name"),
                idToken = opt("idToken"),
                accessToken = opt("accessToken"),
                refreshToken = opt("refreshToken"),
                accessExpiresAtMillis = json.optLong("accessExpiresAtMillis", 0),
                scopes = if (scopes == null) emptySet() else (0 until scopes.length()).map { scopes.getString(it) }.toSet(),
                welcomeShown = json.optBoolean("welcomeShown", false),
                preferredModel = opt("preferredModel")
            )
        }
    }
}

/**
 * Keeps [StoredCredentials] encrypted with an AES-GCM key held in the Android Keystore.
 * The file lives in the no-backup directory, so tokens never leave the device in a backup,
 * matching the requirement that tokens stay local and under the user's control.
 */
class CredentialStore(context: Context) {

    private val file = File(context.noBackupFilesDir, "chatgpt/credentials.bin")

    @Synchronized
    fun load(): StoredCredentials? {
        if (!file.exists()) return null
        return try {
            val blob = file.readBytes()
            val ivLength = blob[0].toInt()
            val iv = blob.copyOfRange(1, 1 + ivLength)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(blob.copyOfRange(1 + ivLength, blob.size))
            StoredCredentials.fromJson(JSONObject(String(plain, Charsets.UTF_8)))
        } catch (_: Exception) {
            // The key can disappear (e.g. after a device restore); start over rather than crash.
            file.delete()
            null
        }
    }

    @Synchronized
    fun save(credentials: StoredCredentials) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(credentials.toJson().toString().toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        file.parentFile?.mkdirs()
        // Write to a temp file and rename, so a crash never leaves a half-written record.
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { out ->
            out.write(iv.size)
            out.write(iv)
            out.write(encrypted)
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "chatgpt_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
