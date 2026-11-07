package com.piezmaker

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 端到端：含噪域的 .pez → PezImporter → 草稿 → 导出 chart.json。
 * 验证「读到 / 不报错 / 原样写回」这条完整链路，而不只是单测各段。
 */
class NoiseAreaImportTest {

    private fun triple(i: Int, n: Int, d: Int) = JSONArray().put(i).put(n).put(d)

    private fun chartJson(withNoise: Boolean): String {
        val note = JSONObject()
            .put("type", 1).put("above", 1)
            .put("startTime", triple(1, 0, 1)).put("endTime", triple(1, 0, 1))
            .put("positionX", 0.0).put("yOffset", 0.0).put("alpha", 255)
            .put("size", 1.0).put("speed", 1.0).put("isFake", 0).put("visibleTime", 999999.0)
        val layer = JSONObject().put("alphaEvents", JSONArray()).put("moveXEvents", JSONArray())
            .put("moveYEvents", JSONArray()).put("rotateEvents", JSONArray()).put("speedEvents", JSONArray())
        val line = JSONObject()
            .put("Name", "line").put("Texture", "line.png")
            .put("eventLayers", JSONArray().put(layer))
            .put("notes", JSONArray().put(note))
            .put("zOrder", 0)
        val meta = JSONObject()
            .put("name", "noise-test").put("composer", "c").put("charter", "ch")
            .put("level", "IN 12").put("song", "music.ogg").put("background", "bg.png")
            .put("offset", 0).put("RPEVersion", if (withNoise) 220 else 160)
        val doc = JSONObject()
            .put("META", meta)
            .put("BPMList", JSONArray().put(JSONObject().put("bpm", 150.0).put("startTime", triple(0, 0, 1))))
            .put("judgeLineList", JSONArray().put(line))
        if (withNoise) {
            val area = JSONObject()
                .put("Name", "噪域A")
                .put("bottomLeft", JSONObject().put("x", -400).put("y", -225))
                .put("topRight", JSONObject().put("x", 400).put("y", 225))
                .put("appearTime", triple(0, 0, 1))
                .put("disappearTime", triple(64, 0, 1))
                .put("isInvert", true)
                .put("activeIntervals", JSONArray().put(
                    JSONObject().put("startTime", triple(16, 0, 1))
                        .put("endTime", triple(32, 0, 1)).put("readyFlash", 0.5)
                ))
                .put("moveXEvents", JSONArray())
                .put("moveYEvents", JSONArray())
                .put("rotateEvents", JSONArray())
                .put("scaleXEvents", JSONArray())
                .put("scaleYEvents", JSONArray())
                .put("vendorExtension", JSONObject().put("x", 1).put("y", "保留我"))
            doc.put("blockAreaList", JSONArray().put(area))
        }
        return doc.toString()
    }

    /** 造一个最小 pez（只放 chart.json；音频/背景缺失不影响解析） */
    private fun pez(withNoise: Boolean): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("chart.json"))
            z.write(chartJson(withNoise).toByteArray(Charsets.UTF_8))
            z.closeEntry()
            z.putNextEntry(ZipEntry("info.txt"))
            z.write("Name: noise-test\nSong: music.ogg\nPicture: bg.png\nChart: chart.json\n".toByteArray())
            z.closeEntry()
        }
        return bos.toByteArray()
    }

    private fun importIt(withNoise: Boolean): ImportedChart =
        PezImporter.import({ ZipInputStream(pez(withNoise).inputStream()) })

    @Test
    fun importsNoiseAreaFromPez() {
        val chart = importIt(true)
        assertEquals(1, chart.noiseAreas.size)
        val a = chart.noiseAreas[0]
        assertEquals("噪域A", a.name)
        assertEquals(-400f, a.left, 1e-3f)
        assertEquals(400f, a.right, 1e-3f)
        assertEquals(-225f, a.bottom, 1e-3f)
        assertEquals(225f, a.top, 1e-3f)
        assertTrue(a.invert)
        assertEquals(1, a.intervals.size)
        // 150 BPM：1 拍 = 400ms。第 16 拍 = 6400ms，第 32 拍 = 12800ms
        assertEquals(6400.0, a.intervals[0].startMs, 1e-6)
        assertEquals(12800.0, a.intervals[0].endMs, 1e-6)
        assertEquals(64 * 400.0, a.disappearMs, 1e-6)
    }

    @Test
    fun pezWithoutNoiseStillImports() {
        val chart = importIt(false)
        assertTrue(chart.noiseAreas.isEmpty())
        assertEquals(1, chart.notes.size)
    }

    @Test
    fun fullCirclePezImportDraftExportKeepsNoise() {
        val chart = importIt(true)
        // 1) 导入结果 → 草稿
        val line = Line(0)
        val draft = DraftCodec.encode(listOf(line), chart.notes, emptyList(), chart.noiseAreas)
        // 2) 草稿 → 解码（模拟再次打开项目）
        val d = DraftCodec.decode(draft)
        assertNotNull(d)
        assertEquals(1, d!!.noise.size)
        // 3) 解码结果 → 导出 chart.json
        val json = PezExporter.buildChartJsonCore(
            name = chart.name, level = chart.level, charter = chart.charter, composer = chart.composer,
            bpm = chart.bpm, delayMs = 0, fallbackPosPct = chart.linePosPct,
            lines = d.lines, notes = d.notes,
            musicName = "music.ogg", imageName = "bg.png",
            noiseAreas = d.noise
        )
        val out = JSONObject(json)
        val arr = out.getJSONArray("blockAreaList")
        assertEquals(1, arr.length())
        val a = arr.getJSONObject(0)
        assertEquals("噪域A", a.getString("Name"))
        assertTrue(a.getBoolean("isInvert"))
        // 未知字段必须原样活着回去
        assertTrue(a.has("vendorExtension"))
        assertEquals("保留我", a.getJSONObject("vendorExtension").getString("y"))
        // 导出 JSON 本身必须合法（能整体解析已经证明了），且音符还在
        assertEquals(1, out.getJSONArray("judgeLineList").length())
    }
}
