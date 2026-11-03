package com.piezmaker

/**
 * 玩家一句话里的"手感要求" → 谱面约束。
 *
 * 现在的模型经常把「蓝键多点」这种话忽略掉（照样输出一堆黄键），所以在本地再做一层：
 * 1) [parse] 把中文/英文要求解析成权重（蓝键/黄键/红键/长条 各自要更多还是更少、密还是疏）；
 * 2) [enforce] 在 AI 写完之后强制套用（改键型 + 补/删音符），保证"说到做到"。
 *
 * 只在用户明确提了要求时生效（[Spec.any] = false 时完全不动 AI 的结果）。
 */
object ChartStyle {

    class Spec(
        val tap: Float = 1f,      // 蓝键 (Tap) 权重
        val drag: Float = 1f,     // 黄键 (Drag)
        val flick: Float = 1f,    // 红键 (Flick)
        val hold: Float = 1f,     // 长条 (Hold)
        val density: Float = 1f,  // >1 更密 / <1 更疏
        val raw: String = ""
    ) {
        val any: Boolean
            get() = tap != 1f || drag != 1f || flick != 1f || hold != 1f || density != 1f

        /** 给用户看的一句话（应用后提示用） */
        fun describe(): String {
            val sb = StringBuilder()
            if (tap != 1f) sb.append(if (tap > 1f) "蓝键更多、" else "蓝键更少、")
            if (drag != 1f) sb.append(if (drag > 1f) "黄键更多、" else "黄键更少、")
            if (flick != 1f) sb.append(if (flick > 1f) "红键更多、" else "红键更少、")
            if (hold != 1f) sb.append(if (hold > 1f) "长条更多、" else "长条更少、")
            if (density != 1f) sb.append(if (density > 1f) "更密、" else "更疏、")
            return sb.toString().trimEnd('、').ifEmpty { "无特别要求" }
        }
    }

    // 各键型的中文/英文叫法（越靠前越常用）
    private val TAP_WORDS = listOf("蓝键", "蓝色的键", "tap", "单击", "单点", "点击")
    private val DRAG_WORDS = listOf("黄键", "黄色的键", "drag", "滑键", "拖动")
    private val FLICK_WORDS = listOf("红键", "红色的键", "flick", "甩", "划键")
    private val HOLD_WORDS = listOf("长条", "长按", "hold", "面条")
    private val MORE_WORDS = listOf("多点", "多一些", "多些", "更多", "多一点", "多", "密", "增加", "加多", "点")
    private val LESS_WORDS = listOf("少点", "少一些", "少些", "更少", "少", "不要", "别", "减少", "太多了", "太多", "没那么多")
    private val DENSE_WORDS = listOf("更密", "密一点", "密点", "密集", "多点音符", "音符多点", "铺满", "塞满", "密")
    private val SPARSE_WORDS = listOf("更疏", "疏一点", "疏点", "稀疏", "空一点", "简单点", "音符少", "少放点", "留白")

    /** 解析一句话里的手感要求 */
    fun parse(text: String): Spec {
        val s = text.lowercase(java.util.Locale.US)
        var tap = 1f
        var drag = 1f
        var flick = 1f
        var hold = 1f
        var density = 1f

        fun dirFor(word: String, hit: Int): Int {
            // 只看命中位置前后 8 个字符，判断是"多"还是"少"
            val from = (hit - 8).coerceAtLeast(0)
            val to = (hit + word.length + 8).coerceAtMost(s.length)
            val around = s.substring(from, to)
            val more = MORE_WORDS.filter { around.contains(it) }.maxByOrNull { it.length }
            val less = LESS_WORDS.filter { around.contains(it) }.maxByOrNull { it.length }
            // 更长（更具体）的词优先："少点" 比 "点" 更具体
            if (less != null && (more == null || less.length >= more.length)) return -1
            if (more != null) return 1
            return 0
        }

        fun scan(words: List<String>): Int {
            var best = 0
            for (w in words) {
                var i = s.indexOf(w)
                while (i >= 0) {
                    val d = dirFor(w, i)
                    if (d != 0) return d     // 明确说了多/少就用它
                    i = s.indexOf(w, i + w.length)
                }
            }
            // 只提到键型、没说多少 → 当成"要点这个键"（轻微倾斜）
            for (w in words) if (s.contains(w)) { best = 1; break }
            return best
        }

        fun bump(base: Float, d: Int): Float = when {
            d > 0 -> 2.2f
            d < 0 -> 0.35f
            else -> base
        }

        val t = scan(TAP_WORDS); if (t != 0) tap = bump(tap, t)
        val d2 = scan(DRAG_WORDS); if (d2 != 0) drag = bump(drag, d2)
        val f = scan(FLICK_WORDS); if (f != 0) flick = bump(flick, f)
        val h = scan(HOLD_WORDS); if (h != 0) hold = bump(hold, h)

        // 密度：单独的"密一点 / 空一点"，或者由"蓝键多点"这类要求顺带推一把
        val dense = DENSE_WORDS.any { s.contains(it) }
        val sparse = SPARSE_WORDS.any { s.contains(it) }
        if (dense && !sparse) density = 1.25f
        if (sparse && !dense) density = 0.8f
        if (tap > 1f && density == 1f) density = 1.12f     // 蓝键多点 → 顺便多铺一点

        // 什么都没提到 → 不干预
        val touched = listOf(tap, drag, flick, hold, density).any { it != 1f }
        return if (!touched) Spec() else Spec(tap, drag, flick, hold, density, text)
    }

    // ------------------------------------------------------------------ 套用
    private fun passOf(type: Int): Int = when (type) {
        1 -> 1
        4 -> 2
        3 -> 3
        else -> 4
    }

    /**
     * 按 [spec] 改谱面：
     * 1) 键型：算出目标占比（普通谱大致 蓝 .55 / 黄 .22 / 红 .15 / 长条 .08，
     *    再按权重缩放），把超出占比的音符改成缺口最大的那种；
     * 2) 密度：要求更密就在半拍网格上补音符（优先用要求变多的键型），要求更疏就抽稀。
     */
    fun enforce(
        notes: List<RecordedNote>,
        spec: Spec,
        durationMs: Long,
        bpm: Double,
        lineId: Int
    ): List<RecordedNote> {
        if (!spec.any || notes.isEmpty()) return notes
        val dur = durationMs.coerceAtLeast(1L)
        val sorted = notes.sortedBy { it.startMs }
        var out = sorted.toMutableList()

        // ---- 1) 目标占比
        val wTap = 0.55f * spec.tap
        val wDrag = 0.22f * spec.drag
        val wFlick = 0.15f * spec.flick
        val wHold = 0.08f * spec.hold
        val sum = wTap + wDrag + wFlick + wHold
        val share = floatArrayOf(wTap / sum, wDrag / sum, wFlick / sum, wHold / sum)   // 1/4/3/2 → 下标
        val typeOfSlot = intArrayOf(1, 4, 3, 2)

        // 统计当前各键型数量（slot 顺序）—— 先数，再定目标
        val count = IntArray(4)
        for (n in out) {
            val slot = when (n.type) { 1 -> 0; 4 -> 1; 3 -> 2; else -> 3 }
            count[slot]++
        }
        val weight = floatArrayOf(spec.tap, spec.drag, spec.flick, spec.hold)
        val target = IntArray(4)
        for (i in 0 until 4) {
            target[i] = Math.round(share[i] * out.size).toInt()
            // 玩家明确要"更多"的键型：至少保住现有数量（否则会被默认占比改少，
            // 比如「长条多一点」的谱面本来长条就占 25%，不能被压到 8%）
            if (weight[i] > 1f) target[i] = maxOf(target[i], count[i])
        }
        // 需要变小：把超出最多的槽位里的音符改成缺口最大的槽位
        for (i in out.indices) {
            val slot = when (out[i].type) { 1 -> 0; 4 -> 1; 3 -> 2; else -> 3 }
            if (count[slot] <= target[slot]) continue
            // 找缺口最大的槽位
            var bestSlot = -1
            var bestGap = 0
            for (j in 0 until 4) {
                val gap = target[j] - count[j]
                if (gap > bestGap) { bestGap = gap; bestSlot = j }
            }
            if (bestSlot < 0) break
            val n = out[i]
            val nt = typeOfSlot[bestSlot]
            out[i] = when (nt) {
                1 -> if (n.type == 2 || n.type == 4 || n.type == 3)
                    n.copy(type = 1, endMs = n.startMs, pass = passOf(1)) else n
                2 -> n.copy(
                    type = 2, pass = passOf(2),
                    endMs = (n.startMs + 380L).coerceAtMost(dur).coerceAtLeast(n.startMs + 120L)
                )
                else -> n.copy(type = nt, pass = passOf(nt), endMs = n.startMs)
            }
            if (out[i].type != n.type) { count[slot]--; count[bestSlot]++ }
        }

        // ---- 2) 密度
        if (spec.density > 1.02) {
            out = addNotes(out, spec, dur, bpm, lineId)
        } else if (spec.density < 0.98) {
            out = thin(out, spec.density)
        }
        return out.sortedBy { it.startMs }
    }

    /** 补音符：在半拍网格上找"空档"补；优先补要求变多的键型 */
    private fun addNotes(
        notes: MutableList<RecordedNote>,
        spec: Spec,
        durationMs: Long,
        bpm: Double,
        lineId: Int
    ): MutableList<RecordedNote> {
        if (notes.isEmpty()) return notes
        val beat = 60_000.0 / (if (bpm > 1.0) bpm else 120.0)
        val stepMs = (beat / 2).toLong().coerceAtLeast(90L)
        // 已被占据的时间片（±1/6 拍算同一拍点）
        val occupied = notes.map { it.startMs }.sorted()
        fun busy(t: Long): Boolean {
            val tol = (beat / 6).toLong().coerceAtLeast(45L)
            var lo = 0
            var hi = occupied.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                val v = occupied[mid]
                if (Math.abs(v - t) <= tol) return true
                if (v < t) lo = mid + 1 else hi = mid - 1
            }
            return false
        }
        // 要补的键型：权重最大的那个（蓝键优先）
        val slotType = when {
            spec.tap >= spec.drag && spec.tap >= spec.flick && spec.tap >= spec.hold -> 1
            spec.drag >= spec.flick && spec.drag >= spec.hold -> 4
            spec.flick >= spec.hold -> 3
            else -> 2
        }
        val grid = floatArrayOf(-0.75f, -0.5f, -0.25f, 0f, 0.25f, 0.5f, 0.75f)
        val cap = Math.round(notes.size * 0.45f).coerceAtLeast(0)
        val added = ArrayList<RecordedNote>()
        var t = stepMs
        var gi = 0
        while (t < durationMs && added.size < cap && cap > 0) {
            if (!busy(t)) {
                val x = grid[gi % grid.size]
                gi++
                val end = if (slotType == 2) (t + 360L).coerceAtMost(durationMs) else t
                added.add(RecordedNote(slotType, t, end, x, passOf(slotType), lineId, true, false))
            }
            t += stepMs
        }
        if (added.isEmpty()) return notes
        val out = notes.toMutableList()
        out.addAll(added)
        out.sortBy { it.startMs }
        return out
    }

    /** 抽稀：保留的比例 = density（0.8 → 每 5 个丢 1 个） */
    private fun thin(notes: MutableList<RecordedNote>, density: Float): MutableList<RecordedNote> {
        val keep = density.coerceIn(0.4f, 0.95f)
        val drop = Math.round(1f / (1f - keep)).coerceAtLeast(2)
        if (notes.size < drop * 2) return notes
        val out = ArrayList<RecordedNote>(notes.size)
        for ((i, n) in notes.withIndex()) if (i % drop != drop - 1) out.add(n)
        return if (out.isEmpty()) notes else out
    }
}
