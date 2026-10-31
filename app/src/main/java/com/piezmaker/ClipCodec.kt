package com.piezmaker

/**
 * `.frclip` 文本格式（纯 JVM，可单测）。
 *
 * 用途：把剪贴板里的一条内容导出成文件、也可以再导入回来。
 * 格式故意做得很朴素，手改也行：
 * ```
 * #FRCLIP 1
 * N <type> <startMs> <endMs> <posX> <above> <fake> <pass>
 * ...
 * ```
 * 时间用绝对毫秒；粘贴时整体按"时间中心对齐播放头"平移，所以导出的是哪段不影响使用。
 */
object ClipCodec {

    const val HEADER = "#FRCLIP 1"

    fun encode(notes: List<RecordedNote>): String {
        val sb = StringBuilder(64 + notes.size * 48)
        sb.append(HEADER).append('\n')
        for (n in notes) {
            sb.append("N ")
                .append(n.type).append(' ')
                .append(n.startMs).append(' ')
                .append(n.endMs).append(' ')
                .append(fmt(n.posX)).append(' ')
                .append(if (n.above) 1 else 0).append(' ')
                .append(if (n.fake) 1 else 0).append(' ')
                .append(n.pass).append('\n')
        }
        return sb.toString()
    }

    /** 解析；文件头不对或者一行都读不出来 → 返回空列表 */
    fun decode(text: String): List<RecordedNote> {
        val out = ArrayList<RecordedNote>()
        var sawHeader = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) {
                if (line.startsWith("#FRCLIP")) sawHeader = true
                continue
            }
            if (!line.startsWith("N ")) continue
            val p = line.substring(2).split(' ').filter { it.isNotEmpty() }
            if (p.size < 7) continue
            val type = p[0].toIntOrNull() ?: continue
            val st = p[1].toLongOrNull() ?: continue
            val en = p[2].toLongOrNull() ?: continue
            val x = p[3].toFloatOrNull() ?: continue
            out.add(
                RecordedNote(
                    type = type.coerceIn(1, 4),
                    startMs = st.coerceAtLeast(0L),
                    endMs = en.coerceAtLeast(st),
                    posX = x.coerceIn(-1f, 1f),
                    pass = p[6].toIntOrNull() ?: passOfType(type),
                    lineId = 0,
                    above = (p[4].toIntOrNull() ?: 1) != 0,
                    fake = (p[5].toIntOrNull() ?: 0) != 0
                )
            )
        }
        if (!sawHeader && out.isEmpty()) return emptyList()
        return out
    }

    private fun passOfType(type: Int): Int = when (type) {
        2 -> 4
        3 -> 3
        4 -> 2
        else -> 1
    }

    private fun fmt(v: Float): String {
        val r = Math.round(v * 10000f) / 10000f
        return if (r == Math.floor(r.toDouble()).toFloat()) r.toInt().toString() else r.toString()
    }
}
