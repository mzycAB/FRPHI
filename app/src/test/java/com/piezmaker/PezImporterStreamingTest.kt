package com.piezmaker

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 流式导入回归（big song 那次 OOM 的教训）。
 *
 * 根因：46MB 级谱面 JSON（741 线、24 万事件）整棵 parse 成 JSONObject 会 OOM，
 * runCatching 把 OOM 吞掉后报成「压缩包里没有找到谱面 JSON」。
 * 修复：RPE 路径全流式——JsonDoc 字节切片顶层成员、判定线逐条 parse 完即弃。
 * 这里用小谱面验证流式路径的正确性（美化格式 / BOM / GBK / 字符串内结构字符）；
 * 真实大谱的验证在桌面原始包上做过一次（741 线 7741 音符 44 万关键帧，导入成功）。
 */
class PezImporterStreamingTest {

    private fun triple(i: Int, n: Int, d: Int) = JSONArray().put(i).put(n).put(d)

    private fun event(s: Double): JSONObject = JSONObject()
        .put("startTime", triple(0, 0, 1)).put("endTime", triple(100000, 0, 1))
        .put("start", s).put("end", s).put("easingType", 1)

    private fun sampleChart(name: String): JSONObject {
        val layer = JSONObject()
            .put("speedEvents", JSONArray().put(event(1.6)))
            .put("moveXEvents", JSONArray()).put("moveYEvents", JSONArray()).put("rotateEvents", JSONArray())
        val line = JSONObject()
            .put("Name", name).put("Texture", "line.png")
            .put("eventLayers", JSONArray().put(layer))
            .put("notes", JSONArray().put(
                JSONObject().put("type", 1).put("above", 1)
                    .put("startTime", triple(0, 0, 1)).put("endTime", triple(0, 0, 1))
                    .put("positionX", 0.0).put("yOffset", 0.0).put("alpha", 255)
                    .put("size", 1.0).put("speed", 1.0).put("isFake", 0).put("visibleTime", 999999.0)
            ))
        return JSONObject()
            .put("META", JSONObject().put("name", "流式回归").put("offset", 0))
            .put("BPMList", JSONArray().put(JSONObject().put("bpm", 120.0).put("startTime", triple(0, 0, 1))))
            .put("judgeLineList", JSONArray().put(line))
    }

    private fun importZip(bytes: ByteArray): ImportedChart =
        PezImporter.import(
            openZip = { ByteArrayInputStream(bytes) },
            saveAsset = { _, _ -> false }
        )

    @Test
    fun `美化JSON带BOM和字符串内大括号引号也能流式导入`() {
        // 美化格式（缩进换行）+ UTF-8 BOM + 线名里有 { } " 等结构字符——
        // 字节级扫描必须正确跳过字符串字面量，否则整个文档定位就崩了
        val chart = sampleChart("""带{大括}号"引号"的线""")
        // toString 是紧凑的；手动加上缩进和换行模拟 RPE 的美化导出
        val pretty = chart.toString(2)
        val withBom = "\uFEFF" + pretty
        val res = PezImporter.importText(withBom, "美化")
        assertEquals(1, res.lines.size)
        assertEquals("""带{大括}号"引号"的线""", res.lines[0].name)
        assertEquals(1, res.lines[0].notes.size)
    }

    @Test
    fun `GBK编码的zip谱面也能导入`() {
        // RPE 在中文 Windows 上常存 GBK；GBK 尾字节可能撞上 0x5C（'\'），
        // 字节扫描前必须整篇转 UTF-8，否则中文线名会花、扫描会被带偏
        val gbk = sampleChart("中文线名测试").toString().toByteArray(charset("GBK"))
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry("3541851603059275.json")); zos.write(gbk); zos.closeEntry()
            zos.putNextEntry(ZipEntry("info.txt")); zos.write("#\nName: GBK测\n".toByteArray(Charsets.UTF_8)); zos.closeEntry()
        }
        val res = importZip(bos.toByteArray())
        assertEquals(1, res.lines.size)
        assertEquals("中文线名测试", res.lines[0].name)
    }

    @Test
    fun `任意数字命名的PhiZone打包也能找到谱面`() {
        // Phigrim / PhiZone 下载包：谱面不叫 chart.json，由 info.txt 的 Chart: 指路
        val chartBytes = sampleChart("phizone").toString().toByteArray(Charsets.UTF_8)
        val info = """
            #
            Name: big song
            Chart: 3541851603059275.json
            Song: 3541851603059275.mp3
        """.trimIndent()
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry("3541851603059275.json")); zos.write(chartBytes); zos.closeEntry()
            zos.putNextEntry(ZipEntry("info.txt")); zos.write(info.toByteArray(Charsets.UTF_8)); zos.closeEntry()
        }
        val res = importZip(bos.toByteArray())
        assertEquals("phizone", res.lines[0].name)
        assertTrue(res.warnings.isEmpty() || res.warnings.none { it.contains("找不到") })
    }
}
