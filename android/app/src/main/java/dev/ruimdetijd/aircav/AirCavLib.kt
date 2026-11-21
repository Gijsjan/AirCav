package dev.ruimdetijd.aircav

import android.content.Context
import java.io.File
import java.util.UUID

object AirCavLib {
    init {
        System.loadLibrary("aircav")
    }

    @JvmStatic
    external fun open(
        dataDir: String,
        syncUrl: String,
        clientId: String,
        encryptionSecret: String,
    ): Long

    @JvmStatic
    external fun listTasks(handle: Long): String?

    @JvmStatic
    external fun addTask(
        handle: Long,
        description: String,
        tagsJson: String,
    )

    @JvmStatic
    external fun completeTask(handle: Long, uuid: String): Boolean

    @JvmStatic
    external fun removeTask(handle: Long, uuid: String)

    @JvmStatic
    external fun sync(handle: Long)

    @JvmStatic
    external fun close(handle: Long)

    fun dataDir(context: Context): File =
        File(context.filesDir, "taskdb").apply { mkdirs() }

    fun newUuid(): String = UUID.randomUUID().toString()
}
