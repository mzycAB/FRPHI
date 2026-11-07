package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 直接改谱的两类细粒度操作：
 *  - 单个音符：改时间/位置/结束时间/**键型**/所属判定线/正反面/真假，只动给了的字段；
 *  - 判定线关键帧：同一时刻只改给了的字段（只给 sp 不能把姿态冲成 0），还能删掉某一帧。
 */
class AiChartEditTest {

    private fun holder(notes: List<RecordedNote> = mutableListOf(), lines: List<Line> = listOf(Line(0))): AiChart.Holder {
        val ls = lines.map { l ->
            Line(l.id).apply {
                posPct = l.posPct; speed = l.speed; moveX = l.moveX; moveY = l.moveY; rotate = l.rotate
                alpha = l.alpha; name = l.name
                keyframes.addAll(l.keyframes)
                invalidate()
            }
        }.toMutableList()
        return AiChart.Holder(ls, notes.sortedBy { it.startMs }.toMutableList(), 1)
    }

    @Test
    fun `fix 改键型 Tap 变长条会自动补 end`() {
        val h = holder(mutableListOf(RecordedNote(1, 1000, 1000, 0.5f, 1, 0, true, false)))
        val ok = AiChart.applyJsonLine("""{"fix":0,"note":2}""", h, 60_000, 120.0, 1)
        assertTrue(ok)
        val n = h.notes[0]
        assertEquals(2, n.type)
        assertEquals(4, n.pass)
        assertTrue("长条要有结束时间：${n.endMs}", n.endMs > n.startMs)
    }

    @Test
    fun `fix 只给 x 时其它字段不动`() {
        val h = holder(mutableListOf(RecordedNote(3, 800, 800, -0.25f, 3, 0, false, true)))
        AiChart.applyJsonLine("""{"fix":0,"x":0.75}""", h, 60_000, 120.0, 1)
        val n = h.notes[0]
        assertEquals(0.75f, n.posX, 0.0001f)
        assertEquals(3, n.type)
        assertEquals(800L, n.startMs)
        assertFalse("above 不能被动", n.above)
        assertTrue("fake 不能被动", n.fake)
    }

    @Test
    fun `fix 可以改真假与正反面`() {
        val h = holder(mutableListOf(RecordedNote(1, 500, 500, 0f, 1, 0, true, false)))
        AiChart.applyJsonLine("""{"fix":0,"fake":1}""", h, 60_000, 120.0, 1)
        assertTrue(h.notes[0].fake)
        AiChart.applyJsonLine("""{"fix":0,"above":0,"fake":0}""", h, 60_000, 120.0, 1)
        assertFalse(h.notes[0].above)
        assertFalse(h.notes[0].fake)
    }

    @Test
    fun `kf 只给 sp 不会把姿态冲成 0`() {
        val l = Line(0).apply {
            moveX = 0.4f; moveY = -0.2f; rotate = 30f; alpha = 0.75f
            keyframes.add(LineKeyframe(1000, 0.4f, -0.2f, 30f, 1f, 0.75f))
        }
        val h = holder(lines = listOf(l))
        AiChart.applyJsonLine("""{"kf":0,"t":1000,"sp":2.0}""", h, 60_000, 120.0, 1)
        val kf = h.lines[0].keyframes.first { it.timeMs == 1000L }
        assertEquals(2.0f, kf.speed, 0.001f)
        assertEquals(0.4f, kf.moveX, 0.001f)
        assertEquals(-0.2f, kf.moveY, 0.001f)
        assertEquals(30f, kf.rotate, 0.001f)
        assertEquals(0.75f, kf.alpha, 0.001f)
    }

    @Test
    fun `kf 在空线上新建时用线的姿态做基准`() {
        val l = Line(0).apply { moveX = 0.3f; rotate = 15f; alpha = 0.5f }
        val h = holder(lines = listOf(l))
        AiChart.applyJsonLine("""{"kf":0,"t":2000,"sp":1.5}""", h, 60_000, 120.0, 1)
        val kf = h.lines[0].keyframes.first { it.timeMs == 2000L }
        assertEquals(0.3f, kf.moveX, 0.001f)
        assertEquals(15f, kf.rotate, 0.001f)
        assertEquals(0.5f, kf.alpha, 0.001f)
        assertEquals(1.5f, kf.speed, 0.001f)
    }

    @Test
    fun `kfdel 删除指定时刻的帧`() {
        val l = Line(0).apply {
            keyframes.add(LineKeyframe(1000, 0f, 0f, 0f, 1f, 1f))
            keyframes.add(LineKeyframe(2000, 0f, 0f, 90f, 1f, 1f))
            invalidate()
        }
        val h = holder(lines = listOf(l))
        val ok = AiChart.applyJsonLine("""{"kfdel":0,"t":1000}""", h, 60_000, 120.0, 1)
        assertTrue(ok)
        assertEquals(1, h.lines[0].keyframes.size)
        assertEquals(2000L, h.lines[0].keyframes[0].timeMs)
        // 没有 t 就不能乱删
        assertFalse(AiChart.applyJsonLine("""{"kfdel":0}""", h, 60_000, 120.0, 1))
        assertEquals(1, h.lines[0].keyframes.size)
    }

    @Test
    fun `谱面摘要里带有关键帧清单`() {
        val l = Line(0).apply {
            keyframes.add(LineKeyframe(1500, 0.1f, 0.2f, 45f, 1.5f, 0.8f))
            invalidate()
        }
        val digest = AiChart.chartDigest(listOf(l), listOf(RecordedNote(1, 100, 100, 0f, 1, 0, true, false)), 50)
        assertTrue(digest.contains("kf line=0 t=1500"))
        assertTrue(digest.contains("sp=1.5"))
        assertTrue(digest.contains("a=0.8"))
    }
}
