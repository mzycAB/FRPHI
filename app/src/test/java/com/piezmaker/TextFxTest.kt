package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Test

/** 文字线入场 / 出场动画 */
class TextFxTest {

    private val out = FloatArray(1)
    private fun st(
        now: Long, enterFx: Int = TextFx.FADE, exitFx: Int = TextFx.FADE,
        enterMs: Long = 1000L, exitMs: Long = 1000L,
        start: Long = 0L, end: Long = 10000L, len: Int = 10
    ): Pair<Int, Float> {
        val n = TextFx.state(enterFx, exitFx, enterMs, exitMs, now, start, end, len, out)
        return n to out[0]
    }

    @Test
    fun `区间外不显示`() {
        assertEquals(0 to 0f, st(-1))
        assertEquals(0 to 0f, st(10001))
    }

    @Test
    fun `渐显_透明度线性上升`() {
        assertEquals(10 to 0f, st(0))
        assertEquals(10 to 0.5f, st(500))
        assertEquals(10 to 1f, st(1000))
    }

    @Test
    fun `打字入场_逐字显示`() {
        assertEquals(0, st(0, enterFx = TextFx.TYPE).first)
        assertEquals(5, st(500, enterFx = TextFx.TYPE).first)
        assertEquals(10, st(1000, enterFx = TextFx.TYPE).first)
        assertEquals(1f, st(500, enterFx = TextFx.TYPE).second)
    }

    @Test
    fun `渐隐_透明度线性下降`() {
        assertEquals(10 to 1f, st(9000))
        assertEquals(10 to 0.5f, st(9500))
        assertEquals(10 to 0f, st(10000))
    }

    @Test
    fun `打字出场_逐字退去`() {
        assertEquals(10, st(9000, exitFx = TextFx.TYPE).first)
        assertEquals(5, st(9500, exitFx = TextFx.TYPE).first)
        assertEquals(0, st(10000, exitFx = TextFx.TYPE).first)
        assertEquals(1f, st(9500, exitFx = TextFx.TYPE).second)
    }

    @Test
    fun `无特效直接完整显示_默认值`() {
        assertEquals(10 to 1f, st(0, enterFx = TextFx.NONE))
        assertEquals(10 to 1f, st(500, enterFx = TextFx.NONE))
        assertEquals(10 to 1f, st(9500, exitFx = TextFx.NONE))
        assertEquals(10 to 1f, st(10000, enterFx = TextFx.NONE, exitFx = TextFx.NONE))
        assertEquals(0 to 0f, st(-1, enterFx = TextFx.NONE, exitFx = TextFx.NONE))
    }

    @Test
    fun `特效值域枚举`() {
        assertEquals(0, TextFx.NONE)
        assertEquals(1, TextFx.FADE)
        assertEquals(2, TextFx.TYPE)
        assertEquals(0, TextFx.clampFx(-5))
        assertEquals(2, TextFx.clampFx(99))
    }

    @Test
    fun `零时长等于没有特效`() {
        assertEquals(10 to 1f, st(10, enterMs = 0L, exitMs = 0L))
        assertEquals(10 to 1f, st(9990, enterMs = 0L, exitMs = 0L))
    }

    @Test
    fun `特效时长超过显示区间时夹到区间_不会互相吞掉`() {
        val r = st(500, enterMs = 50000L, exitMs = 50000L, start = 0L, end = 1000L)
        assertEquals(10, r.first)
        assertEquals(0.5f, r.second)
    }

    @Test
    fun `区间重叠时入场优先`() {
        // 700ms 同时落在入场(0..800)与出场(200..1000)区间，应以入场为准
        val r = st(700, enterMs = 800L, exitMs = 800L, start = 0L, end = 1000L)
        assertEquals(10, r.first)
        assertEquals(700f / 800f, r.second, 0.001f)
    }

    @Test
    fun `空文字与非零起点`() {
        assertEquals(0, st(500, len = 0).first)
        // 2500ms 落在非零起点区间的入场段内 → 半透明
        assertEquals(10 to 0.5f, st(2500, start = 2000L, end = 12000L))
        // 区间中段 → 完整显示
        assertEquals(10 to 1f, st(5000, start = 2000L, end = 12000L))
    }
}
