package com.piezmaker

/**
 * 判定线关键帧：时间 + 姿态 + 流速。
 *
 * [speed] 是"从这个关键帧到下一个关键帧"的流速（RPE speed 值，1 = 正常）。
 * 这样就能表达"第一个关键帧流速 1、第二个关键帧流速 2"这种变速：
 * 前一段按 1 下落，到了第二个关键帧之后按 2 下落。
 */
data class LineKeyframe(
    val timeMs: Long,
    val moveX: Float,
    val moveY: Float,
    val rotate: Float,
    val speed: Float = 1f,
    /** 判定线透明度 0~1（RPE 的 alphaEvents，255 为完全不透明） */
    val alpha: Float = 1f
)

/**
 * 流速曲线：把"关键帧上的流速"积分成"音符在屏幕上的纵向距离"。
 *
 * 有变速时，音符到判定线的距离不是 `速度 × 时间`，而是 `∫速度 dt`（Phira 的 height 就是这个）。
 * 例如前 1 秒流速 1、后 1 秒流速 2，则第 2 秒时的高度是 1×1 + 2×1 = 3（而不是 2×2=4）。
 * 分段常速，所以积分是折线，可以二分 O(log n) 求值。
 */
class SpeedTrack private constructor(
    private val times: LongArray,     // 各段起点（升序）
    private val s0: FloatArray,       // 段起点流速
    private val s1: FloatArray,       // 段终点流速（最后一帧 = s0）
    private val cum: DoubleArray      // 各段起点的累积高度
) {
    private fun idxOf(t: Long): Int {
        var lo = 0
        var hi = times.size - 1
        var idx = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        return idx
    }

    /** 某一时刻的流速（RPE/Phiedit 语义：相邻两个关键帧之间**线性过渡**，不是台阶） */
    fun speedAt(t: Long): Float {
        val i = idxOf(t)
        if (i >= times.size - 1) return s0[i]
        val span = (times[i + 1] - times[i]).coerceAtLeast(1L)
        val k = ((t - times[i]).toFloat() / span).coerceIn(0f, 1f)
        return s0[i] + (s1[i] - s0[i]) * k
    }

    /** 从 0 到 t 的累积高度（对"线性变化的流速"做积分：梯形 / 抛物线） */
    fun heightAt(t: Long): Double {
        if (times.isEmpty()) return 0.0
        if (t <= times[0]) return s0[0] * t.toDouble()      // 第一个关键帧之前：常速
        val i = idxOf(t)
        if (i >= times.size - 1) return cum[i] + s0[i] * (t - times[i]).toDouble()
        val span = (times[i + 1] - times[i]).coerceAtLeast(1L)
        val dt = (t - times[i]).toDouble()
        val L = span.toDouble()
        return cum[i] + s0[i] * dt + (s1[i] - s0[i]) * dt * dt / (2.0 * L)
    }

    companion object {
        /** 没有关键帧时的恒速轨迹 */
        fun constant(speed: Float): SpeedTrack {
            val v = if (speed.isFinite() && speed >= 0f) speed else 1f
            return SpeedTrack(longArrayOf(0L), floatArrayOf(v), floatArrayOf(v), doubleArrayOf(0.0))
        }

        /**
         * @param keyframes 关键帧（可乱序，内部会排序）
         * @param baseSpeed 没有关键帧时用的恒速
         */
        fun build(keyframes: List<LineKeyframe>, baseSpeed: Float): SpeedTrack {
            val base = if (baseSpeed.isFinite() && baseSpeed >= 0f) baseSpeed else 1f
            if (keyframes.isEmpty()) return constant(base)
            val kfs = keyframes.sortedBy { it.timeMs }
            val n = kfs.size
            val times = LongArray(n)
            val a = FloatArray(n)
            val b = FloatArray(n)
            val cum = DoubleArray(n)
            for (i in 0 until n) {
                val sp = kfs[i].speed
                times[i] = kfs[i].timeMs
                a[i] = if (sp.isFinite() && sp >= 0f) sp else base
                b[i] = if (i + 1 < n) {
                    val sp2 = kfs[i + 1].speed
                    if (sp2.isFinite() && sp2 >= 0f) sp2 else base
                } else a[i]
            }
            // 第一帧之前用第一帧的流速（常速）→ H(t0) = a0 * t0
            cum[0] = a[0].toDouble() * times[0]
            for (i in 0 until n - 1) {
                val dt = (times[i + 1] - times[i]).toDouble()
                cum[i + 1] = cum[i] + (a[i] + b[i]) / 2.0 * dt    // 梯形积分
            }
            return SpeedTrack(times, a, b, cum)
        }
    }
}

/** 流速合法范围（与设置页/输入框一致） */
const val LINE_SPEED_MIN = 0f
const val LINE_SPEED_MAX = 100f

fun clampLineSpeed(v: Float): Float =
    if (!v.isFinite()) 1f else v.coerceIn(LINE_SPEED_MIN, LINE_SPEED_MAX)

/**
 * 一条判定线：位置 + 姿态 + 关键帧 + 流速。
 * 音符挂在 [RecordedNote.lineId] 上（全局音符列表里按 id 归组）。
 */
class Line(val id: Int) {
    /** 屏幕纵向百分比（0~100），对应设置页的"判定线位置"（每条线可以不同） */
    var posPct: Int = 60

    /** 全局姿态：没有关键帧、或不在关键帧时间段内时用它 */
    var moveX: Float = 0f
    var moveY: Float = 0f
    var rotate: Float = 0f

    /** 没有关键帧时的恒速（关键帧上的流速各自覆盖自己那一段）；0 = 音符静止贴在线上 */
    var speed: Float = 1f

    /** 没有关键帧时的透明度 0~1 */
    var alpha: Float = 1f

    /** 导出到 RPE 的 Name 字段 */
    var name: String = ""

    val keyframes = mutableListOf<LineKeyframe>()

    private var kfSorted: Array<LineKeyframe>? = null
    private var track: SpeedTrack? = null

    /** 关键帧有增删改后必须调用 */
    fun invalidate() {
        kfSorted = null
        track = null
    }

    fun sortedKeyframes(): Array<LineKeyframe> {
        val cached = kfSorted
        if (cached != null && cached.size == keyframes.size) return cached
        val arr = keyframes.toTypedArray()
        java.util.Arrays.sort(arr) { a, b -> a.timeMs.compareTo(b.timeMs) }
        kfSorted = arr
        return arr
    }

    fun speedTrack(): SpeedTrack {
        val cached = track
        if (cached != null) return cached
        val t = SpeedTrack.build(keyframes, speed)
        track = t
        return t
    }

    /** 当前时刻的流速（关键帧里取最后一个 time<=now 的） */
    fun speedAt(now: Long): Float = speedTrack().speedAt(now)

    /** 某一时刻的透明度（关键帧间线性插值；没有关键帧就用整体 alpha） */
    fun alphaAt(now: Long): Float {
        val kfs = sortedKeyframes()
        if (kfs.isEmpty()) return alpha.coerceIn(0f, 1f)
        if (now <= kfs[0].timeMs) return kfs[0].alpha.coerceIn(0f, 1f)
        if (now >= kfs[kfs.size - 1].timeMs) return kfs[kfs.size - 1].alpha.coerceIn(0f, 1f)
        var lo = 0
        var hi = kfs.size - 1
        var idx = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (kfs[mid].timeMs <= now) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        val a = kfs[idx]
        val b = kfs[(idx + 1).coerceAtMost(kfs.size - 1)]
        val span = (b.timeMs - a.timeMs).coerceAtLeast(1L)
        val t = ((now - a.timeMs).toFloat() / span).coerceIn(0f, 1f)
        return (a.alpha + (b.alpha - a.alpha) * t).coerceIn(0f, 1f)
    }

    /** 关键帧在 now 时刻的姿态写进 out[0..2] */
    fun poseInto(now: Long, out: FloatArray) {
        val kfs = sortedKeyframes()
        if (kfs.isEmpty()) {
            out[0] = moveX; out[1] = moveY; out[2] = rotate
            return
        }
        LinePose.interpolate(kfs, now, out)
    }

    /** 打一个关键帧（同一时刻的旧帧会被替换）；流速默认取"此刻正在生效的流速"，保证变速连续 */
    fun putKeyframe(t: Long, x: Float, y: Float, r: Float, sp: Float = speedAt(t)) {
        keyframes.removeAll { it.timeMs == t }
        keyframes.add(LineKeyframe(t, x, y, r, clampLineSpeed(sp)))
        invalidate()
    }

    /** 改某个关键帧的流速 */
    fun setKeyframeSpeed(t: Long, sp: Float): Boolean {
        val i = keyframes.indexOfFirst { it.timeMs == t }
        if (i < 0) return false
        keyframes[i] = keyframes[i].copy(speed = clampLineSpeed(sp))
        invalidate()
        return true
    }

    /** 改某个关键帧的透明度 */
    fun setKeyframeAlpha(t: Long, a: Float): Boolean {
        val i = keyframes.indexOfFirst { it.timeMs == t }
        if (i < 0) return false
        keyframes[i] = keyframes[i].copy(alpha = a.coerceIn(0f, 1f))
        invalidate()
        return true
    }

    /** 改某个关键帧的姿态（x/y/rot） */
    fun setKeyframePose(t: Long, x: Float, y: Float, r: Float): Boolean {
        val i = keyframes.indexOfFirst { it.timeMs == t }
        if (i < 0) return false
        keyframes[i] = keyframes[i].copy(moveX = x, moveY = y, rotate = r)
        invalidate()
        return true
    }

    fun removeKeyframeAt(t: Long): Boolean {
        val i = keyframes.indexOfFirst { it.timeMs == t }
        if (i < 0) return false
        keyframes.removeAt(i)
        invalidate()
        return true
    }

    /** 深拷贝（撤销快照用） */
    fun snapshot(): UndoEntry.LineSnap = UndoEntry.LineSnap(
        id, posPct, moveX, moveY, rotate, clampLineSpeed(speed), name, ArrayList(keyframes)
    )

    fun restore(s: UndoEntry.LineSnap) {
        posPct = s.posPct
        moveX = s.moveX
        moveY = s.moveY
        rotate = s.rotate
        speed = s.speed
        name = s.name
        keyframes.clear()
        keyframes.addAll(s.keyframes)
        invalidate()
    }
}
