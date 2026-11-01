package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Test

/** 自动校准：只把"离粗线一格"的音符吸到粗线上 */
class CalibrateTest {

    private val cell = 125.0          // 120BPM / hLines=4 → 每格 125ms，每拍 500ms

    @Test
    fun `粗线后的第一格_回到这条粗线`() {
        assertEquals(0L, Calibrate.target(125L, cell, 4))
        assertEquals(500L, Calibrate.target(625L, cell, 4))
    }

    @Test
    fun `粗线前的第一格_推到下条粗线`() {
        assertEquals(500L, Calibrate.target(375L, cell, 4))
        assertEquals(1000L, Calibrate.target(875L, cell, 4))
    }

    @Test
    fun `粗线本身和中间格不动`() {
        assertEquals(-1L, Calibrate.target(0L, cell, 4))
        assertEquals(-1L, Calibrate.target(500L, cell, 4))
        assertEquals(-1L, Calibrate.target(250L, cell, 4))   // 每拍第 2 格，离两条粗线都 2 格
        assertEquals(-1L, Calibrate.target(750L, cell, 4))
    }

    @Test
    fun `每拍2格时_唯一的细线吸到前一条粗线`() {
        assertEquals(0L, Calibrate.target(250L, 250.0, 2))
        assertEquals(500L, Calibrate.target(750L, 250.0, 2))
    }

    @Test
    fun `每拍1格或异常参数不动`() {
        assertEquals(-1L, Calibrate.target(125L, cell, 1))
        assertEquals(-1L, Calibrate.target(125L, 0.0, 4))
        assertEquals(-1L, Calibrate.target(125L, Double.NaN, 4))
    }

    @Test
    fun `每拍3格_两条细线都吸附`() {
        val c = 500.0 / 3
        // 第 1 格（离前面粗线 1 格）→ 0
        assertEquals(0L, Calibrate.target(Math.round(c), c, 3))
        // 第 2 格（离下条粗线 1 格）→ 500
        assertEquals(500L, Calibrate.target(Math.round(c * 2), c, 3))
    }
}
