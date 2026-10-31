package com.piezmaker

/**
 * 纯本地的「按节拍写谱」兜底引擎（AI 没返回可用结果时用）。
 *
 * 时间只取音频分析出来的重音点；位置按固定图案成组摆放（楼梯 / 纵连 / 交互），
 * 键型由频率特征决定：低频干脆的一下=Tap，中频=Drag，高频强烈=Flick，能量持续=Hold。
 * 难度 0~20 控制音符密度与双押比例。
 */
object AutoChart {

    private val GRID = floatArrayOf(-0.75f, -0.5f, -0.25f, 0f, 0.25f, 0.5f, 0.75f)

    /**
     * AI 结果的后处理：保证谱面「不成套也不乱」——
     * 1) 全是蓝键 → 按比例补 Drag / Flick / Hold；
     * 2) 位置只有两三个点、或者全在左右交替 → 重排成 楼梯 / 纵连 / 交互 / 楼梯 的循环。
     */
    fun enhance(
        notes: List<RecordedNote>, difficulty: Double, durationMs: Long, bpm: Double
    ): List<RecordedNote> {
        if (notes.isEmpty()) return notes
        val d = difficulty.coerceIn(0.0, 20.0)
        var out = notes.sortedBy { it.startMs }
        // 抽稀到「半拍~一拍」级别：和用户示例谱的间距接近，不再挤成一片
        out = thin(out, gapMs(d, bpm))
        val taps = out.count { it.type == 1 }
        if (taps >= out.size * 0.80 || out.none { it.type == 3 } || out.none { it.type == 4 }) {
            out = diversifyTypes(out, d, durationMs)
        }
        if (looksMonotone(out)) out = PatternLibrary.rewrite(out, d, durationMs)
        return out
    }

    /** 相邻音符的最小间隔：难度高约 0.45 拍、普通 0.5 拍、简单 0.75 拍（限制 60~900ms） */
    private fun gapMs(difficulty: Double, bpm: Double): Long {
        val beatMs = 60_000.0 / (if (bpm > 1.0) bpm else 120.0)
        val beats = when {
            difficulty >= 12.0 -> 0.45
            difficulty >= 6.0 -> 0.50
            else -> 0.75
        }
        return (beatMs * beats).toLong().coerceIn(60L, 900L)
    }

    /** 过密抽稀：以 minGap 为最小间隔，同一时刻的多押保留 */
    private fun thin(notes: List<RecordedNote>, minGap: Long): List<RecordedNote> {
        if (notes.size < 8) return notes
        val out = ArrayList<RecordedNote>(notes.size)
        var lastT: Long? = null
        var i = 0
        val maxPerTime = 2
        while (i < notes.size) {
            val t = notes[i].startMs
            val keep = lastT == null || t - lastT!! >= minGap
            var kept = 0
            while (i < notes.size && notes[i].startMs == t) {
                if (keep && kept < maxPerTime) { out.add(notes[i]); kept++ }
                i++
            }
            if (keep) lastT = t
        }
        if (out.isEmpty()) return notes
        return out
    }

    /** 位置太单调（只有 1~3 个点，或几乎全是左右交替）→ 按图案整份重排 */
    private fun looksMonotone(notes: List<RecordedNote>): Boolean {
        if (notes.size < 8) return false
        val distinct = notes.map { Math.round(it.posX * 20) }.toSet()
        if (distinct.size <= 3) return true
        var alt = 0
        for (i in 2 until notes.size) {
            val samePrev = Math.abs(notes[i].posX - notes[i - 1].posX) < 0.05f
            val samePrev2 = Math.abs(notes[i].posX - notes[i - 2].posX) < 0.05f
            if (!samePrev && samePrev2) alt++
        }
        if (alt >= (notes.size - 2) * 0.75) return true
        // 整首都在爬楼梯（同方向等步长移动）
        var stair = 0
        for (i in 2 until notes.size) {
            val d1 = notes[i - 1].posX - notes[i - 2].posX
            val d2 = notes[i].posX - notes[i - 1].posX
            if (Math.abs(d1 - d2) < 0.03f && Math.abs(d1) >= 0.15f) stair++
        }
        return stair >= (notes.size - 2) * 0.50
    }

    /** 蓝键太多时按比例改成 Drag / Flick / Hold（难度越低长条越多） */
    private fun diversifyTypes(
        notes: List<RecordedNote>, d: Double, durationMs: Long
    ): List<RecordedNote> {
        val n = notes.size
        if (n < 16) return notes
        var dragLeft = Math.round(n * 0.33).toInt() - notes.count { it.type == 4 }
        var flickLeft = Math.round(n * 0.22).toInt() - notes.count { it.type == 3 }
        var holdLeft = Math.round(n * (if (d <= 8.0) 0.12 else 0.04)).toInt() - notes.count { it.type == 2 }
        dragLeft = dragLeft.coerceAtLeast(0); flickLeft = flickLeft.coerceAtLeast(0); holdLeft = holdLeft.coerceAtLeast(0)
        return notes.mapIndexed { i, note ->
            if (note.type != 1) note
            else when {
                holdLeft > 0 && i % 19 == 7 -> {
                    holdLeft--
                    note.copy(type = 2, pass = 4, endMs = (note.startMs + 420).coerceAtMost(durationMs))
                }
                flickLeft > 0 && i % 5 == 2 -> { flickLeft--; note.copy(type = 3, pass = 3) }
                dragLeft > 0 && i % 3 == 1 -> { dragLeft--; note.copy(type = 4, pass = 2) }
                else -> note
            }
        }
    }

    fun build(
        beats: List<AudioBeats.Beat>,
        difficulty: Double,
        lineId: Int,
        durationMs: Long,
        bpm: Double
    ): List<RecordedNote> {
        if (beats.isEmpty()) return emptyList()
        val d = difficulty.coerceIn(0.0, 20.0)
        val target = Math.round((0.7 + d * 0.13) * (durationMs / 1000.0)).toInt().coerceAtLeast(20)
        val minGap = gapMs(d, bpm)
        // 先把过密的点抽稀，再按目标数量取样
        val spaced = ArrayList<AudioBeats.Beat>(beats.size)
        var last = Long.MIN_VALUE / 4
        for (b in beats) {
            if (b.t - last >= minGap) { spaced.add(b); last = b.t }
        }
        val picked = pick(spaced.ifEmpty { beats }, target)

        // 先用占位音符（只有时间），再让模板库填位置 + 键型
        val base = picked.map { RecordedNote(1, it.t, it.t, 0f, 1, lineId, true, false) }
        var out = PatternLibrary.rewrite(base, d, durationMs)

        // 能量持续很久的重音 → 改成 Hold（保留模板给的位置）
        out = out.mapIndexed { i, n ->
            val b = picked[i]
            if (d <= 15.0 && b.sustainMs >= 300 && n.type == 1) {
                n.copy(
                    type = 2, pass = 4,
                    endMs = (b.t + b.sustainMs.coerceIn(300, 1200)).coerceAtMost(durationMs)
                )
            } else n
        }

        // 重拍双押（难度够了才加）
        val doubleEvery = if (d >= 12.0) 6 else if (d >= 8.0) 9 else 0
        if (doubleEvery > 0) {
            val extra = ArrayList<RecordedNote>()
            for ((i, n) in out.withIndex()) {
                if (i % doubleEvery != 0) continue
                if (picked.getOrNull(i)?.strength ?: 0f < 0.72f) continue
                val x2 = if (n.posX >= 0f) GRID[0] else GRID[6]
                extra.add(RecordedNote(1, n.startMs, n.startMs, x2, 1, lineId, true, false))
            }
            out = out + extra
        }
        return out.sortedBy { it.startMs }
    }

    /** 均匀抽/补到目标数量：不改变时间顺序，密度不够时保留全部 */
    private fun pick(beats: List<AudioBeats.Beat>, target: Int): List<AudioBeats.Beat> {
        if (beats.size <= target) return beats
        val step = Math.ceil(beats.size.toDouble() / target).toInt().coerceAtLeast(1)
        val out = ArrayList<AudioBeats.Beat>(target)
        var i = 0
        while (i < beats.size && out.size < target) {
            out.add(beats[i])
            i += step
        }
        return out
    }
}
