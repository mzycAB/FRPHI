package com.piezmaker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 按判定线分组的音符下标：HashMap 版与旧线性版结果必须完全一致 */
class NoteIndexTest {

    private fun note(line: Int, t: Long) = RecordedNote(
        type = 1, startMs = t, endMs = t, posX = 0f, lineId = line
    )

    private fun build(notes: List<RecordedNote>) =
        NoteIndex.build(notes) { arr -> NoteFrustum.sortIdxByStart(arr, notes) }

    /** 旧实现（线性找线 id）：作为结果对照 */
    private fun legacy(notes: List<RecordedNote>): Map<Int, List<Int>> {
        val map = LinkedHashMap<Int, MutableList<Int>>()
        for (i in notes.indices) map.getOrPut(notes[i].lineId) { ArrayList() }.add(i)
        map.values.forEach { it.sortBy { idx -> notes[idx].startMs } }
        return map
    }

    @Test
    fun `分组与线内升序`() {
        val notes = listOf(
            note(2, 300), note(1, 200), note(2, 100),
            note(1, 50), note(2, 200), note(3, 10)
        )
        val r = build(notes)
        assertEquals(listOf(2, 1, 3), r.ids.toList())
        // 线 2：下标 0(300) 2(100) 4(200) → 升序后 2,4,0
        assertArrayEquals(intArrayOf(2, 4, 0), r.arrays[0])
        assertArrayEquals(intArrayOf(3, 1), r.arrays[1])
        assertArrayEquals(intArrayOf(5), r.arrays[2])
        assertNull("线数 ≤ 8 不用查表", r.slot)
    }

    @Test
    fun `与旧线性实现结果完全一致_随机数据`() {
        val rnd = java.util.Random(20261108L)
        repeat(30) {
            val n = 20 + rnd.nextInt(400)
            val lineCount = 1 + rnd.nextInt(20)
            val notes = (0 until n).map { note(rnd.nextInt(lineCount), rnd.nextInt(50000).toLong()) }
            val r = build(notes)
            val old = legacy(notes)
            assertEquals("线集合", old.keys.toSet(), r.ids.toSet())
            for (k in r.ids.indices) {
                val id = r.ids[k]
                assertEquals("line=$id 的下标列表", old[id], r.arrays[k].toList())
            }
            for (a in r.arrays) {
                for (i in 1 until a.size) assertTrue("线内升序", notes[a[i - 1]].startMs <= notes[a[i]].startMs)
            }
        }
    }

    @Test
    fun `线多时提供slot查表`() {
        val notes = (0 until 200).map { note(it % 12, (200 - it).toLong()) }
        val r = build(notes)
        assertNotNull(r.slot)
        assertEquals(12, r.slot!!.size)
        for (k in r.ids.indices) assertEquals(k, r.slot!![r.ids[k]])
    }

    @Test
    fun `741线大谱仍能在O_n完成且结果正确`() {
        // 旧实现两遍各要线性扫最多 741 个 id；这里 3 万音符 × 741 线，
        // 用 HashMap 后是纯线性。断言结果正确 + 能跑完（不设脆弱的时间阈值）。
        val notes = (0 until 30000).map { i -> note(i % 741, ((i * 7919) % 100000).toLong()) }
        val r = build(notes)
        assertEquals(741, r.ids.size)
        assertNotNull(r.slot)
        var total = 0
        for (a in r.arrays) {
            total += a.size
            for (i in 1 until a.size) assertTrue(notes[a[i - 1]].startMs <= notes[a[i]].startMs)
        }
        assertEquals(30000, total)
    }

    @Test
    fun `空谱面`() {
        val r = build(emptyList())
        assertEquals(0, r.ids.size)
        assertEquals(0, r.arrays.size)
        assertNull(r.slot)
    }
}
