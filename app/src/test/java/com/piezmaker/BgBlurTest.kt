package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 背景高斯模糊（3 次可分离盒式滤波）的数值性质回归。
 */
class BgBlurTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `常数图模糊后不变`() {
        val w = 16; val h = 16
        val px = IntArray(w * h) { argb(80, 160, 240) }
        BgBlur.blur(px, w, h, 3)
        for (p in px) assertEquals(argb(80, 160, 240), p)
    }

    @Test
    fun `模糊后整图通道均值守恒`() {
        // 高斯模糊是低通滤波，不该改变整图平均亮度
        val w = 32; val h = 32
        val seed = intArrayOf(argb(255, 0, 0), argb(0, 255, 0), argb(0, 0, 255), argb(255, 255, 0))
        val px = IntArray(w * h) { seed[it % seed.size] }
        val sumBefore = px.sumOf { ((it ushr 16) and 0xFF) + ((it ushr 8) and 0xFF) + (it and 0xFF) }
        BgBlur.blur(px, w, h, 4)
        val sumAfter = px.sumOf { ((it ushr 16) and 0xFF) + ((it ushr 8) and 0xFF) + (it and 0xFF) }
        // 滑动窗口均值 + 边缘延伸，总量近似守恒（每像素通道均值误差 < 2）
        assertEquals(sumBefore.toDouble(), sumAfter.toDouble(), (w * h * 3 * 2).toDouble())
    }

    @Test
    fun `锐利边缘被抹平`() {
        // 左半黑右半白：模糊后中间出现灰过渡带，且不再有纯黑/纯白像素贴着边界
        val w = 32; val h = 8
        val px = IntArray(w * h) { if (it % w < w / 2) argb(0, 0, 0) else argb(255, 255, 255) }
        BgBlur.blur(px, w, h, 4)
        val midCol = (w / 2 - 1) + 1 * w   // 边界右侧第一列
        val v = (px[midCol] ushr 16) and 0xFF
        assertTrue("边界处应该是灰色过渡（实测 $v）", v in 40..215)
    }

    @Test
    fun `半径至少为1_不崩`() {
        val w = 4; val h = 4
        val px = IntArray(w * h) { argb(10, 20, 30) }
        BgBlur.blur(px, w, h, 0)
        assertEquals(w * h, px.size)
    }
}
