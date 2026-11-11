package com.piezmaker

/**
 * 噪域的**运行时**（打歌时用）：一块区域里叠噪点特效，手指碰到「亮着的」噪域就断触。
 *
 * 与 [NoiseArea] 的分工：
 *  - [NoiseArea] 只管「从 .pez 读出来、导出原样写回」；
 *  - 这里只管「这一帧那根手指算不算被噪域掐断」「这块区域怎么变形、该多亮」。
 *
 * 纯 JVM 逻辑，不碰 Android API，可以直接单测。
 *
 * ## 语义来源
 * Phigros 的噪域（第九章）是**判定机制**：手指进入亮着的噪域 → 该手指失效，直到松开前
 * 判不了任何音符，正在按的长条也会 Miss，同时伴随红色特效与音乐减音。
 * RPE 的 `blockAreaList`（Phira Pro / Re:PhiEdit Next）提供的是**区域 + 时间轴**，
 * 判定干扰这一层在 RPE 数据里没有（它只管视觉）。这里把两者合起来：
 *   区域与时间轴来自 RPE 数据 → 视觉与断触按 Phigros 的规则做。
 *
 * ## 变换
 * 一块噪域每帧可以平移 / 旋转 / 缩放。规则（与 Re:PhiEdit Next 的 NoiseAreaRuntime 一致）：
 *  1. 以区域中心为原点，先缩放、再旋转，最后**叠加**绝对位移
 *     （`moveXEvents` 只有相对中心的偏移量有意义：RPE 里它存的是绝对坐标，
 *      所以位移 = 当前值 − 中心 x）；
 *  2. 缩小到以中心为基准，不改变中心位置（便于谱师对齐）。
 */
class NoiseRuntime(val areas: List<NoiseArea>) {

    /**
     * 命检测用的时间索引。
     *
     * 手指每按一次都要判「落在哪块亮着的噪域里」。官谱一万四千多块时逐个判
     * `activeAt` 是每次按下都扫一万四，落指那一刻会明显卡。先用索引取出
     * 「此刻活着的」（实测通常个位数到几百块），再在里面做命中判定。
     *
     * 建一次就够：这个 Runtime 是按 [areas] 新建的（草稿载入 / 进打歌时重建），
     * 所以索引在构造里直接建好。
     */
    private val index = NoiseTimeIndex().also { it.build(areas) }

    /** 命中判定用的临时列表（复用，避免每次落指都 new） */
    private val hitCand = ArrayList<NoiseArea>(64)
    private val hitCandIdx = NoiseTimeIndex.IntList(64)

    /** 一根手指被噪域掐断的记录（用于画断触特效、以及松开时复原音量） */
    class Jam(val area: NoiseArea, val startedMs: Double)

    /** 一个区域在某一时刻的采样结果（绘制与命中检测共用） */
    class Sample(
        /** 是否在「亮着」的激活区间内（只有亮着的噪域才会掐断手指） */
        val active: Boolean,
        /** 是否在生命周期内（要画） */
        val alive: Boolean,
        /** 四个角（屏幕像素），顺序：左上、右上、右下、左下 */
        val xs: FloatArray,
        val ys: FloatArray,
        /** 区域中心（屏幕像素） */
        val cx: Float,
        val cy: Float,
        /** 亮度 0~1：激活时 1，预备闪烁时在 0~1 之间脉动 */
        val intensity: Float,
        /** 警示区：只闪不掐手（绘制时压暗一点做区分） */
        val warning: Boolean = false
    ) {
        /** 点是否落在四边形内（凸四边形 + 叉积同号；支持旋转后的区域） */
        fun contains(px: Float, py: Float): Boolean {
            var pos = false
            var neg = false
            for (i in 0 until 4) {
                val j = (i + 1) and 3
                val cross = (xs[j] - xs[i]) * (py - ys[i]) - (ys[j] - ys[i]) * (px - xs[i])
                if (cross > 0f) pos = true else if (cross < 0f) neg = true
                if (pos && neg) return false
            }
            return true
        }
    }

    // ---------------------------------------------------------------- 世界 → 屏幕

    /**
     * 世界坐标 → 屏幕像素。
     * RPE 世界：x ∈ [-675, 675]、y ∈ [-450, 450]（**y 向上为正**），对应
     * [chartW] × [chartH] 的 16:9 视口，屏幕原点在视口中心。
     */
    private fun sx(wx: Float, chartW: Float, screenW: Float): Float =
        screenW / 2f + wx * (chartW / 1350f)

    private fun sy(wy: Float, chartH: Float, screenH: Float): Float =
        screenH / 2f - wy * (chartH / 900f)

    // 四角相对中心的偏移是常量形状，只有缩放会变 ⇒ 缓存一份 4 元数组，别再每帧 new
    private val cornerX = FloatArray(4)
    private val cornerY = FloatArray(4)

    /** 在 [nowMs] 时刻采样一块噪域；不可见（生命周期外 / 被隐藏）返回 null */
    fun sample(a: NoiseArea, nowMs: Double, chartW: Float, chartH: Float, screenW: Float, screenH: Float): Sample? {
        if (!a.visible || !a.aliveAt(nowMs)) return null
        // 矩形关键帧（编辑过的噪域）优先；没有关键帧就是静态区域
        val rc = a.rectAt(nowMs)
        val l = rc[0]; val b = rc[1]; val r = rc[2]; val t = rc[3]
        val cxW = (l + r) / 2f
        val cyW = (b + t) / 2f
        // 事件值：没有轨道时退回默认（位移 = 中心自身 = 不移动；缩放 = 1；旋转 = 0）
        val mx = a.trackValue("moveXEvents", nowMs, cxW) - cxW
        val my = a.trackValue("moveYEvents", nowMs, cyW) - cyW
        val rot = a.trackValue("rotateEvents", nowMs, 0f)
        val scx = a.trackValue("scaleXEvents", nowMs, 1f)
        val scy = a.trackValue("scaleYEvents", nowMs, 1f)
        val rad = Math.toRadians(rot.toDouble())
        val cos = Math.cos(rad).toFloat()
        val sin = Math.sin(rad).toFloat()

        // 世界坐标里四个角（相对中心的偏移 × 缩放），再旋转、平移，最后换到屏幕
        cornerX[0] = l - cxW; cornerX[1] = r - cxW
        cornerX[2] = r - cxW; cornerX[3] = l - cxW
        cornerY[0] = t - cyW; cornerY[1] = t - cyW
        cornerY[2] = b - cyW; cornerY[3] = b - cyW
        val xs = FloatArray(4)
        val ys = FloatArray(4)
        for (i in 0 until 4) {
            val lx = cornerX[i] * scx
            val ly = cornerY[i] * scy
            // 旋转（世界 y 向上）：逆时针为正
            val wx = lx * cos - ly * sin + cxW + mx
            val wy = lx * sin + ly * cos + cyW + my
            xs[i] = sx(wx, chartW, screenW)
            ys[i] = sy(wy, chartH, screenH)
        }
        val ccx = sx(cxW + mx, chartW, screenW)
        val ccy = sy(cyW + my, chartH, screenH)
        val intensity = intensityAt(a, nowMs)
        return Sample(a.activeAt(nowMs), true, xs, ys, ccx, ccy, intensity, a.warning)
    }

    /**
     * 亮度：激活 = 1；激活前 [readyFlash] 秒内做一次「预备脉冲」（Phigros 那种要亮不亮）；
     * 其余时间 0（区域仍然画出来，但暗）。
     */
    fun intensityAt(a: NoiseArea, nowMs: Double): Float {
        if (a.activeAt(nowMs)) return 1f
        for (iv in a.intervals) {
            val rf = iv.readyFlash * 1000.0
            if (rf <= 0) continue
            val t0 = iv.startMs - rf
            if (nowMs >= t0 && nowMs < iv.startMs) {
                val p = ((nowMs - t0) / rf).toFloat().coerceIn(0f, 1f)
                // 两次脉冲：(1 + cos(2π × 3p)) / 2 → 0~1 之间起伏三次
                return ((1f + Math.cos(p * Math.PI * 6.0).toFloat()) / 2f) * 0.6f
            }
        }
        return 0f
    }

    /**
     * 屏幕点是否落在某块「亮着」的噪域里；返回那块噪域（没命中返回 null）。
     *
     * 官谱噪域能有一万多块，手指每按下一次都全量 sample 会明显卡。
     * 这里先用**粗包围盒**（未缩放未旋转的矩形，比实际区域偏大）快速排除，
     * 只有点落在粗框里的少数几块才做精确的四边形判定 —— 结果完全一致，
     * 因为粗框是实际区域的外接矩形，框外一定不在区域内。
     */
    fun jammedAt(
        px: Float, py: Float, nowMs: Double,
        chartW: Float, chartH: Float, screenW: Float, screenH: Float
    ): NoiseArea? {
        // 先用时间索引取出「此刻活着」的，再逐个判 —— 一万四千块的谱面只碰几十块
        index.query(nowMs, hitCand, hitCandIdx)
        for (a in hitCand) {
            if (!a.visible || a.warning) continue        // 隐藏 / 警示区不掐手
            if (!a.activeAt(nowMs)) continue
            val rc = a.rectAt(nowMs)
            val l = rc[0]; val b = rc[1]; val r = rc[2]; val t = rc[3]
            val cxW = (l + r) / 2f
            val cyW = (b + t) / 2f
            // 粗排除：按未变换的矩形判断（变换只有「以中心缩放 + 绕中心旋转 + 平移」，
            // 半径最大不超过原矩形对角线的一半，这里按对角线做保守外扩）
            val halfDiag = Math.hypot(
                ((r - l) / 2f).toDouble(),
                ((t - b) / 2f).toDouble()
            ).toFloat()
            // 平移量（事件值 − 中心），与 sample() 里的算法一致
            val mx = a.trackValue("moveXEvents", nowMs, cxW) - cxW
            val my = a.trackValue("moveYEvents", nowMs, cyW) - cyW
            val pcx = sx(cxW + mx, chartW, screenW)
            val pcy = sy(cyW + my, chartH, screenH)
            // 世界半径 → 屏幕：两轴缩放不同，取较大者（保守）
            val rScr = halfDiag * Math.max(chartW / 1350f, chartH / 900f)
            val dx = px - pcx
            val dy = py - pcy
            if (dx * dx + dy * dy > rScr * rScr) continue
            val s = sample(a, nowMs, chartW, chartH, screenW, screenH) ?: continue
            if (s.contains(px, py)) return a
        }
        return null
    }

    // ---------------------------------------------------------------- 手指状态机

    /** 每根手指的噪域状态：null = 正常；非 null = 被这块噪域掐着，直到抬起 */
    private val jammed = HashMap<Int, Jam>()

    /** 这一帧有没有新增断触（用来放音效、抖屏） */
    var newJams = 0
        private set

    /**
     * 每帧更新一根手指。
     * @return true = 这根手指此刻**失效**（不能判任何音符、正在按的长条要断）
     *
     * 规则（照 Phigros）：
     *  - 手指在亮着的噪域里 → 断触；**一旦断了，在抬起之前一直是断的**，
     *    哪怕手指又划出了噪域（这是 Phigros 的「松开前都无法判定」）；
     *  - 抬起后才恢复。
     */
    fun updateFinger(
        id: Int, down: Boolean, px: Float, py: Float, nowMs: Double,
        chartW: Float, chartH: Float, screenW: Float, screenH: Float
    ): Boolean {
        if (!down) {
            jammed.remove(id)
            return false
        }
        if (jammed.containsKey(id)) return true
        val a = jammedAt(px, py, nowMs, chartW, chartH, screenW, screenH) ?: return false
        jammed[id] = Jam(a, nowMs)
        newJams++
        return true
    }

    /** 清掉每帧计数（在每帧开头调） */
    fun beginFrame() { newJams = 0 }

    fun jammedCount(): Int = jammed.size

    fun clear() { jammed.clear() }
}
