package dev.ruimdetijd.aircav

import android.content.Context
import org.json.JSONObject
import java.io.File

/** JSONL log of every voice attempt: transcript, parsed task, or error. */
object VoiceLog {
    private const val FILE = "voice-log.jsonl"

    fun append(context: Context, build: JSONObject.() -> Unit) {
        runCatching {
            val entry = JSONObject().apply {
                put("ts", System.currentTimeMillis() / 1000)
                build()
            }
            synchronized(this) {
                File(context.filesDir, FILE).appendText(entry.toString() + "\n")
            }
        }
    }

    fun file(context: Context): File = File(context.filesDir, FILE)
}
