package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 延迟校准的匹配与统计 */
class CalibMathTest {

    private fun tap(pos: Long) = CalibMath.mapTap(pos, 1600, 2000, 300, 600)

    @Test
    fun `点在滴上_偏差为0`() {
        val t = tap(1600)
        assertNotNull(t)
        assertEquals(1600L, t!!.dipPos)
        assertEquals(0L, t.delta)
    }

    @Test
    fun `晚一点也算这一滴`() {
        assertEquals(120L, tap(1720)!!.delta)
        assertEquals(-80L, tap(1520)!!.delta)
        // 第二轮：滴在 3600
        assertEquals(3600L, tap(3620)!!.dipPos)
        assertEquals(20L, tap(3620)!!.delta)
    }

    @Test
    fun `点在前面的嗒上_不计数`() {
        // 嗒在 0/400/800/1200，离滴(1600)分别是 -1600/-1200/-800/-400
        assertNull(tap(0))
        assertNull(tap(400))
        assertNull(tap(800))
        assertNull(tap(1200))
        // 1200 距离滴 -400，超出允许的 -300
        assertNull(tap(1150))
    }

    @Test
    fun `第一声滴之前的点击不计数`() {
        assertNull(tap(500))
        assertNull(tap(1299))     // 1600-300=1300 才刚开始算
        assertNotNull(tap(1300))
    }

    @Test
    fun `太晚的点击不计数`() {
        // 2200 之后最近的是下一滴 3600，偏差 -1400 → 超出
        assertNull(tap(2300))
        assertNotNull(tap(2199))  // 偏差 +599，刚好在允许内
    }

    @Test
    fun `中位数不受单个点歪的影响`() {
        val good = listOf(100L, 105L, 110L, 95L, 102L, 108L, 99L, 101L, 97L, 103L)
        assertEquals(101L, CalibMath.median(good))
        // 平均值是 102，中位数 101；混进一个 -800 的脏数据后：
        val dirty = good.drop(1) + (-800L)
        val mean = dirty.sum() / dirty.size
        assertEquals(12L, mean)              // 平均值被拖偏 ~90ms
        assertEquals(101L, CalibMath.median(dirty))   // 中位数几乎不动（101.5 → 101）
    }

    @Test
    fun `空列表与偶数个样本`() {
        assertEquals(0L, CalibMath.median(emptyList()))
        assertEquals(15L, CalibMath.median(listOf(10L, 20L)))
        assertEquals(20L, CalibMath.median(listOf(30L, 10L, 20L)))
    }
}
