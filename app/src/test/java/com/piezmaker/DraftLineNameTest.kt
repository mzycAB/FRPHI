package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Test

/** 判定线名字（RPE 的 Name）必须原样存进草稿再读出来，拖排序/重开都不能变 */
class DraftLineNameTest {

    private fun line(id: Int, name: String): Line = Line(id).apply {
        this.name = name
        posPct = 60
        speed = 1f
        invalidate()
    }

    @Test
    fun `名字_带空格也要原样往返`() {
        val lines = listOf(line(0, "line"), line(3, "spin left"), line(7, ""))
        val text = DraftCodec.encode(lines, emptyList())
        val back = DraftCodec.decode(text)!!
        assertEquals(3, back.lines.size)
        assertEquals("line", back.lines[0].name)
        assertEquals("spin left", back.lines[1].name)
        assertEquals("", back.lines[2].name)
    }

    @Test
    fun `没有LN行的老草稿_名字是空`() {
        val text = "#FRPHI-DRAFT 3\nL 0 60 0 0 0 1\nL 1 40 0 0 0 1\n"
        val back = DraftCodec.decode(text)!!
        assertEquals(2, back.lines.size)
        assertEquals("", back.lines[0].name)
        assertEquals("", back.lines[1].name)
    }
}
