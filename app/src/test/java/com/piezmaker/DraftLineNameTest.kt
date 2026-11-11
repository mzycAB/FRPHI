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

    @Test
    fun `图片线和文字线_草稿往返不丢类型`() {
        val img = Line(10).apply {
            name = "line10"
            texture = "line_123.png"
            texturePath = "C:/随便的本地路径/line_123.png"   // 本机路径不写进草稿
            scaleX = 0.7f
            scaleY = 0.7f
            invalidate()
        }
        val txt = Line(11).apply {
            name = "line11"
            lineText = "Success!"                       // 文字含空格也要原样往返
            scaleX = 1f
            scaleY = 0.5f
            invalidate()
        }
        val plain = Line(12).apply { name = "line12"; invalidate() }

        val text = DraftCodec.encode(listOf(img, txt, plain), emptyList())
        assert(text.contains("LP 10")) { "图片线要写 LP 行（$text）" }
        assert(text.contains("LT 11")) { "文字线要写 LT 行（$text）" }

        val back = DraftCodec.decode(text)!!
        assertEquals(3, back.lines.size)
        val i = back.lines.first { it.id == 10 }
        assertEquals(LineKind.Image, i.kind)
        assertEquals("line_123.png", i.texture)
        assertEquals("", i.texturePath)                 // 本地路径不还原（加载时按 lineimg 目录拼）
        assertEquals(0.7f, i.scaleX, 1e-4f)
        val t = back.lines.first { it.id == 11 }
        assertEquals(LineKind.Text, t.kind)
        assertEquals("Success!", t.lineText)
        assertEquals(0.5f, t.scaleY, 1e-4f)
        val p = back.lines.first { it.id == 12 }
        assertEquals(LineKind.Normal, p.kind)
    }

    @Test
    fun `老草稿没有LP_LT行_线不会变成素材线`() {
        val text = "#FRPHI-DRAFT 3\nL 0 60 0 0 0 1\nLN 0 line\n"
        val back = DraftCodec.decode(text)!!
        assertEquals(1, back.lines.size)
        assertEquals(LineKind.Normal, back.lines[0].kind)
        assertEquals("", back.lines[0].texture)
        assertEquals("", back.lines[0].lineText)
    }
}
