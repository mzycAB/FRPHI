package com.piezmaker

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 判定线命名规则回归：判定线 / 图片线 / 文字线**各自独立编号**（用户点名）——
 * 已有"判定线 1"不能让新建的图片线变成"图片线 2"。
 * 以及 zOrder（图层序）的导入 / 草稿 / 导出无损保留。
 */
class LineNamesZOrderTest {

    // ------------------------------------------------------------ 命名规则

    private fun line(name: String, kind: LineKind, texture: String = "line.png", lineText: String = "") =
        Line(0).apply { this.name = name; this.texture = texture; this.lineText = lineText }

    @Test
    fun `判定线1不影响图片线从1开始`() {
        val lines = listOf(line("判定线 1", LineKind.Normal), line("判定线 2", LineKind.Normal))
        assertEquals("图片线 1", LineNames.next(lines, LineKind.Image))
        assertEquals("文字线 1", LineNames.next(lines, LineKind.Text))
    }

    @Test
    fun `各类独立递增_已建线的名字不重排`() {
        val lines = mutableListOf(
            line("判定线 1", LineKind.Normal),
            line("图片线 1", LineKind.Image, texture = "a.png"),
            line("图片线 2", LineKind.Image, texture = "b.png"),
            line("判定线 3", LineKind.Normal)
        )
        assertEquals("图片线 3", LineNames.next(lines, LineKind.Image))
        lines.removeAt(2)   // 删掉图片线 2：已建线的名字不动（图片线 1 还是图片线 1），
        assertEquals("图片线 2", LineNames.next(lines, LineKind.Image))   // 新线复用空号
        lines.removeAt(1)
        assertEquals("图片线 1", LineNames.next(lines, LineKind.Image))
    }

    @Test
    fun `用户自命名不参与计数_空表从1开始`() {
        val lines = listOf(line("我的封面", LineKind.Image, texture = "a.png"))
        assertEquals("图片线 1", LineNames.next(lines, LineKind.Image))
        assertEquals("判定线 1", LineNames.next(emptyList(), LineKind.Normal))
    }

    // ------------------------------------------------------------ zOrder 无损保留

    private fun chartWithZOrder(z0: Int, z1: Int): JSONObject {
        fun ln(id: Int, z: Int) = JSONObject()
            .put("Name", "L$id").put("Texture", "line.png")
            .put("eventLayers", JSONArray())
            .put("notes", JSONArray())
            .put("zOrder", z)
        return JSONObject()
            .put("META", JSONObject().put("name", "z").put("offset", 0))
            .put("BPMList", JSONArray().put(JSONObject().put("bpm", 120.0).put("startTime", JSONArray().put(0).put(0).put(1))))
            .put("judgeLineList", JSONArray().put(ln(0, z0)).put(ln(1, z1)))
    }

    @Test
    fun `zOrder导入草稿往返保留`() {
        val res = PezImporter.importText(chartWithZOrder(5, -3).toString(), "z")
        assertEquals(5, res.lines[0].zOrder)
        assertEquals(-3, res.lines[1].zOrder)

        val text = DraftCodec.encode(res.lines.map { l ->
            Line(l.id).apply {
                name = l.name
                zOrder = l.zOrder
                texture = l.texture
            }
        }, emptyList())
        val back = DraftCodec.decode(text) ?: error("decode 失败")
        assertEquals(5, back.lines.first { it.id == 0 }.zOrder)
        assertEquals(-3, back.lines.first { it.id == 1 }.zOrder)
    }

    @Test
    fun `导出写回真实zOrder而不是0`() {
        val l = Line(0).apply {
            name = "t"; texture = ""; zOrder = 7
        }
        val json = PezExporter.buildChartJsonCore(
            name = "z", level = "", charter = "", composer = "",
            bpm = 120.0, delayMs = 0, fallbackPosPct = 60,
            lines = listOf(l), notes = emptyList(), musicName = "m.ogg", imageName = "bg.png"
        )
        assertTrue("导出应包含真实 zOrder：$json", json.contains("\"zOrder\": 7"))
    }
}
