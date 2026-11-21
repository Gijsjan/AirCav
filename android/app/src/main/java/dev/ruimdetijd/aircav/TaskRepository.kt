package dev.ruimdetijd.aircav

import android.content.Context
import org.json.JSONArray

class TaskRepository(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    private var handle: Long = 0L
    private var currentSettings: Settings? = null

    @Synchronized
    fun ensureOpen() {
        val settings = settingsStore.load()
        if (handle != 0L && settings == currentSettings) return
        close()
        currentSettings = settings
        handle = AirCavLib.open(
            AirCavLib.dataDir(context).absolutePath,
            settings.syncUrl,
            settings.clientId,
            settings.encryptionSecret,
        )
    }

    @Synchronized
    fun close() {
        if (handle != 0L) {
            AirCavLib.close(handle)
            handle = 0L
        }
    }

    @Synchronized
    fun list(): TaskListResult = TaskParser.parse(AirCavLib.listTasks(handle))

    @Synchronized
    fun add(description: String, tags: List<String>, project: String) {
        AirCavLib.addTask(handle, description, JSONArray(tags).toString(), project)
    }

    @Synchronized
    fun complete(uuid: String) {
        AirCavLib.completeTask(handle, uuid)
    }

    @Synchronized
    fun remove(uuid: String) {
        AirCavLib.removeTask(handle, uuid)
    }

    @Synchronized
    fun sync() {
        AirCavLib.sync(handle)
    }

    @Synchronized
    fun undo(): Boolean = AirCavLib.undo(handle)
}
