package com.piezmaker

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * phira pro 导入 FRPHI 导出谱面报 `blockAreaList[0] / scaleXEvents / missing transform anchor`
 * 的回归测试。
 *
 * 根因：FRPHI 用矩形关键帧派生出的 move/scale 事件（[NoiseArea.Companion.build] →
 * rectTracks）不带 `anchor`，而读器把 5 条事件轨道都当成 transform 事件、要求每条都带锚点。
 * 真实官谱（14355 块）里 rotateEvents / scaleEvents 每条都有 `anchor`，默认 `{x:0.5,y:0.5}`。
 *
 * 这里的底线：导出的 blockAreaList 里**任何** transform 事件都带合法 anchor，
 * 且补齐只做加法（已有合法 anchor 不动、未知字段不丢）。
 */
class NoiseAreaAnchorTest {

    private fun assertTrackAnchors(o: JSONObject, vararg tracks: String) {
        for (tk in tracks) {
            val evs = o.optJSONArray(tk) ?: continue
            for (i in 0 until evs.length()) {
                val e = evs.optJSONObject(i) ?: continue
                val an = e.optJSONObject("anchor")
                assertTrue("$tk[$i] 缺 anchor", an != null)
                val x = an!!.optDouble("x", Double.NaN)
                val y = an.optDouble("y", Double.NaN)
                assertTrue("$tk[$i].anchor.x 非法", x.isFinite())
                assertTrue("$tk[$i].anchor.y 非法", y.isFinite())
            }
        }
    }

    private val transformTracks = arrayOf(
        "moveXEvents", "moveYEvents", "rotateEvents", "scaleXEvents", "scaleYEvents", "scaleEvents"
    )

    /** FRPHI 自己编辑出来的噪域（带矩形关键帧、dirty）→ 导出的 5 条轨道全都要带 anchor */
    @Test
    fun editedAreaDerivedEventsAlwaysHaveAnchor() {
        val a = NoiseArea(
            name = "编辑过的",
            left = -100f, bottom = -50f, right = 100f, top = 50f,
            rectKeys = listOf(
                NoiseArea.RectKey(0, -100f, -50f, 100f, 50f),
                NoiseArea.RectKey(1000, 0f, 0f, 200f, 100f)
            ),
            dirty = true
        )
        val back = NoiseArea.toJson(listOf(a))
        assertEquals(1, back.length())
        val o = back.getJSONObject(0)
        assertTrackAnchors(o, *transformTracks)
        // 派生的 scaleXEvents 至少有一条，且锚点是默认中心
        val sx = o.getJSONArray("scaleXEvents")
        assertTrue(sx.length() > 0)
        assertEquals(0.5, sx.getJSONObject(0).getJSONObject("anchor").getDouble("x"), 1e-9)
        assertEquals(0.5, sx.getJSONObject(0).getJSONObject("anchor").getDouble("y"), 1e-9)

        // 编辑后立刻存草稿的 JSON 同样要合法
        val draft = NoiseArea.toJsonObject(a)
        assertTrackAnchors(draft, *transformTracks)
    }

    /** 原样写回的谱面（raw 分支）本来缺 anchor，导出边界必须补齐，且不能丢未知字段 */
    @Test
    fun rawPassthroughGetsAnchorsAndKeepsUnknownFields() {
        val raw = JSONObject(
            """
            {
              "Name": "无锚点",
              "bottomLeft": {"x":-300,"y":-200},
              "topRight": {"x":300,"y":200},
              "scaleXEvents": [ {"startTime":[0,0,1],"endTime":[8,0,1],"start":1,"end":1.5,"easingType":1} ],
              "scaleYEvents": [ {"startTime":[0,0,1],"endTime":[8,0,1],"start":1,"end":1.2,"easingType":1} ],
              "moveXEvents": [ {"startTime":[0,0,1],"endTime":[8,0,1],"start":0,"end":10,"easingType":1} ],
              "someFutureField": {"keep":"me"}
            }
            """.trimIndent()
        )
        val a = NoiseArea(raw = raw)
        val back = NoiseArea.toJson(listOf(a)).getJSONObject(0)
        assertTrackAnchors(back, *transformTracks)
        assertTrue("未知字段不能丢", back.has("someFutureField"))
        assertEquals("me", back.getJSONObject("someFutureField").getString("keep"))
        assertEquals("无锚点", back.getString("Name"))
    }

    /** 官谱格式的 scaleEvents / rotateEvents 缺锚点时也要补（混格式草稿导出） */
    @Test
    fun officialFormatTransformEventsGetAnchors() {
        val raw = JSONObject(
            """
            {
              "bottomLeftPercentage": {"x":0.2,"y":0.2},
              "topRightPercentage": {"x":0.8,"y":0.8},
              "scaleEvents": [ {"time":1.0,"scale":{"x":1.0,"y":1.0}} ],
              "rotateEvents": [ {"time":1.0,"rotation":0.0} ],
              "moveEvents": [ {"time":1.0,"endPosition":{"x":0.5,"y":0.5}} ]
            }
            """.trimIndent()
        )
        val o = NoiseArea.toJson(listOf(NoiseArea(raw = raw))).getJSONObject(0)
        assertTrackAnchors(o, *transformTracks)
        assertEquals(0.5, o.getJSONArray("scaleEvents").getJSONObject(0)
            .getJSONObject("anchor").getDouble("x"), 1e-9)
    }

    /** 已有合法 anchor 不动（不能把谱师精心设的锚点冲成 0.5） */
    @Test
    fun existingValidAnchorIsPreserved() {
        val raw = JSONObject(
            """
            {
              "rotateEvents": [ {"startTime":[0,0,1],"endTime":[8,0,1],"start":0,"end":90,"easingType":1,
                                 "anchor":{"x":0.3154,"y":0.9175}} ]
            }
            """.trimIndent()
        )
        val an = NoiseArea.toJson(listOf(NoiseArea(raw = raw))).getJSONObject(0)
            .getJSONArray("rotateEvents").getJSONObject(0).getJSONObject("anchor")
        assertEquals(0.3154, an.getDouble("x"), 1e-4)
        assertEquals(0.9175, an.getDouble("y"), 1e-4)
    }

    /** 端到端：走 PezExporter 生成的 chart.json，blockAreaList[0].scaleXEvents 必须带 anchor */
    @Test
    fun exportedChartJsonNoiseEventsHaveAnchor() {
        val a = NoiseArea(
            left = -100f, bottom = -50f, right = 100f, top = 50f,
            rectKeys = listOf(
                NoiseArea.RectKey(0, -100f, -50f, 100f, 50f),
                NoiseArea.RectKey(1000, 0f, 0f, 200f, 100f)
            ),
            dirty = true
        )
        val json = PezExporter.buildChartJsonCore(
            name = "t", level = "", charter = "", composer = "",
            bpm = 120.0, delayMs = 0, fallbackPosPct = 60,
            lines = listOf(Line(0)), notes = emptyList(),
            musicName = "music.mp3", imageName = "bg.png",
            noiseAreas = listOf(a)
        )
        val ba = JSONObject(json).getJSONArray("blockAreaList").getJSONObject(0)
        assertTrackAnchors(ba, *transformTracks)
        assertEquals(220, JSONObject(json).getJSONObject("META").getInt("RPEVersion"))
    }
}
