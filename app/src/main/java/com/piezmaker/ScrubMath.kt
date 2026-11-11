package com.piezmaker

/**
 * 编辑模式「搓时间轴」的像素 ↔ 毫秒换算（纯 JVM，可单元测试）。
 *
 * 画面里音符的纵向位移：
 *   d = (H(now) − H(noteMs)) × 0.16 × (height/720) × timelineScale × note.speed
 *   H'(t) = 判定线瞬时流速 speedAt(t)
 * ⇒ 画面像素/毫秒 = 基础比例 × |speedAt(now)| × note.speed
 * ⇒ 想让「手指划 1px，画面动 1px」，dt 必须 = dy / (基础比例 × |speedAt|)，
 *   **不能只除基础比例**。
 *
 * ★ 血泪（2026-11-08 真机日志 `keyframe ... sp=10.000`）：
 *   只按基础比例算时，流速 10 的谱面上画面位移 = 手指 × 10，玩家看到的就是
 *   「拖一点点飞一大截 / 完全不跟手」，判定线编辑界面同样（共用同一标尺）。
 *   之前把「变速谱按自身流速走」当既有设计保留是错的：那个要防的只是
 *   **除数趋零**（官谱 0 / 0.001 定格段 1px 飞 10 秒），不是拒绝把 speedAt 乘进来。
 *
 * ★ 另一条血泪：以前给比例加 `coerceIn(0.004f, 0.16f)` 上限，高分屏/放大缩放时
 *   又和渲染脱钩（1080×2400 @scale=1 → 画面 3.33 倍）。上限一律不要。
 *
 * 结论：只夹**下限**（流速下限 + 最终 px/ms 下限），不夹上限；
 * 渲染和搓动共用 [pxPerMs] 与同一个 [effectiveSpeed]，结构上永不再脱钩。
 */
object ScrubMath {

    /** 最终搓动除数下限：0.004 px/ms ⇒ 1px 最多 250ms */
    const val MIN_PX_PER_MS = 0.004f

    /** 判定线瞬时流速下限：官谱 0 / 0.001 定格段不让除数趋零；**只夹下限，不夹上限** */
    const val MIN_SCRUB_SPEED = 0.5f

    /**
     * 屏幕像素 / 谱面毫秒的基础比例（不含判定线流速）。
     * ★ 渲染专用，**不夹下限**：timelineScale 能拉到 0.01，此时 base 本来就是 0.0016，
     * 抬到 0.004 会改变音符位置和 NoteFrustum 的剔除窗口（回归）。
     */
    fun pxPerMs(heightPx: Int, timelineScale: Float): Float =
        0.16f * (heightPx / 720f) * timelineScale

    /** 有效流速：非有限值退回 1；负流速只影响方向，取绝对值算幅度；只夹下限 */
    fun effectiveSpeed(lineSpeed: Float): Float {
        val s = if (lineSpeed.isFinite()) kotlin.math.abs(lineSpeed) else 1f
        return s.coerceAtLeast(MIN_SCRUB_SPEED)
    }

    /** 真实画面像素/毫秒（测试对照用；定格段不夹，如实反映画面几乎不动） */
    fun renderPxPerMs(heightPx: Int, timelineScale: Float, lineSpeed: Float): Float =
        pxPerMs(heightPx, timelineScale) *
            (if (lineSpeed.isFinite()) kotlin.math.abs(lineSpeed) else 1f)

    /** 搓动专用标尺：和渲染同一个比例，只夹下限防定格段爆炸 */
    fun scrubPxPerMs(heightPx: Int, timelineScale: Float, lineSpeed: Float = 1f): Float =
        (pxPerMs(heightPx, timelineScale) * effectiveSpeed(lineSpeed))
            .coerceAtLeast(MIN_PX_PER_MS)

    /** 手指位移 → 谱面时间 */
    fun msForPixels(dyPx: Float, heightPx: Int, timelineScale: Float, lineSpeed: Float = 1f): Float =
        dyPx / scrubPxPerMs(heightPx, timelineScale, lineSpeed)

    /** 谱面时间 → 画面位移（默认 note.speed = 1） */
    fun pixelsForMs(dtMs: Float, heightPx: Int, timelineScale: Float, lineSpeed: Float = 1f): Float =
        dtMs * renderPxPerMs(heightPx, timelineScale, lineSpeed)

    /**
     * 浮点余量累加器：每个 ACTION_MOVE 单独 `round().toInt()` 会反复丢掉小数，
     * 慢速拖动时画面明显跟不上手。不足 1ms 的余量留到下一个输入点。
     */
    class Accumulator {
        private var remainderMs = 0f

        /** 返回本段应累加的整数毫秒 */
        fun push(dyPx: Float, heightPx: Int, timelineScale: Float, lineSpeed: Float = 1f): Int {
            val exact = remainderMs + msForPixels(dyPx, heightPx, timelineScale, lineSpeed)
            val whole = Math.round(exact)
            remainderMs = exact - whole
            return whole
        }

        fun reset() { remainderMs = 0f }
        fun remainderMs(): Float = remainderMs
    }
}
