package com.piezmaker

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * PezImporter 单元测试（纯 JVM，不依赖 Android 运行时）。
 * 覆盖：BPM 分段换算、META.offset、判定线 moveY/rotate/speed 事件、横线/竖线反推、素材抽取。
 */
class PezImporterTest {

    // ------------------------------------------------------------------ 构造 pez

    private fun zipOf(files: List<Pair<String, ByteArray>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, data) in files) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun triple(i: Int, n: Int, d: Int) = JSONArray().put(i).put(n).put(d)

    private fun event(
        si: Int, sn: Int, sd: Int,
        ei: Int, en: Int, ed: Int,
        start: Double, end: Double,
        easing: Int = 1
    ): JSONObject = JSONObject()
        .put("startTime", triple(si, sn, sd))
        .put("endTime", triple(ei, en, ed))
        .put("start", start).put("end", end)
        .put("easingType", easing)

    private fun note(
        type: Int, si: Int, sn: Int, sd: Int,
        ei: Int, en: Int, ed: Int,
        posX: Double
    ): JSONObject = JSONObject()
        .put("type", type)
        .put("above", 1)
        .put("startTime", triple(si, sn, sd))
        .put("endTime", triple(ei, en, ed))
        .put("positionX", posX)
        .put("yOffset", 0.0)
        .put("alpha", 255)
        .put("size", 1.0)
        .put("speed", 1.0)
        .put("isFake", 0)
        .put("visibleTime", 999999.0)

    /** 一个"像 Phira 下载的"谱面：多 BPM、有 offset、有 moveY 动画、有流速、有音频和背景图 */
    private fun samplePez(): ByteArray {
        val meta = JSONObject()
            .put("id", "1").put("name", "测试曲").put("level", "15")
            .put("composer", "曲师").put("charter", "谱师").put("illustration", "")
            .put("song", "music.ogg").put("background", "bg.jpg")
            .put("offset", 100).put("RPEVersion", 160)

        val bpmList = JSONArray()
            .put(JSONObject().put("bpm", 120.0).put("startTime", triple(0, 0, 1)))
            .put(JSONObject().put("bpm", 60.0).put("startTime", triple(4, 0, 1)))

        val layer = JSONObject()
            // moveY：0 拍 +90 → 4 拍 -90（线性）
            .put("moveYEvents", JSONArray().put(event(0, 0, 1, 4, 0, 1, 90.0, -90.0)))
            // 流速恒为 1.6
            .put("speedEvents", JSONArray().put(event(0, 0, 1, 100000, 0, 1, 1.6, 1.6)))
            .put("moveXEvents", JSONArray())
            .put("rotateEvents", JSONArray())

        val notes = JSONArray()
            .put(note(1, 0, 0, 4, 0, 0, 4, 0.0))          // 拍 0   → 100ms
            .put(note(2, 1, 0, 4, 1, 2, 4, 337.5))        // 拍 1→1.5 → 600/850ms
            .put(note(3, 2, 0, 4, 2, 0, 4, -337.5))       // 拍 2   → 1100ms
            .put(note(4, 5, 0, 4, 5, 0, 4, 675.0))        // 拍 5（换 BPM 后）→ 3100ms
            .put(note(1, 5, 1, 8, 5, 1, 8, 0.0))          // 拍 5.125 → 3225ms（分母 8）

        val line = JSONObject()
            .put("Name", "line").put("Texture", "line.png").put("father", -1)
            .put("rotateWithFather", false)
            .put("eventLayers", JSONArray().put(layer))
            .put("notes", notes)
            .put("isCover", 1).put("isGif", false).put("zOrder", 0)

        val chart = JSONObject()
            .put("META", meta)
            .put("BPMList", bpmList)
            .put("judgeLineList", JSONArray().put(line))

        val info = """
            #
            Name: 测试曲
            Path: 1
            Song: music.ogg
            Picture: bg.jpg
            Chart: chart.json
            Level: 15
            Composer: 曲师
            Charter: 谱师
            Illustration: 
        """.trimIndent()

        return zipOf(
            listOf(
                "chart.json" to chart.toString().toByteArray(Charsets.UTF_8),
                "info.txt" to info.toByteArray(Charsets.UTF_8),
                "music.ogg" to ByteArray(2048) { (it % 251).toByte() },
                "bg.jpg" to ByteArray(1024) { (it % 97).toByte() }
            )
        )
    }

    private fun parse(bytes: ByteArray, assets: MutableMap<String, Int> = HashMap()): ImportedChart {
        return PezImporter.import(
            openZip = { ZipInputStream(bytes.inputStream()) },
            saveAsset = { name: String, ins: InputStream ->
                assets[name] = ins.readBytes().size
                true
            }
        )
    }

    // ------------------------------------------------------------------ 测试

    @Test
    fun `解析多BPM谱面_音符时间按分段换算并加offset`() {
        val chart = parse(samplePez())

        assertEquals(5, chart.notes.size)
        assertEquals(100L, chart.notes[0].startMs)     // 拍0
        assertEquals(600L, chart.notes[1].startMs)     // 拍1 = 0.5s（120BPM）
        assertEquals(850L, chart.notes[1].endMs)       // 拍1.5 = 0.75s
        assertEquals(1100L, chart.notes[2].startMs)    // 拍2 = 1.0s
        assertEquals(3100L, chart.notes[3].startMs)    // 拍5：4 拍 @120 = 2s，再 1 拍 @60 = 1s
        assertEquals(3225L, chart.notes[4].startMs)    // 拍5.125 = 3s + 0.125s
        assertEquals(120.0, chart.bpm, 1e-9)
        assertEquals(2, chart.bpmChanges)
    }

    @Test
    fun `识别流速`() {
        val chart = parse(samplePez())
        assertEquals(1.6, chart.speed, 1e-5)
    }

    @Test
    fun `识别判定线位置`() {
        val chart = parse(samplePez())
        // moveY 起始 +90 → RPE 向上为正 → 屏幕上 40%
        assertEquals(40, chart.linePosPct)
        // 关键帧按事件时间生成，并叠加 offset
        assertTrue("应该有判定线关键帧", chart.keyframes.isNotEmpty())
        val first = chart.keyframes.first()
        assertEquals(100L, first.timeMs)
        assertEquals(0.0, first.moveY.toDouble(), 1e-4)
        val at2s = chart.keyframes.firstOrNull { it.timeMs == 2100L }
        assertNotNull("拍4（2s）应该有端点关键帧", at2s)
        // y 归一化：pct/100 + yNorm/2 = 0.5 - v/900，v=-90 → yNorm = 0.4
        assertEquals(0.4, at2s!!.moveY.toDouble(), 1e-4)
    }

    @Test
    fun `识别横线竖线`() {
        val chart = parse(samplePez())
        assertEquals(8, chart.hLines)   // 分母 4/8 → 最小公共格 = 1/8 拍
        assertEquals(4, chart.vLines)   // positionX 0 / ±337.5 / 675 → 4 格
    }

    @Test
    fun `抽取音频和背景图`() {
        val assets = HashMap<String, Int>()
        val chart = parse(samplePez(), assets)
        assertEquals("music.ogg", chart.musicName)
        assertEquals("bg.jpg", chart.imageName)
        assertEquals(2048, assets["music.ogg"])
        assertEquals(1024, assets["bg.jpg"])
    }

    @Test
    fun `识别曲目信息`() {
        val chart = parse(samplePez())
        assertEquals("测试曲", chart.name)
        assertEquals("曲师", chart.composer)
        assertEquals("谱师", chart.charter)
        assertEquals("15", chart.level)
    }

    @Test
    fun `没有info_txt时用META兜底`() {
        val chart = JSONObject()
            .put(
                "META", JSONObject().put("name", "无info").put("song", "a.mp3")
                    .put("background", "b.png").put("offset", 0)
            )
            .put("BPMList", JSONArray().put(JSONObject().put("bpm", 150.0).put("startTime", triple(0, 0, 1))))
            .put(
                "judgeLineList", JSONArray().put(
                    JSONObject().put("Name", "l").put("Texture", "line.png")
                        .put("eventLayers", JSONArray())
                        .put("notes", JSONArray().put(note(1, 0, 0, 1, 0, 0, 1, 0.0)))
                )
            )
        val bytes = zipOf(
            listOf(
                "chart.json" to chart.toString().toByteArray(Charsets.UTF_8),
                "a.mp3" to ByteArray(16),
                "b.png" to ByteArray(16)
            )
        )
        val assets = HashMap<String, Int>()
        val res = parse(bytes, assets)
        assertEquals("无info", res.name)
        assertEquals(1, res.notes.size)
        assertEquals(0L, res.notes[0].startMs)
        assertEquals(1.0, res.speed, 1e-9)
        assertEquals("a.mp3", res.musicName)
        assertEquals("b.png", res.imageName)
        assertEquals(setOf("a.mp3", "b.png"), assets.keys)
    }

    @Test
    fun `extra_json不会被误当成谱面`() {
        val extra = JSONObject().put("shaderEvents", JSONArray())
        val chart = JSONObject()
            .put("META", JSONObject().put("name", "x"))
            .put("BPMList", JSONArray().put(JSONObject().put("bpm", 120.0).put("startTime", triple(0, 0, 1))))
            .put(
                "judgeLineList", JSONArray().put(
                    JSONObject().put("Name", "l").put("Texture", "line.png")
                        .put("eventLayers", JSONArray())
                        .put("notes", JSONArray().put(note(1, 0, 0, 4, 0, 0, 4, 0.0)))
                )
            )
        val bytes = zipOf(
            listOf(
                "extra.json" to extra.toString().toByteArray(Charsets.UTF_8),
                "chart.json" to chart.toString().toByteArray(Charsets.UTF_8)
            )
        )
        assertEquals("x", parse(bytes).name)
    }

    @Test
    fun `多条判定线各保留各自的音符和姿态`() {
        // line0 只有 1 个音符（没事件）；line1 有 2 个音符 + moveX/rotate 事件
        val line0 = JSONObject()
            .put("Name", "a").put("Texture", "line.png")
            .put("eventLayers", JSONArray())
            .put("notes", JSONArray().put(note(1, 0, 0, 1, 0, 0, 1, 0.0)))

        val layer1 = JSONObject().put(
            "moveXEvents",
            JSONArray().put(event(0, 0, 1, 100000, 0, 1, 135.0, 135.0))
        ).put(
            "rotateEvents",
            JSONArray().put(event(0, 0, 1, 100000, 0, 1, 30.0, 30.0))
        )
        val line1 = JSONObject()
            .put("Name", "b").put("Texture", "line.png")
            .put("eventLayers", JSONArray().put(layer1))
            .put(
                "notes", JSONArray()
                    .put(note(1, 0, 0, 1, 0, 0, 1, 0.0))
                    .put(note(1, 1, 0, 1, 1, 0, 1, 0.0))
            )

        val chart = JSONObject()
            .put("META", JSONObject().put("name", "多线"))
            .put("BPMList", JSONArray().put(JSONObject().put("bpm", 120.0).put("startTime", triple(0, 0, 1))))
            .put("judgeLineList", JSONArray().put(line0).put(line1))

        val res = parse(zipOf(listOf("chart.json" to chart.toString().toByteArray(Charsets.UTF_8))))

        // 两条判定线各自保留（不再合并），音符按 lineId 归组，posX 不再跨线换算
        assertEquals(2, res.lineCount)
        assertEquals(3, res.notes.size)
        assertEquals(1, res.lines[0].notes.size)
        assertEquals(2, res.lines[1].notes.size)
        assertTrue(res.notes.all { it.posX == 0f })
        assertTrue(res.notes.all { it.lineId == 0 || it.lineId == 1 })
        // 旋转：RPE 30 度 → 本项目 rotate = -30（主判定线 = 音符多那条）
        assertEquals(-30.0, res.lines[1].rotate.toDouble(), 1e-4)
        // 横向偏移 135/675 = 0.2
        assertEquals(0.2, res.lines[1].moveX.toDouble(), 1e-5)
        assertEquals(0.0, res.lines[0].moveX.toDouble(), 1e-5)
    }


    // ------------------------------------------------------------------ zip 后缀 / 各种包结构

    /** 标准 pez 把后缀改成 .zip 后必须一样能导入（ZipInputStream 不看后缀） */
    @Test
    fun `没有音符的判定线是表演线_要保留`() {
        // line0：正常挂音符；line1：一个音符都没有，只有 moveX 关键帧（表演用的装饰线）
        val layer0 = JSONObject().put(
            "moveYEvents",
            JSONArray().put(event(0, 0, 1, 100000, 0, 1, 90.0, 90.0))
        )
        val line0 = JSONObject()
            .put("Name", "main").put("Texture", "line.png")
            .put("eventLayers", JSONArray().put(layer0))
            .put("notes", JSONArray().put(note(1, 0, 0, 1, 0, 0, 1, 0.0)))

        val layer1 = JSONObject().put(
            "moveXEvents",
            JSONArray()
                .put(event(0, 0, 1, 1, 0, 1, 0.0, 400.0))
                .put(event(1, 0, 1, 2, 0, 1, 400.0, -400.0))
        )
        val line1 = JSONObject()
            .put("Name", "show").put("Texture", "line.png")
            .put("eventLayers", JSONArray().put(layer1))
            .put("notes", JSONArray())

        val chart = JSONObject()
            .put("META", JSONObject().put("name", "表演线"))
            .put("BPMList", JSONArray().put(JSONObject().put("bpm", 120.0).put("startTime", triple(0, 0, 1))))
            .put("judgeLineList", JSONArray().put(line0).put(line1))

        val res = parse(zipOf(listOf("chart.json" to chart.toString().toByteArray(Charsets.UTF_8))))

        assertEquals(2, res.lineCount)
        assertEquals(1, res.lines[0].notes.size)
        assertEquals(0, res.lines[1].notes.size)                 // 空线保留，但确实没音符
        assertTrue("表演线应该带关键帧：" + res.lines[1].keyframes, res.lines[1].keyframes.size >= 2)
        // 关键帧确实来自 moveX 动画（x 会变）
        val xs = res.lines[1].keyframes.map { Math.round(it.moveX * 1000) / 1000.0 }.distinct()
        assertTrue("moveX 关键帧应有多个取值：$xs", xs.size >= 2)
        assertEquals(1, res.notes.size)                           // 音符总数不含空线
    }

    @Test
    fun `后缀改成zip的pez照样能导入`() {
        val chart = parse(samplePez())
        assertEquals("测试曲", chart.name)
        assertEquals(5, chart.notes.size)
    }

    /** 文件放在子文件夹里 + 中文文件名 + info.txt 指向带目录的路径 */
    @Test
    fun `文件在子目录里也能导入`() {
        val meta = JSONObject()
            .put("name", "子目录谱").put("song", "音乐.ogg").put("background", "背景.png")
            .put("charter", "").put("composer", "").put("level", "1").put("offset", 0)
        val chartJson = JSONObject()
            .put("META", meta)
            .put("BPMList", JSONArray().put(JSONObject().put("bpm", 128.0).put("startTime", triple(0, 0, 1))))
            .put(
                "judgeLineList", JSONArray().put(
                    JSONObject().put("Name", "l").put("Texture", "line.png")
                        .put("eventLayers", JSONArray())
                        .put("notes", JSONArray().put(note(1, 0, 0, 4, 0, 0, 4, 0.5)))
                )
            )
        val info = "#\nName: 子目录谱\nSong: 音乐.ogg\nPicture: 背景.png\nChart: pez/谱面.json\n"
        val bytes = zipOf(
            listOf(
                "pez/谱面.json" to chartJson.toString().toByteArray(Charsets.UTF_8),
                "pez/info.txt" to info.toByteArray(Charsets.UTF_8),
                "pez/音乐.ogg" to ByteArray(64),
                "pez/背景.png" to ByteArray(64)
            )
        )
        val assets = HashMap<String, Int>()
        val res = parse(bytes, assets)
        assertEquals("子目录谱", res.name)
        assertEquals(1, res.notes.size)
        assertEquals(128.0, res.bpm, 1e-9)
        assertEquals("pez/音乐.ogg", res.musicName)
        assertEquals("pez/背景.png", res.imageName)
        assertEquals(setOf("pez/音乐.ogg", "pez/背景.png"), assets.keys)
    }

    /** 没有 info.txt、谱面文件名也不是 chart.json（RPE 另存时会这样） */
    @Test
    fun `谱面文件名任意且没有info也能导入`() {
        val chartJson = JSONObject()
            .put("META", JSONObject().put("name", "任意名"))
            .put("BPMList", JSONArray().put(JSONObject().put("bpm", 90.0).put("startTime", triple(0, 0, 1))))
            .put(
                "judgeLineList", JSONArray().put(
                    JSONObject().put("Name", "l").put("Texture", "line.png")
                        .put("eventLayers", JSONArray())
                        .put("notes", JSONArray().put(note(1, 0, 0, 2, 0, 0, 2, 0.0)))
                )
            )
        val bytes = zipOf(
            listOf(
                "我的谱面.json" to chartJson.toString().toByteArray(Charsets.UTF_8),
                "track.mp3" to ByteArray(32),
                "cover.jpg" to ByteArray(32)
            )
        )
        val res = parse(bytes)
        assertEquals("任意名", res.name)
        assertEquals("track.mp3", res.musicName)
        assertEquals("cover.jpg", res.imageName)
    }

    /** 用仓库里的真实示例谱面跑一遍（不存在就跳过） */    @Test
    fun `仓库示例谱面可以导入`() {
        val f = listOf(File("../demo_Test_Beat.pez"), File("demo_Test_Beat.pez"))
            .firstOrNull { it.exists() } ?: return
        val chart = parse(f.readBytes())
        assertEquals("Test Beat", chart.name)
        assertEquals(9, chart.notes.size)
        assertEquals(120.0, chart.bpm, 1e-9)
        assertEquals(4, chart.hLines)
        assertEquals(8, chart.vLines)      // 0 / ±337.5 / -168.75
        assertEquals(1.0, chart.speed, 1e-9)
        assertEquals(50, chart.linePosPct) // 没有 moveY 事件 → 屏幕正中
        assertTrue(chart.keyframes.isEmpty())
        assertEquals("demo.wav", chart.musicName)
        assertEquals("demo_bg.png", chart.imageName)
    }
}
