package dev.ruimdetijd.aircav

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object MistralApi {
    private const val TAG = "AirCavMistral"

    private const val BASE = "https://api.mistral.ai/v1"
    const val STT_MODEL = "voxtral-mini-latest"

    fun hasKey(apiKey: String): Boolean = apiKey.isNotBlank()

    fun transcribe(apiKey: String, wav: File, model: String = STT_MODEL): String {
        val boundary = "----tc${UUID.randomUUID()}"
        val conn = (URL("$BASE/audio/transcriptions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connectTimeout = 30_000
            readTimeout = 120_000
        }
        DataOutputStream(conn.outputStream).use { out ->
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
            out.writeBytes("$model\r\n")
            out.writeBytes("--$boundary\r\n")
            out.writeBytes(
                "Content-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\n",
            )
            out.writeBytes("Content-Type: audio/wav\r\n\r\n")
            wav.inputStream().use { it.copyTo(out) }
            out.writeBytes("\r\n--$boundary--\r\n")
        }
        val status = conn.responseCode
        if (status !in 200..299) {
            val err = runCatching {
                conn.errorStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty().take(500)
            Log.e(TAG, "transcription failed ($status): $err")
            throw IllegalStateException("transcription failed ($status): ${err.ifBlank { "no error body" }}")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return JSONObject(body).getString("text").trim()
    }

}
