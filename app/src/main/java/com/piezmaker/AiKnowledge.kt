package com.piezmaker

/**
 * 本地知识库检索（没接 AI 时用）。
 *
 * 把用户的问题切成中文片段/英文单词，逐个和内置提示词（FEATURE_DOC + 命令说明）
 * 的每一行比对，命中多的行优先返回。接入 AI 之后不会走这里。
 */
object AiKnowledge {

    private val lines: List<String> by lazy {
        AiPrompts.knowledgeText()
            .split('\n')
            .map { it.trim().removePrefix("【").removeSuffix("】") }
            .filter { it.length >= 4 }
    }

    /** 返回命中的原文行；null = 没命中 */
    fun search(query: String, maxLines: Int = 10): String? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val tokens = tokensOf(q)
        if (tokens.isEmpty()) return null
        val scored = ArrayList<Pair<String, Int>>()
        for (line in lines) {
            var n = 0
            for (t in tokens) if (line.contains(t, ignoreCase = true)) n++
            if (n > 0) scored.add(line to n)
        }
        if (scored.isEmpty()) return null
        return scored.sortedByDescending { it.second }
            .take(maxLines)
            .joinToString("\n") { "· " + it.first }
    }

    /** 中文按 2~4 字滑窗，英文按单词；去掉太常见的单字 */
    private fun tokensOf(q: String): List<String> {
        val out = LinkedHashSet<String>()
        val cjk = Regex("[\\u4e00-\\u9fa5]+")
        for (m in cjk.findAll(q)) {
            val s = m.value
            if (s.length <= 4) {
                if (s.length >= 2) out.add(s)
            } else {
                for (i in 0..s.length - 2) out.add(s.substring(i, i + 2))
                out.add(s.take(4))
            }
        }
        val words = Regex("[A-Za-z0-9_]{2,}").findAll(q).map { it.value }
        for (w in words) out.add(w)
        val stop = setOf("什么", "怎么", "如何", "可以", "能不能", "为什么", "一下", "这个", "那个")
        return out.filter { it !in stop }
    }
}
