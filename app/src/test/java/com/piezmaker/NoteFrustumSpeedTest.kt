package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：视锥时间窗必须按**音符流速**算，绝不能拿判定线自己的流速。
 *
 * 事故现场（2026-11-07，用户报「音符到上面就不渲染了」）：
 * 判定线流速 10、音符流速 1、编辑器缩放 0.1 时，窗口只有该有的 1/10
 * （±1913ms / 510px，而屏幕是 1200px），屏幕上沿近 200px 的音符整片被剔掉。
 * 复刻：`Scale0.1LineSpeed10`，修复前后「被漏」从 6 变成 0。
 */
class NoteFrustumSpeedTest {

    private fun buildNotes(every: Long = 134L, until: Long = 40_000L): List<RecordedNote> {
        val list = ArrayList<RecordedNote>()
        var t = 400L
        while (t <= until) {
            list.add(RecordedNote(1, t, t, 0.5f))   // 类型 / 起 / 止 / posX；speed 默认 1
            t += every
        }
        return list
    }

    /**
     * 复刻 drawNotesOfLine 的「窗口 → 二分切片 → 纵向判定」，
     * 返回（屏幕内的音符数、被漏掉的数）。
     */
    private fun probe(
        lineSpeed: Float, noteSpeed: Float, scale: Float,
        screenH: Float = 1200f, screenW: Float = 2000f, posPct: Int = 60, now: Long = 20_000L,
        notes: List<RecordedNote> = buildNotes()
    ): Pair<Int, Int> {
        val line = Line(0).apply { this.posPct = posPct; this.speed = lineSpeed }
        val kfs = listOf(LineKeyframe(0L, 0f, 0f, 0f, lineSpeed))
        val tr = SpeedTrack.build(kfs, lineSpeed)
        val base = 0.16f * (screenH / 720f) * scale
        val chartH = minOf(screenW, screenH * 16f / 9f) / (16f / 9f)
        val lcY = (screenH - chartH) / 2f + posPct / 100f * chartH
        // ★ 传的是音符流速（修复后的行为）
        val win = NoteFrustum.window(tr, base, screenH, noteSpeed, now)
        val all = notes.indices.sortedBy { notes[it].startMs }.toIntArray()
        val from = if (win == null) 0 else NoteFrustum.lowerBound(all, notes, win.first)
        val to = if (win == null) all.size else NoteFrustum.upperBound(all, notes, win.last)
        var onScreen = 0
        var dropped = 0
        for (k in all.indices) {
            val n = notes[all[k]]
            val d = ((tr.heightAt(now) - tr.heightAt(n.startMs)) * base * n.speed).toFloat()
            val y = lcY + d
            if (y < 0f || y > screenH) continue
            onScreen++
            if (k !in from until to) dropped++
        }
        return onScreen to dropped
    }

    @Test
    fun lineSpeed10NoteSpeed1_scale01_keepsEverythingOnScreen() {
        val (onScreen, dropped) = probe(lineSpeed = 10f, noteSpeed = 1f, scale = 0.1f)
        assertTrue("应该确实有一批音符在屏幕上", onScreen > 20)
        assertEquals("屏幕内的音符一个都不能被剔除", 0, dropped)
    }

    @Test
    fun lineSpeed1NoteSpeed1_keepsEverythingOnScreen() {
        val (onScreen, dropped) = probe(lineSpeed = 1f, noteSpeed = 1f, scale = 0.1f)
        assertTrue(onScreen > 20)
        assertEquals(0, dropped)
    }

    @Test
    fun playScale_keepsEverythingOnScreen() {
        val (onScreen, dropped) = probe(lineSpeed = 10f, noteSpeed = 1f, scale = 0.6f)
        assertTrue(onScreen > 3)
        assertEquals(0, dropped)
    }

    @Test
    fun lineNearTop_keepsEverythingOnScreen() {
        val (_, dropped) = probe(lineSpeed = 6f, noteSpeed = 1f, scale = 0.1f, posPct = 8)
        assertEquals(0, dropped)
    }

    /**
     * 窗口的「像素半径」必须≈屏幕高 × REACH_FACTOR，与判定线流速无关
     * （距离公式里判定线流速已经积进 H 了）。
     */
    @Test
    fun windowPixelReachMatchesReachFactor() {
        val screenH = 1200f
        for (lineSpeed in floatArrayOf(1f, 10f, 50f)) {
            for (noteSpeed in floatArrayOf(1f, 2f)) {
                val line = Line(0).apply { this.speed = lineSpeed }
                val kfs = listOf(LineKeyframe(0L, 0f, 0f, 0f, lineSpeed))
                val tr = SpeedTrack.build(kfs, lineSpeed)
                val base = 0.16f * (screenH / 720f) * 0.1f
                val now = 20_000L
                val win = NoteFrustum.window(tr, base, screenH, noteSpeed, now)!!
                // 窗口两端对应的屏幕距离（像素）
                val pxUp = (tr.heightAt(now) - tr.heightAt(win.first)) * base * noteSpeed
                val pxDown = (tr.heightAt(win.last) - tr.heightAt(now)) * base * noteSpeed
                val need = screenH * NoteFrustum.REACH_FACTOR
                // 上侧最多只能到谱面开头（t=0），下侧同理到 MAX_TIME；别把"谱面本来就没了"当漏画
                val maxUp = (tr.heightAt(now) - tr.heightAt(0L)) * base * noteSpeed
                val needUp = minOf(need.toDouble(), maxUp).toFloat()
                assertTrue(
                    "线速$lineSpeed/音符速$noteSpeed 上侧视野只有 ${pxUp.toInt()}px，至少要 ${needUp.toInt()}",
                    pxUp >= needUp - 1f
                )
                assertTrue(
                    "线速$lineSpeed/音符速$noteSpeed 下侧视野只有 ${pxDown.toInt()}px",
                    pxDown >= need - 1f
                )
            }
        }
    }
}
