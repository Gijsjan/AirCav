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

    /**
     * Transcribe with context biasing: `contextBias` are domain words
     * (project names, e.g. "AFS") the model should prefer over plausible
     * spellings. If the endpoint rejects the parameter, retry without it.
     */
    fun transcribe(apiKey: String, wav: File, contextBias: List<String> = emptyList()): String {
        val bias = contextBias.filter { it.isNotBlank() }.distinct()
        return try {
            transcribeOnce(apiKey, wav, bias)
        } catch (e: HttpRejectException) {
            if (bias.isEmpty()) throw e
            Log.w(TAG, "context bias rejected (${e.status}); retrying without it")
            transcribeOnce(apiKey, wav, emptyList())
        }
    }

    private class HttpRejectException(val status: Int, message: String) : IllegalStateException(message)

    private fun transcribeOnce(apiKey: String, wav: File, contextBias: List<String>): String {
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
            out.writeBytes("$STT_MODEL\r\n")
            contextBias.forEach { term ->
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"context_bias\"\r\n\r\n")
                out.writeBytes("$term\r\n")
            }
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
            if (biasRejectable(status)) {
                throw HttpRejectException(status, "transcription failed ($status): ${err.ifBlank { "no error body" }}")
            }
            throw IllegalStateException("transcription failed ($status): ${err.ifBlank { "no error body" }}")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return JSONObject(body).getString("text").trim()
    }

    // parameter mistakes (bad request / unprocessable) -> retry without bias
    private fun biasRejectable(status: Int): Boolean = status == 400 || status == 422

}
