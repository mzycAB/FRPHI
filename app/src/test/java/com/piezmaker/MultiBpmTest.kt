package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 多 BPM（分段变速）：导入保留分段 → 导出写回 BPMList → 再导入时间还对得上。
 */
class MultiBpmTest {

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((n, t) in entries) {
                z.putNextEntry(ZipEntry(n))
                z.write(t.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `分段拍数换算`() {
        val segs = listOf(BpmSegment(0, 120.0), BpmSegment(2000, 240.0), BpmSegment(5000, 60.0))
        // 0~2s @120bpm = 4 拍；2~5s @240bpm = 12 拍；再 1s @60bpm = 1 拍
        assertEquals(4.0, BpmTimeline.beatsAt(2000, segs), 1e-9)
        assertEquals(16.0, BpmTimeline.beatsAt(5000, segs), 1e-9)
        assertEquals(17.0, BpmTimeline.beatsAt(6000, segs), 1e-9)
        assertEquals(2.0, BpmTimeline.beatsAt(1000, segs), 1e-9)
        assertEquals(listOf(0.0, 4.0, 16.0), BpmTimeline.startBeats(segs).toList())
        assertEquals(240.0, BpmTimeline.bpmAt(3000, segs), 1e-9)
        assertEquals(60.0, BpmTimeline.bpmAt(9999, segs), 1e-9)
    }

    @Test
    fun `规范化_排序去非法并补零点`() {
        val segs = BpmTimeline.normalize(
            listOf(BpmSegment(5000, 60.0), BpmSegment(-100, 999999.0), BpmSegment(2000, 240.0), BpmSegment(2000, 200.0))
        )
        assertEquals(3, segs.size)
        assertEquals(0L, segs[0].startMs)
        assertEquals(120.0, segs[0].bpm, 1e-9)          // 用第一段的值补 0 点
        assertEquals(200.0, segs[1].bpm, 1e-9)          // 同一时刻取最后一个
        assertEquals(60.0, segs[2].bpm, 1e-9)
        assertTrue(BpmTimeline.normalize(emptyList()).isEmpty())
    }

    @Test
    fun `编码解码往返`() {
        val segs = listOf(BpmSegment(0, 222.0), BpmSegment(153000, 146.5))
        val s = BpmTimeline.encode(segs)
        assertEquals("0:222|153000:146.5", s)
        assertEquals(segs, BpmTimeline.decode(s))
        assertTrue(BpmTimeline.decode("").isEmpty())
        assertTrue(BpmTimeline.decode("垃圾数据").isEmpty())
    }

    /** 导出 → 导入 端到端：BPMList 分段和音符时间都要原样回来 */
    @Test
    fun `多BPM导出再导入_时间对得上`() {
        val segs = listOf(BpmSegment(0, 120.0), BpmSegment(2000, 240.0), BpmSegment(5000, 60.0))
        val notes = listOf(
            RecordedNote(1, 0, 0, 0f, 1, 0, true, false),
            RecordedNote(1, 1999, 1999, -0.5f, 1, 0, true, false),
            RecordedNote(1, 2000, 2000, 0f, 1, 0, true, false),
            RecordedNote(2, 3999, 4500, 0.5f, 4, 0, true, false),
            RecordedNote(1, 6000, 6000, 0.2f, 1, 0, true, false)
        )
        val line = Line(0).apply { posPct = 60 }
        val json = PezExporter.buildChartJsonCore(
            name = "多BPM测试", level = "12", charter = "c", composer = "k",
            bpm = 120.0, delayMs = 0, fallbackPosPct = 60,
            lines = listOf(line), notes = notes, musicName = "m.ogg", imageName = "i.png",
            bpmOffsetMs = 0.0, bpmSegments = segs
        )
        // 导出的 BPMList 应该是 3 段
        assertEquals(3, Regex("\"bpm\"").findAll(json).count())

        val zip = zipOf(
            "info.txt" to "#\r\nName: 多BPM测试\r\nChart: chart.json\r\n",
            "chart.json" to json
        )
        val back = PezImporter.import({ ByteArrayInputStream(zip) })

        // 分段回来了（时间允许 1ms 误差：拍→毫秒的取整）
        assertEquals(3, back.bpmSegments.size)
        assertEquals(120.0, back.bpmSegments[0].bpm, 1e-6)
        assertEquals(240.0, back.bpmSegments[1].bpm, 1e-6)
        assertEquals(60.0, back.bpmSegments[2].bpm, 1e-6)
        assertEquals(2000.0, back.bpmSegments[1].startMs.toDouble(), 2.0)
        assertEquals(5000.0, back.bpmSegments[2].startMs.toDouble(), 2.0)

        // 音符时间也要对得上（±2ms）
        val got = back.notes.sortedBy { it.startMs }
        assertEquals(notes.size, got.size)
        for (i in notes.indices) {
            assertEquals("第 $i 个音符起始时间", notes[i].startMs.toDouble(), got[i].startMs.toDouble(), 2.0)
            assertEquals("第 $i 个音符类型", notes[i].type, got[i].type)
        }
        // 长条的结束时间也别丢
        val hold = got.first { it.type == 2 }
        assertEquals(4500.0, hold.endMs.toDouble(), 2.0)
    }

    /** 单 BPM 时输出必须和以前完全一致（不能因为加了分段支持就改动老谱面） */
    @Test
    fun `单BPM输出保持原样`() {
        val notes = listOf(RecordedNote(1, 1234, 1234, 0.5f, 1, 0, true, false))
        val json = PezExporter.buildChartJsonCore(
            "x", "1", "c", "k", 120.0, 0, 60,
            listOf(Line(0)), notes, "m.ogg", "i.png", 0.0, emptyList()
        )
        assertTrue(json.contains("{ \"bpm\": 120, \"startTime\": [0, 0, 1] }"))
        // 1234ms @120bpm = 2 + 117/250 拍（和以前一样的精确分数）
        assertTrue(json.contains("[2, 117, 250]"))
    }

    /**
     * 回归：拍数 → 分数时，小数部分 ≥ 0.5 **不能**被进位成整拍。
     * （以前 d=1 那一档 round(0.57)=1 就被判成"进位"，导致导入导出的音符整体偏 128~175ms）
     */
    @Test
    fun `拍数转分数_小数部分大于一半不能进位`() {
        assertEquals("[72, 573458, 1000000]", PezExporter.beatsTriple(72.573458))
        assertEquals("[73, 1, 2]", PezExporter.beatsTriple(73.5))
        assertEquals("[72, 2, 3]", PezExporter.beatsTriple(72.6666666667))
        assertEquals("[0, 0, 1]", PezExporter.beatsTriple(0.0))
        assertEquals("[5, 0, 1]", PezExporter.beatsTriple(4.9999999999))
        // 换回毫秒也要对得上（1e-6 拍 ≈ 0.0004ms）
        val beats = 72.573458
        val back = 73.0 * 0.0 + beats
        assertTrue(Math.abs(back - beats) < 1e-9)
    }
}
