package com.piezmaker

/**
 * 文字线的入场 / 出场动画计算（纯 JVM，可单测；渲染路径零分配）。
 *
 * 入场：渐显 = alpha 0→1；打字 = 逐字显示。
 * 出场：渐隐 = alpha 1→0；打字 = 逐字退去。
 * 区间由文字事件（textEvents）给出；没有事件时用整首谱面。
 */
object TextFx {

    /** 无入场/出场特效（默认） */
    const val NONE = 0
    /** 渐显 / 渐隐 */
    const val FADE = 1
    /** 打字：逐字显示 / 逐字退去 */
    const val TYPE = 2

    private const val MIN_FX = 0
    private const val MAX_FX = TYPE

    /**
     * @param out 复用的输出数组，out[0] = 整体 alpha（0..1）
     * @param start/end 文字显示区间（毫秒）
     * @return 应显示的字符数（0..textLen），配 `Canvas.drawText(text, 0, shown, ...)` 用。
     *         用字符数而不是 substring，帧循环里不产生垃圾。
     */
    fun state(
        enterFx: Int, exitFx: Int, enterMs: Long, exitMs: Long,
        now: Long, start: Long, end: Long, textLen: Int,
        out: FloatArray
    ): Int {
        out[0] = 1f
        if (textLen <= 0) return 0
        if (now < start || now > end) {
            out[0] = 0f
            return 0
        }
        val span = (end - start).coerceAtLeast(1L)
        val eIn = if (enterFx == NONE) 0L else enterMs.coerceIn(0L, span)
        val eOut = if (exitFx == NONE) 0L else exitMs.coerceIn(0L, span)

        // 入场优先：区间重叠时先按入场算，避免两套特效互相打架
        if (eIn > 0L && now < start + eIn) {
            val t = (now - start).toFloat() / eIn
            if (enterFx == TYPE) {
                return (textLen * t).toInt().coerceIn(0, textLen)
            }
            if (enterFx == FADE) {
                out[0] = t.coerceIn(0f, 1f)
            }
            return textLen
        }
        if (eOut > 0L && now > end - eOut) {
            val t = (end - now).toFloat() / eOut
            if (exitFx == TYPE) {
                return (textLen * t).toInt().coerceIn(0, textLen)
            }
            if (exitFx == FADE) {
                out[0] = t.coerceIn(0f, 1f)
            }
            return textLen
        }
        return textLen
    }

    /** 0..2，防止草稿/外部数据塞进非法特效值 */
    fun clampFx(v: Int): Int = v.coerceIn(MIN_FX, MAX_FX)
}
