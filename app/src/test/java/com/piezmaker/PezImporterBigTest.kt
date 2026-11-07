package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 大谱面回归：RPE 的 json 是带缩进的美化格式，60 线 + 2 万事件的谱面能到 10MB 以上。
 * 以前所有文本共用一个 8MB 上限，超过的 json 直接被丢掉 → 报"压缩包里没有找到谱面 JSON"。
 * 现在 json 单独放宽到 64MB，并且解析前先去缩进。
 */
class PezImporterBigTest {

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, text) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test
    fun `超过8MB的谱面JSON也能导入`() {
        // 用一段 8MB 的字符串把 json 撑过 8MB（模拟真实大谱面；这里不掺用户的谱面数据）
        val pad = "x".repeat(8 * 1024 * 1024)
        val json = """
        {
           "META" : {
              "name" : "Big Chart",
              "composer" : "c",
              "charter" : "ch",
              "level" : "15",
              "offset" : 0,
              "intro" : "${pad}"
           },
           "BPMList" : [ { "bpm" : 180.0, "startTime" : [ 0, 0, 1 ] } ],
           "judgeLineList" : [
              {
                 "Name" : "line0",
                 "notes" : [
                    { "type" : 1, "startTime" : [ 1, 0, 1 ], "endTime" : [ 1, 0, 1 ],
                      "positionX" : 0.0, "above" : 1, "isFake" : 0, "speed" : 1.0 },
                    { "type" : 1, "startTime" : [ 2, 0, 1 ], "endTime" : [ 2, 0, 1 ],
                      "positionX" : 100.0, "above" : 1, "isFake" : 0, "speed" : 1.0 }
                 ]
              }
           ]
        }
        """.trimIndent()
        assertTrue("样本本身要超过 8MB（实际 ${json.length} 字节）", json.length > 8 * 1024 * 1024)

        val zip = zipOf(
            "info.txt" to "#\r\nName: Big Chart\r\nChart: big.json\r\nLevel: 15\r\n",
            "big.json" to json
        )
        val chart = PezImporter.import({ ZipInputStream(ByteArrayInputStream(zip)) })
        assertEquals("Big Chart", chart.name)
        assertEquals(180.0, chart.bpm, 0.001)
        assertEquals(1, chart.lineCount)
        assertEquals(2, chart.notes.size)
    }

    /** 去缩进不能动字符串里的反斜杠/引号 */
    @Test
    fun `去缩进保持字符串原样`() {
        val raw = "{ \"a\" : \"x \\\" y \\\\ z\" ,\n  \"b\" : [ 1, 2 ] }"
        val min = PezImporter.minifyJson(raw)
        assertEquals("{\"a\":\"x \\\" y \\\\ z\",\"b\":[1,2]}", min)
    }
}
