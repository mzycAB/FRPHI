package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑模式搓时间轴：手指位移必须和画面位移 1:1（真机「不跟手」回归）。
 *
 * 两个历史 bug：
 *  1) scrubPxPerMs 被 coerceIn(0.004, 0.16) 锁死上限，高分屏/放大缩放与渲染脱钩；
 *  2) 标尺不含判定线瞬时流速 speedAt(now)。真机日志
 *     `HONOR ELN-W09 ... [keyframe] sp=10.000` ⇒ 画面 = 手指 × 10，
 *     主画布与判定线编辑界面都不跟手。
 */
class ScrubMathTest {

    /** 旧实现：只按基础比例，且锁死上限 */
    private fun oldBuggyScrubPxPerMs(heightPx: Int, scale: Float): Float =
        (0.16f * (heightPx / 720f) * scale).coerceIn(0.004f, 0.16f)

    @Test
    fun `基础比例跟屏高与缩放走`() {
        assertEquals(0.16f, ScrubMath.pxPerMs(720, 1f), 1e-6f)
        assertEquals(0.4266667f, ScrubMath.pxPerMs(1920, 1f), 1e-5f)
        assertEquals(0.5333333f, ScrubMath.pxPerMs(2400, 1f), 1e-5f)
        assertEquals(1.0666667f, ScrubMath.pxPerMs(2400, 2f), 1e-5f)
    }

    @Test
    fun `渲染标尺与搓动标尺在常用范围完全相等_别再脱钩`() {
        listOf(720, 1080, 1920, 2400, 3200).forEach { h ->
            listOf(0.05f, 0.1f, 0.3f, 0.6f, 1f, 2f, 4f, 100f).forEach { s ->
                assertEquals("h=$h scale=$s", ScrubMath.pxPerMs(h, s), ScrubMath.scrubPxPerMs(h, s, 1f), 1e-6f)
            }
        }
    }

    @Test
    fun `各分辨率缩放下常速谱手指位移等于画面位移`() {
        listOf(720, 1080, 1920, 2400, 3200).forEach { h ->
            listOf(0.5f, 1f, 2f, 4f).forEach { s ->
                val dt = ScrubMath.msForPixels(100f, h, s, 1f)
                assertEquals("h=$h scale=$s", 100f, ScrubMath.pixelsForMs(dt, h, s, 1f), 0.001f)
            }
        }
    }

    /** 真机日志 sp=10.000：这条不过就是「拖一点点飞一大截」 */
    @Test
    fun `流速10的谱面仍然1比1跟手_真机日志场景`() {
        val h = 2400
        val s = 0.1f                       // 编辑页默认缩放
        val sp = 10f                       // 日志里的关键帧流速
        val dt = ScrubMath.msForPixels(100f, h, s, sp)
        val visualPx = ScrubMath.pixelsForMs(dt, h, s, sp)
        assertEquals("手指 100px，画面应 100px", 100f, visualPx, 0.001f)
        // 旧实现（不含 speedAt）：100px 手指 → 1000px 画面
        val oldDt = 100f / ScrubMath.pxPerMs(h, s)
        val oldVisualPx = oldDt * ScrubMath.renderPxPerMs(h, s, sp)
        assertEquals(1000f, oldVisualPx, 0.01f)
        assertEquals(10f, oldVisualPx / 100f, 0.001f)
    }

    @Test
    fun `各流速下都1比1跟手_含高速与负流速`() {
        listOf(720, 1920, 2400, 3200).forEach { h ->
            listOf(0.1f, 0.6f, 1f, 2f).forEach { s ->
                listOf(0.5f, 1f, 2f, 5f, 10f, 50f, 100f, -10f).forEach { sp ->
                    val dt = ScrubMath.msForPixels(100f, h, s, sp)
                    val visual = ScrubMath.pixelsForMs(dt, h, s, sp)
                    assertEquals("h=$h scale=$s speed=$sp", 100f, visual, 0.001f)
                }
            }
        }
    }

    @Test
    fun `只夹流速下限不夹上限`() {
        val base = ScrubMath.pxPerMs(2400, 1f)
        assertEquals(base * 100f, ScrubMath.scrubPxPerMs(2400, 1f, 100f), 1e-4f)
        assertEquals(base * 10f, ScrubMath.scrubPxPerMs(2400, 1f, -10f), 1e-4f)
        assertEquals(base * ScrubMath.MIN_SCRUB_SPEED, ScrubMath.scrubPxPerMs(2400, 1f, 0.001f), 1e-4f)
        assertEquals(base * ScrubMath.MIN_SCRUB_SPEED, ScrubMath.scrubPxPerMs(2400, 1f, 0f), 1e-4f)
    }

    @Test
    fun `定格段不炸_但画面按谱面自身流速几乎不动`() {
        // 官谱 0.001 定格段：直接用瞬时比例做除数 ⇒ 100px 跳 187.5 秒
        val base = ScrubMath.pxPerMs(2400, 1f)
        assertTrue("不夹下限确实会炸", 100f / (base * 0.001f) > 100_000f)
        val dt = ScrubMath.msForPixels(100f, 2400, 1f, 0.001f)
        assertEquals(375f, dt, 0.01f)
        assertTrue("时间推进有界", dt < 1000f)
        // 画面本来就是定格的，动一点点才是对的
        assertEquals(0.2f, ScrubMath.pixelsForMs(dt, 2400, 1f, 0.001f), 0.01f)
    }

    @Test
    fun `非有限流速退回1`() {
        assertEquals(ScrubMath.pxPerMs(2400, 1f), ScrubMath.scrubPxPerMs(2400, 1f, Float.NaN), 1e-4f)
        assertEquals(ScrubMath.pxPerMs(2400, 1f), ScrubMath.scrubPxPerMs(2400, 1f, Float.POSITIVE_INFINITY), 1e-4f)
    }

    @Test
    fun `旧公式在2400屏把画面放大3_33倍_别把上限夹回来`() {
        val h = 2400
        val renderPxPerMs = ScrubMath.pxPerMs(h, 1f)
        val oldDt = 100f / oldBuggyScrubPxPerMs(h, 1f)
        assertEquals(333.333f, oldDt * renderPxPerMs, 0.01f)
        val newDt = ScrubMath.msForPixels(100f, h, 1f, 1f)
        assertEquals(100f, newDt * renderPxPerMs, 0.001f)
    }

    @Test
    fun `缩放2倍时旧公式差6_67倍`() {
        val h = 2400
        val oldDt = 100f / oldBuggyScrubPxPerMs(h, 2f)
        assertEquals(666.666f, oldDt * ScrubMath.pxPerMs(h, 2f), 0.02f)
    }

    @Test
    fun `720屏上旧公式本来就对_无需改的部分`() {
        val h = 720
        val oldDt = 100f / oldBuggyScrubPxPerMs(h, 1f)
        assertEquals(100f, oldDt * ScrubMath.pxPerMs(h, 1f), 0.001f)
    }

    @Test
    fun `余量累加器慢速拖动不丢位移`() {
        val acc = ScrubMath.Accumulator()
        var sum = 0
        var oldSum = 0
        repeat(100) {
            sum += acc.push(0.1f, 2400, 1f)                       // 每帧 0.1875ms
            oldSum += Math.round(0.1f / ScrubMath.scrubPxPerMs(2400, 1f))  // 旧做法：每帧都丢
        }
        assertEquals("逐帧 round 会把慢速拖动全吃掉", 0, oldSum)
        assertTrue("累计应接近 18.75ms，实得 $sum", sum >= 18 && sum <= 19)
    }

    @Test
    fun `余量累加器在高流速谱上仍不丢位移`() {
        val acc = ScrubMath.Accumulator()
        var sum = 0
        repeat(100) { sum += acc.push(0.1f, 2400, 0.1f, 10f) }  // 每帧 0.1875ms
        assertTrue("累计应接近 18.75ms，实得 $sum", sum >= 18 && sum <= 19)
    }

    @Test
    fun `余量累加器正负拖动可抵消`() {
        val acc = ScrubMath.Accumulator()
        var sum = 0
        sum += acc.push(100f, 2400, 1f)
        sum += acc.push(-100f, 2400, 1f)
        assertTrue("往返回到原点，实得 $sum", Math.abs(sum) <= 1)
    }

    @Test
    fun `极小缩放只夹搓动除数_不改渲染比例`() {
        // timelineScale 允许 0.01：渲染比例本来就是 0.0016，抬到下限会改变音符位置
        // 和 NoteFrustum 的剔除窗口，所以下限只能加在搓动的除数上。
        assertEquals(0.0016f, ScrubMath.pxPerMs(720, 0.01f), 1e-6f)
        assertTrue(ScrubMath.pxPerMs(720, 0.01f) < ScrubMath.MIN_PX_PER_MS)
        assertEquals(ScrubMath.MIN_PX_PER_MS, ScrubMath.scrubPxPerMs(720, 0.01f, 1f), 1e-6f)
        assertTrue(ScrubMath.msForPixels(1f, 720, 0.01f).isFinite())
    }

    @Test
    fun `重置后余量清零`() {
        val acc = ScrubMath.Accumulator()
        acc.push(0.3f, 2400, 1f)
        acc.reset()
        assertEquals(0f, acc.remainderMs(), 1e-6f)
    }
}
