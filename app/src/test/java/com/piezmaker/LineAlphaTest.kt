package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Test

/** 判定线透明度：关键帧插值 + 草稿往返 + 流速 0 允许 */
class LineAlphaTest {

    @Test
    fun `没有关键帧时用整体透明度`() {
        val l = Line(0).apply { alpha = 0.4f }
        assertEquals(0.4f, l.alphaAt(1234L), 1e-6f)
    }

    @Test
    fun `关键帧之间线性插值`() {
        val l = Line(0).apply {
            alpha = 1f
            keyframes.add(LineKeyframe(1000, 0f, 0f, 0f, 1f, 0f))
            keyframes.add(LineKeyframe(3000, 0f, 0f, 0f, 1f, 1f))
            invalidate()
        }
        assertEquals(0f, l.alphaAt(1000L), 1e-6f)
        assertEquals(0.5f, l.alphaAt(2000L), 1e-6f)
        assertEquals(1f, l.alphaAt(3000L), 1e-6f)
        assertEquals(1f, l.alphaAt(9999L), 1e-6f)   // 超出末端取最后一帧
    }

    @Test
    fun `草稿能存下透明度和流速0`() {
        val l = Line(0).apply {
            speed = 0f
            alpha = 0.25f
            keyframes.add(LineKeyframe(500, 0f, 0f, 0f, 0f, 0.5f))
            invalidate()
        }
        val back = DraftCodec.decode(DraftCodec.encode(listOf(l), emptyList()))!!
        assertEquals(0f, back.lines[0].speed, 1e-6f)
        assertEquals(0.25f, back.lines[0].alpha, 1e-6f)
        assertEquals(0f, back.lines[0].keyframes[0].speed, 1e-6f)
        assertEquals(0.5f, back.lines[0].keyframes[0].alpha, 1e-6f)
    }
}
