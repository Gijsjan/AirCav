package dev.ruimdetijd.aircav

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Settings(
    val syncUrl: String,
    val clientId: String,
    val encryptionSecret: String,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ALIAS = "aircav_settings_key"
    }

    private fun settingsKey(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(KEY_ALIAS)) {
            val kg = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore",
            )
            kg.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            kg.generateKey()
        }
        (ks.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    } catch (e: Exception) {
        null
    }

    private fun encrypt(value: String): String? {
        val key = settingsKey() ?: return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.ENCRYPT_MODE, key) }
            val iv = c.iv
            val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun decrypt(stored: String): String? {
        val key = settingsKey() ?: return null
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            if (raw.size <= 12) return null
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun getSecret(name: String, default: String): String {
        val v = prefs.getString(name, null) ?: return default
        if (v.startsWith("enc:")) {
            return decrypt(v.removePrefix("enc:")) ?: default
        }
        // legacy plaintext value: migrate to encrypted storage on first read
        val enc = encrypt(v)
        if (enc != null) {
            prefs.edit().putString(name, "enc:$enc").apply()
        }
        return v
    }

    private fun putSecret(name: String, value: String) {
        val enc = encrypt(value)
        prefs.edit().putString(
            name,
            if (enc != null) "enc:$enc" else value,
        ).apply()
    }

    fun load(): Settings {
        if (!prefs.contains("client_id")) {
            val clientId = AirCavLib.newUuid()
            val secret = AirCavLib.newUuid().replace("-", "")
            prefs.edit().putString("client_id", clientId).putString("sync_url", "").apply()
            putSecret("encryption_secret", secret)
        }
        // drop legacy values from pre-offline-voice versions
        if (prefs.contains("mistral_api_key")) {
            prefs.edit().remove("mistral_api_key").apply()
        }
        return Settings(
            syncUrl = prefs.getString("sync_url", "") ?: "",
            clientId = prefs.getString("client_id", "") ?: "",
            encryptionSecret = getSecret("encryption_secret", ""),
        )
    }

    fun save(settings: Settings) {
        prefs.edit()
            .putString("sync_url", settings.syncUrl.trim())
            .putString("client_id", settings.clientId.trim())
            .apply()
        putSecret("encryption_secret", settings.encryptionSecret)
    }
}

data class TaskItem(
    val uuid: String,
    val description: String,
    val tags: List<String>,
)

data class TaskListResult(
    val tasks: List<TaskItem>,
    val contexts: List<String>,
)

object TaskParser {
    fun parse(json: String?): TaskListResult {
        if (json.isNullOrEmpty()) return TaskListResult(emptyList(), emptyList())
        val obj = JSONObject(json)
        val arr = obj.getJSONArray("tasks")
        val tasks = (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i)
            val tagsArr = t.getJSONArray("tags")
            TaskItem(
                uuid = t.getString("uuid"),
                description = t.getString("description"),
                tags = (0 until tagsArr.length()).map { j -> tagsArr.getString(j) },
            )
        }
        val contextsArr = obj.getJSONArray("contexts")
        val contexts = (0 until contextsArr.length()).map { contextsArr.getString(it) }
        return TaskListResult(tasks, contexts)
    }
}
