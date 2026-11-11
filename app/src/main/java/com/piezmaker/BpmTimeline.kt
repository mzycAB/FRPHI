package com.piezmaker

/** 一段 BPM：从 [startMs]（音乐时间，毫秒）开始按 [bpm] 走 */
data class BpmSegment(val startMs: Long, val bpm: Double)

/**
 * 多 BPM（分段变速）的时间轴换算。纯函数，可在 JVM 上单测。
 *
 * RPE 里 `BPMList` 的每一项是 `{bpm, startTime:[i,n,d]}`（startTime 是**拍**）；
 * 本应用内部统一用毫秒，所以导出时要把毫秒按分段换算成拍，导入时反过来。
 */
object BpmTimeline {

    /** 规范化：丢掉非法项、按时间排序、同一时刻只留最后一个、保证第一段从 0 开始 */
    fun normalize(segs: List<BpmSegment>, fallbackBpm: Double = 120.0): List<BpmSegment> {
        val out = ArrayList<BpmSegment>()
        for (s in segs.sortedBy { it.startMs }) {
            if (!s.bpm.isFinite() || s.bpm < 1.0 || s.bpm > 1000.0) continue
            val t = if (s.startMs < 0) 0L else s.startMs
            if (out.isNotEmpty() && out.last().startMs == t) out[out.size - 1] = BpmSegment(t, s.bpm)
            else out.add(BpmSegment(t, s.bpm))
        }
        if (out.isEmpty()) return emptyList()
        if (out[0].startMs > 0L) {
            val first = if (fallbackBpm.isFinite() && fallbackBpm > 1.0) fallbackBpm else out[0].bpm
            out.add(0, BpmSegment(0L, first))
        }
        return out
    }

    /** 某一时刻生效的 BPM（没有分段时返回 [fallback]） */
    fun bpmAt(ms: Long, segs: List<BpmSegment>, fallback: Double = 120.0): Double {
        if (segs.isEmpty()) return fallback
        var cur = segs[0].bpm
        for (s in segs) {
            if (s.startMs <= ms) cur = s.bpm else break
        }
        return cur
    }

    /** 毫秒 → 拍（分段累积；[segs] 需已 normalize） */
    fun beatsAt(ms: Long, segs: List<BpmSegment>): Double {
        if (segs.isEmpty()) return 0.0
        val t = if (ms < 0) 0L else ms
        var beats = 0.0
        for (i in segs.indices) {
            val s = segs[i]
            if (t <= s.startMs) break
            val next = if (i + 1 < segs.size) segs[i + 1].startMs else Long.MAX_VALUE
            val end = if (t < next) t else next
            if (end > s.startMs) beats += (end - s.startMs) / 60000.0 * s.bpm
            if (t < next) break
        }
        return beats
    }

    /** 毫秒（可带小数，BPM 偏移支持 3 位小数）→ 拍 */
    fun beatsAt(ms: Double, segs: List<BpmSegment>): Double {
        if (segs.isEmpty() || ms <= 0.0) return 0.0
        val whole = Math.floor(ms).toLong()
        return beatsAt(whole, segs) + (ms - whole) / 60000.0 * bpmAt(whole, segs)
    }

    /** 每一段起点的拍数（第 0 段是 0） */
    fun startBeats(segs: List<BpmSegment>): DoubleArray {
        val out = DoubleArray(segs.size)
        for (i in 1 until segs.size) {
            val dt = (segs[i].startMs - segs[i - 1].startMs).toDouble()
            out[i] = out[i - 1] + dt / 60000.0 * segs[i - 1].bpm
        }
        return out
    }

    /** 存到项目标签里用：`0:222|153000:146.5` */
    fun encode(segs: List<BpmSegment>): String =
        normalize(segs).joinToString("|") { it.startMs.toString() + ":" + trim(it.bpm) }

    fun decode(s: String?): List<BpmSegment> {
        if (s.isNullOrBlank()) return emptyList()
        val out = ArrayList<BpmSegment>()
        for (part in s.split('|')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val i = p.indexOf(':')
            if (i <= 0) continue
            val ms = p.substring(0, i).trim().toLongOrNull() ?: continue
            val bpm = p.substring(i + 1).trim().toDoubleOrNull() ?: continue
            out.add(BpmSegment(ms, bpm))
        }
        return normalize(out)
    }

    private fun trim(v: Double): String {
        val r = Math.round(v * 1000.0) / 1000.0
        return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
    }
}
