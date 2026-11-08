package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 判定线快照必须**完整**：撤销是「整份还原线」，快照里漏一个字段，
 * 撤销后那个字段就会停在"改之后"的值上（玩家会以为撤销坏了）。
 *
 * 实际漏过两个：整线透明度 `alpha`（面板「透明度」输入框）和原始文字事件
 * `textEvents`（图片线的副标题 / 文字线按时间换字）。
 */
class LineSnapshotTest {

    private fun richLine() = Line(7).apply {
        posPct = 42
        moveX = 0.25f; moveY = -0.5f; rotate = 30f
        speed = 1.75f
        alpha = 0.35f
        name = "L7"
        texture = "bg.png"
        lineText = "文字"
        scaleX = 1.5f; scaleY = 0.75f
        zOrder = 3
        fatherId = 2
        rotWithFather = true
        keyframes.add(LineKeyframe(1000, 0.1f, 0.2f, 10f, 1.2f, 0.8f))
        ctrlYEvents.add(CtrlEvent(300f, 1f, 0))
        inclineEvents.add(InclineEvent(0L, 5000L, 0f, 30f, 0))
        textEvents.add(TextEvent(0, 5000, "副标题", "副标题2", 1, "font.ttf", 2))
        invalidate()
    }

    @Test
    fun `快照还原后所有字段都回到原值`() {
        val l = richLine()
        val snap = l.snapshot()

        // 改成一堆别的值（模拟一次「改素材 / 改透明度 / 改文字」的编辑）
        l.posPct = 90
        l.moveX = 0f; l.moveY = 0f; l.rotate = 0f
        l.speed = 5f
        l.alpha = 1f
        l.name = "改过"
        l.texture = "other.png"
        l.lineText = "改过的文字"
        l.scaleX = 3f; l.scaleY = 3f
        l.zOrder = 0
        l.fatherId = -1
        l.rotWithFather = false
        l.keyframes.clear()
        l.ctrlYEvents.clear()
        l.inclineEvents.clear()
        l.textEvents.clear()

        l.restore(snap)

        assertEquals(42, l.posPct)
        assertEquals(0.25f, l.moveX, 1e-6f)
        assertEquals(-0.5f, l.moveY, 1e-6f)
        assertEquals(30f, l.rotate, 1e-6f)
        assertEquals(1.75f, l.speed, 1e-6f)
        assertEquals("整线透明度漏进快照 ⇒ 撤销后透明度回不来", 0.35f, l.alpha, 1e-6f)
        assertEquals("L7", l.name)
        assertEquals("bg.png", l.texture)
        assertEquals("文字", l.lineText)
        assertEquals(1.5f, l.scaleX, 1e-6f)
        assertEquals(0.75f, l.scaleY, 1e-6f)
        assertEquals(3, l.zOrder)
        assertEquals(2, l.fatherId)
        assertTrue(l.rotWithFather)
        assertEquals(1, l.keyframes.size)
        assertEquals(1000L, l.keyframes[0].timeMs)
        assertEquals(1, l.ctrlYEvents.size)
        assertEquals(1, l.inclineEvents.size)
        assertEquals("文字事件漏进快照 ⇒ 撤销后副标题停在改后的内容上", 1, l.textEvents.size)
        assertEquals("副标题", l.textEvents[0].start)
        assertEquals(2, l.textEvents[0].linkGroup)
    }

    @Test
    fun `快照是引用拷贝_之后改线不会污染快照`() {
        val l = Line(1).apply { alpha = 0.5f; textEvents.add(TextEvent(0, 100, "a", "b")) }
        val snap = l.snapshot()
        l.alpha = 1f
        l.textEvents.add(TextEvent(0, 200, "c", "d"))
        assertEquals(0.5f, snap.alpha, 1e-6f)
        assertEquals(1, snap.text.size)
    }

    /** 关键帧/文字事件的数量要算进撤销栈预算（不然 big song 上快照会偷偷吃内存） */
    @Test
    fun `快照引用数把文字事件也算上`() {
        val l = Line(1).apply {
            repeat(4) { keyframes.add(LineKeyframe(it * 1000L, 0f, 0f, 0f, 1f, 1f)) }
            repeat(3) { textEvents.add(TextEvent(0, 100, "a", "b")) }
            invalidate()
        }
        val e = UndoEntry()
        e.linesBefore = listOf(l.snapshot())
        assertEquals(7L, e.snapRefs())
    }

    @Test
    fun `没有文字事件时的默认值保持兼容`() {
        val snap = UndoEntry.LineSnap(1, 50, 0f, 0f, 0f, 1f, "x", emptyList())
        assertEquals(1f, snap.alpha, 1e-6f)
        assertEquals(0, snap.text.size)
        assertEquals(0, snap.keyframes.size)
    }
}
