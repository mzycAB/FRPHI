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
    val alpha: Float = 1f,
    /** 判定线/图片/文字的缩放（RPE extended.scaleXEvents / scaleYEvents，逐事件生效） */
    val scaleX: Float = 1f,
    val scaleY: Float = 1f
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

    /**
     * [heightAt] 的反函数：给定累积高度 [h]（∫speed dt，从 0 开始），返回对应的时间（毫秒）。
     *
     * 编辑器里"玩家点的屏幕位置 → 音符时间"必须走这个逆解，而不是
     * `now - 距离 / 当前瞬时流速` —— 后者只在常速下成立，遇到变速（两个关键帧流速不同）
     * 会把音符放到错误的时间，显示出来就会偏移。
     */
    fun timeAtHeight(h: Double): Double {
        if (times.isEmpty()) return 0.0
        if (h <= 0.0) return 0.0
        val n = times.size
        // 第一个关键帧之前：height = s0[0] * t
        if (h <= cum[0]) {
            val v = s0[0]
            return if (v > 1e-9f) h / v else 0.0
        }
        for (i in 0 until n - 1) {
            if (h > cum[i + 1]) continue
            val L = (times[i + 1] - times[i]).coerceAtLeast(1L).toDouble()
            val a0 = s0[i].toDouble()
            val dv = (s1[i] - s0[i]).toDouble()
            val need = h - cum[i]
            if (Math.abs(dv) < 1e-9) {
                if (a0 < 1e-9) return times[i].toDouble()
                return times[i].toDouble() + (need / a0).coerceIn(0.0, L)
            }
            // a0*dt + dv*dt²/(2L) = need（流速线性变化时的梯形积分）
            val a = dv / (2.0 * L)
            val disc = a0 * a0 + 4.0 * a * need
            if (disc <= 0.0) return times[i].toDouble()
            val dt = (-a0 + Math.sqrt(disc)) / (2.0 * a)
            return times[i].toDouble() + dt.coerceIn(0.0, L)
        }
        // 最后一个关键帧之后：常速外推
        val last = n - 1
        val v = s0[last]
        return if (v > 1e-9f) times[last].toDouble() + (h - cum[last]) / v else times[last].toDouble()
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

/**
 * RPE 判定线的 ctrl 对象事件（yControl / posControl / sizeControl / alphaControl 各一份列表）。
 * JSON 形如 `{ "easing": 1, "x": 120.0, "y": 0.5 }`，其中 **x 是"音符到判定线的落点距离"**
 * （RPE 落点距离 px，1 流速秒 = 120px），不是时间。Phira 会按每个音符自己的距离求值。
 */
data class CtrlEvent(val x: Float, val value: Float, val easing: Int)

/** RPE extended.inclineEvents（倾斜事件，单位度；时间驱动，与判定线事件同语义） */
data class InclineEvent(
    val startMs: Long,
    val endMs: Long,
    val start: Float,
    val end: Float,
    val easing: Int
)

/**
 * 通用数值轨道：点列 (x, 值, 下一段缓动)。
 *  - ctrl 对象：x = 落点距离 px；缓动**移位**——第 i 段（点 i → 点 i+1）用事件 i+1 的 easing
 *    （Phira parse_ctrl_events：RPE 里事件的缓动管"以该事件结尾的区间"），事件 0 的缓动被丢弃
 *  - incline：x = 毫秒；事件展开成 (起点, v0, ease) + (终点, v1, HOLD)，事件之间的空档保持原值
 */
class ValueTrack private constructor(
    private val xs: FloatArray,
    private val vs: FloatArray,
    private val es: IntArray    // es[i] = 第 i 段（xs[i]→xs[i+1]）的缓动；最后一个点之后值恒定
) {
    fun valueAt(x: Float): Float {
        val n = xs.size
        if (n == 0) return 1f
        if (x <= xs[0]) return vs[0]
        val last = n - 1
        if (x >= xs[last]) return vs[last]
        var lo = 0
        var hi = last
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (xs[mid] <= x) lo = mid else hi = mid
        }
        val span = xs[hi] - xs[lo]
        if (span <= 1e-6f) return vs[hi]
        if (es[lo] == HOLD) return vs[lo]
        val t = ((x - xs[lo]) / span).toDouble().coerceIn(0.0, 1.0)
        return (vs[lo] + (vs[hi] - vs[lo]) * PezImporter.Ease.of(es[lo], t)).toFloat()
    }

    companion object {
        const val HOLD = -1

        /** ctrl 事件 → 轨道（缓动按下一段移位）；空列表返回 null（= 恒 1，无轨道） */
        fun fromCtrlEvents(events: List<CtrlEvent>): ValueTrack? {
            if (events.isEmpty()) return null
            val evs = events.sortedBy { it.x }
            val n = evs.size
            val xs = FloatArray(n)
            val vs = FloatArray(n)
            val es = IntArray(n)
            for (i in 0 until n) {
                xs[i] = evs[i].x
                vs[i] = evs[i].value
                es[i] = if (i + 1 < n) evs[i + 1].easing else 0
            }
            return ValueTrack(xs, vs, es)
        }

        /** incline 事件 → 轨道（毫秒域，事件间保持）；空列表返回 null（= 恒 0，无倾斜） */
        fun fromInclineEvents(events: List<InclineEvent>): ValueTrack? {
            if (events.isEmpty()) return null
            data class P(val x: Float, val v: Float, val e: Int)
            val pts = ArrayList<P>(events.size * 2)
            for (ev in events.sortedBy { it.startMs }) {
                pts.add(P(ev.startMs.toFloat(), ev.start, ev.easing))
                pts.add(P(ev.endMs.toFloat(), ev.end, HOLD))
            }
            pts.sortBy { it.x }
            val n = pts.size
            return ValueTrack(
                FloatArray(n) { pts[it].x },
                FloatArray(n) { pts[it].v },
                IntArray(n) { pts[it].e }
            )
        }
    }
}

/** 流速合法范围（与设置页/输入框一致） */
const val LINE_SPEED_MIN = 0f
const val LINE_SPEED_MAX = 100f

fun clampLineSpeed(v: Float): Float =
    if (!v.isFinite()) 1f else v.coerceIn(LINE_SPEED_MIN, LINE_SPEED_MAX)

/** 判定线种类：RPE 里没有"类型"字段，类型由 Texture 和 textEvents 决定（和 Phiedit / Phira 一致） */
enum class LineKind {
    /** 普通判定线：Texture = line.png，画成一条线 */
    Normal,
    /** 图片线：Texture 指向包内一张 png，Phira 按图片原始像素尺寸 × scale 画 */
    Image,
    /** 文字线：Texture = line.png + extended.textEvents，Phira 用自带字体渲染 */
    Text
}

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

    /** 导出到 RPE 的 Name 字段（**不含**类型前缀：类型由 texture/lineText 决定，别往这里塞"图片/文字"） */
    var name: String = ""

    /** 图片线贴图（RPE 的 Texture，只存**文件名**）：空 = 内建 line.png */
    var texture: String = ""

    /** 图片线在本机的位置（filesDir/lineimg/<texture>）；不导出、不进草稿 */
    var texturePath: String = ""

    /** 文字线内容（RPE extended.textEvents）；非空 = 文字线 */
    var lineText: String = ""

    /** 图片/文字线的静态缩放（RPE extended.scaleXEvents / scaleYEvents 的单一值） */
    var scaleX: Float = 1f
    var scaleY: Float = 1f

    val kind: LineKind
        get() = when {
            lineText.isNotEmpty() -> LineKind.Text
            texture.isNotEmpty() -> LineKind.Image
            else -> LineKind.Normal
        }

    /** 父线 id（RPE father，-1 = 无）。Phira fetch_pos 语义：位置**始终**跟随父线（含其完整旋转），
     *  旋转只在 [rotWithFather] 为 true 时叠加父线旋转。 */
    var fatherId: Int = -1
    var rotWithFather: Boolean = false

    /** ctrl 对象事件（RPE yControl/posControl/sizeControl/alphaControl）：按音符到线的距离调制音符 */
    val ctrlYEvents = mutableListOf<CtrlEvent>()
    val ctrlPosEvents = mutableListOf<CtrlEvent>()
    val ctrlSizeEvents = mutableListOf<CtrlEvent>()
    val ctrlAlphaEvents = mutableListOf<CtrlEvent>()
    /** incline 倾斜事件（度）：非长条音符的横向位置随下落距离向判定线中心收缩 */
    val inclineEvents = mutableListOf<InclineEvent>()

    private var ctrlYTrack: ValueTrack? = null
    private var ctrlPosTrack: ValueTrack? = null
    private var ctrlSizeTrack: ValueTrack? = null
    private var ctrlAlphaTrack: ValueTrack? = null
    private var inclTrack: ValueTrack? = null

    fun ctrlYTrack(): ValueTrack? {
        var t = ctrlYTrack
        if (t == null) { t = ValueTrack.fromCtrlEvents(ctrlYEvents); ctrlYTrack = t }
        return t
    }

    fun ctrlPosTrack(): ValueTrack? {
        var t = ctrlPosTrack
        if (t == null) { t = ValueTrack.fromCtrlEvents(ctrlPosEvents); ctrlPosTrack = t }
        return t
    }

    fun ctrlSizeTrack(): ValueTrack? {
        var t = ctrlSizeTrack
        if (t == null) { t = ValueTrack.fromCtrlEvents(ctrlSizeEvents); ctrlSizeTrack = t }
        return t
    }

    fun ctrlAlphaTrack(): ValueTrack? {
        var t = ctrlAlphaTrack
        if (t == null) { t = ValueTrack.fromCtrlEvents(ctrlAlphaEvents); ctrlAlphaTrack = t }
        return t
    }

    /** incline（度）在 now 时刻的值；没有事件返回 0 */
    fun inclineAt(now: Long): Float {
        var t = inclTrack
        if (t == null) { t = ValueTrack.fromInclineEvents(inclineEvents); inclTrack = t }
        return t?.valueAt(now.toFloat()) ?: 0f
    }

    val keyframes = mutableListOf<LineKeyframe>()

    private var kfSorted: Array<LineKeyframe>? = null
    private var track: SpeedTrack? = null

    /** 关键帧有增删改后必须调用 */
    fun invalidate() {
        kfSorted = null
        track = null
        ctrlYTrack = null
        ctrlPosTrack = null
        ctrlSizeTrack = null
        ctrlAlphaTrack = null
        inclTrack = null
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

    /**
     * 某一时刻**正在生效**的关键帧时间：`time <= now` 的最后一帧（比第一帧还早就用第一帧）。
     * 整条线没有关键帧时返回 -1。
     *
     * 缩放这类"改了就只该改这一段"的编辑靠它定位落点：
     * 以前是直接把新值写进**每一帧**，于是改一次缩放，整首歌的关键帧全被改成同一个值，
     * 看起来就像"缩放根本加不进关键帧"。
     */
    fun activeKeyframeTimeAt(now: Long): Long {
        val kfs = sortedKeyframes()
        if (kfs.isEmpty()) return -1L
        var hit = kfs[0].timeMs
        for (k in kfs) {
            if (k.timeMs <= now) hit = k.timeMs else break
        }
        return hit
    }

    /** 当前时刻的流速（关键帧里取最后一个 time<=now 的） */
    fun speedAt(now: Long): Float = speedTrack().speedAt(now)

    /** 某一时刻的透明度（关键帧间线性插值；没有关键帧就用整体 alpha）。
     *  **保留原值**：RPE 里负数 alpha 是"隐藏判定线和它的音符"的特殊值（Phira PE alpha extension）。 */
    fun alphaAt(now: Long): Float {
        val kfs = sortedKeyframes()
        if (kfs.isEmpty()) return alpha
        if (now <= kfs[0].timeMs) return kfs[0].alpha
        if (now >= kfs[kfs.size - 1].timeMs) return kfs[kfs.size - 1].alpha
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
        return a.alpha + (b.alpha - a.alpha) * t
    }

    /**
     * 某一时刻的缩放（RPE 的 scaleXEvents / scaleYEvents 逐事件插值）：
     * out[0] = scaleX，out[1] = scaleY。没有关键帧时用整体的 [scaleX] / [scaleY]。
     */
    fun scaleAt(now: Long, out: FloatArray) {
        val kfs = sortedKeyframes()
        if (kfs.isEmpty()) { out[0] = scaleX; out[1] = scaleY; return }
        if (now <= kfs[0].timeMs) { out[0] = kfs[0].scaleX; out[1] = kfs[0].scaleY; return }
        val lastKf = kfs[kfs.size - 1]
        if (now >= lastKf.timeMs) { out[0] = lastKf.scaleX; out[1] = lastKf.scaleY; return }
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
        out[0] = a.scaleX + (b.scaleX - a.scaleX) * t
        out[1] = a.scaleY + (b.scaleY - a.scaleY) * t
    }

    /** 某一时刻的 scaleX（界面读取用；热路径请用 [scaleAt] 复用数组） */
    fun scaleXAt(now: Long): Float {
        val kfs = sortedKeyframes()
        if (kfs.isEmpty() || now <= kfs[0].timeMs) return if (kfs.isEmpty()) scaleX else kfs[0].scaleX
        val lastKf = kfs[kfs.size - 1]
        if (now >= lastKf.timeMs) return lastKf.scaleX
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
        return a.scaleX + (b.scaleX - a.scaleX) * t
    }

    /** 某一时刻的 scaleY（判定线粗细用） */
    fun scaleYAt(now: Long): Float {
        val kfs = sortedKeyframes()
        if (kfs.isEmpty() || now <= kfs[0].timeMs) return if (kfs.isEmpty()) scaleY else kfs[0].scaleY
        val lastKf = kfs[kfs.size - 1]
        if (now >= lastKf.timeMs) return lastKf.scaleY
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
        return a.scaleY + (b.scaleY - a.scaleY) * t
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

    /** 打一个关键帧（同一时刻的旧帧会被替换）；流速/透明度/缩放默认取"此刻正在生效的值"，保证连续 */
    fun putKeyframe(
        t: Long, x: Float, y: Float, r: Float,
        sp: Float = speedAt(t),
        sx: Float = scaleXAt(t),
        sy: Float = scaleYAt(t),
        al: Float = alphaAt(t)
    ) {
        keyframes.removeAll { it.timeMs == t }
        // 以前这里 alpha 写死 1f：在淡入淡出的中间打一帧，那一帧会突然变成完全不透明，把渐变打断
        keyframes.add(LineKeyframe(t, x, y, r, clampLineSpeed(sp), al, sx, sy))
        invalidate()
    }

    /** 改某个关键帧的缩放（RPE scaleXEvents/scaleYEvents） */
    fun setKeyframeScale(t: Long, sx: Float, sy: Float): Boolean {
        val i = keyframes.indexOfFirst { it.timeMs == t }
        if (i < 0) return false
        keyframes[i] = keyframes[i].copy(scaleX = sx, scaleY = sy)
        invalidate()
        return true
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
        id, posPct, moveX, moveY, rotate, clampLineSpeed(speed), name, ArrayList(keyframes),
        fatherId, rotWithFather,
        ArrayList(ctrlYEvents), ArrayList(ctrlPosEvents), ArrayList(ctrlSizeEvents),
        ArrayList(ctrlAlphaEvents), ArrayList(inclineEvents),
        texture, lineText, scaleX, scaleY
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
        fatherId = s.fatherId
        rotWithFather = s.rotWithFather
        ctrlYEvents.clear(); ctrlYEvents.addAll(s.ctrlY)
        ctrlPosEvents.clear(); ctrlPosEvents.addAll(s.ctrlPos)
        ctrlSizeEvents.clear(); ctrlSizeEvents.addAll(s.ctrlSize)
        ctrlAlphaEvents.clear(); ctrlAlphaEvents.addAll(s.ctrlAlpha)
        inclineEvents.clear(); inclineEvents.addAll(s.incline)
        texture = s.texture
        lineText = s.lineText
        scaleX = s.scaleX
        scaleY = s.scaleY
        // texturePath 不进快照（本机路径，撤销不该动它）
        invalidate()
    }
}
