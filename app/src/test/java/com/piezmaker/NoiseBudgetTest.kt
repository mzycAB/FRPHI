package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Test

/** 打歌噪域：块数 + 累计屏幕面积双重限流 */
class NoiseBudgetTest {

    private fun rect(l: Float, t: Float, r: Float, b: Float) = floatArrayOf(l, t, r, b)

    @Test
    fun `小块很多时受块数上限`() {
        val rects = (0 until 100).map { rect(0f, 0f, 10f, 10f) }
        assertEquals(12, NoiseBudget.limit(rects, 360f, 780f, 12, 1.5f))
    }

    @Test
    fun `一块铺满屏时面积预算立刻收紧`() {
        val full = rect(0f, 0f, 360f, 780f)
        // 第一块就吃掉 1 屏；1.5 屏预算下第二块仍能进，第三块被挡
        assertEquals(2, NoiseBudget.limit(listOf(full, full, full), 360f, 780f, 12, 1.5f))
        // 预算 0.5 屏：只允许 1 块（保底，不能一块都不画）
        assertEquals(1, NoiseBudget.limit(listOf(full, full, full), 360f, 780f, 12, 0.5f))
    }

    @Test
    fun `屏幕外已裁剪成零面积时不过度消耗预算`() {
        val zero = rect(0f, 0f, 0f, 0f)
        assertEquals(12, NoiseBudget.limit(List(12) { zero }, 360f, 780f, 12, 1.5f))
    }

    @Test
    fun `空列表与非法参数`() {
        assertEquals(0, NoiseBudget.limit(emptyList(), 360f, 780f, 12, 1.5f))
        assertEquals(0, NoiseBudget.limit(listOf(rect(0f, 0f, 10f, 10f)), 360f, 780f, 0, 1.5f))
        assertEquals(0, NoiseBudget.limit(listOf(rect(0f, 0f, 10f, 10f)), 0f, 780f, 12, 1.5f))
    }

    @Test
    fun `先画的是前缀_亮着的优先不变`() {
        val small = rect(0f, 0f, 20f, 20f)
        val huge = rect(0f, 0f, 360f, 780f)
        // 小在前 → 12 块都能画；大在前 → 只画到面积耗尽
        assertEquals(12, NoiseBudget.limit(List(12) { small }, 360f, 780f, 12, 1.5f))
        assertEquals(2, NoiseBudget.limit(listOf(huge, huge, huge, small), 360f, 780f, 12, 1.5f))
    }
}
