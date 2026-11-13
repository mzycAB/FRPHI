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
 *
 * ★ 关于 `HTTP 404 ... "Not Found (request_id: …)"`：
 *   这条错误体是**上游网关**（DeepSeek 等）自己的格式（`type: not_found_error`），
 *   含义只有一个 —— **请求的路径不存在**（不是 key 错、不是额度错，那两种是 401/402/429）。
 *   也就是说用户填的「接口地址」少了或多了路径：
 *     · 只填到 https://api.deepseek.com           （没有 /chat/completions）
 *     · 只填到 https://api.deepseek.com/v1        （没有 /chat/completions）
 *     · 末尾多一个 /  → …/chat/completions/       （上游按严格路由 ⇒ 404）
 *     · 填了 /v1/chat/completion 之类的手误
 *   所以这里不再"用户填什么就打什么"：[normalizeUrl] 先把地址补成标准形式，
 *   再对不同写法做最多 [MAX_TRY] 次兜底重试（404/405 才重试，其它错误立即报）。
 */
object AiClient {

    class Cancel {
        @Volatile var cancelled = false
    }

    /** [httpCode] = 0 表示不是 HTTP 层错误（网络异常 / 接口没返回内容 / 返回体里带 error） */
    class ApiError(message: String, val httpCode: Int = 0) : Exception(message)

    /** 最多试几个地址写法（正常情况第 1 个就成功） */
    private const val MAX_TRY = 3

    /**
     * 把用户填的地址补成 `…/chat/completions`。
     *
     * - 去首尾空白（含全角空格/换行：从网页复制粘贴最常见）
     * - 去掉末尾多余的 `/`
     * - 已经是 `…/chat/completions` ⇒ 原样返回
     * - 只到 `…/v1`（或 `/v1beta`、`/openai`）⇒ 补 `/chat/completions`
     * - 只有域名（没有路径）⇒ 补 `/v1/chat/completions`（DeepSeek/多数中转站两种都吃，
     *   `/v1` 形式最通用）
     */
    fun normalizeUrl(raw: String): String {
        var u = raw.trim()
        u = u.replace("\u3000", "").replace("\n", "").replace("\r", "").replace(" ", "")
        while (u.endsWith("/")) u = u.dropLast(1)
        if (u.isEmpty()) return u
        // 只写了域名（api.deepseek.com/v1）没写协议：补 https，别让人卡在"未配置"
        if (!u.contains("://") && u.contains('.')) u = "https://$u"
        val lower = u.lowercase()
        if (lower.endsWith("/chat/completions")) return u
        val tail = lower.substringAfterLast('/')
        return if (tail == "v1" || tail == "v1beta" || tail == "openai" || tail == "compatible-mode") {
            "$u/chat/completions"
        } else if (lower.contains("://") && lower.substringAfter("://").none { it == '/' }) {
            "$u/v1/chat/completions"
        } else {
            // 其它情况（自定义路径 / 手误）：先按"补一段 /chat/completions"猜，
            // 猜错了还有 [urlCandidates] 的第二、三个候选兜底
            "$u/chat/completions"
        }
    }

    /**
     * 候选地址（去重、保序、最多 [MAX_TRY] 个）：
     *   1. 规范化后的地址（绝大多数情况就是它）
     *   2. 用户原样填的地址（已经带 /chat/completions 的自定义网关）
     *   3. 域名 + /v1/chat/completions（只填了域名/自定义前缀时最容易漏的那个）
     */
    fun urlCandidates(raw: String): List<String> {
        var t = raw.trim().replace("\u3000", "").replace(" ", "")
        while (t.endsWith("/")) t = t.dropLast(1)
        val out = ArrayList<String>(MAX_TRY)
        fun add(u: String) {
            if (u.startsWith("http") && out.size < MAX_TRY && out.none { it.equals(u, true) }) out.add(u)
        }
        add(normalizeUrl(raw))
        add(t)
        if (t.contains("://")) {
            val origin = t.substringBefore("://") + "://" + t.substringAfter("://").substringBefore('/')
            add("$origin/v1/chat/completions")
        }
        return out    }

    /**
     * 从上游错误体里捞出人能看的 message。
     * DeepSeek/中转站都是 `{"error":{"message":"…","type":"…"}}`；阿里/火山之类是
     * `{"code":"…","message":"…"}`。捞不到就把原文截断。
     */
    private fun errMessage(body: String?): String {
        if (body.isNullOrBlank()) return ""
        val raw = body.trim()
        try {
            val o = JSONObject(raw)
            o.optJSONObject("error")?.let { e ->
                val m = e.optString("message", "")
                if (m.isNotEmpty()) return m
            }
            o.optString("message", "").let { if (it.isNotEmpty()) return it }
        } catch (_: Exception) { }
        return raw.take(240)
    }

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

        val urls = urlCandidates(ep.apiUrl).ifEmpty { listOf(ep.apiUrl) }
        var last: ApiError? = null
        for (u in urls) {
            try {
                return post(u, ep.apiKey, body, cancel, onDelta)
            } catch (e: ApiError) {
                last = e
                // 只有「路径不对」才换写法重试；401/402/429/网络异常立刻抛（重试也没用）
                if (e.httpCode != 404 && e.httpCode != 405) {
                    if (e.httpCode == 0) throw e          // 本来就不是 HTTP 层错误，原样抛
                    throw ApiError("HTTP ${e.httpCode} " + errMessage(e.message), e.httpCode)
                }
            }
        }
        val e = last ?: ApiError("接口无响应")
        throw ApiError(
            "HTTP ${e.httpCode} 找不到接口（已试 ${urls.size} 种地址写法：${urls.joinToString(" / ")}）。" +
                "通常是「接口地址」少了或多了路径，或者这个网关没有「${ep.model}」这个模型名。" +
                "标准形式一般是 https://api.deepseek.com/v1/chat/completions。" +
                "上游原话：" + errMessage(e.message),
            e.httpCode
        )
    }

    /** 单次 POST（一个具体地址）；非 2xx 抛 [ApiError] 并带上 httpCode */
    private fun post(
        url: String,
        apiKey: String,
        body: String,
        cancel: Cancel,
        onDelta: (String) -> Unit
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 180000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Authorization", "Bearer " + apiKey)
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                } catch (_: Exception) { null }
                throw ApiError(err?.trim()?.take(300).orEmpty(), code)
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
        // 接口报错（HTTP 200 但 body 里带 error，中转站常见）
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
