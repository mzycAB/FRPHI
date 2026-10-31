package com.piezmaker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个 AI 接口配置（OpenAI 兼容的 /chat/completions）。
 * [official] = DeepSeek 官方内置项：不能被删除，左滑删除等于"清空内容"。
 */
data class AiEndpoint(
    var name: String,
    var apiUrl: String,
    var apiKey: String,
    var model: String,
    val official: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("url", apiUrl)
        put("key", apiKey)
        put("model", model)
        put("official", official)
    }

    /** 能不能直接拿来请求：地址、Key、模型都得有 */
    val usable: Boolean
        get() = apiUrl.startsWith("http") && apiKey.isNotBlank() && model.isNotBlank()

    companion object {
        fun fromJson(o: JSONObject) = AiEndpoint(
            o.optString("name", "自定义"),
            o.optString("url", ""),
            o.optString("key", ""),
            o.optString("model", ""),
            o.optBoolean("official", false)
        )
    }
}

/**
 * AI 接口配置存储（SharedPreferences "frphi_ai"，JSON 数组 + 当前选用下标）。
 *
 * 默认列表：DeepSeek 官方 + 自定义1。点「+」创建自定义2、自定义3……
 * 官方项左滑 = 清空内容；自定义项左滑 = 真删除。
 */
object AiStore {
    const val PREF = "frphi_ai"
    private const val K_LIST = "list"
    private const val K_ACTIVE = "active"

    const val OFFICIAL_URL = "https://api.deepseek.com/v1/chat/completions"
    const val OFFICIAL_MODEL = "deepseek-chat"

    fun defaults(): MutableList<AiEndpoint> = mutableListOf(
        AiEndpoint("DeepSeek 官方", OFFICIAL_URL, "", OFFICIAL_MODEL, true),
        AiEndpoint("自定义1", "", "", "", false)
    )

    fun load(ctx: Context): MutableList<AiEndpoint> {
        val p = ctx.getSharedPreferences(PREF, 0)
        val raw = p.getString(K_LIST, null)
        if (raw.isNullOrBlank()) return defaults()
        val list = try {
            val arr = JSONArray(raw)
            val out = mutableListOf<AiEndpoint>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(AiEndpoint.fromJson(o))
            }
            out
        } catch (_: Exception) {
            mutableListOf()
        }
        if (list.isEmpty()) return defaults()
        // 官方项必须存在（老版本被删过的话补回来）
        if (list.none { it.official }) list.add(0, defaults()[0])
        return list
    }

    fun save(ctx: Context, list: List<AiEndpoint>, active: Int) {
        val arr = JSONArray()
        for (e in list) arr.put(e.toJson())
        ctx.getSharedPreferences(PREF, 0).edit()
            .putString(K_LIST, arr.toString())
            .putInt(K_ACTIVE, active.coerceIn(0, maxOf(0, list.size - 1)))
            .apply()
    }

    fun activeIndex(ctx: Context, list: List<AiEndpoint>): Int =
        ctx.getSharedPreferences(PREF, 0).getInt(K_ACTIVE, 0).coerceIn(0, maxOf(0, list.size - 1))

    fun active(ctx: Context): AiEndpoint? {
        val list = load(ctx)
        return list.getOrNull(activeIndex(ctx, list))
    }

    fun setActive(ctx: Context, list: List<AiEndpoint>, index: Int) = save(ctx, list, index)

    /** 下一个自定义接口的名字：自定义N（N = 现有自定义编号最大值 + 1） */
    fun nextCustomName(list: List<AiEndpoint>): String {
        var max = 0
        for (e in list) {
            if (e.official) continue
            val m = Regex("自定义\\s*(\\d+)").find(e.name) ?: continue
            max = maxOf(max, m.groupValues[1].toIntOrNull() ?: 0)
        }
        return "自定义" + (max + 1)
    }
}

/**
 * AI 聊天记录持久化（SharedPreferences "frphi_ai_chat"）。
 * 只存「我 / AI」两列纯文本，最多保留最近 [MAX] 条，避免无限膨胀。
 */
object AiChatStore {
    private const val PREF = "frphi_ai_chat"
    private const val K_MSG = "msgs"
    const val MAX = 60

    fun load(ctx: Context): MutableList<Pair<Boolean, String>> {
        val out = mutableListOf<Pair<Boolean, String>>()
        val raw = ctx.getSharedPreferences(PREF, 0).getString(K_MSG, null) ?: return out
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("t", "")
                if (t.isNotEmpty()) out.add(o.optBoolean("me", false) to t)
            }
        } catch (_: Exception) {}
        return out
    }

    fun save(ctx: Context, msgs: List<Pair<Boolean, String>>) {
        val arr = JSONArray()
        for ((me, t) in msgs.takeLast(MAX)) {
            arr.put(JSONObject().put("me", me).put("t", t))
        }
        ctx.getSharedPreferences(PREF, 0).edit().putString(K_MSG, arr.toString()).apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, 0).edit().remove(K_MSG).apply()
    }
}
