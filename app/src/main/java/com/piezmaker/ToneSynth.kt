package com.piezmaker

import java.io.File
import java.io.FileOutputStream

/**
 * 极简本地合成：生成一小段五声音阶旋律（正弦波 + 软包络），写成 16bit / 44.1k / 单声道 WAV。
 * AI 不能真的"作曲"出音频，但可以让它决定参数，这里给出一个能听、能用来写谱的曲子。
 */
object ToneSynth {

    private const val SR = 44100

    fun write(file: File, seconds: Int, bpm: Double): Boolean {
        val sec = seconds.coerceIn(3, 180)
        val bps = bpm.coerceIn(40.0, 240.0)
        val beatMs = 60_000.0 / bps
        val totalSamples = (sec * SR)
        val out = ShortArray(totalSamples)
        val scale = intArrayOf(0, 2, 4, 7, 9, 12, 9, 7)   // 五声音阶上下行
        val base = 440.0
        var t = 0.0
        var i = 0
        while (t < sec) {
            val beatSec = beatMs / 1000.0
            val half = i % 2 == 1                  // 每秒拍拆成两半，节奏更像歌
            val dur = if (half) beatSec * 0.42 else beatSec * 0.5
            val deg = scale[(i / 2) % scale.size]
            val freq = base * Math.pow(2.0, (deg + if (half) -12 else 0) / 12.0)
            mixNote(out, t, dur, freq, 0.22)
            if (i % 4 == 0) mixNote(out, t, beatSec * 0.9, freq / 2.0, 0.12)   // 低音铺底
            t += beatSec
            i++
        }
        return try {
            writeWav(file, out)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 往缓冲里叠一个带 ADSR 包络的正弦音符 */
    private fun mixNote(buf: ShortArray, startSec: Double, durSec: Double, freq: Double, amp: Double) {
        val s0 = (startSec * SR).toInt()
        val n = (durSec * SR).toInt()
        if (n <= 0) return
        val attack = (SR * 0.012).toInt().coerceAtLeast(1)
        val release = (SR * 0.05).toInt().coerceAtLeast(1)
        for (k in 0 until n) {
            val idx = s0 + k
            if (idx < 0 || idx >= buf.size) continue
            val env = when {
                k < attack -> k.toDouble() / attack
                k > n - release -> (n - k).toDouble() / release
                else -> 1.0
            }.coerceIn(0.0, 1.0)
            val v = Math.sin(2.0 * Math.PI * freq * k / SR) * amp * env
            val mix = buf[idx].toInt() + (v * Short.MAX_VALUE).toInt()
            buf[idx] = mix.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun writeWav(file: File, pcm: ShortArray) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { fos ->
            val dataLen = pcm.size * 2
            fun le32(v: Int) {
                fos.write(v and 0xFF); fos.write((v shr 8) and 0xFF)
                fos.write((v shr 16) and 0xFF); fos.write((v shr 24) and 0xFF)
            }
            fun le16(v: Int) {
                fos.write(v and 0xFF); fos.write((v shr 8) and 0xFF)
            }
            fos.write("RIFF".toByteArray()); le32(36 + dataLen); fos.write("WAVE".toByteArray())
            fos.write("fmt ".toByteArray()); le32(16); le16(1); le16(1)
            le32(SR); le32(SR * 2); le16(2); le16(16)
            fos.write("data".toByteArray()); le32(dataLen)
            for (s in pcm) le16(s.toInt())
        }
    }
}
