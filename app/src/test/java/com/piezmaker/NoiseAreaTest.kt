package com.piezmaker

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 噪域（blockAreaList）只读支持：解析 / 激活区间 / 原样往返 / 导出。
 *
 * 重点守住两条底线：
 *  1. 读到就要读全（区域、生命周期、激活区间、5 条事件轨道）；
 *  2. **导出必须原样** —— FRPHI 不认识的字段一个都不能丢，否则等于把谱师的噪域改坏了。
 */
class NoiseAreaTest {

    private fun obj(s: String) = JSONObject(s)

    /** 一块典型的噪域：120BPM，第 0 拍出现，第 32 拍消失，第 8~16 拍激活 */
    private val sample = """
        {
          "Name": "主噪域",
          "bottomLeft": { "x": -300, "y": -200 },
          "topRight": { "x": 300, "y": 200 },
          "appearTime": [0, 0, 1],
          "disappearTime": [32, 0, 1],
          "isInvert": false,
          "activeIntervals": [ { "startTime": [8, 0, 1], "endTime": [16, 0, 1], "readyFlash": 0.5 } ],
          "moveXEvents": [ { "startTime": [0,0,1], "endTime": [8,0,1], "start": -300, "end": 300, "easingType": 1 } ],
          "moveYEvents": [],
          "rotateEvents": [ { "startTime": [8,0,1], "endTime": [16,0,1], "start": 0, "end": 90, "easingType": 1,
                              "anchor": { "x": 0, "y": 0 } } ],
          "scaleXEvents": [],
          "scaleYEvents": [],
          "layerId": "default",
          "someFutureField": { "keep": "me" }
        }
    """.trimIndent()

    @Test
    fun parsesAreaGeometryAndLifecycle() {
        val areas = NoiseArea.parseAll(JSONArray("[ $sample ]"), listOf(0.0 to 120.0))
        assertEquals(1, areas.size)
        val a = areas[0]
        assertEquals("主噪域", a.name)
        assertEquals(-300f, a.left, 1e-3f)
        assertEquals(300f, a.right, 1e-3f)
        assertEquals(-200f, a.bottom, 1e-3f)
        assertEquals(200f, a.top, 1e-3f)
        assertEquals(0f, a.cx, 1e-3f)
        assertEquals(0f, a.cy, 1e-3f)
        // 120 BPM：1 拍 = 500ms
        assertEquals(0.0, a.appearMs, 1e-6)
        assertEquals(16000.0, a.disappearMs, 1e-6)
        assertEquals(1, a.intervals.size)
        assertEquals(4000.0, a.intervals[0].startMs, 1e-6)   // 第 8 拍
        assertEquals(8000.0, a.intervals[0].endMs, 1e-6)     // 第 16 拍
    }

    @Test
    fun parsesAllFiveTracksAndAnchor() {
        val a = NoiseArea.parseAll(JSONArray("[$sample]"), listOf(0.0 to 120.0))[0]
        // 空轨道不入表，非空轨道在
        assertEquals(2, a.tracks.size)
        assertTrue(a.tracks.containsKey("moveXEvents"))
        assertTrue(a.tracks.containsKey("rotateEvents"))
        val rot = a.tracks.getValue("rotateEvents")[0]
        assertEquals(0f, rot.start, 1e-3f)
        assertEquals(90f, rot.end, 1e-3f)
        assertEquals(0f, rot.anchorX, 1e-3f)
        assertEquals(0f, rot.anchorY, 1e-3f)
    }

    @Test
    fun activeStateFollowsIntervalsAndLifetime() {
        val a = NoiseArea.parseAll(JSONArray("[$sample]"), listOf(0.0 to 120.0))[0]
        // 生命周期之前 / 之间 / 激活中 / 之后
        assertFalse(a.aliveAt(-1.0))
        assertTrue(a.aliveAt(1000.0))
        assertFalse(a.activeAt(1000.0))
        assertTrue(a.activeAt(5000.0))
        assertTrue(a.activeAt(7999.0))
        assertFalse(a.activeAt(9000.0))
        // 生命周期结束之后一律不算亮
        assertFalse(a.aliveAt(17000.0))
        assertFalse(a.activeAt(17000.0))
    }

    @Test
    fun trackValueEvaluatesBetweenEndpoints() {
        val a = NoiseArea.parseAll(JSONArray("[$sample]"), listOf(0.0 to 120.0))[0]
        // moveX：0→4000ms 从 -300 缓动到 300
        assertEquals(-300f, a.trackValue("moveXEvents", 0.0, 0f), 1e-2f)
        assertEquals(0f, a.trackValue("moveXEvents", 2000.0, 0f), 1e-2f)
        assertEquals(300f, a.trackValue("moveXEvents", 4000.0, 0f), 1e-2f)
        // 没有轨道 → fallback
        assertEquals(42f, a.trackValue("scaleXEvents", 1000.0, 42f), 1e-3f)
    }

    @Test
    fun multiBpmScalesBeatsToMilliseconds() {
        // 第 0 拍 120BPM（1 拍 500ms），第 4 拍起改 60BPM（1 拍 1000ms）：
        //   第 8 拍 = 4×500 + 4×1000 = 6000ms
        //   第 16 拍 = 4×500 + 12×1000 = 14000ms
        val a = NoiseArea.parseAll(
            JSONArray("[$sample]"),
            listOf(0.0 to 120.0, 4.0 to 60.0)
        )[0]
        assertEquals(6000.0, a.intervals[0].startMs, 1e-6)
        assertEquals(14000.0, a.intervals[0].endMs, 1e-6)
        // 生命周期也跟着换算：第 32 拍 = 4×500 + 28×1000 = 30000ms
        assertEquals(30000.0, a.disappearMs, 1e-6)
    }

    @Test
    fun rawJsonRoundTripKeepsUnknownFields() {
        val areas = NoiseArea.parseAll(JSONArray("[$sample]"), listOf(0.0 to 120.0))
        val back = NoiseArea.toJson(areas)
        assertEquals(1, back.length())
        val o = back.getJSONObject(0)
        // 关键：FRPHI 不认识的字段必须还在
        assertTrue(o.has("someFutureField"))
        assertEquals("me", o.getJSONObject("someFutureField").getString("keep"))
        // 已知字段值也不能被改写
        assertEquals("主噪域", o.getString("Name"))
        assertEquals(32, o.getJSONArray("disappearTime").getInt(0))
        assertEquals(300, o.getJSONObject("topRight").getInt("x"))
    }

    @Test
    fun draftRoundTripKeepsNoiseAreas() {
        val areas = NoiseArea.parseAll(JSONArray("[$sample]"), listOf(0.0 to 120.0))
        val line = Line(0)
        val text = DraftCodec.encode(listOf(line), emptyList(), emptyList(), areas)
        val d = DraftCodec.decode(text)
        assertNotNull(d)
        assertEquals(1, d!!.noise.size)
        val na = d.noise[0]
        assertEquals("主噪域", na.name)
        assertEquals(-300f, na.left, 1e-3f)
        // 草稿解码后 raw 依然在 → 再导出还是一样的东西
        assertNotNull(na.raw)
        assertTrue(na.raw!!.has("someFutureField"))
    }

    @Test
    fun exportWritesNoiseAndBumpsRpeVersion() {
        val areas = NoiseArea.parseAll(JSONArray("[$sample]"), listOf(0.0 to 120.0))
        val json = PezExporter.buildChartJsonCore(
            name = "t", level = "", charter = "", composer = "",
            bpm = 120.0, delayMs = 0, fallbackPosPct = 60,
            lines = listOf(Line(0)), notes = emptyList(),
            musicName = "music.mp3", imageName = "bg.png",
            noiseAreas = areas
        )
        val doc = JSONObject(json)
        assertTrue(doc.has("blockAreaList"))
        assertEquals(1, doc.getJSONArray("blockAreaList").length())
        // 有噪域时 RPEVersion 必须 ≥ 220
        assertEquals(220, doc.getJSONObject("META").getInt("RPEVersion"))
        // 原始字段还在
        assertTrue(doc.getJSONArray("blockAreaList").getJSONObject(0).has("someFutureField"))
        // 没有噪域时保持 160（不改变既有导出格式）
        val plain = PezExporter.buildChartJsonCore(
            name = "t", level = "", charter = "", composer = "",
            bpm = 120.0, delayMs = 0, fallbackPosPct = 60,
            lines = listOf(Line(0)), notes = emptyList(),
            musicName = "music.mp3", imageName = "bg.png"
        )
        val pd = JSONObject(plain)
        assertEquals(160, pd.getJSONObject("META").getInt("RPEVersion"))
        assertFalse(pd.has("blockAreaList"))
    }

    @Test
    fun brokenAreaIsSkippedNotFatal() {
        val arr = JSONArray("[ $sample , { \"Name\": \"ok-but-empty\" } , 12 ]")
        val warnings = ArrayList<String>()
        val areas = NoiseArea.parseAll(arr, listOf(0.0 to 120.0), warnings)
        // 缺几何字段的那块用默认值兜住（-250~250），数字那条直接跳过
        assertEquals(2, areas.size)
        assertEquals(-250f, areas[1].left, 1e-3f)
        assertEquals(250f, areas[1].right, 1e-3f)
    }

    // ------------------------------------------------------------------ 编辑（矩形关键帧）

    @Test
    fun rectKeysInterpolateLinearly() {
        val a = NoiseArea(
            left = -100f, bottom = -50f, right = 100f, top = 50f,
            rectKeys = listOf(
                NoiseArea.RectKey(0, -100f, -50f, 100f, 50f),
                NoiseArea.RectKey(1000, 0f, 0f, 200f, 100f)
            )
        )
        val r0 = a.rectAt(0.0)
        assertEquals(-100f, r0[0], 1e-4f)
        assertEquals(100f, r0[2], 1e-4f)
        val mid = a.rectAt(500.0)
        assertEquals(-50f, mid[0], 1e-4f)
        assertEquals(150f, mid[2], 1e-4f)
        assertEquals(75f, mid[3], 1e-4f)
        // 末帧之后保持末帧
        val after = a.rectAt(2000.0)
        assertEquals(0f, after[0], 1e-4f)
        assertEquals(200f, after[2], 1e-4f)
    }

    @Test
    fun frphiRoundTripKeepsKeyframesAndFlags() {
        val a = NoiseArea(
            name = "编辑过的",
            left = -100f, bottom = -50f, right = 100f, top = 50f,
            appearMs = 1000.0, disappearMs = Double.MAX_VALUE,
            intervals = listOf(NoiseArea.Interval(1000.0, Double.MAX_VALUE, 0.5)),
            rectKeys = listOf(
                NoiseArea.RectKey(1000, -100f, -50f, 100f, 50f),
                NoiseArea.RectKey(3000, -200f, 0f, 200f, 200f)
            ),
            visible = false, warning = true, dirty = true
        )
        val json = NoiseArea.toJsonObject(a)
        // 标准字段写成首帧矩形（其他播放器仍能画出一个合法噪域）
        assertEquals(-100.0, json.getJSONObject("bottomLeft").getDouble("x"), 1e-3)
        assertEquals(100.0, json.getJSONObject("topRight").getDouble("x"), 1e-3)
        // FRPHI 私有数据
        val fr = json.getJSONObject("_frphi")
        assertFalse(fr.getBoolean("visible"))
        assertTrue(fr.getBoolean("warning"))
        assertEquals(2, fr.getJSONArray("rectKeys").length())

        val parsed = NoiseArea.parseOne(json, NoiseArea.Companion.Beats(emptyList()))
        assertFalse(parsed.visible)
        assertTrue(parsed.warning)
        assertTrue("带 _frphi 的块应标记为 dirty（导出时按当前值重建）", parsed.dirty)
        assertEquals(2, parsed.rectKeys.size)
        assertEquals(3000L, parsed.rectKeys[1].timeMs)
        assertEquals(-200f, parsed.rectKeys[1].left, 1e-3f)
    }
}
