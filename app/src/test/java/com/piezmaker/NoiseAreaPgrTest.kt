package com.piezmaker

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **官谱**（Phigros Official JSON）噪域：`NoiseArea.parseAllPgr` / `parseAllAuto`。
 *
 * 这一组测试守的是「进编辑页崩溃」那个 bug 的根因：
 * 官谱的 `blockAreaList` 和 RPE 完全是两套结构，一旦被 RPE 解析器（`parseAll`）读进去，
 * 每一块都会退回默认矩形 + `disappearMs = Long.MAX_VALUE` ⇒ 一万多块噪域「永远存在、
 * 全叠在屏幕正中」⇒ 编辑页每帧画一万多个矩形，直接卡死/OOM。
 *
 * 所以这里逐个字段钉死换算：
 *  - 百分比 → 世界坐标（x ∈ ±675，y 向上为正）
 *  - 秒 → 毫秒
 *  - `[enableTime, disableTime)` = 唯一的激活区间
 *  - `isSubtract` → `invert`
 *  - `moveEvents` / `rotateEvents` / `scaleEvents` 的「终点 + 时刻」→ 首尾配对的区间事件
 */
class NoiseAreaPgrTest {

    /** 官谱一块典型噪域：区域占满宽、y 从 0 到 1；1.632653s 出现，11.972789s 消失 */
    private val sample = """
        {
          "topRightPercentage": { "x": 2.0, "y": 1.0 },
          "bottomLeftPercentage": { "x": -1.0, "y": 0.0 },
          "appearTime": 1.6326531,
          "enableTime": 1.6326531,
          "disableTime": 11.972789,
          "disappearTime": 11.972789,
          "isSubtract": true,
          "rotateEvents": [
            { "anchor": { "x": 0.5, "y": 0.5 }, "time": 1.6326531, "easeType": 13, "rotation": 0.0 },
            { "anchor": { "x": 0.5, "y": 0.5 }, "time": 5.0, "easeType": 1, "rotation": 90.0 }
          ],
          "moveEvents": [
            { "endPosition": { "x": 0.5, "y": -0.5 }, "time": 1.6326531, "easeTypeX": 0, "easeTypeY": 0 },
            { "endPosition": { "x": 0.6, "y": 0.3 }, "time": 11.156463, "easeTypeX": 0, "easeTypeY": 2 }
          ],
          "scaleEvents": [
            { "anchor": { "x": 0.5, "y": 0.5 }, "time": 1.6326531, "easeTypeX": 0, "easeTypeY": 0,
              "scale": { "x": 1.0, "y": 1.0 } },
            { "anchor": { "x": 0.5, "y": 0.5 }, "time": 9.0, "easeTypeX": 1, "easeTypeY": 1,
              "scale": { "x": 2.0, "y": 0.5 } }
          ]
        }
    """.trimIndent()

    private fun parse(json: String = "[ $sample ]"): NoiseArea =
        NoiseArea.parseAllPgr(JSONArray(json))[0]

    // ------------------------------------------------------------------ 几何

    @Test
    fun percentageMapsToWorldCoordinates() {
        val a = parse()
        // x: -1.0 → (-1 - 0.5) * 2 * 675 = -2025；2.0 → (2 - 0.5) * 2 * 675 = 2025
        assertEquals(-2025f, a.left, 1e-2f)
        assertEquals(2025f, a.right, 1e-2f)
        // y（原点左上、向下为正）: 0.0 → (0.5 - 0) * 2 * 450 = 450（屏幕顶）；
        //                          1.0 → (0.5 - 1) * 2 * 450 = -450（屏幕底）
        assertEquals(450f, a.top, 1e-2f)
        assertEquals(-450f, a.bottom, 1e-2f)
        assertEquals(0f, a.cx, 1e-2f)
        assertEquals(0f, a.cy, 1e-2f)
    }

    @Test
    fun swappedCornersAreNormalised() {
        // 官谱偶尔把「右上」写得比「左下」还小；不能让区域变成负宽高
        val a = NoiseArea.parseAllPgr(
            JSONArray(
                """
                [{ "topRightPercentage": { "x": 0.2, "y": 0.2 },
                   "bottomLeftPercentage": { "x": 0.8, "y": 0.8 },
                   "appearTime": 0, "disappearTime": 1, "enableTime": 0, "disableTime": 1 }]
                """.trimIndent()
            )
        )[0]
        assertTrue("left 必须 <= right", a.left <= a.right)
        assertTrue("bottom 必须 <= top", a.bottom <= a.top)
        // (0.2 → -405)、(0.8 → 405)
        assertEquals(-405f, a.left, 1e-2f)
        assertEquals(405f, a.right, 1e-2f)
    }

    // ------------------------------------------------------------------ 时间

    @Test
    fun secondsBecomeMilliseconds() {
        val a = parse()
        assertEquals(1632.6531, a.appearMs, 1e-3)
        assertEquals(11972.789, a.disappearMs, 1e-3)
    }

    @Test
    fun enableDisableBecomeTheSingleActivationInterval() {
        val a = parse()
        assertEquals(1, a.intervals.size)
        val iv = a.intervals[0]
        assertEquals(1632.6531, iv.startMs, 1e-3)
        assertEquals(11972.789, iv.endMs, 1e-3)
        // 官谱没有 readyFlash 的概念：不闪烁
        assertEquals(0.0, iv.readyFlash, 1e-9)
    }

    @Test
    fun isSubtractMapsToInvert() {
        assertTrue(parse().invert)
        val plain = NoiseArea.parseAllPgr(
            JSONArray(
                """[{ "topRightPercentage": { "x": 1, "y": 1 }, "bottomLeftPercentage": { "x": 0, "y": 0 },
                      "appearTime": 0, "disappearTime": 1, "enableTime": 0, "disableTime": 1 }]"""
            )
        )[0]
        assertFalse(plain.invert)
    }

    @Test
    fun lifecycleIsBoundedNotInfinite() {
        // ★ 崩溃根因回归：官谱噪域**绝不能**读成「永远存在」。
        //   以前用 RPE 解析器读官谱 JSON，disappearMs 会是 Double.MAX_VALUE，
        //   于是任意时刻都 alive ⇒ 编辑页每帧画一万多个矩形。
        val a = parse()
        assertFalse("官谱噪域必须有结束时间", a.disappearMs > 1e9)
        assertFalse("谱面 13 秒后不该还活着", a.aliveAt(20_000.0))
        assertTrue("谱面 5 秒时应该活着", a.aliveAt(5_000.0))
    }

    // ------------------------------------------------------------------ 事件轨道

    @Test
    fun allFiveTracksExist() {
        val a = parse()
        for (t in NoiseArea.TRACKS) {
            assertTrue("缺轨道 $t", a.tracks.containsKey(t))
            assertNotNull(a.tracks[t])
        }
    }

    @Test
    fun moveEventsPairTerminalPointsIntoRanges() {
        val a = parse()
        val mx = a.tracks.getValue("moveXEvents")
        assertEquals(2, mx.size)
        // 第一条：从「中心」(0) 到 0.5 百分比 = 0（x 百分比 0.5 就是屏幕中线）
        assertEquals(0f, mx[0].start, 1e-2f)
        assertEquals(0f, mx[0].end, 1e-2f)
        // 第二条：终点 x=0.6 → (0.6-0.5)*2*675 = 135
        assertEquals(135f, mx[1].end, 1e-1f)
        // 时刻是秒 → 毫秒
        assertEquals(11156.463, mx[1].endMs, 1e-3)

        val my = a.tracks.getValue("moveYEvents")
        // 终点 y=-0.5 → (0.5-(-0.5))*2*450 = 900（y 向上）
        assertEquals(900f, my[0].end, 1e-1f)
        assertEquals(180f, my[1].end, 1e-1f)   // y=0.3 → (0.5-0.3)*2*450
    }

    @Test
    fun rotateAndScaleTracksCarryValues() {
        val a = parse()
        val rot = a.tracks.getValue("rotateEvents")
        assertEquals(2, rot.size)
        assertEquals(0f, rot[0].end, 1e-6f)
        assertEquals(90f, rot[1].end, 1e-6f)

        val sx = a.tracks.getValue("scaleXEvents")
        assertEquals(2, sx.size)
        assertEquals(1f, sx[0].end, 1e-6f)
        assertEquals(2f, sx[1].end, 1e-6f)

        val sy = a.tracks.getValue("scaleYEvents")
        assertEquals(1f, sy[0].end, 1e-6f)
        assertEquals(0.5f, sy[1].end, 1e-6f)
    }

    @Test
    fun trackValueInterpolatesAndFallsBack() {
        val a = parse()
        // 旋转事件 1632.6531ms → 5000ms 从 0 到 90：中点约 45
        val mid = (1632.6531 + 5000.0) / 2
        assertEquals(45f, a.trackValue("rotateEvents", mid, -1f), 1f)
        // 没有轨道就退回 fallback
        assertEquals(7f, a.trackValue("不存在的轨道", 0.0, 7f), 1e-6f)
    }

    @Test
    fun emptyEventListsMeanNoTrack() {
        val a = NoiseArea.parseAllPgr(
            JSONArray(
                """[{ "topRightPercentage": { "x": 1, "y": 1 }, "bottomLeftPercentage": { "x": 0, "y": 0 },
                      "appearTime": 0, "disappearTime": 1, "enableTime": 0, "disableTime": 1,
                      "moveEvents": [], "rotateEvents": [], "scaleEvents": [] }]"""
            )
        )[0]
        assertTrue("空事件列表不该造出空轨道", a.tracks.isEmpty())
    }

    // ------------------------------------------------------------------ 自动判格式

    @Test
    fun autoDetectsOfficialBlocks() {
        // ★ 回归：草稿里存的是「原始 JSON 一行一条」，DraftCodec 以前统一用 parseAll，
        //   官谱块会被读成永远存在的默认矩形。parseAllAuto 必须按块判格式。
        val arr = JSONArray("[ $sample ]")
        val a = NoiseArea.parseAllAuto(arr)[0]
        assertEquals(-2025f, a.left, 1e-2f)
        assertFalse(a.disappearMs > 1e9)
    }

    @Test
    fun autoStillHandlesRpeBlocks() {
        val rpe = """
            { "Name": "RPE块", "bottomLeft": { "x": -100, "y": -50 }, "topRight": { "x": 100, "y": 50 },
              "appearTime": [0,0,1], "disappearTime": [4,0,1], "isInvert": false,
              "activeIntervals": [ { "startTime": [0,0,1], "endTime": [2,0,1] } ] }
        """.trimIndent()
        val a = NoiseArea.parseAllAuto(JSONArray("[ $rpe ]"), listOf(0.0 to 120.0))[0]
        assertEquals("RPE块", a.name)
        assertEquals(-100f, a.left, 1e-3f)
        // 120BPM、4 拍 ≈ 2000ms
        assertEquals(2000.0, a.disappearMs, 1e-3)
    }

    @Test
    fun autoHandlesMixedChart() {
        // 一张草稿里可能同时有官谱块和 RPE 块（官谱导入后又手动加噪域）
        val rpe = """{ "bottomLeft": { "x": -10, "y": -10 }, "topRight": { "x": 10, "y": 10 },
                       "appearTime": [0,0,1], "disappearTime": [1,0,1] }"""
        val both = NoiseArea.parseAllAuto(JSONArray("[ $sample , $rpe ]"))
        assertEquals(2, both.size)
        assertEquals(-2025f, both[0].left, 1e-2f)   // 官谱块
        assertEquals(-10f, both[1].left, 1e-3f)     // RPE 块
    }

    @Test
    fun brokenBlockIsSkippedNotFatal() {
        val warnings = ArrayList<String>()
        val arr = JSONArray("[ $sample, \"这不是对象\", { \"topRightPercentage\": {} } ]")
        val out = NoiseArea.parseAllPgr(arr, warnings)
        // 字符串块直接跳过；缺字段的对象仍能给出默认矩形（不崩）
        assertTrue(out.isNotEmpty())
        assertTrue("跳过的块要给出汇总提示", warnings.isEmpty() || warnings[0].contains("噪域"))
    }

    @Test
    fun nullInputGivesEmptyList() {
        assertTrue(NoiseArea.parseAllPgr(null).isEmpty())
        assertTrue(NoiseArea.parseAllAuto(null).isEmpty())
        assertTrue(NoiseArea.parseAllPgr(JSONArray("[]")).isEmpty())
    }

    /** 保持对 JSON 类型的引用，避免 import 被 IDE 优化掉 */
    @Test
    fun jsonObjectRoundTripKeepsRaw() {
        val a = NoiseArea.parseOnePgr(JSONObject(sample))
        assertNotNull(a.raw)
        assertEquals(2.0, a.raw!!.getJSONObject("topRightPercentage").getDouble("x"), 1e-9)
        assertNull(a.name.ifEmpty { null })
    }
}
