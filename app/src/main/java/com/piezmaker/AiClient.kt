package com.piezmaker

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容的 /chat/completions 客户端（只用系统 HttpURLConnection，零依赖）。
 *
 * 流式（SSE）读取：每收到一段增量就回调 onDelta，进度条/取消都靠它。
 * 有些中转站不支持流式，会直接返回一整段 JSON —— 这种情况也能兜住。
 */
object AiClient {

    class Cancel {
        @Volatile var cancelled = false
    }

    class ApiError(message: String) : Exception(message)

    /**
     * 发一次对话请求，返回完整回答文本。
     * @param messages 依次是 (role, content) —— role = system / user / assistant
     */
    fun chat(
        ep: AiEndpoint,
        messages: List<Pair<String, String>>,
        cancel: Cancel,
        maxTokens: Int = 4096,
        temperature: Double = 0.7,
        onDelta: (String) -> Unit = {}
    ): String {
        if (!ep.usable) throw ApiError("接口未配置完整（地址 / API Key / 模型）")
        val body = JSONObject().apply {
            put("model", ep.model)
            put("stream", true)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            val arr = JSONArray()
            for ((role, content) in messages) {
                arr.put(JSONObject().put("role", role).put("content", content))
            }
            put("messages", arr)
        }.toString()

        val conn = (URL(ep.apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 180000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Authorization", "Bearer " + ep.apiKey)
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                } catch (_: Exception) { null }
                throw ApiError("HTTP $code " + (err?.take(300)?.trim() ?: ""))
            }
            val ctype = conn.contentType ?: ""
            val sb = StringBuilder()
            if (ctype.contains("event-stream", true)) {
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { br ->
                    var line = br.readLine()
                    while (line != null) {
                        if (cancel.cancelled) break
                        val t = line.trim()
                        if (t.startsWith("data:")) {
                            val payload = t.substring(5).trim()
                            if (payload.isNotEmpty() && payload != "[DONE]") {
                                val piece = readDelta(payload)
                                if (piece.isNotEmpty()) {
                                    sb.append(piece)
                                    onDelta(sb.toString())
                                }
                            }
                        }
                        line = br.readLine()
                    }
                }
            } else {
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val piece = readDelta(text)
                if (piece.isNotEmpty()) {
                    sb.append(piece)
                    onDelta(sb.toString())
                }
            }
            if (sb.isEmpty() && !cancel.cancelled) throw ApiError("接口没有返回内容")
            return sb.toString()
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    /** 从一段 JSON（流式增量或完整响应）里取正文 */
    private fun readDelta(json: String): String {
        val o = try { JSONObject(json) } catch (_: Exception) { return "" }
        // 接口报错
        o.optJSONObject("error")?.let { e ->
            throw ApiError(e.optString("message", "接口返回错误"))
        }
        val choices = o.optJSONArray("choices") ?: return ""
        val c0 = choices.optJSONObject(0) ?: return ""
        c0.optJSONObject("delta")?.optString("content")?.let { if (it != "null") return it }
        c0.optJSONObject("message")?.optString("content")?.let { if (it != "null") return it }
        c0.optString("text")?.let { if (it != "null") return it }
        return ""
    }
}
