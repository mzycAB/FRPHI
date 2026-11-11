package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长按「撤销」= 重做：撤销掉的步骤要被完整记住，正向重放后谱面回到撤销前的样子。
 */
class UndoRedoTest {

    private fun note(type: Int, t: Long, x: Float = 0f) =
        RecordedNote(type, t, t, x, type, 0, true, false)

    @Test
    fun `改写_撤销后再重做回到新值`() {
        val old = note(1, 1000, 0f)
        val new = note(3, 1000, 0.5f)
        val notes = mutableListOf(new)            // 当前是新值
        val e = UndoEntry()
        e.noteModified(0, old)                    // 改动时记下旧值（模拟 setNote 的顺序）

        e.captureAfter(notes)                     // 撤销前回填"新值"
        e.undoNotes(notes)
        assertEquals("撤销后应回到旧值", old, notes[0])

        e.redoNotes(notes)
        assertEquals("重做后应回到新值", new, notes[0])
    }

    @Test
    fun `新增_撤销后删掉_重做后加回来`() {
        val n = note(2, 500)
        val notes = mutableListOf(note(1, 100))
        val e = UndoEntry()
        e.noteAdded(n)
        notes.add(n)

        e.captureAfter(notes)
        e.undoNotes(notes)
        assertEquals(1, notes.size)

        e.redoNotes(notes)
        assertEquals(2, notes.size)
        assertEquals(n, notes[1])
    }

    @Test
    fun `删除_撤销后插回原位_重做后再次删除`() {
        val a = note(1, 100); val b = note(2, 200); val c = note(3, 300)
        val notes = mutableListOf(a, c)           // b 被删掉了
        val e = UndoEntry()
        e.noteRemoved(1, b)

        e.captureAfter(notes)
        e.undoNotes(notes)
        assertEquals(listOf(a, b, c), notes)

        e.redoNotes(notes)
        assertEquals(listOf(a, c), notes)
    }

    @Test
    fun `一次手势里删加改混在一起_两边都对`() {
        val n1 = note(1, 100); val n2 = note(2, 200); val n3 = note(3, 300)
        val notes = mutableListOf(n1, n2, n3)
        val e = UndoEntry()
        // 记录：删掉下标 0，改写下标 0（此时是 n2），再加一个 n4
        e.noteRemoved(0, n1)
        val removed = notes.removeAt(0)
        e.noteModified(0, notes[0])               // 旧值 n2
        val n2new = n2.copy(posX = 0.9f)
        notes[0] = n2new
        val n4 = note(4, 400)
        e.noteAdded(n4)
        notes.add(n4)

        e.captureAfter(notes)
        e.undoNotes(notes)
        assertEquals(listOf(n1, n2, n3), notes)

        e.redoNotes(notes)
        assertEquals(3, notes.size)
        assertEquals(n2new, notes[0])
        assertEquals(n4, notes[2])
    }

    @Test
    fun `没有回填新值时_重做不会把音符改坏`() {
        val old = note(1, 100)
        val cur = note(1, 100, 0.3f)
        val notes = mutableListOf(cur)
        val e = UndoEntry()
        e.noteModified(0, old)
        // 故意不调用 captureAfter（模拟异常路径）
        e.undoNotes(notes)
        e.redoNotes(notes)
        assertEquals("没有新值就保持撤销后的样子，不要写 null", old, notes[0])
    }

    /** 剪贴板拖动排序也必须能撤销：撤销回拖动前的顺序，重做回拖动后的顺序 */
    @Test
    fun `剪贴板拖动排序_撤销回原顺序_重做回新顺序`() {
        val a = RecordView.ClipEntry(listOf(note(1, 100)), cut = false, id = 1)
        val b = RecordView.ClipEntry(listOf(note(1, 200)), cut = false, id = 2)
        val c = RecordView.ClipEntry(listOf(note(1, 300)), cut = false, id = 3)
        val entries = mutableListOf(a, b, c)

        val e = UndoEntry()
        e.clipBefore = ArrayList(entries)          // 拖动前的快照
        entries.add(2, entries.removeAt(0))        // 与面板拖动一致：把第 0 条移到第 2 条
        e.clipAfter = ArrayList(entries)

        // RecordView.undo() / redo() 恢复剪贴板的方式
        entries.clear()
        e.clipBefore?.let { entries.addAll(it) }
        assertEquals("撤销后应回到拖动前的顺序", listOf(a, b, c), entries)

        entries.clear()
        e.clipAfter?.let { entries.addAll(it) }
        assertEquals("重做后应回到拖动后的顺序", listOf(b, c, a), entries)
    }

    /**
     * AI 一整轮改动（整份草稿替换）的撤销步：RecordView.commitPendingUndo 的构造方式 ——
     * 「改动前音符按原下标倒序记 Removed」+「当前音符记 Added」。
     * 撤销必须原样回到改动前，重做必须回到改动后（AI 的结果）。
     */
    @Test
    fun `AI整轮替换_撤销回到改动前_重做回到改动后`() {
        val a = note(1, 100, 0f); val b = note(2, 200, 0.2f); val c = note(3, 300, -0.2f)
        val before = listOf(a, b, c)

        val a2 = a.copy(posX = 0.5f)                 // AI 改了第一个、又加了一个
        val d = note(4, 400, 0.9f)
        val cur = mutableListOf(a2, b, c, d)

        val e = UndoEntry()
        for (i in before.indices.reversed()) e.noteRemoved(i, before[i])
        for (n in cur) e.noteAdded(n)

        e.captureAfter(cur)
        e.undoNotes(cur)
        assertEquals("撤销后必须和 AI 改之前一模一样", before, cur)

        e.redoNotes(cur)
        assertEquals("重做后必须回到 AI 的结果", listOf(a2, b, c, d), cur)
    }

    /** 空谱面被 AI 整份写入：撤销要回到空，重做要写回来 */
    @Test
    fun `空谱被AI整份写入_撤销能清空_重做能写回`() {
        val cur = mutableListOf(note(1, 100), note(1, 200))
        val e = UndoEntry()
        for (n in cur) e.noteAdded(n)
        e.captureAfter(cur)

        e.undoNotes(cur)
        assertEquals(0, cur.size)

        e.redoNotes(cur)
        assertEquals(2, cur.size)
    }
}
