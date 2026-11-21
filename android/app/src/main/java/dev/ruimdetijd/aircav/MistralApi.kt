package dev.ruimdetijd.aircav

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object MistralApi {
    private const val BASE = "https://api.mistral.ai/v1"
    const val STT_MODEL = "voxtral-mini-latest"
    const val PARSE_MODEL = "mistral-small-latest"

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
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        val status = conn.responseCode
        if (status !in 200..299) throw IllegalStateException("transcription failed ($status)")
        return JSONObject(body).getString("text").trim()
    }

    private const val PROMPT = """You convert a spoken sentence into a todo task.
Return only a JSON object: {"description": string, "tags": [string]}.
- description: a short, clear imperative task title in the language that was spoken.
- tags: 0-3 lowercase single-word contexts, chosen from the words in the input when the speaker
  mentions a place, project, or category (home, work, errands, groceries...). Strip filler words.
Do not add any other fields."""

    fun extractTask(apiKey: String, transcript: String, model: String = PARSE_MODEL): ParsedCommand? {
        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", 0)
            put(
                "response_format",
                JSONObject().put("type", "json_object"),
            )
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", "$PROMPT\n\nSpoken input:\n$transcript")
                    },
                ),
            )
        }
        val conn = (URL("$BASE/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        DataOutputStream(conn.outputStream).use { it.write(payload.toString().toByteArray()) }
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("task parsing failed (${conn.responseCode})")
        }
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        val content = JSONObject(body)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
        val obj = JSONObject(content.trim())
        val description = obj.optString("description").trim()
        if (description.isEmpty()) return null
        val tagsArr = obj.optJSONArray("tags") ?: JSONArray()
        val tags = (0 until tagsArr.length()).map { tagsArr.getString(it).trim().lowercase() }
            .filter { it.isNotBlank() && it.contains(Regex("^[a-z0-9_.-]+$")) }
        return ParsedCommand(description, tags)
    }
}
