package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 噪域时间索引：正确性 + 规模回归。
 *
 * 存在的理由就是官谱那张 14355 块噪域的谱面 —— 没有索引时编辑页每帧要画一万多个矩形，
 * 直接卡死。所以这里除了常规的区间查询正确性，还要**按真实规模**跑一遍，
 * 守住「同一时刻只返回少数几块」和「查询足够快」这两条。
 */
class NoiseTimeIndexTest {

    private fun area(appear: Double, disappear: Double, tag: String = "") = NoiseArea(
        name = tag,
        left = -100f, bottom = -100f, right = 100f, top = 100f,
        appearMs = appear, disappearMs = disappear
    )

    private fun queryAt(areas: List<NoiseArea>, t: Double): List<String> {
        val idx = NoiseTimeIndex()
        idx.build(areas)
        val out = ArrayList<NoiseArea>()
        val ids = NoiseTimeIndex.IntList()
        idx.query(t, out, ids)
        assertEquals("区域与下标必须一一对应", out.size, ids.size)
        // 下标必须回指到同一批对象
        for (k in out.indices) {
            assertTrue("下标越界", ids[k] in areas.indices)
            assertTrue("下标与对象不符", areas[ids[k]] === out[k])
        }
        return out.map { it.name }
    }

    @Test
    fun returnsOnlyAliveAreas() {
        val areas = listOf(
            area(0.0, 1000.0, "a"),
            area(500.0, 1500.0, "b"),
            area(1200.0, 2000.0, "c")
        )
        assertEquals(listOf("a"), queryAt(areas, 100.0).sorted())
        assertEquals(listOf("a", "b"), queryAt(areas, 700.0).sorted())
        assertEquals(listOf("b", "c"), queryAt(areas, 1300.0).sorted())
        assertEquals(listOf("c"), queryAt(areas, 1900.0).sorted())
        assertEquals(emptyList<String>(), queryAt(areas, 2100.0))
        assertEquals(emptyList<String>(), queryAt(areas, -1.0))
    }

    @Test
    fun boundariesAreHalfOpen() {
        val areas = listOf(area(1000.0, 2000.0, "x"))
        // appearMs 包含，disappearMs 不包含（与 aliveAt 一致）
        assertEquals(emptyList<String>(), queryAt(areas, 999.999))
        assertEquals(listOf("x"), queryAt(areas, 1000.0))
        assertEquals(listOf("x"), queryAt(areas, 1999.999))
        assertEquals(emptyList<String>(), queryAt(areas, 2000.0))
    }

    @Test
    fun overlappingAreasAreAllReturned() {
        // 官谱里同一时刻几十块重叠是常态，绝不能当成异常只返回一块
        val areas = (0 until 50).map { area(0.0, 10000.0, "n$it") }
        assertEquals(50, queryAt(areas, 5000.0).size)
    }

    @Test
    fun zeroLengthIntervalNeverVisible() {
        // appear == disappear：长度 0，任何时刻都不可见（aliveAt 是半开区间）
        val areas = listOf(area(1000.0, 1000.0, "z"))
        assertEquals(emptyList<String>(), queryAt(areas, 1000.0))
    }

    @Test
    fun emptyListIsSafe() {
        assertEquals(emptyList<String>(), queryAt(emptyList(), 0.0))
        val idx = NoiseTimeIndex()
        idx.build(emptyList())
        assertEquals(0, idx.size)
    }

    @Test
    fun rebuildIsSkippedWhenUnchanged() {
        val areas = listOf(area(0.0, 1000.0), area(500.0, 1500.0))
        val idx = NoiseTimeIndex()
        idx.build(areas)
        assertTrue(idx.lastBuildRebuilt)
        idx.build(areas)
        assertFalse("同一份列表不该重排", idx.lastBuildRebuilt)
        // 内容变了（换成另一份列表）就该重排
        idx.build(listOf(area(0.0, 5.0)))
        assertTrue(idx.lastBuildRebuilt)
    }

    @Test
    fun invalidateForcesRebuild() {
        // ★ 这是实际踩过的坑：applyDraft 是 noiseAreas.clear() + addAll()，
        //   列表实例和「首末元素身份」都可能碰巧没变，索引会拿着旧数据不重建。
        val a = area(0.0, 1000.0, "old")
        val b = area(5000.0, 6000.0, "new")
        val live = ArrayList<NoiseArea>()
        live.add(a)
        live.add(b)
        val idx = NoiseTimeIndex()
        idx.build(live)
        // 原地改成只有 b
        live.clear()
        live.add(b)
        idx.invalidate()
        idx.build(live)
        assertTrue(idx.lastBuildRebuilt)
        val out = ArrayList<NoiseArea>()
        idx.query(10_000.0, out)
        assertTrue("旧区域不该还活着", out.isEmpty())
    }

    // ------------------------------------------------------------------ 规模回归

    /**
     * 按 ハテ AT 的真实形状造 14355 块噪域：谱长 170 秒、块与块时间上几乎不重叠、
     * 但整体按时间铺满 —— 这正是官谱噪域的样子。
     */
    private fun realisticChart(n: Int = 14355): List<NoiseArea> {
        val rnd = Random(20261105)
        val out = ArrayList<NoiseArea>(n)
        for (i in 0 until n) {
            val t0 = rnd.nextDouble(0.0, 169_000.0)
            val len = rnd.nextDouble(300.0, 2_500.0)
            out.add(area(t0, t0 + len, "b$i"))
        }
        return out
    }

    @Test
    fun realisticScaleQueryIsCheap() {
        val areas = realisticChart()
        val idx = NoiseTimeIndex()
        // 构建一次（编辑器里只在噪域变化时发生）
        val tBuild = System.nanoTime()
        idx.build(areas)
        val buildMs = (System.nanoTime() - tBuild) / 1_000_000
        assertEquals(14355, idx.size)
        assertTrue("一次排序构建应远低于 1 秒，实测 ${buildMs}ms", buildMs < 1000)

        // 每帧一次查询：扫 300 个时刻，全部加起来也要很快
        val out = ArrayList<NoiseArea>(256)
        val ids = NoiseTimeIndex.IntList(256)
        var totalHit = 0
        var maxHit = 0
        val tQuery = System.nanoTime()
        val frames = 300
        for (f in 0 until frames) {
            val t = 40_000.0 + f * 300.0
            idx.query(t, out, ids)
            totalHit += out.size
            if (out.size > maxHit) maxHit = out.size
        }
        val queryMs = (System.nanoTime() - tQuery) / 1_000_000
        val perFrameMicros = queryMs * 1000.0 / frames
        // 平均每帧返回的块数必须远小于总数（否则索引等于没做）
        val avg = totalHit.toDouble() / frames
        assertTrue("每帧平均只该返回极少块，实测 $avg / max $maxHit", avg < areas.size / 20.0)
        // 每帧查询应远低于 1ms（手机上大约 2~4 倍，仍远低于一帧 16ms 预算）
        assertTrue("每帧查询应低于 1ms，实测 %.1fµs".format(perFrameMicros), perFrameMicros < 1000)
    }

    @Test
    fun queryMatchesBruteForce() {
        // 随机形状 vs 逐块 aliveAt：结果集合必须完全一致
        val areas = realisticChart(2000)
        val idx = NoiseTimeIndex()
        idx.build(areas)
        val out = ArrayList<NoiseArea>()
        val ids = NoiseTimeIndex.IntList()
        val rnd = Random(7)
        repeat(200) {
            val t = rnd.nextDouble(0.0, 170_000.0)
            idx.query(t, out, ids)
            val fast = out.map { System.identityHashCode(it) }.toHashSet()
            val slow = areas.filter { it.aliveAt(t) }.map { System.identityHashCode(it) }.toHashSet()
            assertEquals("t=$t 时索引结果与全量扫描不一致", slow, fast)
        }
    }
}
