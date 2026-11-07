package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * BPM 检测核心（包络 → 速度）的对拍测试。
 *
 * 合成"节拍器"包络：每拍一个尖峰、其余为 0，再加一点噪声和 8 分音符碎拍，
 * 看检测结果能不能稳定落回原速度（也检查半速 / 双速的倍频错误）。
 */
class BpmDetectTest {

    /** 造一段 10ms 一格的起音包络：主拍每 [bpm] 一拍，可选 8 分音符弱拍 + 噪声 */
    private fun clicks(bpm: Double, seconds: Int = 60, hats: Boolean = false, noise: Double = 0.0): DoubleArray {
        val frames = seconds * 100
        val env = DoubleArray(frames)
        val beat = 60_000.0 / bpm / 10.0          // 一拍 = 多少格
        var k = 0
        while (true) {
            val i = Math.round(k * beat).toInt()
            if (i >= frames) break
            env[i] += 1.0
            if (hats) {
                val j = Math.round((k + 0.5) * beat).toInt()
                if (j in env.indices) env[j] += 0.45
            }
            k++
        }
        if (noise > 0.0) {
            val rnd = Random(1234)
            for (i in env.indices) env[i] += rnd.nextDouble() * noise
        }
        return env
    }

    private fun detect(env: DoubleArray): Double = BpmDetect.fromEnvelope(env, 10).bpm

    @Test
    fun `节拍器_常见速度都能检出`() {
        for (bpm in listOf(90.0, 120.0, 128.0, 150.0, 174.0)) {
            val got = detect(clicks(bpm))
            assertEquals("BPM $bpm 检出 $got", bpm, got, 1.2)
        }
    }

    @Test
    fun `带八分音符碎拍_不会判成双速`() {
        // 有 hats 时 8 分网格更密，仍然应该回到主速度附近
        for (bpm in listOf(100.0, 128.0)) {
            val got = detect(clicks(bpm, hats = true))
            assertTrue("BPM $bpm 检出 $got（不应是双速）", Math.abs(got - bpm * 2) > 3)
            assertEquals("BPM $bpm 检出 $got", bpm, got, 1.5)
        }
    }

    @Test
    fun `有噪声也能检出`() {
        val got = detect(clicks(140.0, noise = 0.12))
        assertEquals(140.0, got, 2.0)
    }

    @Test
    fun `首个重拍偏移_与节拍相位一致`() {
        // 第 30 格（300ms）才开始第一拍
        val env = DoubleArray(6000)
        val beat = 60_000.0 / 120.0 / 10.0
        var k = 0
        while (true) {
            val i = 30 + Math.round(k * beat).toInt()
            if (i >= env.size) break
            env[i] = 1.0
            k++
        }
        val est = BpmDetect.fromEnvelope(env, 10)
        assertEquals(120.0, est.bpm, 1.2)
        // 相位是"拍网格上的第一个落点"，允许一格误差
        assertTrue("offset=${est.offsetMs}", Math.abs(est.offsetMs - 300L) <= 10L)
    }

    @Test
    fun `空包络_不返回假速度`() {
        assertEquals(0.0, BpmDetect.fromEnvelope(DoubleArray(0), 10).bpm, 0.0)
        assertEquals(0.0, BpmDetect.fromEnvelope(DoubleArray(500), 10).bpm, 0.0)
    }
}
