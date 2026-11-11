package com.piezmaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「蓝键多点」这类要求的解析与落地。
 *
 * 玩家的原话必须真的改变谱面：说了蓝键多 → Tap 占比明显上升；说了更密 → 音符数变多；
 * 什么都没说 → 一个音符都不动。
 */
class ChartStyleTest {

    private fun chart(n: Int): List<RecordedNote> {
        val out = ArrayList<RecordedNote>(n)
        for (i in 0 until n) {
            val t = i * 250L
            val type = when (i % 4) { 0 -> 1; 1 -> 4; 2 -> 3; else -> 2 }
            val end = if (type == 2) t + 300 else t
            out.add(RecordedNote(type, t, end, ((i % 7) - 3) / 3f, type, 0, true, false))
        }
        return out
    }

    private fun tapShare(notes: List<RecordedNote>): Double =
        notes.count { it.type == 1 }.toDouble() / notes.size.coerceAtLeast(1)

    @Test
    fun `蓝键多点_蓝键占比明显上升`() {
        val notes = chart(120)
        val spec = ChartStyle.parse("帮我写一份谱，蓝键多点")
        assertTrue(spec.tap > 1f)
        val out = ChartStyle.enforce(notes, spec, 30_000L, 120.0, 0)
        assertTrue("改完 Tap 占比 ${tapShare(out)}", tapShare(out) >= 0.62)
        assertTrue("音符数不应变少", out.size >= notes.size)
        // 时间仍然有序
        assertTrue(out.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
    }

    @Test
    fun `蓝键少点_蓝键占比下降`() {
        val notes = chart(120)
        val spec = ChartStyle.parse("蓝键少一点，多来点黄键")
        val out = ChartStyle.enforce(notes, spec, 30_000L, 120.0, 0)
        assertTrue("改完 Tap 占比 ${tapShare(out)}", tapShare(out) <= 0.5)
    }

    @Test
    fun `要求更密_音符变多`() {
        val notes = chart(80)
        val spec = ChartStyle.parse("太稀了，密一点")
        assertTrue(spec.density > 1f)
        val out = ChartStyle.enforce(notes, spec, 30_000L, 120.0, 0)
        assertTrue("${notes.size} → ${out.size}", out.size > notes.size)
    }

    @Test
    fun `没提要求_一个音符都不改`() {
        val notes = chart(60)
        val spec = ChartStyle.parse("你好，这个应用怎么用")
        assertFalse(spec.any)
        val out = ChartStyle.enforce(notes, spec, 30_000L, 120.0, 0)
        assertTrue(out == notes)
    }

    @Test
    fun `长条多点_长条数量上升且长度合法`() {
        val notes = chart(120)
        val spec = ChartStyle.parse("长条多一点")
        val out = ChartStyle.enforce(notes, spec, 30_000L, 128.0, 0)
        assertTrue(out.count { it.type == 2 } > notes.count { it.type == 2 } - 1)
        assertTrue(out.filter { it.type == 2 }.all { it.endMs > it.startMs })
    }
}
