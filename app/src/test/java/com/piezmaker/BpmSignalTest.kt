package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

/**
 * 合成一段"鼓点"PCM，走完整条真实信号链（分频能量 → 起音包络 → 梳状打分 → BPM），
 * 确认不是只在理想脉冲上能work。
 *
 * 信号：每拍一个衰减的 55Hz 底鼓（振幅 0.8），每半拍一个 30ms 噪声镲片（0.18），
 * 中间垫一点底噪。解码器（MediaCodec）在 JVM 上没有，这里自己按 10ms 窗口累加，
 * 与 BpmDetect 解码循环里的算式一致。
 */
class BpmSignalTest {

    private class Env(val low: List<Double>, val all: List<Double>)

    private fun synth(bpm: Double, seconds: Int = 30, sr: Int = 44100): Env {
        val frames = seconds * 100
        val window = sr / 100                      // 10ms
        val n = frames * window
        val buf = DoubleArray(n)
        val rnd = Random(7)
        val beat = 60.0 / bpm                      // 秒
        // 底鼓：55Hz 衰减正弦
        var k = 0
        while (true) {
            val t0 = k * beat
            if (t0 > seconds) break
            val s0 = (t0 * sr).toInt()
            val len = (0.16 * sr).toInt()
            for (i in 0 until len) {
                val idx = s0 + i
                if (idx >= n) break
                val env = Math.exp(-i / (0.045 * sr))
                buf[idx] += 0.8 * env * Math.sin(2 * Math.PI * 55.0 * i / sr)
            }
            k++
        }
        // 镲片：半拍位置的白噪声短脉冲
        k = 0
        while (true) {
            val t0 = (k + 0.5) * beat
            if (t0 > seconds) break
            val s0 = (t0 * sr).toInt()
            val len = (0.03 * sr).toInt()
            for (i in 0 until len) {
                val idx = s0 + i
                if (idx >= n) break
                buf[idx] += 0.18 * Math.exp(-i / (0.008 * sr)) * (rnd.nextDouble() * 2 - 1)
            }
            k++
        }
        // 底噪 + 一个持续的和弦，模拟真实混音里的"非鼓"能量
        for (i in 0 until n) {
            buf[i] += 0.02 * (rnd.nextDouble() * 2 - 1) +
                0.05 * Math.sin(2 * Math.PI * 220.0 * i / sr) * (0.6 + 0.4 * Math.sin(2 * Math.PI * 0.7 * i / sr))
        }

        // 10ms 一格的低频 / 全频能量（和 BpmDetect 解码循环同一套写法）
        val low = ArrayList<Double>(frames)
        val all = ArrayList<Double>(frames)
        var z = 0.0
        val fc = 150.0
        val b = Math.exp(-2.0 * Math.PI * fc / sr)
        val a = 1.0 - b
        var accLow = 0.0
        var accAll = 0.0
        for (i in 0 until n) {
            val v = buf[i]
            accAll += v * v
            z = a * v + b * z
            accLow += z * z
            if ((i + 1) % window == 0) {
                low.add(accLow / window)
                all.add(accAll / window)
                accLow = 0.0
                accAll = 0.0
            }
        }
        return Env(low, all)
    }

    @Test
    fun `合成鼓点_128BPM`() {
        val e = synth(128.0)
        val env = BpmDetect.onsetOf(e.low, e.all)
        val est = BpmDetect.fromEnvelope(env, 10)
        assertEquals("检出 ${est.bpm}", 128.0, est.bpm, 2.0)
    }

    @Test
    fun `合成鼓点_100BPM`() {
        val e = synth(100.0)
        val env = BpmDetect.onsetOf(e.low, e.all)
        val est = BpmDetect.fromEnvelope(env, 10)
        assertEquals("检出 ${est.bpm}", 100.0, est.bpm, 2.0)
    }

    @Test
    fun `合成鼓点_170BPM不会被判成一半`() {
        val e = synth(170.0)
        val env = BpmDetect.onsetOf(e.low, e.all)
        val est = BpmDetect.fromEnvelope(env, 10)
        assertEquals("检出 ${est.bpm}", 170.0, est.bpm, 2.5)
    }

    /** 慢歌 / 快歌 / 三倍速都不能翻车（梳状打分天生偏爱慢，必须靠倍频校正拉回来） */
    @Test
    fun `各种速度都对得上`() {
        for (bpm in listOf(70.0, 85.0, 100.0, 128.0, 160.0, 180.0)) {
            val e = synth(bpm, seconds = 24)
            val env = BpmDetect.onsetOf(e.low, e.all)
            val est = BpmDetect.fromEnvelope(env, 10)
            assertEquals("$bpm 检出 ${est.bpm}", bpm, est.bpm, 2.0)
            // 相位：鼓点从 0 秒开始 → 首个重拍应该贴近 0
            assertEquals("$bpm 相位 ${est.offsetMs}", 0.0, est.offsetMs.toDouble(), 60.0)
        }
    }
}
