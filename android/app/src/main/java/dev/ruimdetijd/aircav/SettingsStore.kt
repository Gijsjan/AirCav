package dev.ruimdetijd.aircav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Settings(
    val syncUrl: String,
    val clientId: String,
    val encryptionSecret: String,
    val mistralApiKey: String,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Settings {
        if (!prefs.contains("client_id")) {
            prefs.edit()
                .putString("client_id", AirCavLib.newUuid())
                .putString("encryption_secret", AirCavLib.newUuid().replace("-", ""))
                .putString("sync_url", "")
                .apply()
        }
        return Settings(
            syncUrl = prefs.getString("sync_url", "") ?: "",
            clientId = prefs.getString("client_id", "") ?: "",
            encryptionSecret = prefs.getString("encryption_secret", "") ?: "",
            mistralApiKey = prefs.getString("mistral_api_key", "") ?: "",
        )
    }

    fun save(settings: Settings) {
        prefs.edit()
            .putString("sync_url", settings.syncUrl.trim())
            .putString("client_id", settings.clientId.trim())
            .putString("encryption_secret", settings.encryptionSecret)
            .putString("mistral_api_key", settings.mistralApiKey.trim())
            .apply()
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
