package com.piezmaker

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue

/**
 * 本地音乐分析：解码 PCM → 10ms 一格的能量包络（全频带 + 低频/中频/高频三档），
 * 取"能量突然变强"的位置当重音点，并给每个点附带：
 *   strength 0~1   —— 这一下有多重
 *   low/mid/high   —— 低频(鼓)/中频(人声/主旋律)/高频(镲片) 的能量占比
 *   sustainMs      —— 之后能量还能维持多久（用来判断"该放长条"）
 *
 * AI 听不到声音，靠这些数字决定蓝/黄/红键与长条。
 * 解码失败时返回 null，调用方退回"按 BPM 均分节拍"。
 */
object AudioBeats {
    private const val TAG = "FRPHI"
    private const val WIN_MS = 10

    /** 一个重音点 */
    class Beat(
        val t: Long,
        val strength: Float,     // 0~1
        val low: Float,          // 0~1
        val mid: Float,
        val high: Float,
        val sustainMs: Long      // 能量维持时长（0 = 很干脆的一下）
    ) {
        val dominant: Char
            get() = when {
                low >= mid && low >= high -> 'L'
                mid >= high -> 'M'
                else -> 'H'
            }
    }

    class Result(val durationMs: Long, val beats: List<Beat>)

    /** 一阶低通（RBJ biquad 简化）：用来切低频/中频 */
    private class LP {
        var a = 0.0; var b = 0.0; var z = 0.0
        fun set(sr: Int, fc: Double) {
            val x = Math.exp(-2.0 * Math.PI * fc / sr.toDouble())
            b = x
            a = 1.0 - x
        }
        fun run(v: Double): Double {
            z = a * v + b * z
            return z
        }
    }

    fun analyze(ctx: Context, uri: Uri, maxWallMs: Long = 60000L): Result? {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(ctx, uri, null)
            var trackIndex = -1
            var mime = ""
            var durationUs = 0L
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    trackIndex = i
                    mime = m
                    durationUs = if (f.containsKey(MediaFormat.KEY_DURATION))
                        f.getLong(MediaFormat.KEY_DURATION) else 0L
                    break
                }
            }
            if (trackIndex < 0) return null
            extractor.selectTrack(trackIndex)
            val inFormat = extractor.getTrackFormat(trackIndex)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inFormat, null, null, 0)
            codec.start()

            var sampleRate = if (inFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (inFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var floatPcm = false

            // 四个能量包络（求和后开方当 RMS）
            val eAll = ArrayList<Double>(4096)
            val eLow = ArrayList<Double>(4096)
            val eMid = ArrayList<Double>(4096)
            val eHigh = ArrayList<Double>(4096)
            var accAll = 0.0; var accLow = 0.0; var accMid = 0.0; var accHigh = 0.0
            var accCount = 0
            fun flushWindow() {
                if (accCount <= 0) return
                val n = accCount.toDouble()
                eAll.add(accAll / n); eLow.add(accLow / n); eMid.add(accMid / n); eHigh.add(accHigh / n)
                accAll = 0.0; accLow = 0.0; accMid = 0.0; accHigh = 0.0; accCount = 0
            }

            val lp250 = LP()
            val lp2000 = LP()
            var filtersReady = false
            fun setupFilters() {
                lp250.set(sampleRate, 250.0)
                lp2000.set(sampleRate, 2000.0)
                filtersReady = true
            }
            setupFilters()
            var perWindow = (sampleRate * WIN_MS / 1000).coerceAtLeast(1) * channels

            val info = MediaCodec.BufferInfo()
            val started = System.currentTimeMillis()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (System.currentTimeMillis() - started > maxWallMs) break
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(2000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 2000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                            sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            floatPcm = of.getInteger(MediaFormat.KEY_PCM_ENCODING) == 4
                        }
                        perWindow = (sampleRate * WIN_MS / 1000).coerceAtLeast(1) * channels
                        setupFilters()
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            if (floatPcm) {
                                val fb = buf.order(ByteOrder.nativeOrder()).asFloatBuffer()
                                var i = 0
                                while (i < fb.remaining()) {
                                    val v = fb.get(i).toDouble()
                                    processSample(v, filtersReady, lp250, lp2000,
                                        { accAll += it * it }, { accLow += it * it },
                                        { accMid += it * it }, { accHigh += it * it })
                                    accCount++
                                    i++
                                    if (accCount >= perWindow) flushWindow()
                                }
                            } else {
                                val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                                var i = 0
                                while (i < sb.remaining()) {
                                    val v = sb.get(i).toDouble() / 32768.0
                                    processSample(v, filtersReady, lp250, lp2000,
                                        { accAll += it * it }, { accLow += it * it },
                                        { accMid += it * it }, { accHigh += it * it })
                                    accCount++
                                    i++
                                    if (accCount >= perWindow) flushWindow()
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            flushWindow()

            val totalMs = if (durationUs > 0) durationUs / 1000
                          else eAll.size.toLong() * WIN_MS
            val beats = beats(eAll, eLow, eMid, eHigh)
            Log.i(TAG, "AudioBeats: windows=${eAll.size} beats=${beats.size} dur=${totalMs}ms")
            return Result(totalMs, beats)
        } catch (e: Exception) {
            Log.w(TAG, "AudioBeats failed", e)
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private inline fun processSample(
        v: Double, ready: Boolean, lp250: LP, lp2000: LP,
        addAll: (Double) -> Unit, addLow: (Double) -> Unit,
        addMid: (Double) -> Unit, addHigh: (Double) -> Unit
    ) {
        val low = if (ready) lp250.run(v) else 0.0
        val midLow = if (ready) lp2000.run(v) else 0.0
        val mid = midLow - low
        val high = v - midLow
        addAll(v)
        addLow(low)
        addMid(mid)
        addHigh(high)
    }

    /** 能量包络 → 重音点（含强度/三个频段占比/维持时长） */
    private fun beats(
        eAll: List<Double>, eLow: List<Double>, eMid: List<Double>, eHigh: List<Double>
    ): List<Beat> {
        if (eAll.size < 8) return emptyList()
        val nov = DoubleArray(eAll.size)
        for (i in 1 until eAll.size) nov[i] = maxOf(0.0, eAll[i] - eAll[i - 1])
        val sm = DoubleArray(eAll.size)
        for (i in 0 until eAll.size) {
            var s = 0.0; var c = 0
            for (k in -1..1) {
                val j = i + k
                if (j in 0 until eAll.size) { s += nov[j]; c++ }
            }
            sm[i] = s / c
        }
        val mean = sm.average()
        var varSum = 0.0
        for (v in sm) varSum += (v - mean) * (v - mean)
        val std = Math.sqrt(varSum / sm.size.coerceAtLeast(1))
        val thr = mean + std * 0.55

        val picked = ArrayList<Int>()
        var lastI = -1000
        for (i in 1 until sm.size - 1) {
            if (sm[i] < thr) continue
            if (sm[i] < sm[i - 1] || sm[i] < sm[i + 1]) continue
            if (i - lastI < 9) continue           // 90ms 内不要两个
            picked.add(i)
            lastI = i
        }
        if (picked.size > 600) {
            val pq = PriorityQueue<Int>(compareBy { sm[it] })
            for (i in picked) {
                pq.add(i)
                if (pq.size > 600) pq.poll()
            }
            return pq.toSortedSet().map { i -> makeBeat(i, sm, eAll, eLow, eMid, eHigh) }
        }
        return picked.map { i -> makeBeat(i, sm, eAll, eLow, eMid, eHigh) }
    }

    private fun makeBeat(
        i: Int, sm: DoubleArray, eAll: List<Double>,
        eLow: List<Double>, eMid: List<Double>, eHigh: List<Double>
    ): Beat {
        val peak = eAll[i].coerceAtLeast(1e-9)
        val lo = Math.sqrt(eLow[i] / peak).coerceIn(0.0, 1.0)
        val mi = Math.sqrt(eMid[i] / peak).coerceIn(0.0, 1.0)
        val hi = Math.sqrt(eHigh[i] / peak).coerceIn(0.0, 1.0)
        // 强度：相对全局 95 分位的能量
        val strength = (sm[i] / (sm.maxOrNull()?.coerceAtLeast(1e-9) ?: 1.0)).toFloat().coerceIn(0f, 1f)
        // 维持时长：往后能量不低于峰值 55% 的时间
        var sustain = 0L
        for (k in i + 1 until minOf(i + 60, eAll.size)) {
            if (eAll[k] < peak * 0.55) break
            sustain = (k - i).toLong() * WIN_MS
        }
        return Beat(i.toLong() * WIN_MS, strength, lo.toFloat(), mi.toFloat(), hi.toFloat(), sustain)
    }
}
