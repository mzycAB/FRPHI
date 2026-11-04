package com.piezmaker

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 本地 BPM 检测（不需要联网、不需要 AI、不需要任何第三方库）。
 *
 * 算法流程参考开源实现：
 *  - SoundTouch 的 `BPMDetect`（LGPL-2.1，https://gitlab.com/soundtouch/soundtouch）：
 *    把音频切成很短的窗口 → 算每个窗口的"能量突增"当起音包络 → 在 60~200 BPM 之间
 *    逐个候选拍长做自相关 / 梳状滤波打分，取分数最高的那个当速度。
 *  - aubio 的 tempo 检测（GPL-3.0，https://github.com/aubio/aubio）：
 *    先做 onset（起音）检测函数，再按候选周期做"节拍梳"累加，最后用先验（流行曲多在
 *    90~160）压掉半速 / 双速的倍频错误。
 *
 * 本项目是 Kotlin 重写（不引入 C/C++ 源码），只用系统 MediaCodec 解码：
 *   PCM → 10ms 一格的低频(≤150Hz)/全频能量 → 起音包络 → 梳状滤波打分 → BPM + 首个重拍时刻。
 * 两个开源项目的许可证与出处见应用内「长按日志 → 版权声明」。
 */
object BpmDetect {
    private const val TAG = "FRPHI"
    private const val WIN_MS = 10          // 一格的时长（毫秒）
    private const val MAX_ANALYZE_MS = 120_000L   // 最多分析前 120 秒，够判断速度了
    private const val MIN_BPM = 60.0
    private const val MAX_BPM = 200.0

    /** 检测结果：BPM + 首个重拍（毫秒，用来填「BPM 偏移」）+ 置信度 0~1 */
    class Result(
        val bpm: Double,
        val offsetMs: Long,
        val confidence: Float,
        val durationMs: Long
    ) {
        /** 整数就显示整数（128），否则最多 3 位小数（152.436） */
        fun bpmText(): String {
            val r = Math.round(bpm * 1000.0) / 1000.0
            return if (Math.abs(r - Math.round(r)) < 0.0005) Math.round(r).toString() else r.toString()
        }

        fun offsetText(): String = offsetMs.toString() + "ms"
        fun confidenceText(): String = Math.round(confidence * 100f).toString() + "%"
        fun shortText(): String = "BPM " + bpmText() + "（首个重拍 " + offsetText() + "）"
    }

    /** 包络 → 速度（纯函数，JVM 上可单测） */
    class Estimate(val bpm: Double, val confidence: Double, val offsetMs: Long)

    /**
     * 检测一个音频 Uri 的 BPM。
     * @param onProgress 0~100 的进度回调（在调用线程上回调）
     * @param isCancelled 返回 true 就尽快结束，返回 null
     */
    fun detect(
        ctx: Context,
        uri: Uri,
        onProgress: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): Result? {
        if (uri == Uri.EMPTY) return null
        val env = envelopeOf(ctx, uri, onProgress, isCancelled) ?: return null
        if (isCancelled()) return null
        onProgress(86)
        val est = fromEnvelope(env.env, WIN_MS)
        if (isCancelled()) return null
        onProgress(96)
        if (est.bpm <= 0.0) return null
        Log.i(
            TAG, "BpmDetect: bpm=" + est.bpm + " conf=" + est.confidence +
                " off=" + est.offsetMs + "ms dur=" + env.durationMs + "ms"
        )
        DebugLog.add(
            "audio",
            "BPM 检测：${String.format(java.util.Locale.US, "%.3f", est.bpm)} BPM，" +
                "首个重拍 ${est.offsetMs}ms，置信度 ${Math.round(est.confidence * 100)}%"
        )
        onProgress(100)
        return Result(
            snapBpm(est.bpm), est.offsetMs,
            est.confidence.toFloat().coerceIn(0f, 1f), env.durationMs
        )
    }

    /** 速度取整：常见的整数 BPM 直接落到整数上（避免 127.98 这种）；其余保留 3 位小数 */
    private fun snapBpm(bpm: Double): Double {
        val r = Math.round(bpm).toDouble()
        return if (Math.abs(bpm - r) < 0.22) r else Math.round(bpm * 1000.0) / 1000.0
    }

    // ------------------------------------------------------------------ 核心算法
    /**
     * 起音包络 → (BPM, 置信度, 首个重拍毫秒)。
     *
     * 1) 包络去均值（去掉底噪），负值归零，按 95 分位归一化；
     * 2) 候选 BPM 从 60 走到 200（步长 0.5）：对每个候选拍长 P（格数），
     *    在所有相位 φ 上做"节拍梳"累加 —— 沿 φ、φ+P、φ+2P… 取样求和再除以取样数，
     *    得到"这个速度下每一拍都踩在重音上"的程度；
     * 3) 乘一个以 120 BPM 为中心的柔和先验（压掉半速）；
     * 4) 双速校验：如果 2× 的梳状分数和它差不多，就取 2×（鼓点密集的歌常被判成一半）；
     * 5) 抛物线插值细化 BPM，最好的相位就是首个重拍时刻。
     */
    fun fromEnvelope(env: DoubleArray, stepMs: Int): Estimate {
        if (env.size < 64 || stepMs <= 0) return Estimate(0.0, 0.0, 0L)
        val n = env.size
        // 1) 去均值 + 归零 + 归一化
        var mean = 0.0
        for (v in env) mean += v
        mean /= n
        val x = DoubleArray(n)
        var maxV = 0.0
        for (i in 0 until n) {
            val v = env[i] - mean
            x[i] = if (v > 0) v else 0.0
            if (x[i] > maxV) maxV = x[i]
        }
        if (maxV <= 1e-9) return Estimate(0.0, 0.0, 0L)
        val sorted = x.copyOf()
        sorted.sort()
        val p95 = sorted[(n * 95 / 100).coerceIn(0, n - 1)]
        val norm = if (p95 > 1e-9) p95 else maxV
        for (i in 0 until n) x[i] /= norm      // 主体落在 0~1，个别大重音可以 >1

        // 2) 逐候选打分
        val bpmSteps = ((MAX_BPM - MIN_BPM) * 2).toInt() + 1          // 0.5 BPM 一档
        val raw = DoubleArray(bpmSteps)
        val phases = IntArray(bpmSteps)
        for (k in 0 until bpmSteps) {
            val bpm = MIN_BPM + k * 0.5
            val period = 60_000.0 / bpm / stepMs                      // 一拍 = 多少格（浮点）
            if (period < 2.0) { raw[k] = 0.0; continue }
            var bestScore = 0.0
            var bestPhase = 0
            val phaseCount = Math.floor(period).toInt().coerceAtLeast(1)
            for (phi in 0 until phaseCount) {
                var sum = 0.0
                var cnt = 0
                var pos = phi.toDouble()
                while (true) {
                    val i = Math.round(pos).toInt()
                    if (i >= n) break
                    // ±1 格取最大：节拍落点常差半格，只取单点会因为"差一格"漏掉整拍
                    var v = x[i]
                    if (i > 0 && x[i - 1] > v) v = x[i - 1]
                    if (i + 1 < n && x[i + 1] > v) v = x[i + 1]
                    sum += v
                    cnt++
                    pos += period
                }
                if (cnt < 4) continue
                val score = sum / cnt
                // 相位在 ±1 格内本来就能对齐，几千个相位会打平：只认明显更高的，
                // 否则保留更靠前的相位（"首个重拍"更接近 0）
                if (score > bestScore * 1.01) { bestScore = score; bestPhase = phi }
            }
            raw[k] = bestScore
            phases[k] = bestPhase
        }

        // 3) 先验（log2 空间、中心 120 BPM、σ=0.85 个八度）
        val finalScore = DoubleArray(bpmSteps)
        var bestK = -1
        var bestFinal = -1.0
        for (k in 0 until bpmSteps) {
            val bpm = MIN_BPM + k * 0.5
            // 中心取 130：绝大多数谱面的速度在 100~180，中心压太低会把鼓点密的歌判成半速
            val oct = Math.log(bpm / 130.0) / Math.log(2.0)
            val prior = Math.exp(-0.5 * (oct / 0.9) * (oct / 0.9))
            finalScore[k] = raw[k] * (0.35 + 0.65 * prior)
            if (finalScore[k] > bestFinal) { bestFinal = finalScore[k]; bestK = k }
        }
        if (bestK < 0 || raw[bestK] <= 0.0) return Estimate(0.0, 0.0, 0L)

        // 4) 倍频校正：梳状打分天生偏爱"慢"（周期越长，取到的都是最强的重音），
        //    所以查到的速度经常只有真实速度的 1/2、1/3。这里回头比一次 2× / 3×：
        //    细网格上多出来的位置如果也有明显的重音（比如八分音符的镲），就说明真实速度更快。
        var chosen = bestK
        run {
            val k2 = Math.round(((MIN_BPM + bestK * 0.5) * 2.0 - MIN_BPM) / 0.5).toInt()
            if (k2 in 0 until bpmSteps && raw[k2] >= raw[bestK] * 0.72) chosen = k2
        }
        // 还是偏慢（< 96）时再看 3×：3 倍速的歌（如 180）会被判成 60，网格就太粗了
        run {
            val cur = MIN_BPM + chosen * 0.5
            if (cur < 96.0) {
                val k3 = Math.round((cur * 3.0 - MIN_BPM) / 0.5).toInt().coerceIn(0, bpmSteps - 1)
                if (MIN_BPM + k3 * 0.5 <= MAX_BPM && raw[k3] >= raw[bestK] * 0.55) chosen = k3
            }
        }

        // 5) 抛物线插值：用左右两档的分数细化峰值位置
        var bpm = MIN_BPM + chosen * 0.5
        if (chosen > 0 && chosen < bpmSteps - 1) {
            val a = finalScore[chosen - 1]
            val b = finalScore[chosen]
            val c = finalScore[chosen + 1]
            val den = a - 2 * b + c
            if (Math.abs(den) > 1e-12) {
                val d = 0.5 * (a - c) / den                    // -1~1 之间的一小步
                bpm += d.coerceIn(-0.5, 0.5) * 0.5
            }
        }

        // 相位：沿用所选档位的最好相位（换成毫秒）
        val offsetMs = phases[chosen].toLong() * stepMs
        val conf = (finalScore[chosen] / 1.0).coerceIn(0.0, 1.0)
        return Estimate(bpm, conf, offsetMs)
    }

    // ------------------------------------------------------------------ 解码
    private class Env(val env: DoubleArray, val durationMs: Long)

    /** 解码 PCM，算 10ms 一格的起音包络（低频 + 全频） */
    private fun envelopeOf(
        ctx: Context,
        uri: Uri,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean
    ): Env? {
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
                    if (f.containsKey(MediaFormat.KEY_DURATION)) durationUs = f.getLong(MediaFormat.KEY_DURATION)
                    break
                }
            }
            if (trackIndex < 0) return null
            extractor.selectTrack(trackIndex)
            val inFormat = extractor.getTrackFormat(trackIndex)
            // WAV / 未压缩 PCM（从视频里提取出来的音轨就是这种）：直接读采样，
            // 不依赖设备上有没有 "audio/raw" 解码器 —— 有些机型确实没有，会直接失败。
            if (mime == "audio/raw" || mime == "audio/x-raw") {
                return envelopeOfRaw(extractor, inFormat, onProgress, isCancelled, durationUs)
            }
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inFormat, null, null, 0)
            codec.start()

            var sampleRate = if (inFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (inFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var floatPcm = false

            // 低频（踢鼓）与全频能量，按 10ms 累加
            val eLow = ArrayList<Double>(8192)
            val eAll = ArrayList<Double>(8192)
            var accLow = 0.0
            var accAll = 0.0
            var accCount = 0
            var perWindow = (sampleRate * WIN_MS / 1000).coerceAtLeast(1) * channels
            var lpState = 0.0
            var lpA = 0.0
            var lpB = 0.0
            fun setupFilter() {
                val fc = 150.0
                val z = Math.exp(-2.0 * Math.PI * fc / sampleRate.toDouble())
                lpB = z
                lpA = 1.0 - z
            }
            setupFilter()

            fun flushWindow() {
                if (accCount <= 0) return
                val d = accCount.toDouble()
                eLow.add(accLow / d)
                eAll.add(accAll / d)
                accLow = 0.0; accAll = 0.0; accCount = 0
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var windows = 0
            val totalWindows = if (durationUs > 0)
                (durationUs / 1000 / WIN_MS).toInt().coerceAtLeast(1) else 0
            var lastProgress = 0

            while (!outputDone) {
                if (isCancelled()) return null
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
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            floatPcm = of.getInteger(MediaFormat.KEY_PCM_ENCODING) == 4
                        perWindow = (sampleRate * WIN_MS / 1000).coerceAtLeast(1) * channels
                        setupFilter()
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            if (floatPcm) {
                                val fb = buf.order(ByteOrder.nativeOrder()).asFloatBuffer()
                                var i = 0
                                val cnt = fb.remaining()
                                while (i < cnt) {
                                    val v = fb.get(i).toDouble()
                                    accAll += v * v
                                    lpState = lpA * v + lpB * lpState
                                    accLow += lpState * lpState
                                    accCount++
                                    i++
                                    if (accCount >= perWindow) flushWindow()
                                }
                            } else {
                                val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                                var i = 0
                                val cnt = sb.remaining()
                                while (i < cnt) {
                                    val v = sb.get(i).toDouble() / 32768.0
                                    accAll += v * v
                                    lpState = lpA * v + lpB * lpState
                                    accLow += lpState * lpState
                                    accCount++
                                    i++
                                    if (accCount >= perWindow) flushWindow()
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        windows = eAll.size
                        if (totalWindows > 0) {
                            val p = (windows * 80 / totalWindows).coerceIn(0, 80)
                            if (p != lastProgress) { lastProgress = p; onProgress(p) }
                        }
                        // 只分析前 MAX_ANALYZE_MS：够判断速度了，长歌不必等
                        if (windows * WIN_MS > MAX_ANALYZE_MS) outputDone = true
                    }
                }
            }
            flushWindow()

            val totalMs = if (durationUs > 0) durationUs / 1000
                          else eAll.size.toLong() * WIN_MS
            if (eAll.size < 40) return null
            return Env(onsetOf(eLow, eAll), totalMs)
        } catch (e: Exception) {
            Log.w(TAG, "BpmDetect decode failed", e)
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    /**
     * 未压缩 PCM 的解码路径（WAV：从视频里抽出来的音轨、以及用户直接选的 wav）。
     * 和 [envelopeOf] 里 MediaCodec 的输出处理完全同一套算法，只是采样直接从 extractor 读。
     */
    private fun envelopeOfRaw(
        ex: MediaExtractor,
        fmt: MediaFormat,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean,
        durationUs: Long
    ): Env? {
        var sampleRate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        val channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
        val floatPcm = fmt.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
            fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) == 4

        val eLow = ArrayList<Double>(8192)
        val eAll = ArrayList<Double>(8192)
        var accLow = 0.0
        var accAll = 0.0
        var accCount = 0
        var perWindow = (sampleRate * WIN_MS / 1000).coerceAtLeast(1) * channels
        var lpState = 0.0
        var lpA = 0.0
        var lpB = 0.0
        fun setupFilter() {
            val fc = 150.0
            val z = Math.exp(-2.0 * Math.PI * fc / sampleRate.toDouble())
            lpB = z
            lpA = 1.0 - z
        }
        setupFilter()
        fun flushWindow() {
            if (accCount <= 0) return
            val d = accCount.toDouble()
            eLow.add(accLow / d)
            eAll.add(accAll / d)
            accLow = 0.0; accAll = 0.0; accCount = 0
        }

        val buf = ByteBuffer.allocateDirect(256 * 1024).order(ByteOrder.nativeOrder())
        val totalWindows = if (durationUs > 0) (durationUs / 1000 / WIN_MS).toInt().coerceAtLeast(1) else 0
        var lastProgress = 0
        while (true) {
            if (isCancelled()) return null
            buf.clear()
            val size = ex.readSampleData(buf, 0)
            if (size < 0) break
            buf.position(0)
            buf.limit(size)
            if (floatPcm) {
                val fb = buf.asFloatBuffer()
                var i = 0
                val n = fb.remaining()
                while (i < n) {
                    val v = fb.get(i).toDouble()
                    accAll += v * v
                    lpState = lpA * v + lpB * lpState
                    accLow += lpState * lpState
                    accCount++
                    i++
                    if (accCount >= perWindow) flushWindow()
                }
            } else {
                val sb = buf.asShortBuffer()
                var i = 0
                val n = sb.remaining()
                while (i < n) {
                    val v = sb.get(i).toDouble() / 32768.0
                    accAll += v * v
                    lpState = lpA * v + lpB * lpState
                    accLow += lpState * lpState
                    accCount++
                    i++
                    if (accCount >= perWindow) flushWindow()
                }
            }
            ex.advance()
            if (totalWindows > 0) {
                val p = (eAll.size * 80 / totalWindows).coerceIn(0, 80)
                if (p != lastProgress) { lastProgress = p; onProgress(p) }
            }
            if (eAll.size * WIN_MS > MAX_ANALYZE_MS) break
        }
        flushWindow()
        if (eAll.size < 40) return null
        val totalMs = if (durationUs > 0) durationUs / 1000 else eAll.size.toLong() * WIN_MS
        return Env(onsetOf(eLow, eAll), totalMs)
    }

    /** 能量 → 起音包络：低频突增为主、全频突增为辅，再去掉慢变化的底（internal：JVM 测试要用） */
    internal fun onsetOf(eLow: List<Double>, eAll: List<Double>): DoubleArray {
        val n = eAll.size
        val out = DoubleArray(n)
        val eps = 1e-10
        for (i in 1 until n) {
            val dLow = Math.log(eLow[i] + eps) - Math.log(eLow[i - 1] + eps)
            val dAll = Math.log(eAll[i] + eps) - Math.log(eAll[i - 1] + eps)
            val low = if (dLow > 0) dLow else 0.0
            val all = if (dAll > 0) dAll else 0.0
            out[i] = low + 0.55 * all
        }
        // 去掉缓慢变化的底（1 秒滑窗均值），只留下"这一下比周围明显更响"的部分
        val win = 100
        val res = DoubleArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            sum += out[i]
            if (i >= win) sum -= out[i - win]
            val avg = sum / minOf(i + 1, win).toDouble()
            res[i] = (out[i] - avg).coerceAtLeast(0.0)
        }
        return res
    }
}
