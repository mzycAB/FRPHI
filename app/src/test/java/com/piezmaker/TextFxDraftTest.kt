package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** 文字动画设置的草稿往返与撤销快照 */
class TextFxDraftTest {

    private fun textLine() = Line(7).apply {
        lineText = "演出字幕"
        textEnterFx = TextFx.TYPE
        textExitFx = TextFx.TYPE
        textEnterMs = 450L
        textExitMs = 800L
    }

    @Test
    fun `草稿LFX行往返不丢动画设置`() {
        val l = textLine()
        val text = DraftCodec.encode(listOf(l), emptyList())
        assertNotNull(DraftCodec.decode(text))
        val l2 = DraftCodec.decode(text)!!.lines.first { it.id == 7 }
        assertEquals(TextFx.TYPE, l2.textEnterFx)
        assertEquals(TextFx.TYPE, l2.textExitFx)
        assertEquals(450L, l2.textEnterMs)
        assertEquals(800L, l2.textExitMs)
        assertEquals("演出字幕", l2.lineText)
    }

    @Test
    fun `旧草稿没有LFX行时用默认值`() {
        // 手工模拟旧草稿：只有 L + LT，没有 LFX
        val old = DraftCodec.HEADER + "\nL 7 60 0.0 0.0 0.0 1.0 1.0\nLT 7 1.0 1.0 老字幕\n"
        val d = DraftCodec.decode(old)
        assertNotNull(d)
        val l = d!!.lines.first { it.id == 7 }
        assertEquals(TextFx.NONE, l.textEnterFx)
        assertEquals(TextFx.NONE, l.textExitFx)
        assertEquals(300L, l.textEnterMs)
        assertEquals(300L, l.textExitMs)
    }

    @Test
    fun `撤销快照能回退文字动画`() {
        val l = textLine()
        val snap = l.snapshot()
        l.textEnterFx = TextFx.FADE
        l.textExitFx = TextFx.FADE
        l.textEnterMs = 10L
        l.textExitMs = 20L
        l.restore(snap)
        assertEquals(TextFx.TYPE, l.textEnterFx)
        assertEquals(TextFx.TYPE, l.textExitFx)
        assertEquals(450L, l.textEnterMs)
        assertEquals(800L, l.textExitMs)
    }
}
