package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Test

/** 输入事件时间戳补偿 / 输出延迟估算（纯 JVM） */
class AudioLatencyTest {

    @Test
    fun `输出延迟按每缓冲帧数与采样率换算`() {
        // 48kHz / 256 帧 ≈ 5.33ms；44.1kHz / 2048 帧 ≈ 46ms
        assertEquals(5L, AudioLatency.outputLatencyMs(256, 48000))
        assertEquals(46L, AudioLatency.outputLatencyMs(2048, 44100))
        assertEquals(10L, AudioLatency.outputLatencyMs(480, 48000))
    }

    @Test
    fun `拿不到音频属性时输出延迟为0_不影响行为`() {
        assertEquals(0L, AudioLatency.outputLatencyMs(0, 48000))
        assertEquals(0L, AudioLatency.outputLatencyMs(256, 0))
        assertEquals(0L, AudioLatency.outputLatencyMs(-1, -1))
    }

    @Test
    fun `输入排队延迟用同域的uptimeMillis计算`() {
        assertEquals(0L, AudioLatency.inputLagMs(10_000L, 10_000L))
        assertEquals(12L, AudioLatency.inputLagMs(10_012L, 10_000L))
        // 事件比回调「晚」（异常/时钟回绕）不能把播放头往前推
        assertEquals(0L, AudioLatency.inputLagMs(9_990L, 10_000L))
    }

    @Test
    fun `输入排队延迟夹到200ms_防止旧事件把谱面拽回去`() {
        assertEquals(200L, AudioLatency.inputLagMs(20_000L, 10_000L))
        assertEquals(200L, AudioLatency.inputLagMs(10_201L, 10_000L))
        assertEquals(199L, AudioLatency.inputLagMs(10_199L, 10_000L))
    }

    @Test
    fun `事件时刻回推播放头只补偿抖动_不改基准`() {
        assertEquals(0L, AudioLatency.playheadAtEventMs(0L, 0L))
        assertEquals(12_000L, AudioLatency.playheadAtEventMs(12_016L, 16L))
        // 输入延迟为 0 时播放头原样返回（回归：别不小心引入系统性偏移）
        assertEquals(30_000L, AudioLatency.playheadAtEventMs(30_000L, 0L))
    }
}
