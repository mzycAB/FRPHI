package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 判定线缩放的「逐关键帧」语义：
 *  - 改缩放只改落点那一帧，不能把每一帧都刷成同一个值
 *  - 落点 = 选中/刚打的帧，否则是此刻正在生效的那一帧
 *  - 打关键帧不能把别的通道（透明度 / 缩放）打回默认值
 */
class LineScaleKeyframeTest {

    private fun twoFrames() = Line(0).apply {
        keyframes.add(LineKeyframe(1000, 0f, 0f, 0f, 1f, 1f, 1f, 1f))
        keyframes.add(LineKeyframe(3000, 0f, 0f, 0f, 1f, 1f, 1f, 1f))
        invalidate()
    }

    @Test
    fun `此刻生效的关键帧`() {
        assertEquals(-1L, Line(1).activeKeyframeTimeAt(500L))     // 没有关键帧
        val l = twoFrames()
        assertEquals(1000L, l.activeKeyframeTimeAt(0L))           // 比第一帧还早 → 第一帧
        assertEquals(1000L, l.activeKeyframeTimeAt(1000L))
        assertEquals(1000L, l.activeKeyframeTimeAt(2999L))
        assertEquals(3000L, l.activeKeyframeTimeAt(3000L))
        assertEquals(3000L, l.activeKeyframeTimeAt(99999L))       // 超出末端 → 最后一帧
    }

    @Test
    fun `改一帧缩放不影响其它帧`() {
        val l = twoFrames()
        assertTrue(l.setKeyframeScale(3000L, 2f, 0.5f))
        val out = FloatArray(2)
        l.scaleAt(1000L, out)
        assertEquals(1f, out[0], 1e-6f)
        assertEquals(1f, out[1], 1e-6f)
        l.scaleAt(3000L, out)
        assertEquals(2f, out[0], 1e-6f)
        assertEquals(0.5f, out[1], 1e-6f)
        l.scaleAt(2000L, out)                                     // 中间线性过渡，不是整段拉平
        assertEquals(1.5f, out[0], 1e-6f)
        assertEquals(0.75f, out[1], 1e-6f)
        assertEquals(1f, l.keyframes[0].scaleX, 1e-6f)
        assertEquals(1f, l.keyframes[0].scaleY, 1e-6f)
    }

    @Test
    fun `打关键帧保留此刻的透明度和缩放`() {
        val l = Line(0).apply {
            keyframes.add(LineKeyframe(0, 0f, 0f, 0f, 1f, 0f, 0.4f, 0.4f))
            keyframes.add(LineKeyframe(4000, 0f, 0f, 0f, 1f, 1f, 1.6f, 1.6f))
            invalidate()
        }
        l.putKeyframe(2000L, 0f, 0f, 0f)
        val mid = l.keyframes.first { it.timeMs == 2000L }
        assertEquals(0.5f, mid.alpha, 1e-6f)                      // 以前写死 1f，渐变被打断
        assertEquals(1f, mid.scaleX, 1e-6f)                       // 0.4 → 1.6 的中点
        assertEquals(1f, mid.scaleY, 1e-6f)
        assertEquals(0.5f, l.alphaAt(2000L), 1e-6f)
    }
}
