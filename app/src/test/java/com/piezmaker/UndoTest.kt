package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * 撤销增量算法的对拍测试：
 * 随机做 200 步「放置 / 拖动改写 / 删除」，每步前存一份快照，然后逐步撤销，
 * 每一步撤销后都必须和对应快照完全一致。
 */
class UndoTest {

    private fun note(t: Long, x: Float = 0f) = RecordedNote(1, t, t, x, 1)

    @Test
    fun `随机操作序列逐步撤销都能回到当时的状态`() {
        val rnd = Random(20261026)
        val notes = ArrayList<RecordedNote>()
        val stack = ArrayDeque<UndoEntry>()
        val snapshots = ArrayList<List<RecordedNote>>()

        repeat(200) {
            val before = ArrayList(notes)
            val e = UndoEntry()

            // 一步里随机做 1~3 个操作（模拟一次手势里的多次改动）
            repeat(1 + rnd.nextInt(3)) {
                when (rnd.nextInt(3)) {
                    0 -> {
                        val n = note(rnd.nextInt(50_000).toLong(), rnd.nextFloat() * 2 - 1)
                        notes.add(n); e.noteAdded(n)
                    }
                    1 -> if (notes.isNotEmpty()) {
                        val idx = rnd.nextInt(notes.size)
                        e.noteModified(idx, notes[idx])
                        notes[idx] = notes[idx].copy(startMs = notes[idx].startMs + 10)
                    }
                    2 -> if (notes.isNotEmpty()) {
                        val idx = rnd.nextInt(notes.size)
                        e.noteRemoved(idx, notes.removeAt(idx))
                    }
                }
            }

            if (!e.isEmpty()) {
                stack.addLast(e)
                snapshots.add(before)
            }
        }

        assertTrue("应该累积了多步撤销", stack.size > 50)

        var undone = 0
        while (stack.isNotEmpty()) {
            val e = stack.removeAt(stack.lastIndex)
            val expected = snapshots.removeAt(snapshots.lastIndex)
            e.undoNotes(notes)
            assertEquals("第 ${undone + 1} 次撤销后的状态不对", expected, notes)
            undone++
        }
        assertEquals(0, notes.size)
    }

    @Test
    fun `删除中间的多个音符_撤销后位置与内容都还原`() {
        val notes = ArrayList<RecordedNote>()
        for (i in 0 until 10) notes.add(note(i * 100L, i / 10f))
        val before = ArrayList(notes)

        val e = UndoEntry()
        // 模拟框选删除：降序删 7,4,2
        for (idx in listOf(7, 4, 2)) e.noteRemoved(idx, notes.removeAt(idx))
        assertEquals(7, notes.size)

        e.undoNotes(notes)
        assertEquals(before, notes)
    }

    @Test
    fun `拖动改写只记第一次旧值_撤销回到拖动前`() {
        val notes = ArrayList<RecordedNote>()
        for (i in 0 until 5) notes.add(note(i * 100L, 0f))
        val before = ArrayList(notes)

        val e = UndoEntry()
        // 同一次手势里对下标 2 连续改写 5 次（拖动过程）
        repeat(5) { step ->
            e.noteModified(2, notes[2])
            notes[2] = notes[2].copy(startMs = notes[2].startMs + step * 10L, posX = 0.1f * step)
        }
        assertEquals(1, e.ops.size)

        e.undoNotes(notes)
        assertEquals(before, notes)
    }

    @Test
    fun `追加与删除混合_撤销顺序不影响结果`() {
        val notes = ArrayList<RecordedNote>()
        notes.add(note(0))
        notes.add(note(100))
        val before = ArrayList(notes)

        val e = UndoEntry()
        val b = note(200)
        notes.add(b); e.noteAdded(b)
        e.noteRemoved(0, notes.removeAt(0))
        notes.add(note(300)); e.noteAdded(note(300))

        e.undoNotes(notes)
        assertEquals(before, notes)
    }
}
