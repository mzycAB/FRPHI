package com.piezmaker

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * AI 的联网工具（agent 能力）：搜索 / 抓网页。
 *
 * 只用系统 HttpURLConnection，结果转成纯文本回灌给 AI。
 * 搜索用 DuckDuckGo 的 HTML 页面（不依赖任何 API Key），失败再退回 Bing。
 */
object AgentTools {

    fun run(cmd: JSONObject): String? = when (cmd.optString("cmd", "")) {
        "search" -> search(cmd.optString("q", ""))
        "fetch" -> fetch(cmd.optString("url", ""))
        else -> null
    }

    private fun search(q: String): String? {
        if (q.isBlank()) return null
        val enc = URLEncoder.encode(q, "UTF-8")
        val ddg = httpGet("https://html.duckduckgo.com/html/?q=$enc")
        if (ddg != null) {
            val links = Regex("<a[^>]*class=\"result__a\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
                .findAll(ddg).map { stripHtml(it.groupValues[1]) }.filter { it.isNotBlank() }.take(8).toList()
            if (links.isNotEmpty()) return "搜索「$q」结果：\n" + links.joinToString("\n")
        }
        val bing = httpGet("https://www.bing.com/search?q=$enc")
        if (bing != null) {
            val titles = Regex("<h2[^>]*>.*?<a[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
                .findAll(bing).map { stripHtml(it.groupValues[1]) }.filter { it.isNotBlank() }.take(8).toList()
            if (titles.isNotEmpty()) return "搜索「$q」结果：\n" + titles.joinToString("\n")
            return "搜索「$q」页面摘要：\n" + stripHtml(bing).take(2500)
        }
        return null
    }

    private fun fetch(url: String): String? {
        if (!url.startsWith("http")) return null
        val html = httpGet(url) ?: return null
        return "网页 " + url + " 的正文摘要：\n" + stripHtml(html).take(3000)
    }

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
                )
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                val sb = StringBuilder()
                var line = r.readLine()
                while (line != null && sb.length < 600_000) {
                    sb.append(line).append('\n')
                    line = r.readLine()
                }
                sb.toString()
            }
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun stripHtml(s: String): String = s
        .replace(Regex("<script.*?</script>", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("<style.*?</style>", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("\\s+"), " ")
        .trim()
}
