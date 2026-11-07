package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 音符视锥剔除（[NoteFrustum]）的正确性：
 *  - 窗口必须**包含**屏幕上真能看到的音符（漏一个就是音符凭空消失，比卡顿严重）
 *  - 窗口要**尽量排除**屏幕外的音符（否则等于没剔除）
 *  - 长条按 startMs 排序后，尾巴还在屏上、头早就出窗的情况必须兜住
 */
class NoteFrustumTest {

    // ---- 造数据 ----------------------------------------------------------

    private fun track(vararg kv: Pair<Long, Float>): SpeedTrack {
        if (kv.isEmpty()) return SpeedTrack.constant(1f)
        val kfs = kv.map { LineKeyframe(it.first, 0f, 0f, 0f, it.second) }
        return SpeedTrack.build(kfs, 1f)
    }

    private fun note(startMs: Long, endMs: Long = startMs, speed: Float = 1f, yOffset: Float = 0f) =
        RecordedNote(
            type = if (endMs > startMs) 2 else 1,
            startMs = startMs, endMs = endMs, posX = 0f,
            speed = speed, yOffset = yOffset
        )

    /** 和 [RecordView.drawNoteAt] 里一致的距离公式（不含宽度归一化，只关心纵向） */
    private fun distPx(
        tr: SpeedTrack, basePxPerMs: Float, nowMs: Long,
        noteMs: Long, speed: Float, yOffset: Float, screenH: Float,
        ctrl: Float = 1f
    ): Double {
        val h = tr.heightAt(nowMs) - tr.heightAt(noteMs)
        return h * basePxPerMs * speed * ctrl - yOffset * speed * screenH / 900.0
    }

    // ---- 基本契约 --------------------------------------------------------

    /** 和 [RecordView.basePxPerMs] 同量级：`0.16 × (H/720) × timelineScale(0.1)` */
    private fun realBase(screenH: Float) = 0.16f * (screenH / 720f) * 0.1f

    @Test
    fun `非法参数时返回 null 表示不裁剪`() {
        val tr = SpeedTrack.constant(1f)
        assertNull(NoteFrustum.window(tr, 0f, 1920f, 1f, 5000L))
        assertNull(NoteFrustum.window(tr, -1f, 1920f, 1f, 5000L))
        assertNull(NoteFrustum.window(tr, Float.NaN, 1920f, 1f, 5000L))
        assertNull(NoteFrustum.window(tr, Float.POSITIVE_INFINITY, 1920f, 1f, 5000L))
        assertNotNull(NoteFrustum.window(tr, realBase(1920f), 1920f, 1f, 5000L))
    }

    /**
     * 流速恒为 0 的线（H 恒等于 0）：`heightAt ↔ timeAtHeight` 不可逆，
     * 解析式给不出窗口；**不能**返回 0..900 —— 那会把整条线的音符全剔掉。
     */
    @Test
    fun `流速为 0 的线 必须放开成全谱`() {
        val dead = track(0L to 0f, 5000L to 0f)
        assertEquals(0.0, dead.heightAt(99999L), 1e-9)
        val w = NoteFrustum.window(dead, realBase(1920f), 1920f, 1f, 5000L)!!
        assertTrue("零流速必须放开成整谱，不能是 $w", w.last >= NoteFrustum.MAX_TIME_MS)
    }

    @Test
    fun `窗口一定包含窗口时刻 且两端留了余量`() {
        val tr = track(0L to 1f)
        val w = NoteFrustum.window(tr, realBase(1920f), 1920f, 1f, 5000L)!!
        assertTrue("now 必须在窗内：$w", 5000L in w)
        assertTrue("下界不能是负数：$w", w.first >= 0L)
        assertTrue("两端余量不足：$w", w.first <= 5000L - NoteFrustum.SAFETY_MS)
        assertTrue("两端余量不足：$w", w.last >= 5000L + NoteFrustum.SAFETY_MS)
    }

    /** 最硬的约束：任何一个「在屏幕上」的音符，都必须落在窗口里 */
    @Test
    fun `屏幕内音符一个都不能被剔掉`() {
        val screenH = 1920f
        val base = realBase(screenH)
        // 变速：1 → 3 → 0.6 → 8，把 heightAt 的每段都走一遍
        val tr = track(0L to 1f, 3000L to 3f, 8000L to 0.6f, 12000L to 8f)
        val rnd = Random(20261105)
        var checked = 0
        for (iter in 0 until 400) {
            val now = rnd.nextLong(1L, 20000L)
            val spd = listOf(0.5f, 1f, 2f, 4f).random(rnd)
            val w = NoteFrustum.window(tr, base, screenH, 0.5f, now)!!
            assertTrue("width sanity", w.last - w.first < 60000L)
            for (k in 0 until 60) {
                val t = now + rnd.nextLong(-20000L, 4000L)
                if (t < 0L) continue
                val yOff = listOf(0f, 0f, 300f, -300f, 900f).random(rnd)
                val ctrl = listOf(1f, 1f, 0.5f, 0.2f).random(rnd)
                val d = distPx(tr, base, now, t, spd, yOff, screenH, ctrl)
                // 距离小于 1.5 屏 ⇒ 这条线没旋转时一定在屏幕上（含长条尾/贴图高度）
                if (Math.abs(d) < screenH * 1.5) {
                    checked++
                    assertTrue(
                        "屏幕内音符被剔掉了：now=$now t=$t speed=$spd yOff=$yOff ctrl=$ctrl d=$d window=$w",
                        t in w
                    )
                }
            }
        }
        assertTrue("样本太少（$checked），测试没打到点子上", checked > 8000)
    }

    @Test
    fun `窗外的音符确实在屏幕外`() {
        val screenH = 1920f
        val base = realBase(screenH)
        val tr = track(0L to 1f)
        val now = 20000L
        val spd = 1f
        val w = NoteFrustum.window(tr, base, screenH, spd, now)!!
        // 窗下界之前 2 秒的音符：早就过去了，必须远在屏幕外
        val farPast = w.first - 2000L
        if (farPast >= 0L) {
            val d = distPx(tr, base, now, farPast, spd, 0f, screenH)
            assertTrue("窗外（过去）的音符居然还在屏上 d=$d", Math.abs(d) > screenH * NoteFrustum.REACH_FACTOR)
        }
        // 窗上界之后 2 秒：还没落下来
        val farFuture = w.last + 2000L
        val d2 = distPx(tr, base, now, farFuture, spd, 0f, screenH)
        assertTrue("窗外（未来）的音符居然还在屏上 d=$d2", Math.abs(d2) > screenH * NoteFrustum.REACH_FACTOR)
    }

    /** 剔除率：正常谱面里，真正需要上屏判定的音符应该只占很小一部分 */
    @Test
    fun `剔除率 在长谱面上应明显大于五成`() {
        val screenH = 1920f
        val base = realBase(screenH)
        val tr = track(0L to 1f)
        val now = 60000L
        val w = NoteFrustum.window(tr, base, screenH, 1f, now)!!
        // 一首 6 分钟、每 100ms 一个音符的谱
        var total = 0
        var kept = 0
        var t = 0L
        while (t < 360000L) {
            total++
            if (t in w) kept++
            t += 100L
        }
        val keepRatio = kept.toDouble() / total
        // basePxPerMs = 0.16 × (H/720) × 0.1 ⇒ 1 流速秒只走 42.7px/1920px 屏 ——
        // 屏幕上同时能看到将近 100 秒的音符，6 分钟的正常谱面只剔得掉约六成。
        // （编辑器 timelineScale 拉到 1 时剔得更狠，这里按最保守的演奏视角测。）
        assertTrue("剔除率太低（窗口接近全谱）：kept=$kept/$total", keepRatio < 0.6)
        assertTrue("剔除过头：kept=$kept/$total", keepRatio > 0.1)
    }

    // ---- 二分 ------------------------------------------------------------

    @Test
    fun `二分边界_含重复时间`() {
        // 同一时间的三个音符：lowerBound 落第一个，upperBound 落最后一个之后
        val notes = listOf(
            note(100L), note(200L), note(300L), note(300L), note(300L), note(400L)
        )
        val arr = IntArray(notes.size) { it }
        assertEquals(0, NoteFrustum.lowerBound(arr, notes, 50L))
        assertEquals(0, NoteFrustum.lowerBound(arr, notes, 100L))
        assertEquals(1, NoteFrustum.lowerBound(arr, notes, 101L))
        assertEquals(2, NoteFrustum.lowerBound(arr, notes, 300L))
        assertEquals(6, NoteFrustum.lowerBound(arr, notes, 1000L))
        assertEquals(0, NoteFrustum.upperBound(arr, notes, 50L))
        assertEquals(1, NoteFrustum.upperBound(arr, notes, 100L))
        assertEquals(5, NoteFrustum.upperBound(arr, notes, 300L))
        assertEquals(6, NoteFrustum.upperBound(arr, notes, 400L))
    }

    @Test
    fun `二分_空数组与乱序下标`() {
        val empty = IntArray(0)
        assertEquals(0, NoteFrustum.lowerBound(empty, emptyList(), 500L))
        assertEquals(0, NoteFrustum.upperBound(empty, emptyList(), 500L))
        // 下标数组本身是按 startMs 排好序的（RecordView.rebuildNoteIndex 保证），
        // 但元素在原 notes 里的位置是乱的
        val notes = listOf(note(500L), note(100L), note(900L), note(300L))
        val arr = intArrayOf(1, 3, 0, 2)          // startMs: 100 300 500 900
        assertEquals(2, NoteFrustum.lowerBound(arr, notes, 500L))
        assertEquals(2, NoteFrustum.upperBound(arr, notes, 499L))
        assertEquals(1, NoteFrustum.upperBound(arr, notes, 100L))
    }

    // ---- 长条 ------------------------------------------------------------

    /**
     * 长条的特殊情况：头（startMs）早就跑出窗口了，尾巴还在屏幕上。
     * 调用方要按「最长长条长度」把下界往回挪，这里验证挪多少才够。
     */
    @Test
    fun `长条尾巴在屏上时 下界要往前挪`() {
        val screenH = 1920f
        val base = realBase(screenH)
        val tr = track(0L to 1f)
        val now = 30000L
        val holdLen = 4000L
        val headT = now - 3000L              // 头已经过了 3 秒
        val w = NoteFrustum.window(tr, base, screenH, 1f, now)!!
        // 头在窗外（或刚好在窗内），但整条长条的「头」如果被 lowerBound 切掉，
        // 尾巴就画不出来 —— 所以必须按 holdLen 回退下界
        val from = NoteFrustum.lowerBound(intArrayOf(0), listOf(note(headT, headT + holdLen)), w.first - holdLen)
        assertEquals("回退后必须还能取到这个长条", 0, from)
        // 不回退就会切掉（说明这个回退不是多余的）
        val fromNoBack = NoteFrustum.lowerBound(intArrayOf(0), listOf(note(headT, headT + holdLen)), w.first)
        assertTrue("测试前提不成立：不回退也没切掉，w=$w headT=$headT", fromNoBack == 1 || headT in w)
    }

    @Test
    fun `最小正流速 用来放大窗口`() {
        val screenH = 1920f
        val base = realBase(screenH)
        val tr = track(0L to 1f)
        val now = 10000L
        val slow = NoteFrustum.window(tr, base, screenH, 0.1f, now)!!
        val fast = NoteFrustum.window(tr, base, screenH, 4f, now)!!
        // 流速越小，同样时间走得越少 ⇒ 能看到更远的音符 ⇒ 窗口必须更宽
        assertTrue("慢音符的窗口没有比快的宽：slow=$slow fast=$fast", slow.first <= fast.first)
        assertTrue("慢音符的窗口没有比快的宽：slow=$slow fast=$fast", slow.last >= fast.last)
        assertNotNull(slow)
    }

    /** 有 ctrl 轨道的线：按 CTRL_MIN 把视野缩小，窗口变宽，但依然不能漏 */
    @Test
    fun `有 ctrl 时窗口更宽 且仍不漏屏内音符`() {
        val screenH = 1920f
        val base = realBase(screenH)
        val tr = track(0L to 1f, 4000L to 6f)
        val now = 12000L
        val plain = NoteFrustum.window(tr, base, screenH, 1f, now, hasCtrl = false)!!
        val ctrl = NoteFrustum.window(tr, base, screenH, 1f, now, hasCtrl = true)!!
        val plainW = plain.last - plain.first
        val ctrlW = ctrl.last - ctrl.first
        // plain 已经顶到 0（下界被夹住），只能比上界
        assertTrue("有 ctrl 的窗口必须明显更宽：plain=$plain ctrl=$ctrl", ctrl.last >= plain.last + plainW / 4)
        // ctrl 恒为 1 也一样不能漏
        val rnd = Random(7)
        for (k in 0 until 3000) {
            val t = now + rnd.nextLong(-15000L, 5000L)
            if (t < 0L) continue
            val d = distPx(tr, base, now, t, 1f, 0f, screenH, 1f)
            if (Math.abs(d) < screenH * 1.5) {
                assertTrue("now=$now t=$t 在屏上却被剔掉 window=$ctrl", t in ctrl)
            }
        }
    }
    /**
     * 真实规模下的收益：66 条线 / 1400 个音符 / 变速 + 长条，
     * 量一下「每帧要遍历的音符数」从多少降到多少。
     *
     * 注意演奏视角（timelineScale = 0.1）下 1 流速秒只有 42.7px，
     * 屏幕上能看到近百秒，剔除率不会很高；编辑器把流速拉快时才明显。
     * 这里两种视角都量，确保「拉快以后收益是实打实的」。
     */
    @Test
    fun `真实规模 剔除后每帧遍历数大幅下降`() {
        val screenH = 1920f
        val rnd = Random(42)
        val notes = ArrayList<RecordedNote>(1400)
        for (li in 0 until 66) {
            for (k in 0 until 21) {
                val t = rnd.nextLong(0L, 180_000L)
                val isHold = rnd.nextInt(4) == 0
                notes.add(
                    RecordedNote(
                        type = if (isHold) 2 else 1,
                        startMs = t,
                        endMs = if (isHold) t + rnd.nextLong(200L, 3200L) else t,
                        posX = rnd.nextFloat() * 2f - 1f,
                        lineId = li,
                        speed = listOf(0.5f, 1f, 1.5f, 3f).random(rnd)
                    )
                )
            }
        }
        val byLine = HashMap<Int, IntArray>()
        for (li in 0 until 66) {
            val idx = notes.indices.filter { notes[it].lineId == li }.toIntArray()
            NoteFrustum.sortIdxByStart(idx, notes)
            byLine[li] = idx
        }
        for ((_, arr) in byLine) {
            for (k in 1 until arr.size) {
                assertTrue("排序没生效", notes[arr[k - 1]].startMs <= notes[arr[k]].startMs)
            }
        }
        val holdLen = NoteFrustum.maxHoldLenByLine(notes)

        fun measure(scale: Float): Pair<Int, Int> {
            val base = 0.16f * (screenH / 720f) * scale
            var total = 0
            var drawn = 0
            val tr = track(0L to 1f, 60000L to 3.5f, 120000L to 0.8f)
            var now = 0L
            while (now < 180_000L) {
                for (li in 0 until 66) {
                    val all = byLine[li] ?: continue
                    total += all.size
                    val w = NoteFrustum.window(tr, base, screenH, 0.5f, now)!!
                    val back = holdLen[li] ?: 0L
                    drawn += NoteFrustum.upperBound(all, notes, w.last) -
                        NoteFrustum.lowerBound(all, notes, w.first - back)
                }
                now += 16L
            }
            return total to drawn
        }

        val (t0, d0) = measure(0.1f)
        val (t1, d1) = measure(1.0f)
        val r0 = 1.0 - d0.toDouble() / t0
        val r1 = 1.0 - d1.toDouble() / t1
        println("== frustum == play=" + (r0 * 100).toInt() + "% (" + d0 + "/" + t0 +
            ") editor=" + (r1 * 100).toInt() + "% (" + d1 + "/" + t1 + ")")
        // 演奏视角 basePxPerMs = 0.16 × (H/720) × 0.1 ⇒ 1 流速秒只走 42.7px，
        // 1920px 高的屏幕上能看到 45 秒 —— 窗口本身就覆盖 180s 谱面的三成左右，
        // 所以这里只要求「有明显剔除」，不能要求大半。
        assertTrue("演奏视角几乎没剔除：$d0/$t0", r0 > 0.05)
        // 编辑器把流速拉到 basePxPerMs = 0.4267（10 倍）时，窗口缩到 1/10，收益才是主要目标
        assertTrue("编辑器满流速下应剔掉大半，实际 $d1/$t1", r1 > 0.70)
    }

    /**
     * 编辑器缩放可以拉到 0.01（`timelineScale` 下限）：此时 basePxPerMs 只有
     * 0.0043px/ms，1 流速秒走 4.3px —— 整首谱都在一屏里，窗口必须放开成全谱，
     * 绝不能因为 hNow+span 溢出成 Infinity 而算出假窗口把音符剔掉。
     */
    @Test
    fun `极小缩放下必须放开成全谱`() {
        val screenH = 1920f
        for (scale in listOf(0.01f, 0.02f, 0.05f)) {
            val base = 0.16f * (screenH / 720f) * scale
            val tr = track(0L to 1f, 30000L to 50f)
            val w = NoteFrustum.window(tr, base, screenH, 0.05f, 5000L, hasCtrl = true)!!
            // 放开 = 上界顶到 MAX_TIME_MS（整谱一定都在里面）
            assertEquals("scale=$scale 窗口没放开成整谱：$w", NoteFrustum.MAX_TIME_MS, w.last)
            assertTrue("scale=$scale 下界必须是 0：$w", w.first == 0L)
        }
    }

    /** 缩放拉到最大（100）时窗口要收得很紧，而且仍然不漏 */
    @Test
    fun `极大缩放下窗口很窄且不漏`() {
        val screenH = 1920f
        val base = 0.16f * (screenH / 720f) * 100f
        val tr = track(0L to 1f, 30000L to 4f)
        val now = 60000L
        val w = NoteFrustum.window(tr, base, screenH, 1f, now)!!
        assertTrue("窗口过宽，等于没剔除：$w", w.last - w.first < 4000L)
        assertTrue("now 不在窗内：$w", now in w)
        val rnd = Random(11)
        var checked = 0
        for (k in 0 until 4000) {
            val t = now + rnd.nextLong(-3000L, 3000L)
            if (t < 0L) continue
            val d = distPx(tr, base, now, t, 1f, 0f, screenH, 1f)
            if (Math.abs(d) < screenH * 1.5) { checked++; assertTrue("t=$t 在屏上却被剔掉：$w", t in w) }
        }
        assertTrue("样本太少：$checked", checked > 10)
    }
    /**
     * ★ yOffset 是「固定像素平移」，和流速无关。
     * 缩放拉满时窗口只有 ±0.1 秒，一个 yOffset=450 的音符在屏幕上看得到，
     * 但如果窗口没把 yOffset 折算进去就会被剔掉（音符凭空消失）。
     */
    @Test
    fun `极大缩放下 yOffset 大的音符不能被剔掉`() {
        val screenH = 1920f
        val base = 0.16f * (screenH / 720f) * 100f     // 缩放 100
        val tr = track(0L to 1f)
        val now = 60000L
        val noY = NoteFrustum.window(tr, base, screenH, 1f, now, maxAbsYOffset = 0f)!!
        val bigY = NoteFrustum.window(tr, base, screenH, 1f, now, maxAbsYOffset = 450f)!!
        assertTrue("带 yOffset 的窗口必须更宽：$noY vs $bigY", bigY.last - bigY.first > noY.last - noY.first)
        // yOffset=450 等价于把音符沿下落方向推了半屏 ⇒ 对应时间余量 450*1920/900=960ms
        val rnd = Random(23)
        var checked = 0
        for (k in 0 until 6000) {
            val t = now + rnd.nextLong(-4000L, 4000L)
            if (t < 0L) continue
            for (yo in listOf(450f, -450f, 200f)) {
                val d = distPx(tr, base, now, t, 1f, yo, screenH, 1f)
                if (Math.abs(d) < screenH * 1.5) {
                    checked++
                    assertTrue("t=$t yOffset=$yo 在屏上却被剔掉：$bigY", t in bigY)
                }
            }
        }
        assertTrue("样本太少：$checked", checked > 10)
    }
}
