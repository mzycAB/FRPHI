package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipCodecTest {

    @Test
    fun `往返_保留类型时间位置正反幽灵`() {
        val src = listOf(
            RecordedNote(1, 1000, 1000, 0.25f, 1, 0, above = true, fake = false),
            RecordedNote(2, 1500, 2500, -0.5f, 4, 0, above = false, fake = false),
            RecordedNote(3, 3000, 3000, 0.75f, 3, 0, above = true, fake = true)
        )
        val text = ClipCodec.encode(src)
        assertTrue(text.startsWith(ClipCodec.HEADER))
        val back = ClipCodec.decode(text)
        assertEquals(3, back.size)
        assertEquals(2, back[1].type)
        assertEquals(1500L, back[1].startMs)
        assertEquals(2500L, back[1].endMs)
        assertEquals(-0.5f, back[1].posX, 1e-6f)
        assertEquals(false, back[1].above)
        assertEquals(true, back[2].fake)
    }

    @Test
    fun `空文本或垃圾文本返回空列表`() {
        assertEquals(0, ClipCodec.decode("").size)
        assertEquals(0, ClipCodec.decode("hello world").size)
        assertEquals(0, ClipCodec.decode("#FRCLIP 1\nN 1 2 3").size)   // 字段不够
    }

    @Test
    fun `手写的文件也能读`() {
        val text = """
            #FRCLIP 1
            N 1 500 500 0 1 0 1
            N 4 700 700 0.5 1 0 2
        """.trimIndent()
        val list = ClipCodec.decode(text)
        assertEquals(2, list.size)
        assertEquals(4, list[1].type)
        assertEquals(2, list[1].pass)
    }
}
