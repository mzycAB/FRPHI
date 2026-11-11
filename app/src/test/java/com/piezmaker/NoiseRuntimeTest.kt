package com.piezmaker

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 噪域运行时：区域命中（含旋转）、断触状态机、亮度脉冲。
 *
 * 断触规则照 Phigros：**进了亮着的噪域就一直失效到抬起**，
 * 中途划出去也不恢复——这条最容易写错，单独守。
 */
class NoiseRuntimeTest {

    private val CHART_W = 1350f
    private val CHART_H = 900f
    private val SCR_W = 1350f
    private val SCR_H = 900f   // 1:1 视口，世界坐标 = 屏幕偏移，方便断言

    /** 一块覆盖屏幕中央 -200~200 的噪域：120BPM 下第 8~16 拍亮（4000~8000ms） */
    private fun area(rot: Boolean = false, scale: Float = 1f): NoiseArea {
        val tracks = LinkedHashMap<String, List<NoiseArea.NoiseEvent>>()
        if (rot) {
            tracks["rotateEvents"] = listOf(NoiseArea.NoiseEvent(0.0, 4000.0, 0f, 45f, 1, 0f, 0f))
        }
        tracks["scaleXEvents"] = listOf(NoiseArea.NoiseEvent(0.0, 4000.0, 1f, scale))
        tracks["scaleYEvents"] = listOf(NoiseArea.NoiseEvent(0.0, 4000.0, 1f, scale))
        return NoiseArea(
            name = "t",
            left = -200f, bottom = -200f, right = 200f, top = 200f,
            appearMs = 0.0, disappearMs = 16000.0,
            intervals = listOf(NoiseArea.Interval(4000.0, 8000.0, 0.5)),
            tracks = tracks
        )
    }

    @Test
    fun activeAreaJamsFingerUntilRelease() {
        val rt = NoiseRuntime(listOf(area()))
        // 亮之前：屏幕中心不掐
        assertFalse(rt.updateFinger(1, true, 675f, 450f, 2000.0, CHART_W, CHART_H, SCR_W, SCR_H))
        // 亮之后：中心 (675,450) 就是世界原点，落在区域内
        assertTrue(rt.updateFinger(1, true, 675f, 450f, 5000.0, CHART_W, CHART_H, SCR_W, SCR_H))
        // 划到区域外（右上角很远处）——仍然失效，直到抬起
        assertTrue(rt.updateFinger(1, true, 20f, 20f, 5200.0, CHART_W, CHART_H, SCR_W, SCR_H))
        // 抬起后恢复
        assertFalse(rt.updateFinger(1, false, 20f, 20f, 5300.0, CHART_W, CHART_H, SCR_W, SCR_H))
    }

    @Test
    fun inactiveAreaDoesNotJam() {
        val rt = NoiseRuntime(listOf(area()))
        // 区域存在但没到激活区间（2000ms 时还没亮）
        assertNull(rt.jammedAt(675f, 450f, 2000.0, CHART_W, CHART_H, SCR_W, SCR_H))
        // 激活中则命中
        assertNotNull(rt.jammedAt(675f, 450f, 5000.0, CHART_W, CHART_H, SCR_W, SCR_H))
        // 激活结束之后不命中
        assertNull(rt.jammedAt(675f, 450f, 9000.0, CHART_W, CHART_H, SCR_W, SCR_H))
    }

    @Test
    fun outsideAreaDoesNotJam() {
        val rt = NoiseRuntime(listOf(area()))
        // 世界 x=+300 在区域右边界(200)之外 → 屏幕 x = 675 + 300 = 975
        assertNull(rt.jammedAt(975f, 450f, 5000.0, CHART_W, CHART_H, SCR_W, SCR_H))
        // 正好在边界内一点点
        assertNotNull(rt.jammedAt(860f, 450f, 5000.0, CHART_W, CHART_H, SCR_W, SCR_H))
    }

    @Test
    fun sampleMapsWorldToScreenAndScales() {
        val a = area(scale = 1f)
        val rt = NoiseRuntime(listOf(a))
        val s = rt.sample(a, 0.0, CHART_W, CHART_H, SCR_W, SCR_H)
        assertNotNull(s)
        // 世界 (-200, +200) → 屏幕 (675-200, 450-200)
        assertEquals(475f, s!!.xs[0], 1e-2f)
        assertEquals(250f, s.ys[0], 1e-2f)
        // 世界 (-200, -200) → 屏幕 (475, 650)
        assertEquals(475f, s.xs[3], 1e-2f)
        assertEquals(650f, s.ys[3], 1e-2f)
        assertEquals(675f, s.cx, 1e-2f)
        assertEquals(450f, s.cy, 1e-2f)
    }

    @Test
    fun scaleShrinksAroundCenter() {
        // 4000ms 时缩放到 0.25：区域只剩中心附近 100px 见方
        val a = area(scale = 0.25f)
        val rt = NoiseRuntime(listOf(a))
        assertNull(rt.jammedAt(860f, 450f, 4000.0, CHART_W, CHART_H, SCR_W, SCR_H))
        assertNotNull(rt.jammedAt(675f, 450f, 4000.0, CHART_W, CHART_H, SCR_W, SCR_H))
    }

    @Test
    fun rotationTurnsSquareIntoDiamond() {
        // 4000ms 时转了 45°：原来在正方形右边缘内侧的点会被转出去
        val a = area(rot = true)
        val rt = NoiseRuntime(listOf(a))
        val s = rt.sample(a, 4000.0, CHART_W, CHART_H, SCR_W, SCR_H)
        assertNotNull(s)
        // 旋转后角点不再是轴对齐的：xs 里应出现不同的 y 值
        assertTrue(Math.abs(s!!.ys[0] - s.ys[1]) > 1f)
    }

    @Test
    fun intensityPulsesBeforeActivationThenFull() {
        val a = area()
        val rt = NoiseRuntime(listOf(a))
        // 激活中 = 1
        assertEquals(1f, rt.intensityAt(a, 5000.0), 1e-4f)
        // 激活前 0.5s（readyFlash）内：0~0.6 之间起伏
        val v = rt.intensityAt(a, 3800.0)
        assertTrue("预备脉冲应在 0~0.6 之间，实际 $v", v in 0f..0.6001f)
        // 远离激活时 = 0
        assertEquals(0f, rt.intensityAt(a, 1000.0), 1e-4f)
    }

    @Test
    fun newJamCounterOnlyCountsTransitions() {
        val rt = NoiseRuntime(listOf(area()))
        rt.beginFrame()
        rt.updateFinger(1, true, 675f, 450f, 5000.0, CHART_W, CHART_H, SCR_W, SCR_H)
        rt.updateFinger(1, true, 675f, 450f, 5100.0, CHART_W, CHART_H, SCR_W, SCR_H)
        // 同一根手指只算一次断触
        assertEquals(1, rt.newJams)
        assertEquals(1, rt.jammedCount())
    }

    @Test
    fun parsesReadyFlashAndIntervalsFromJson() {
        val arr = JSONArray("""
            [{
              "Name": "r",
              "bottomLeft": {"x": -100, "y": -100}, "topRight": {"x": 100, "y": 100},
              "appearTime": [0,0,1], "disappearTime": [8,0,1],
              "activeIntervals": [{"startTime":[2,0,1],"endTime":[4,0,1],"readyFlash":0.25}]
            }]
        """.trimIndent())
        val a = NoiseArea.parseAll(arr, listOf(0.0 to 120.0))[0]
        assertEquals(0.25, a.intervals[0].readyFlash, 1e-6)
        val rt = NoiseRuntime(listOf(a))
        // 1000ms 激活，readyFlash 0.25 秒 → 750ms 起开始脉冲
        assertEquals(0f, rt.intensityAt(a, 600.0), 1e-4f)
        assertTrue(rt.intensityAt(a, 800.0) > 0f)
    }

    @Test
    fun warningAreaFlashesButDoesNotJam() {
        val rt = NoiseRuntime(listOf(area().edited(warning = true)))
        // 激活时段、手指落在区域内：警示区不断触
        assertFalse(rt.updateFinger(1, true, 675f, 450f, 5000.0, CHART_W, CHART_H, SCR_W, SCR_H))
        assertEquals(0, rt.jammedCount())
    }

    @Test
    fun hiddenAreaIsNotSampled() {
        val a = area().edited(visible = false)
        val rt = NoiseRuntime(listOf(a))
        assertNull(rt.sample(a, 5000.0, CHART_W, CHART_H, SCR_W, SCR_H))
        // 隐藏的也不掐手
        assertNull(rt.jammedAt(675f, 450f, 5000.0, CHART_W, CHART_H, SCR_W, SCR_H))
    }
}
