package com.piezmaker

/**
 * 打歌时噪域「本帧画几块」的预算（纯 JVM，可单测）。
 *
 * 以前只按**块数**截断（MAX_NOISE_PLAY_DRAW=24）：一块铺满整屏和角落里
 * 一小块同样算 1，但前者要铺几千个像素格、后者几十个，开销差两个数量级。
 * 现在再加一条「累计屏幕覆盖面积」上限，只削减视觉 —— 判定与导出仍按全部区域。
 */
object NoiseBudget {

    /**
     * @param rects 每块在屏幕上的包围盒 [left, top, right, bottom]（已裁到屏幕内），
     *              顺序按「亮着的先画」排好。
     * @param maxCoverage 累计面积上限，按屏幕面积的倍数给（例如 1.5 = 最多 1.5 屏）。
     * @return 本帧应画的块数（前缀）。
     */
    fun limit(
        rects: List<FloatArray>,
        screenW: Float,
        screenH: Float,
        maxBlocks: Int,
        maxCoverage: Float
    ): Int {
        if (maxBlocks <= 0 || maxCoverage <= 0f || screenW <= 0f || screenH <= 0f) return 0
        val budget = screenW * screenH * maxCoverage
        var used = 0f
        var n = 0
        for (r in rects) {
            if (n >= maxBlocks || used >= budget) break
            val w = (r[2] - r[0]).coerceAtLeast(0f)
            val h = (r[3] - r[1]).coerceAtLeast(0f)
            used += w * h
            n++
        }
        return n
    }
}
