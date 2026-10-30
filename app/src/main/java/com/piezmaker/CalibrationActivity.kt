package com.piezmaker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import java.io.File
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * 校准用的音游视图：音符从上方落到判定线。
 * 每个音符的"落线时刻"与一声提示音严格对齐：
 *   嗒 嗒 嗒 嗒 滴   （相邻 0.2s，滴与下一轮滴相隔 1s）
 */
class CalibView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val FALL_MS = 900L          // 音符从顶部落到判定线的时间
        const val BEAT_MS = 400L          // 嗒与嗒之间（用户要求 0.4s）
        const val DIP_OFFSET_MS = 1600L   // 4 个嗒(0/400/800/1200)之后 400ms 是"滴"
        const val CYCLE_MS = 2000L        // 滴与滴之间 2s（用户要求从 1s 改为 2s）
    }

    /** 本轮第一个"滴"的 elapsedRealtime（兜底用），由 Activity 设置 */
    var firstDipAt: Long = 0L
    /** 已开始计时 */
    var running: Boolean = false
    /** 媒体播放位置（校准录音 currentPosition）——音符落线改用媒体时钟 */
    var mediaPosProvider: (() -> Int)? = null
    /**
     * 音频输出延迟（ms）：currentPosition 是"已送去播放"的位置，比"耳朵听到"超前这么多。
     * 音符按 currentPosition - audioLagMs 落线，才会和听到的「滴」在同一时刻。
     */
    var audioLagMs: Long = 0L

    private val linePaint = Paint().apply { color = Color.WHITE; strokeWidth = 6f; isAntiAlias = true }
    private val lanePaint = Paint().apply { color = 0x55FFFFFF; isAntiAlias = true }
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val texPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = true }

    /** Phira 默认皮肤的 Tap 音符贴图（和录制界面同一套） */
    private var tapBmp: Bitmap? = null

    // 打击特效（Phira hit_fx 8x7 = 56 帧），点击时有反馈
    private var hitFxBmp: Bitmap? = null
    private class HitFx(val x: Float, val y: Float, val at: Long)
    private val hitFxList = mutableListOf<HitFx>()
    private val HIT_FX_COLS = 8
    private val HIT_FX_ROWS = 7
    private val HIT_FX_DUR = 460L

    private var flashAt: Long = 0L

    var judgeLineY: Float = 0f
        private set

    init {
        tapBmp = try {
            context.assets.open("notes/click.png").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null }
        hitFxBmp = try {
            context.assets.open("notes/hit_fx.png").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null }
    }

    fun spawnHitFx(x: Float, y: Float) {
        if (hitFxList.size > 10) hitFxList.removeAt(0)
        hitFxList.add(HitFx(x, y, SystemClock.elapsedRealtime()))
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    /** 按目标宽画 Phira Tap 贴图，居中于 (cx, cy) */
    private fun drawTapTex(canvas: Canvas, cx: Float, cy: Float, targetW: Float, alpha: Int) {
        val bmp = tapBmp
        if (bmp == null) return
        val nh = targetW * bmp.height / bmp.width
        texPaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawBitmap(
            bmp, null,
            RectF(cx - targetW / 2, cy - nh / 2, cx + targetW / 2, cy + nh / 2),
            texPaint
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        judgeLineY = h * 0.72f
    }

    fun flashNow() {
        flashAt = SystemClock.elapsedRealtime()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 纯色背景（不做光晕）
        canvas.drawColor(Color.parseColor("#0B0E15"))

        // 判定线
        canvas.drawLine(0f, judgeLineY, w, judgeLineY, linePaint)

        // 击打特效序列帧
        val fxBmp = hitFxBmp
        if (fxBmp != null && hitFxList.isNotEmpty()) {
            val nw2 = fxBmp.width / HIT_FX_COLS
            val nh2 = fxBmp.height / HIT_FX_ROWS
            val total = HIT_FX_COLS * HIT_FX_ROWS
            val size = dp(150f) * 0.9f
            val now2 = SystemClock.elapsedRealtime()
            val it = hitFxList.iterator()
            while (it.hasNext()) {
                val fx = it.next()
                val age = now2 - fx.at
                if (age > HIT_FX_DUR) { it.remove(); continue }
                if (age < 0) continue
                val idx = (age * total / HIT_FX_DUR).toInt().coerceIn(0, total - 1)
                val col = idx % HIT_FX_COLS
                val row = idx / HIT_FX_COLS
                texPaint.alpha = 255
                canvas.drawBitmap(
                    fxBmp,
                    Rect(col * nw2, row * nh2, (col + 1) * nw2, (row + 1) * nh2),
                    RectF(fx.x - size / 2, fx.y - size / 2, fx.x + size / 2, fx.y + size / 2),
                    texPaint
                )
            }
        }

        // 点击闪光
        val sinceFlash = (SystemClock.elapsedRealtime() - flashAt).toFloat()
        if (flashAt > 0 && sinceFlash < 260f) {
            val a = 1f - sinceFlash / 260f
            flashPaint.color = ((a * 110).toInt() shl 24) or 0x00E5FF
            canvas.drawRect(0f, judgeLineY - dp(10f), w, judgeLineY + dp(10f), flashPaint)
        }

        if (!running || firstDipAt <= 0L) {
            postInvalidateOnAnimation()
            return
        }

        // 媒体时钟：now = 录音播放位置（媒体坐标），滴在 DIP_OFFSET_MS + c*CYCLE_MS
        // 减去 audioLagMs 让音符与"实际听到"的滴声对齐（否则音符会提前一个输出延迟落线）
        val now = (mediaPosProvider?.invoke()?.toLong()
            ?: (SystemClock.elapsedRealtime() - firstDipAt + DIP_OFFSET_MS)) - audioLagMs
        val pxPerMs = (h * 0.55f) / FALL_MS

        val cycleIdx = Math.floorDiv(now - DIP_OFFSET_MS, CYCLE_MS)
        for (c in (cycleIdx - 1)..(cycleIdx + 1)) {
            val dipAt = DIP_OFFSET_MS + c * CYCLE_MS
            for (k in 0..4) {
                val hitAt = dipAt - (4 - k) * BEAT_MS
                val dt = (hitAt - now).toFloat()
                if (dt < -150f || dt > FALL_MS + 60f) continue
                val y = judgeLineY - dt * pxPerMs
                if (y < -dp(40f) || y > judgeLineY + dp(30f)) continue

                val alpha = if (dt < 0f) (1f - (-dt) / 150f).coerceIn(0f, 1f) else 1f
                val isDip = k == 4
                // 只有「滴」显示音符（嗒只有声音）；缩放与打歌界面一致：Tap 宽 = 屏宽 13.2%（NOTE_WIDTH_RATIO_BASE）
                if (!isDip) continue
                drawTapTex(canvas, w / 2f, y, w * 0.1317f, (alpha * 255).toInt())
            }
        }

        lanePaint.color = 0x55FFFFFF
        canvas.drawCircle(0f, judgeLineY, dp(3f), lanePaint)
        canvas.drawCircle(w, judgeLineY, dp(3f), lanePaint)

        postInvalidateOnAnimation()
    }
}

/**
 * 延迟校准（Phigros 风格）：音符落线 + 「嗒嗒嗒嗒—滴」节奏。
 * 节奏：嗒(0) 嗒(200) 嗒(400) 嗒(600) 滴(800)，下一轮滴在 +1000ms。
 * 玩家跟着"滴"点击，记录（点击时刻 - 滴时刻），10 次取中位数。
 */
class CalibrationActivity : Activity() {

    private companion object {
        const val TOTAL = 10
        /** 点击可以比「滴」早这么多（有人习惯抢拍） */
        const val TAP_EARLY_MS = 300L
        /** 点击可以比「滴」晚这么多（蓝牙耳机 + 反应时间） */
        const val TAP_LATE_MS = 600L
    }

    private lateinit var calibView: CalibView
    private lateinit var txtProgress: TextView
    private lateinit var txtResult: TextView
    private lateinit var txtDesc: TextView
    private lateinit var btnApply: TextView
    private lateinit var btnRetry: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var caliPlayer: MediaPlayer? = null
    private var playStartReal = 0L
    private var hitPool: android.media.SoundPool? = null
    private var sndCalHit = 0

    private var firstDipAt = 0L
    private val deltas = ArrayList<Long>()
    private val usedDips = HashSet<Long>()      // 已采集过的「滴」时刻，防止同一拍重复记
    private var finished = false
    private var started = false

    private val ticker = object : Runnable {
        override fun run() {
            if (finished) return
            // 录音自带节奏，ticker 只负责刷新界面
            calibView.invalidate()
            handler.postDelayed(this, CalibView.CYCLE_MS / 2)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_calibration)

        calibView = findViewById(R.id.calibView)
        txtProgress = findViewById(R.id.txtCalibProgress)
        txtResult = findViewById(R.id.txtCalibResult)
        txtDesc = findViewById(R.id.txtCalibDesc)
        btnApply = findViewById(R.id.btnApply)
        btnRetry = findViewById(R.id.btnRetry)

        btnApply.setOnClickListener {
            setResult(RESULT_OK, Intent().putExtra("delay", suggestedDelay()))
            finish()
        }
        btnRetry.setOnClickListener { restart() }

        calibView.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onTap(e.x, e.y)
            true
        }

        try {
            hitPool = android.media.SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
                .also { sndCalHit = it.load(assets.openFd("sounds/cali_hit.ogg"), 1) }
        } catch (_: Exception) {}
    }

    // ---------------- 音效合成 ----------------
    // ---------------- 录音生成（预渲染 wav，播放而非实时合成，测得更准）----------------
    private fun buildCalibrationWav(): File {
        val f = File(cacheDir, "calibration.wav")
        if (f.exists() && f.length() > 0) return f
        try {
            val sr = 22050
            val totalMs = 60_000
            val n = sr * totalMs / 1000
            val data = ShortArray(n)
            fun addTone(startMs: Int, durMs: Int, freq: Double, vol: Double) {
                val s0 = sr * startMs / 1000
                val len = sr * durMs / 1000
                val attack = (sr * 0.004).toInt().coerceAtLeast(1)
                val release = (sr * 0.03).toInt().coerceAtLeast(1)
                for (i in 0 until len) {
                    val idx = s0 + i
                    if (idx >= n) return
                    var env = 1.0
                    if (i < attack) env = i.toDouble() / attack
                    val fromEnd = len - i
                    if (fromEnd < release) env = min(env, fromEnd.toDouble() / release)
                    data[idx] = ((sin(2 * PI * freq * i / sr) * vol * env * 32767.0).toInt() + data[idx]).toShort()
                }
            }
            // 嗒嗒嗒嗒-滴：嗒 0/400/800/1200（1200Hz 45ms），滴 1600（1800Hz 130ms），每 2000ms 一轮
            for (round in 0 until 30) {
                val base = round * 2000
                addTone(base, 45, 1200.0, 0.30)
                addTone(base + 400, 45, 1200.0, 0.30)
                addTone(base + 800, 45, 1200.0, 0.30)
                addTone(base + 1200, 45, 1200.0, 0.30)
                addTone(base + 1600, 130, 1800.0, 0.45)
            }
            // WAV 头（44 字节）
            val byteData = ByteArray(44 + n * 2)
            fun putStr(off: Int, s4: String) { for (j in 0 until 4) byteData[off + j] = s4[j].code.toByte() }
            fun putInt(off: Int, v: Int) { byteData[off] = (v and 0xFF).toByte(); byteData[off+1] = ((v shr 8) and 0xFF).toByte(); byteData[off+2] = ((v shr 16) and 0xFF).toByte(); byteData[off+3] = ((v shr 24) and 0xFF).toByte() }
            fun putShort(off: Int, v: Int) { byteData[off] = (v and 0xFF).toByte(); byteData[off+1] = ((v shr 8) and 0xFF).toByte() }
            putStr(0, "RIFF"); putInt(4, 36 + n * 2); putStr(8, "WAVE")
            putStr(12, "fmt "); putInt(16, 16); putShort(20, 1); putShort(22, 1)
            putInt(24, sr); putInt(28, sr * 2); putShort(32, 2); putShort(34, 16)
            putStr(36, "data"); putInt(40, n * 2)
            for (i in 0 until n) { byteData[44 + i * 2] = (data[i].toInt() and 0xFF).toByte(); byteData[45 + i * 2] = ((data[i].toInt() shr 8) and 0xFF).toByte() }
            f.writeBytes(byteData)
        } catch (_: Exception) {}
        return f
    }

    private fun startCalibrationAudio() {
        try {
            caliPlayer?.release()
            val mp = MediaPlayer()
            mp.setDataSource(buildCalibrationWav().absolutePath)
            mp.prepare()
            mp.start()
            caliPlayer = mp
            playStartReal = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            e.printStackTrace()
            playStartReal = 0L
        }
    }

    // ---------------- 调度 ----------------
    private fun restart() {
        handler.removeCallbacksAndMessages(null)
        finished = false
        started = true
        deltas.clear()
        usedDips.clear()
        txtResult.text = ""
        txtProgress.text = "0 / $TOTAL"
        txtDesc.text = getString(R.string.calib_desc)
        btnApply.visibility = View.GONE

        startCalibrationAudio()
        // 播放录音：滴在文件内 1600ms 处；滴时刻 = 开始播放墙钟 + 1600（每 2000ms 一轮）
        firstDipAt = playStartReal + CalibView.DIP_OFFSET_MS
        calibView.firstDipAt = firstDipAt
        calibView.audioLagMs = 0L
        calibView.running = true
        calibView.mediaPosProvider = { caliPlayer?.currentPosition ?: 0 }

        handler.postDelayed(ticker, CalibView.CYCLE_MS)
    }

    // ---------------- 点击采集 ----------------
    // 点击一律记录；同一声「滴」只记一次（按滴去重，而不是按偏差接近去重，
    // 否则连续两次偏差接近的点击会被误判成双击而不计数）。
    private fun onTap(x: Float, y: Float) {
        if (finished || !started || firstDipAt <= 0L) return
        calibView.flashNow()
        calibView.spawnHitFx(x, y)
        try { hitPool?.play(sndCalHit, 1f, 1f, 1, 0, 1f) } catch (_: Exception) {}

        // 用媒体时钟算偏差：录到的"点击位置"和"滴的位置"都在同一套 currentPosition 里，
        // 设备输出延迟对两者同样叠加，差值就是"这个设备上音符会晚多少"。
        val pos = (caliPlayer?.currentPosition ?: 0).toLong()
        // 匹配到最近的「滴」；太早（还在「嗒」）或离滴太远的一律不算
        val tap = CalibMath.mapTap(
            pos, CalibView.DIP_OFFSET_MS, CalibView.CYCLE_MS, TAP_EARLY_MS, TAP_LATE_MS
        ) ?: return
        val dipPos = tap.dipPos
        val d = tap.delta
        if (!usedDips.add(dipPos)) return          // 这一拍已经记过
        if (deltas.size >= TOTAL) return

        deltas.add(d)                              // 可正可负
        // 用已经测出来的偏移把落线时刻往后挪：currentPosition 比"耳朵听到"超前这么多，
        // 不补的话音符会提前一个输出延迟到线（蓝牙耳机上非常明显）。
        calibView.audioLagMs = suggestedDelay().toLong().coerceIn(0L, 400L)
        txtProgress.text = "${deltas.size} / $TOTAL"
        txtResult.text = "最近一次：${if (d >= 0) "+" else ""}$d ms"
        if (deltas.size >= TOTAL) finishCalibration()
    }


    private fun finishCalibration() {
        finished = true
        calibView.running = false
        handler.removeCallbacksAndMessages(null)
        val v = suggestedDelay()
        val lo = deltas.min(); val hi = deltas.max()
        txtDesc.text = getString(R.string.calib_done)
        txtResult.text = "建议延迟：$v ms（$TOTAL 次中位数，范围 $lo ~ $hi）"
        btnApply.text = "应用 $v ms"
        btnApply.visibility = View.VISIBLE
    }

    /**
     * 建议延迟：取 10 次的**中位数**。
     * 原来取平均，只要有一次点歪（比如点在「嗒」上）整个结果就被拖偏几十毫秒，
     * 中位数对个别脏数据不敏感。允许负数 = 提前点。
     */
    private fun suggestedDelay(): Int =
        CalibMath.median(deltas).toInt().coerceIn(-500, 500)

    override fun onPause() {
        try { caliPlayer?.pause() } catch (_: Exception) {}

        handler.removeCallbacksAndMessages(null)
        calibView.running = false
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (!finished) restart()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { caliPlayer?.stop(); caliPlayer?.release() } catch (_: Exception) {}
        caliPlayer = null
        super.onDestroy()
    }
}