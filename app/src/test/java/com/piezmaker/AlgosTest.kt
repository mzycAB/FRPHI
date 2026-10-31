package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

/**
 * 热路径算法的正确性对拍 + 基准。
 * 优化版和"优化前写法"在同一份随机数据上跑，结果必须完全一致。
 */
class AlgosTest {

    // ------------------------------------------------------------------ 判定线插值

    @Test
    fun `判定线插值_二分版与线性版结果一致`() {
        val rnd = Random(20261025)
        repeat(20) { round ->
            val n = 1 + rnd.nextInt(60)
            val list = ArrayList<LineKeyframe>(n)
            var t = 0L
            for (i in 0 until n) {
                t += rnd.nextInt(1500).toLong()
                list.add(LineKeyframe(t, rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1, rnd.nextInt(360) - 180f))
            }
            val sorted = list.sortedBy { it.timeMs }.toTypedArray()
            val a = FloatArray(3)
            val b = FloatArray(3)
            for (k in 0 until 300) {
                val now = rnd.nextLong().let { Math.abs(it) % (t + 3000) } - 1000
                LinePose.interpolate(sorted, now, a)
                LinePose.interpolateNaive(sorted.toList(), now, b)
                assertEquals("round=$round now=$now x", b[0], a[0], 1e-5f)
                assertEquals("round=$round now=$now y", b[1], a[1], 1e-5f)
                assertEquals("round=$round now=$now r", b[2], a[2], 1e-5f)
            }
        }
    }

    @Test
    fun `判定线插值_边界情况`() {
        val out = FloatArray(3)
        LinePose.interpolate(emptyArray(), 100L, out)
        assertEquals(0f, out[0], 0f)

        val one = arrayOf(LineKeyframe(1000L, 0.5f, -0.25f, 30f))
        LinePose.interpolate(one, 0L, out)
        assertEquals(0.5f, out[0], 1e-6f)
        LinePose.interpolate(one, 99999L, out)
        assertEquals(30f, out[2], 1e-6f)

        // 两帧之间线性插值
        val two = arrayOf(LineKeyframe(0L, 0f, 0f, 0f), LineKeyframe(1000L, 1f, -1f, 90f))
        LinePose.interpolate(two, 500L, out)
        assertEquals(0.5f, out[0], 1e-6f)
        assertEquals(-0.5f, out[1], 1e-6f)
        assertEquals(45f, out[2], 1e-6f)
    }

    // ------------------------------------------------------------------ 双押

    @Test
    fun `双押检测_滑窗与两两比较结果一致`() {
        val rnd = Random(7)
        repeat(30) { round ->
            val n = 2 + rnd.nextInt(400)
            val times = LongArray(n) { rnd.nextInt(120000).toLong() }
            val judged = HashSet<Int>()
            for (i in 0 until n) if (rnd.nextInt(4) == 0) judged.add(i)
            val win = 40L + rnd.nextInt(120)
            val sorted = times.indices.sortedBy { times[it] }.toIntArray()

            val fast = NoteAlgos.doubleHints(
                sorted, n, Long.MIN_VALUE / 4, Long.MAX_VALUE / 4, win,
                { times[it] }, { it in judged }
            )
            val slow = NoteAlgos.doubleHintsNaive(times.indices.toList(), win, { times[it] }, { it in judged })
            assertEquals("round=$round", slow, fast)
        }
    }

    @Test
    fun `双押检测_只统计时间窗口内`() {
        val times = longArrayOf(0, 100_000, 100_010)
        val sorted = times.indices.sortedBy { times[it] }.toIntArray()
        // 只看 now 附近：后两个算双押，0 号在窗外不参与
        val set = NoteAlgos.doubleHints(sorted, 3, 99_000, 101_000, 50, { times[it] }, { false })
        assertEquals(setOf(1, 2), set)
    }

    // ------------------------------------------------------------------ 基准

    @Test
    fun `基准_双押检测`() {
        for (n in intArrayOf(1000, 3000, 10000)) {
            val rnd = Random(1)
            val times = LongArray(n) { (it * 180_000L / n) + rnd.nextInt(8) }
            val sorted = times.indices.sortedBy { times[it] }.toIntArray()
            val mid = 90_000L
            val win = 60L

            val fastMs = measure(20) {
                NoteAlgos.doubleHints(sorted, n, mid - 8000, mid + 8000, win, { times[it] }, { false })
            }
            // O(n²) 版本只跑一次（n=10000 时要上亿次比较）
            val slowMs = measure(1) {
                NoteAlgos.doubleHintsNaive(times.indices.toList(), win, { times[it] }, { false })
            }
            println(
                "BENCH doubleHints n=$n  优化前(O(n²) 全量) %.1f ms → 现在(滑窗+时间窗) %.4f ms  (×%.0f)"
                    .format(slowMs, fastMs, if (fastMs > 0) slowMs / fastMs else 0.0)
            )
        }
    }

    @Test
    fun `基准_判定线插值`() {
        val rnd = Random(2)
        val kfs = ArrayList<LineKeyframe>()
        var t = 0L
        for (i in 0 until 40) {
            t += 1000
            kfs.add(LineKeyframe(t, rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat() * 90))
        }
        val sorted = kfs.toTypedArray()
        val out = FloatArray(3)

        // 每帧 3000 个音符 × 2 次调用（drawNoteAt 里 center + pose）
        val slowMs = measure(5) {
            for (i in 0 until 6000) {
                LinePose.interpolateNaive(kfs.sortedBy { it.timeMs }, (i * 7 % 40000).toLong(), out)
            }
        }
        val fastMs = measure(5) {
            for (i in 0 until 6000) {
                LinePose.interpolate(sorted, (i * 7 % 40000).toLong(), out)
            }
        }
        println("BENCH linePose 6000 次/帧  优化前 %.3f ms → 现在 %.3f ms  (×%.0f)".format(slowMs, fastMs, slowMs / fastMs))
    }

    private fun measure(reps: Int, block: () -> Unit): Double {
        repeat(2) { block() }                        // 预热
        val t0 = System.nanoTime()
        repeat(reps) { block() }
        return (System.nanoTime() - t0) / 1e6 / reps
    }
}
