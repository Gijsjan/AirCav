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
                val f = File(context.filesDir, FILE)
                f.appendText(entry.toString() + "\n")
                // cap: keep the last 500 entries (plain-text transcripts,
                // so it must not grow unbounded)
                val lines = f.readLines()
                if (lines.size > 500) {
                    f.writeText(lines.takeLast(500).joinToString("\n", postfix = "\n"))
                }
            }
        }
    }

    fun file(context: Context): File = File(context.filesDir, FILE)
}
