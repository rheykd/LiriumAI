package com.localmodels.app.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class RemoteTurn(
    val role: String,
    val text: String,
    val images: List<Pair<File, String>> = emptyList()
)

/** OpenAI-compatible client (works with OpenAI, OpenRouter, Ollama, LM Studio, vLLM...). */
object RemoteApi {

    suspend fun stream(
        baseUrl: String,
        apiKey: String,
        model: String,
        turns: List<RemoteTurn>,
        onToken: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val endpoint = endpoint(baseUrl)
        val body = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("temperature", 0.7)
            val arr = JSONArray()
            for (t in turns) {
                val msg = JSONObject().put("role", t.role)
                if (t.images.isEmpty()) {
                    msg.put("content", t.text)
                } else {
                    val parts = JSONArray()
                    if (t.text.isNotBlank()) {
                        parts.put(JSONObject().put("type", "text").put("text", t.text))
                    }
                    for ((file, mime) in t.images) {
                        val b64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                        parts.put(
                            JSONObject().put("type", "image_url").put(
                                "image_url",
                                JSONObject().put("url", "data:$mime;base64,$b64")
                            )
                        )
                    }
                    msg.put("content", parts)
                }
                arr.put(msg)
            }
            put("messages", arr)
        }

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 600_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream, application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val detail = runCatching {
                    JSONObject(err).optJSONObject("error")?.optString("message")
                }.getOrNull()?.takeIf { it.isNotBlank() }
                throw IllegalStateException(detail ?: "HTTP $code" + if (err.isNotBlank()) ": ${err.take(180)}" else "")
            }

            val result = StringBuilder()
            val contentType = conn.contentType ?: ""
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                if (contentType.contains("application/json") && !contentType.contains("event-stream")) {
                    val text = reader.readText()
                    val content = runCatching {
                        JSONObject(text).getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").opt("content") as? String ?: ""
                    }.getOrDefault("")
                    if (content.isNotEmpty()) {
                        result.append(content)
                        onToken(content)
                    }
                    return@withContext result.toString()
                }
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue
                    val obj = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    if (obj.has("error")) {
                        val msg = obj.optJSONObject("error")?.optString("message")
                            ?: obj.optString("error")
                        throw IllegalStateException(msg)
                    }
                    val choice = obj.optJSONArray("choices")?.optJSONObject(0) ?: continue
                    val piece = (choice.optJSONObject("delta")?.opt("content") as? String).orEmpty()
                        .ifEmpty {
                            (choice.optJSONObject("message")?.opt("content") as? String).orEmpty()
                        }
                    if (piece.isNotEmpty()) {
                        result.append(piece)
                        onToken(piece)
                    }
                }
            }
            result.toString()
        } finally {
            conn.disconnect()
        }
    }

    private fun endpoint(base: String): String {
        val b = base.trim().trimEnd('/')
        if (b.isEmpty()) throw IllegalStateException("не задан адрес API")
        if (b.endsWith("/chat/completions")) return b
        if (b.endsWith("/v1")) return "$b/chat/completions"
        return "$b/v1/chat/completions"
    }
}
