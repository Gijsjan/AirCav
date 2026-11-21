package dev.ruimdetijd.aircav

import android.content.Context
import android.util.Log
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
    val mistralApiKey: String,
    val contextsText: String,
)

data class ContextDef(val name: String, val project: String)

fun parseContexts(text: String): List<ContextDef> =
    text.lines().mapNotNull { line ->
        val parts = line.split('=', limit = 2).map { it.trim() }
        if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
            ContextDef(parts[0], parts[1])
        } else {
            null
        }
    }

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ALIAS = "aircav_settings_key"

        @Volatile
        var keystoreError: String? = null
            private set
    }

    private var cache: Settings? = null

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
        if (enc == null) {
            keystoreError = "Android Keystore unavailable: secret NOT stored securely"
            Log.e("AirCavSettings", keystoreError)
            prefs.edit().putString(name, "").apply() // never silent plaintext
            return
        }
        keystoreError = null
        prefs.edit().putString(name, "enc:$enc").apply()
    }

    // a client id must be a real, non-nil UUID (the nil UUID means "no client"
    // and is rejected by the sync server with a 404)
    private fun validUuid(value: String): Boolean = try {
        java.util.UUID.fromString(value.trim()) != java.util.UUID(0, 0)
    } catch (e: IllegalArgumentException) {
        false
    }

    @Synchronized
    fun load(): Settings {
        cache?.let { return it }
        val loaded = loadFresh()
        cache = loaded
        return loaded
    }

    private fun loadFresh(): Settings {
        if (!prefs.contains("client_id")) {
            val secret = AirCavLib.newUuid().replace("-", "")
            prefs.edit().putString("client_id", AirCavLib.newUuid()).putString("sync_url", "").apply()
            putSecret("encryption_secret", secret)
            putSecret("mistral_api_key", "")
        }
        var clientId = prefs.getString("client_id", "") ?: ""
        if (!validUuid(clientId)) {
            clientId = AirCavLib.newUuid()
            prefs.edit().putString("client_id", clientId).apply()
        }
        return Settings(
            syncUrl = prefs.getString("sync_url", "") ?: "",
            clientId = clientId,
            encryptionSecret = getSecret("encryption_secret", ""),
            mistralApiKey = getSecret("mistral_api_key", ""),
            contextsText = prefs.getString("contexts", "") ?: "",
        )
    }

    fun getSelectedContext(): String? =
        prefs.getString("selected_context", null)?.takeIf { it.isNotEmpty() }

    fun setSelectedContext(name: String?) {
        prefs.edit().putString("selected_context", name ?: "").apply()
    }

    @Synchronized
    fun save(settings: Settings) {
        saveFresh(settings)
        cache = settings
    }

    private fun saveFresh(settings: Settings) {
        val clientId =
            if (validUuid(settings.clientId)) settings.clientId.trim() else AirCavLib.newUuid()
        prefs.edit()
            .putString("sync_url", settings.syncUrl.trim())
            .putString("client_id", clientId)
            .apply()
        putSecret("encryption_secret", settings.encryptionSecret)
        putSecret("mistral_api_key", settings.mistralApiKey.trim())
        if (clientId != settings.clientId.trim()) {
            prefs.edit().putString("invalid_client_id_replaced", "1").apply()
        }
    }
}

data class TaskItem(
    val id: Int?,
    val uuid: String,
    val description: String,
    val tags: List<String>,
    val project: String?,
    val entry: String?,
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
                id = if (t.isNull("id")) null else t.optInt("id", 0).takeIf { it > 0 },
                uuid = t.getString("uuid"),
                description = t.getString("description"),
                tags = (0 until tagsArr.length()).map { j -> tagsArr.getString(j) },
                project = if (t.isNull("project")) null else t.optString("project", null),
                entry = if (t.isNull("entry")) null else t.optString("entry", null),
            )
        }
        val contextsArr = obj.getJSONArray("contexts")
        val contexts = (0 until contextsArr.length()).map { contextsArr.getString(it) }
        return TaskListResult(tasks, contexts)
    }
}
