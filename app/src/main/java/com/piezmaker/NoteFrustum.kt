package com.piezmaker

/**
 * 音符的**视锥剔除时间窗**：算出「这条判定线在 [now] 时刻，哪些开始时间的音符
 * 有可能出现在屏幕上」，供 [RecordView.drawNotesOfLine] 二分裁剪。
 *
 * ## 为什么要算时间窗，而不是直接算坐标
 * 音符在屏幕上的纵向位置不是 `(now - noteTime) × 速度` —— 判定线可以**变速**
 * （关键帧之间流速线性过渡），距离是对流速的积分 `∫speed dt`；而且还有 ctrl
 * 轨道按距离调制、yOffset 平移。要判断「这个音符在不在屏幕上」，最直接的办法是对
 * 每个音符反解时间（`timeForDistPx` 里那个 3 次迭代）—— 那正是现在的开销。
 *
 * 反过来算就便宜得多：`∫speed dt` 是**单调**的（流速非负），所以给定 `now` 时
 * 「高度 → 时间」是单调映射，可以把屏幕上下界各反解一次得到 `[t0, t1]`，
 * 然后按 `startMs` 二分出落在窗内的音符。**每条线每帧只反解 2 次**，
 * 与这条线上有多少音符无关。
 *
 * ## 安全性（宁可多画，绝不漏画）
 * 反解出来的窗口按下面几条**放宽**，保证窗外的音符一定在屏幕外：
 *  1. 视野高度取 `screenH × 2.25`（长条尾、贴图高度、旋转后的外接框都在里面）；
 *  2. ctrl 越小距离越大 —— 有 ctrl 轨道的线，把视野按 ctrl 的下界 [CTRL_MIN] 缩小；
 *  3. `speed` 取该线全谱的**最小正流速**（距离最大 ⇒ 窗口最大）；
 *  4. 窗两端各加 [SAFETY_MS] 固定余量。
 *
 * 真正上屏前，[RecordView.drawNoteAt] 里原有的精确纵向判定仍然保留，
 * 所以时间窗只需要「不漏」，不需要「准到像素」。
 *
 * 纯 JVM，不碰 Android API，可单测。
 */
object NoteFrustum {

    /**
     * ctrl 轨道（`yControl` 等）的下界。ctrl 把「离判定线的距离」乘上这个系数，
     * 小于 1 时距离变大 ⇒ 屏幕上能看到更远的音符，窗口要按它放大。
     * 与其把 ctrl 轨道整个反解一遍，不如按地板值放大 —— ctrl 恒为 0 的退化情况
     * 由调用方「窗口放开成整条线」兜住。
     */
    const val CTRL_MIN = 0.05f

    /** 窗口两端额外放宽的毫秒数（贴图高度、旋转、yOffset、浮点误差都在这里兜） */
    const val SAFETY_MS = 900.0

    /** 视野 = 屏幕高度 × 这个系数（上下各一屏，再多 1/4 屏留给旋转和贴图） */
    const val REACH_FACTOR = 2.25f

    /** 时间上界（约 23 天）。谱面时长不会超过它，用它表示「整条线全画」 */
    const val MAX_TIME_MS = 2_000_000_000L

    /**
     * `H` 跨度超过这个值就直接放弃剔除、放开成全谱。
     *
     * 定标：演奏视角（scale 0.1、屏高 1920）的 span 约 1.0e5，编辑器默认也在这个量级；
     * 只要 span 到了 1e7（≈ 演奏视角的 100 倍、折合流速 1 下也超过 2.7 小时），
     * 整首谱一定全在一屏里，算窗口纯属浪费，而且这个量级下反解会逼近 Double 上限、结果不可靠。
     */
    const val SPAN_GIVE_UP = 1.0e7

    /**
     * 一条线在 [nowMs] 时刻的可见时间窗 `[t0, t1]`。
     *
     * 返回 null 表示「这条线的流速退化（0 或非有限）」，此时所有音符都堆在一起，
     * 调用方应该照旧全画，不要裁剪。
     *
     * @param track 该线的流速轨迹
     * @param basePxPerMs 1ms 走多少像素（不含该线流速，即 `basePxPerMs()`）
     * @param screenH 屏幕高度（px）
     * @param minNoteSpeed 这条线上**音符**流速的最小正值（没有正的就传 1）。
     *        ★ 绝不是判定线自己的流速 `Line.minPositiveSpeed()`：距离公式里乘的是
     *        `note.speed`，判定线流速已经积进 `H` 了。传错会让窗口小 `线速/音符速` 倍，
     *        屏幕上沿的音符被整片剔掉（2026-11-07 血泪：线速 10 / 音符速 1 ⇒ 窗口只剩 1/10）。
     * @param nowMs 当前时刻（毫秒）
     * @param hasCtrl 这条线上有没有改纵向距离的 ctrl 轨道（`yControl`）。
     *        没有时按 ctrl = 1 精确算；有的话没法反解，只能按 [CTRL_MIN] 把视野放大 20 倍
     *        —— 剔除变弱，但仍然比全画省。
     */
    fun window(
        track: SpeedTrack,
        basePxPerMs: Float,
        screenH: Float,
        minNoteSpeed: Float,
        nowMs: Long,
        hasCtrl: Boolean = false,
        maxAbsYOffset: Float = 0f
    ): LongRange? {
        val base = basePxPerMs.toDouble()
        if (!base.isFinite() || base <= 1e-9) return null
        val spd = if (minNoteSpeed.isFinite() && minNoteSpeed > 1e-4f) minNoteSpeed.toDouble() else 1.0
        val reachPx = (screenH * REACH_FACTOR).toDouble()
        // 距离 = (H(now) − H(t)) × base × speed × ctrl  − yOffset × speed × H/900
        // 要 |距离| ≤ reachPx。ctrl 是作用在「距离」上的乘数：
        //   • ctrl ≥ 1 时距离只会**变大**，比 ctrl=1 更早出屏 ⇒ 按 ctrl=1 算一定够用（安全）；
        //   • ctrl < 1 时距离变小、能看到更远 ⇒ 必须按 ctrl 的下界 CTRL_MIN **放大** H_span。
        // 所以有 ctrl 轨道时 H_span = reachPx / (CTRL_MIN × base × speed)，
        // 比没有 ctrl 时宽 20 倍，但绝不会漏（窗口再宽也只是少省一点，绝不会少画）。
        val span = if (hasCtrl) reachPx / (CTRL_MIN * base * spd) else reachPx / (base * spd)
        if (!span.isFinite() || span <= 0.0) return null

        // ★ yOffset 是「沿下落方向的固定像素平移」，和流速无关，所以缩放拉大（窗口只有 0.1 秒）
        //   时它能把音符推出窗口而屏幕上看得到。把它折算成额外的 H 跨度再加进去。
        //   距离里 yOffset 项是 yOffset × speed × H/900 像素 ⇒ 等价于 H 平移 yOffset×H/900。
        val yOffH = if (maxAbsYOffset.isFinite() && maxAbsYOffset > 0f)
            maxAbsYOffset.toDouble() * (screenH / 900.0) else 0.0
        // 音符自己的 speed 也可能很大（官谱 speed=Infinity 夹到 50）⇒ 一并乘上 spd 的地板
        val spanAll = span + yOffH / spd

        // ★ 缩放可以拉到很小（编辑器 timelineScale 允许 0.01）⇒ base 会小到 0.004px/ms，
        //   span 能到几亿。这种跨度下反解没有意义（反正整谱都在一屏里），直接放开。
        //   同时也能挡住 hNow+span 溢出成 Infinity 之后 timeAtHeight 给出的假值。
        if (spanAll >= SPAN_GIVE_UP) return 0L..MAX_TIME_MS

        // H 单调不减 ⇒ 高度区间反解成时间区间。
        // ★ 官谱大量负流速（判定线倒走）⇒ H 先减后增，「高度→时间」不唯一，反解出的窗口是假的
        //   ⇒ 必须整条放开（和零流速同一待遇）。这种线音符都很少，全画没有性能问题。
        if (!track.monotonic) return 0L..MAX_TIME_MS
        // 先确认「窗口上界能不能落在真实的 H 上」：整条线流速都是 0 时 H 恒等于 hNow，
        // 任何 h > 0 都反解不到真实时间段，此时必须放开成全谱 ——
        // 否则 timeAtHeight 会退化成 0，返回一个「0..900」的假窗口，把整条线的音符全剔掉。
        if (!(track.heightAt(Long.MAX_VALUE) > 0.0)) return 0L..MAX_TIME_MS

        val hNow = track.heightAt(nowMs)
        val hLo = hNow - spanAll
        // 上界已经超过「流速 1 走满 MAX_TIME_MS」的高度 ⇒ 往后全都是可见区间，
        // 不用真反解（反解可能落到 Double 上限附近，结果没意义）
        val hMax = track.heightAt(MAX_TIME_MS)
        if (hNow + spanAll >= hMax) {
            val a0 = Math.floor(track.timeAtHeight(hLo)).toLong() - SAFETY_MS.toLong()
            return (if (a0 < 0L) 0L else a0)..MAX_TIME_MS
        }
        val tLo = track.timeAtHeight(hLo)
        val tHi = track.timeAtHeight(hNow + spanAll)
        if (!tLo.isFinite() || !tHi.isFinite()) return null

        var a = Math.floor(minOf(tLo, tHi)).toLong() - SAFETY_MS.toLong()
        var b = Math.ceil(maxOf(tLo, tHi)).toLong() + SAFETY_MS.toLong()
        // heightAt(t<=0) 恒等于「第一段的外推」，所以时间下界夹到 0；
        // 上界夹到 MAX_TIME_MS 防溢出
        if (a < 0L) a = 0L
        if (b > MAX_TIME_MS) b = MAX_TIME_MS
        if (b < a) b = a
        return a..b
    }

    /**
     * 在按 `startMs` 升序排列的下标数组 [arr] 里，二分出第一个 `startMs >= t` 的位置。
     * 返回 `0..arr.size`。
     */
    fun lowerBound(arr: IntArray, notes: List<RecordedNote>, t: Long): Int {
        var lo = 0
        var hi = arr.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (notes[arr[mid]].startMs < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * 二分出第一个 `startMs > t` 的位置（长条要按结束时间往后找，用这个）。
     * 返回 `0..arr.size`。
     */
    fun upperBound(arr: IntArray, notes: List<RecordedNote>, t: Long): Int {
        var lo = 0
        var hi = arr.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (notes[arr[mid]].startMs <= t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * 把 [arr] 里的音符下标按 `startMs` 升序原地排序（**稳定**：同时间按原下标）。
     *
     * 稳定是关键 —— `notes` 的下标还会被选中集、重叠标记等直接引用，
     * 排序只改「遍历顺序」，不改任何下标本身的含义。小数组插入排序，
     * 大数组用复用缓冲的归并（都不装箱）。
     */
    fun sortIdxByStart(arr: IntArray, notes: List<RecordedNote>) {
        if (arr.size <= 24) {
            for (i in 1 until arr.size) {
                val v = arr[i]
                val kv = notes[v].startMs
                var j = i - 1
                while (j >= 0 && (notes[arr[j]].startMs > kv || (notes[arr[j]].startMs == kv && arr[j] > v))) {
                    arr[j + 1] = arr[j]
                    j--
                }
                arr[j + 1] = v
            }
        } else {
            val buf = IntArray(arr.size)
            mergeSortIdx(arr, buf, notes, 0, arr.size)
        }
    }

    private fun mergeSortIdx(a: IntArray, buf: IntArray, notes: List<RecordedNote>, from: Int, to: Int) {
        if (to - from <= 1) return
        val mid = (from + to) ushr 1
        mergeSortIdx(a, buf, notes, from, mid)
        mergeSortIdx(a, buf, notes, mid, to)
        var i = from
        var j = mid
        var k = from
        while (i < mid && j < to) {
            val ci = notes[a[i]].startMs
            val cj = notes[a[j]].startMs
            buf[k++] = if (ci < cj || (ci == cj && a[i] < a[j])) a[i++] else a[j++]
        }
        while (i < mid) buf[k++] = a[i++]
        while (j < to) buf[k++] = a[j++]
        System.arraycopy(buf, from, a, from, to - from)
    }

    /**
     * 每条线上**最长的那根长条**有多长（毫秒）。
     *
     * 视锥剔除按 `startMs` 二分，但长条是「头部时间 → 尾部时间」的一段：
     * 头部早已出窗、尾巴还挂在屏幕上时必须照画。所以下界要往回退这么多。
     */
    fun maxHoldLenByLine(notes: List<RecordedNote>): HashMap<Int, Long> {
        val m = HashMap<Int, Long>(16)
        for (n in notes) {
            if (n.type != 2) continue
            val len = (n.endMs - n.startMs).coerceAtLeast(0L)
            val cur = m[n.lineId]
            if (cur == null || len > cur) m[n.lineId] = len
        }
        return m
    }
}
