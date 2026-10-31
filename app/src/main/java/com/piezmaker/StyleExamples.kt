package com.piezmaker

/**
 * 从用户自己的 example.pez 里抽出来的节奏型（BPM 120、流速 10、难度 10）。
 *
 * 每条只保留「相对上一音符的间隔（拍）+ 相对位置 x（-1..1）+ 键型」，
 * 既给出原样，也生成左右镜像版，AI 可以参考结构而不是照抄，
 * 并按要求自己改间隔、加音符。
 */
object StyleExamples {

    /** 一步：距上一音符多少拍 / 位置 / 键型 / 长条持续拍数 */
    private class S(val dt: Float, val x: Float, val type: Int = 1, val hold: Float = 0f)

    private val patterns: List<Pair<String, List<S>>> = listOf(
        // 起手三个点，位置收在中间
        "起手" to listOf(S(0f, 0f), S(1f, -0.25f), S(1f, 0f)),
        // 跨屏跳（左侧 → 右边 → 回中间）
        "跨屏跳" to listOf(S(4f, 0.75f), S(1f, 0.25f), S(1f, -0.25f), S(1f, -0.75f)),
        // 同一位置连打：蓝 → 黄扫 → 蓝 → 红
        "同点连打" to listOf(S(3f, 0.75f), S(1f, 0.75f, 4), S(1f, 0.75f, 4), S(3f, 0.75f), S(1f, 0.75f, 3)),
        // 居中长条，3 拍后接红键
        "长条接红" to listOf(S(0f, 0f, 2, 4f), S(3f, 0f, 3)),
        // 下行扫：蓝 → 黄 → 黄
        "下行扫" to listOf(S(2f, 0.5f), S(1f, 0.25f, 4), S(1f, 0f, 4)),
        // 右侧蓝 + 同时左侧长条
        "点+长条" to listOf(S(2f, 0.5f), S(0f, -0.5f, 2, 4f)),
        // 左右交互（间隔 1 拍）
        "交互" to listOf(S(8f, 0.5f), S(1f, -0.5f), S(1f, 0.5f), S(1f, -0.5f)),
        // 收尾：同侧连打 + 双押 + 单点
        "双押收尾" to listOf(S(2f, 0.5f), S(3f, 0.5f), S(0f, -0.25f), S(1f, -0.25f)),
        // —— 来自第二份示例谱（example2.pez） ——
        // 双押起手：同拍 -0.5 与 0.25，然后同一侧连打
        "双押起手" to listOf(S(0f, -0.5f), S(0f, 0.25f), S(1f, -0.5f), S(1f, -0.5f)),
        // 黄键滑梯：半拍一下，从右往左扫
        "黄键滑梯" to listOf(
            S(0f, 0.5f, 4), S(0.5f, 0.25f, 4), S(0.5f, 0f, 4),
            S(0.5f, -0.25f, 4), S(0.5f, -0.5f, 4)
        ),
        // 滑梯收红：同样的半拍扫键，最后一下换成红键
        "滑梯收红" to listOf(
            S(0f, 0.5f, 4), S(0.5f, 0.25f, 4), S(0.5f, 0f, 4),
            S(0.5f, -0.25f, 4), S(0.5f, -0.5f, 3)
        )
    )

    private fun typeName(t: Int, hold: Float): String = when (t) {
        2 -> if (hold > 0f) "长条(持续${fmt(hold)}拍)" else "长条"
        3 -> "红"
        4 -> "黄"
        else -> "蓝"
    }

    private fun fmt(v: Float): String =
        if (v.toDouble() == Math.floor(v.toDouble())) v.toInt().toString()
        else String.format(java.util.Locale.US, "%.1f", v)

    private fun dump(list: List<S>, mirror: Boolean): String {
        val sb = StringBuilder()
        for ((i, s) in list.withIndex()) {
            sb.append("  +").append(fmt(s.dt)).append("拍  x=")
                .append(if (mirror) fmt(-s.x) else fmt(s.x))
                .append("  ").append(typeName(s.type, s.hold)).append('\n')
        }
        return sb.toString().trimEnd()
    }

    /** 给提示词用的完整示例集（原样 + 镜像） */
    fun text(): String {
        val sb = StringBuilder()
        sb.append("（下面是从用户自己谱面里抽出来的节奏型；单位是「相对上一音符的拍数」和相对位置 x=-1..1，BPM 参考 120）\n")
        for ((idx, p) in patterns.withIndex()) {
            val (name, list) = p
            sb.append("例").append(idx + 1).append(" ").append(name).append("（原样）\n")
            sb.append(dump(list, mirror = false)).append('\n')
            sb.append("例").append(idx + 1).append(" ").append(name).append("（镜像，x 全部取反）\n")
            sb.append(dump(list, mirror = true)).append('\n')
        }
        return sb.toString()
    }
}
