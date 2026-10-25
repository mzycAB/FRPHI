package com.piezmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import java.io.InputStream

/**
 * 一个音符（录制产物）。
 * type: 1=Tap(click) 2=Hold 3=Flick 4=Drag —— 与 RPE / Phira 一致
 * pass: 1=第一阶段(drag/hold) 2=第二阶段(click) 3=第三阶段(flick)
 */
data class RecordedNote(
    val type: Int,
    val startMs: Long,
    val endMs: Long,
    val posX: Float,          // 归一化 [-1, 1]（对应 RPE positionX/675）
    val pass: Int = 1
)

/** 触点状态 */
private class FingerState {
    var downRealMs: Long = 0
    var downPlayMs: Long = 0
    var upPlayMs: Long = 0
    var downX: Float = 0f
    var downY: Float = 0f
    var curX: Float = 0f
    var curY: Float = 0f
    var minX: Float = 0f
    var maxX: Float = 0f
    var active: Boolean = false

    var kind: Int = 0                                  // 0=未定 1=已转 Hold
    var dragIdx: MutableList<Int> = mutableListOf()    // 本次手势创建的 drag 下标
    var lastDragCell: Int = -1
    var lastFillSlot: Long = -1                        // 第二/三次：上次填充的时间槽

    // 编辑模式（暂停时）
    var mode: Int = 0                  // 0=未定 1=点击放置 2=拖动音符 3=滑动时间 4=框选
    var targetNote: Int = -1
    var lastDownNote: Int = -1
    var lastDownReal: Long = 0
    var prevY: Float = 0f
    var groupDrag = false              // 多选整体拖动中
    var groupOrigin: MutableMap<Int, com.piezmaker.RecordedNote>? = null   // 拖动前各音符原值
    var willDelete = false             // 按下时音符已在红框内（松手未拖动 → 删除）
    var moved = false                  // 本次按下后是否真的拖动过（拖过就不删）
    var lineDown = false               // 按下点在判定线上（长按0.5s进入判定线编辑）
    var kfCandidate = -1               // 关键帧长按候选
    var kfDownReal = 0L
    var kfDownX = 0f; var kfDownY = 0f
}

/**
 * 三阶段制谱视图（Phigros 官方编辑器的流程）：
 *
 *  第一次：蓝键 Tap（点一下）+ 蓝长条 Hold（长按不动）。**没有连续放功能**。
 *  第二次：黄键 Drag；按住不放 → 按最小横线间隔连续填充。
 *  第三次：红键 Flick；按住不放 → 按最小横线间隔连续填充。
 *         第二/三次会把前面放好的音符以半透明渲染出来做参考。
 *
 * 音符按时间轴定位：未到的音符在判定线上方，过了判定线向下移动，出屏即消失。
 */
class RecordView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private companion object {
        const val TAG = "FRPHI"
        /** Autoplay 只有落在这段窗口内的音符才会发声/计分，更旧的一律静默跳过 */
        const val AUTO_HIT_WINDOW_MS = 220L
    }

    // ----- 配置 -----
    var bpm: Double = 120.0
    var hLines: Int = 4          // 横线数：每拍分几格（时间量化）
    var vLines: Int = 8          // 竖线数：位置量化
    var linePosPct: Int = 60     // 判定线位置（屏幕高度百分比）
    var trackLengthMs: Long = 0  // 音频总时长（来自播放页），用于打歌 HUD 进度条

    // ----- 判定线姿态（全局偏移/旋转 + 关键帧动画）-----
    var lineMoveX: Float = 0f
    var lineMoveY: Float = 0f
    var lineRotate: Float = 0f
    val lineKeyframes: MutableList<LineKeyframe> = mutableListOf()   // 按时间升序

    /** 任意时刻的判定线姿态 [x,y,rot]（Phiedit 事件模型：最后 start≤t 的帧起线性插值，之后保持） */
    fun linePoseAt(now: Long): FloatArray {
        if (lineEditActive) return floatArrayOf(lineMoveX, lineMoveY, lineRotate)   // 编辑模式：实时全局姿态
        if (lineKeyframes.isEmpty()) return floatArrayOf(lineMoveX, lineMoveY, lineRotate)
        val kfs = lineKeyframes.sortedBy { it.timeMs }
        val i = kfs.indexOfLast { it.timeMs <= now }
        if (i < 0) return floatArrayOf(kfs.first().moveX, kfs.first().moveY, kfs.first().rotate)
        val a = kfs[i]
        if (i + 1 >= kfs.size) return floatArrayOf(a.moveX, a.moveY, a.rotate)
        val b = kfs[i + 1]
        val span = (b.timeMs - a.timeMs).toFloat().coerceAtLeast(1f)
        val t = ((now - a.timeMs).toFloat() / span).coerceIn(0f, 1f)
        return floatArrayOf(
            a.moveX + (b.moveX - a.moveX) * t,
            a.moveY + (b.moveY - a.moveY) * t,
            a.rotate + (b.rotate - a.rotate) * t
        )
    }

    /** 判定线中心屏幕坐标（姿态应用后） */
    fun lineCenter(now: Long): FloatArray {
        val p = linePoseAt(now)
        return floatArrayOf(width / 2f + p[0] * width / 2f, judgeLineY + p[1] * height / 2f)
    }

    /** 点到旋转后判定线（无限长直线）的距离 */
    fun distToLine(x: Float, y: Float, now: Long): Float {
        val lc = lineCenter(now)
        val pose = linePoseAt(now)
        val a = Math.toRadians(-pose[2].toDouble())
        val dx = Math.cos(a).toFloat()
        val dy = Math.sin(a).toFloat()
        return Math.abs((x - lc[0]) * dy - (y - lc[1]) * dx).toFloat()
    }

    /** 音符屏幕坐标（局部坐标 + 判定线姿态变换）；命中/特效/红框统一用它 */
    fun noteScreenPos(n: RecordedNote, now: Long): FloatArray {
        val lc = lineCenter(now)
        val pose = linePoseAt(now)
        val a = Math.toRadians(-pose[2].toDouble())
        val xl = (n.posX + 1f) / 2f * width - width / 2f
        val yl = (now - n.startMs) * pxPerMs
        return floatArrayOf(
            lc[0] + (xl * Math.cos(a) - yl * Math.sin(a)).toFloat(),
            lc[1] + (xl * Math.sin(a) + yl * Math.cos(a)).toFloat()
        )
    }
    var pass: Int = 1            // 当前编辑阶段 1/2/3
    var playheadProvider: (() -> Long)? = null
    var playingProvider: (() -> Boolean)? = null

    var holdMsThreshold: Long = 240
    val flickMinPx: Float = 12f

    // ----- 编辑模式（暂停时）：自由放置/拖动/删除/搓时间轴 -----
    var editMode: Boolean = false
        set(value) { field = value; invalidate() }
    /** 判定线编辑模式（长按判定线 0.5s 激活）：拖中心方框=移动，拖箭头=旋转，点方块=打关键帧 */
    var lineEditActive: Boolean = false
        set(value) {
            if (field != value) { field = value; onLineEditChanged?.invoke(value); invalidate() }
        }
    var onLineEditChanged: ((Boolean) -> Unit)? = null      // 编辑模式开/关（Activity 显隐输入面板）
    var onLinePoseChanged: (() -> Unit)? = null             // 姿态变化（Activity 刷新输入框）
    /** 判定线流速：音符下落速度倍率（Phiedit speed 事件） */
    var lineSpeed: Float = 1f
    /** 时间轴缩放（横线视觉距离缩放），默认 0.1，由输入框控制 */
    var timelineScale: Float = 0.1f
    var lineEditHand = 0                 // 0=无 1=移动 2=旋转
    var lineEditStartRot = 0f            // 按下时手指相对线中心的角度（度）
    var lineEditStartAng = 0f            // 按下时判定线旋转角
    private var selectedKf = -1          // 长按红框选中的关键帧下标
    private var selectedKfAt = 0L

    /** 编辑模式：被框住的音符集合（画红框），可多选；点红框音符=删除全部，拖红框音符=整体移动 */
    private val selectedSet = mutableSetOf<Int>()
    private var boxActive = false          // 长按1s膨胀动画进行中
    private var boxStartedAt = 0L
    private var boxX0 = 0f; private var boxY0 = 0f
    private var boxX1 = 0f; private var boxY1 = 0f
    private var selectingBox = false       // 正在拉白色预览框
    var seekBy: ((Int) -> Unit)? = null          // 相对毫秒定位（Activity 里 seekTo）
    /** 平滑移动：开启后放置/移动不吸附横竖线交叉点（默认吸附） */
    var smoothMove: Boolean = false
        set(value) { field = value; invalidate() }

    /**
     * 放置兼容（针对手机误触）：
     *  0 = 兼容关闭：拖动/删除只能点中音符本身
     *  1 = 红框兼容：音符已出现红框后，红框及其内部区域都能拖动/删除；第一次选中仍要点到音符
     *  2 = 全部兼容：红框区域本身就能触发选中（不用点中音符），之后红框及内部都能拖动/删除
     */
    var compatMode: Int = 0
        set(value) { field = value.coerceIn(0, 2); invalidate() }
    var pxPerMs: Float = 0.1f * (600f / 720f) * 0.1f   // 时间→像素比例（onDraw 刷新）
    private var lastEditHint = 0L

    // ----- 打歌模式（点"AUTOPLAY"后从当前位置开始自动演出）-----
    var playMode: Boolean = false
        private set
    private val judgedIdx = HashSet<Int>()
    private var combo = 0
    private var hitCount = 0
    private var missCount = 0
    private var judgeText = ""
    private var judgeShownAt = 0L
    private var lastJudgeNow = 0L
    /** 正在通过判定线的长条持续特效（头部到线 → 尾部离线的整段时间） */
    private class HoldFx(val idx: Int, val untilMs: Long)
    private val holdFxList = mutableListOf<HoldFx>()
    private val judgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    /**
     * 开始一次 Autoplay。
     * @param startAtMs 按下 AUTOPLAY 时的音乐位置：比它更早的音符直接视为“已过去”，
     *                  否则第一帧会把整张谱所有音符瞬间打一遍。
     */
    fun startPlaySession(startAtMs: Long = playMs()) {
        playMode = true
        judgedIdx.clear()
        combo = 0
        hitCount = 0
        missCount = 0
        judgeText = ""
        judgeShownAt = 0
        lastJudgeNow = startAtMs
        holdFxList.clear()
        seedPassedNotes(startAtMs)
        Log.i(TAG, "startPlaySession startAt=${startAtMs}ms skipped=${judgedIdx.size}/${notes.size} sound=$soundEnabled")
        invalidate()
    }

    /** 把开始时间之前已经过去的音符标记为“已处理”，只跳过、不出声、不计分 */
    private fun seedPassedNotes(startAtMs: Long) {
        val cut = startAtMs - AUTO_HIT_WINDOW_MS
        for (i in notes.indices) {
            if (notes[i].startMs < cut) judgedIdx.add(i)
        }
    }

    fun stopPlaySession() {
        playMode = false
        invalidate()
    }

    fun playStats(): String = "Autoplay · ${hitCount}H · ${combo}x · ${judgedIdx.size}/${notes.size}"

    /** 长按音效按钮时弹出，便于在手机上排查声音/判定问题 */
    fun debugInfo(): String {
        val pos = try { playheadProvider?.invoke() ?: 0L } catch (_: Exception) { 0L }
        return "now=${pos}ms judged=${judgedIdx.size}/${notes.size} " +
            "playMode=$playMode snd=${if (soundPool != null) "ok" else "null"} " +
            "ids=$sndClick/$sndDrag/$sndFlick enabled=$soundEnabled"
    }

    // ----- 音符数据 -----
    val notes: MutableList<RecordedNote> = mutableListOf()

    private val fingers = SparseArray<FingerState>()

    // ----- 绘制资源 -----
    private var bgBitmap: Bitmap? = null
    private val gridPaint = Paint().apply { color = intColor(0x66FFFFFF); strokeWidth = 2f }
    private val linePaint = Paint().apply { color = Color.WHITE; strokeWidth = 6f }
    /** 拍号数字：垂直居中贴在粗线上，避免看起来属于相邻的细线 */
    private val beatNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        color = intColor(0xCCFFFFFF)
    }
    private val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val texPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = true }

    private var hitFxBmp: Bitmap? = null
    private var tapBmp: Bitmap? = null
    private var holdBmp: Bitmap? = null
    private var flickBmp: Bitmap? = null
    private var dragBmp: Bitmap? = null
    // Phira 双押提示贴图（*_mh，半透明放大型）
    private var tapMh: Bitmap? = null
    private var holdMh: Bitmap? = null
    private var flickMh: Bitmap? = null
    private var dragMh: Bitmap? = null

    private fun loadTex(n: String): Bitmap? = try {
        context.assets.open("notes/$n.png").use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) { null }

    // 打击音效（Phira ogg；与 judge.rs 映射一致：Click→click / Flick→flick / Drag→drag / Hold→click）
    private var soundPool: SoundPool? = null
    private var sndClick = 0
    private var sndDrag = 0
    private var sndFlick = 0
    private var soundPlayFailLogged = false

    /** 打击音效开关（默认开） */
    var soundEnabled: Boolean = true
        set(value) { field = value; invalidate() }

    /** 进入录制页就预加载，避免第一次打击（尤其 Autoplay 第一下）没声音 */
    fun prepareSounds() = ensureSounds()

    private fun ensureSounds() {
        if (soundPool != null) return
        try {
            val sp = SoundPool.Builder()
                .setMaxStreams(24)   // 支持多种音符同时命中并发播放
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
            sp.setOnLoadCompleteListener { _, sampleId, status ->
                if (status != 0) Log.w(TAG, "SoundPool load failed sample=$sampleId status=$status")
            }
            sndClick = sp.load(context.assets.openFd("sounds/click.ogg"), 1)
            sndDrag = sp.load(context.assets.openFd("sounds/drag.ogg"), 1)
            sndFlick = sp.load(context.assets.openFd("sounds/flick.ogg"), 1)
            soundPool = sp
            Log.i(TAG, "SoundPool preload ids=$sndClick/$sndDrag/$sndFlick")
        } catch (e: Exception) {
            Log.e(TAG, "SoundPool init failed", e)
            soundPool = null
        }
    }

    fun playHitSound(type: Int) {
        if (!soundEnabled) return
        ensureSounds()
        val sp = soundPool ?: return
        val id = when (type) {
            3 -> sndFlick
            4 -> sndDrag
            else -> sndClick        // Tap / Hold 都用 click（Phira 同款映射）
        }
        try {
            val stream = sp.play(id, 1f, 1f, 1, 0, 1f)
            if (stream == 0 && !soundPlayFailLogged) {
                soundPlayFailLogged = true
                Log.w(TAG, "SoundPool.play returned 0 (sample not ready?) id=$id")
            }
        } catch (e: Exception) {
            Log.e(TAG, "SoundPool.play failed", e)
        }
    }

    fun releaseSounds() {
        soundPool?.release()
        soundPool = null
    }

    // 打击特效（Phira hit_fx 精灵图 8x7 = 56 帧）
    private class HitFx(val x: Float, val y: Float, val at: Long)
    private val hitFxList = mutableListOf<HitFx>()
    private val HIT_FX_COLS = 8
    private val HIT_FX_ROWS = 7
    private val HIT_FX_DUR = 460L

    var judgeLineY: Float = 0f
        private set

    init {
        isClickable = true
        setBackgroundColor(Color.parseColor("#14161E"))
        tapBmp = loadTex("click")
        holdBmp = loadTex("hold")
        flickBmp = loadTex("flick")
        dragBmp = loadTex("drag")
        hitFxBmp = loadTex("hit_fx")
        tapMh = loadTex("click_mh")
        holdMh = loadTex("hold_mh")
        flickMh = loadTex("flick_mh")
        dragMh = loadTex("drag_mh")
        ensureSounds()   // 提前加载，第一次打击就有声音
    }

    /** 在 (x,y) 播一次 Phira 打击特效 */
    fun spawnHitFx(x: Float, y: Float) {
        if (hitFxList.size > 14) hitFxList.removeAt(0)
        hitFxList.add(HitFx(x, y, SystemClock.elapsedRealtime()))
    }

    fun setBackgroundImage(uri: Uri) {
        try {
            val ins: InputStream = context.contentResolver.openInputStream(uri) ?: return
            val bmp = BitmapFactory.decodeStream(ins)
            ins.close()
            bgBitmap = bmp
            postInvalidate()
        } catch (_: Exception) {}
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        judgeLineY = h * (linePosPct.coerceIn(0, 100) / 100f)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun intColor(v: Long): Int = v.toInt()
    /** 编辑模式搓时间：拖动过程累积的偏移（渲染用虚拟时间，松手才写回播放器） */
    var editSeekOffset: Long = 0
    var onSeekAbs: ((Int) -> Unit)? = null

    private fun playMs(): Long = (playheadProvider?.invoke() ?: 0L) + editSeekOffset

    /** 编辑模式：累加搓动偏移（线/数字/音符都平滑跟随，不触发真实 seek） */
    fun addEditSeek(dt: Int) {
        editSeekOffset += dt
        invalidate()
    }

    /** 编辑模式：松手后把最终时间写回播放器并清零偏移 */
    fun flushEditSeek() {
        if (editSeekOffset == 0L) return
        val base = playheadProvider?.invoke() ?: 0L
        val target = (base + editSeekOffset).toInt()
        editSeekOffset = 0
        onSeekAbs?.invoke(target)
        invalidate()
    }

    /** 一格毫秒（最小横线间隔） */
    private fun cellMs(): Double = (60000.0 / bpm / hLines.coerceAtLeast(1)).coerceAtLeast(1.0)

    /** 毫秒量化到格点 */
    private fun quantMs(ms: Long): Long {
        val c = cellMs()
        return (Math.round(ms / c) * c).toLong()
    }

    /** 屏幕 x 量化到竖线格，映射 [-1,1] */
    fun quantizeX(screenX: Float): Float {
        val w = width.toFloat().coerceAtLeast(1f)
        val p = (screenX / w).coerceIn(0f, 1f)
        val cell = 1f / vLines
        val q = Math.round(p / cell).coerceIn(0, vLines) * cell
        return q * 2f - 1f
    }

    /** 屏幕 x 直接映射 [-1,1]（不平滑吸附） */
    private fun rawPosX(screenX: Float): Float =
        (screenX / width.toFloat() * 2f - 1f).coerceIn(-1f, 1f)

    private fun cellIndex(screenX: Float): Int {
        val w = width.toFloat().coerceAtLeast(1f)
        val p = (screenX / w).coerceIn(0f, 1f)
        return Math.round(p * vLines)
    }

    private fun drawTex(canvas: Canvas, bmp: Bitmap?, l: Float, t: Float, r: Float, b: Float, alpha: Int = 255) {
        if (bmp == null || r <= l || b <= t || alpha <= 0) return
        texPaint.alpha = alpha
        canvas.drawBitmap(bmp, null, RectF(l, t, r, b), texPaint)
    }

    /** Phiedit 风格横线：每拍 = 主线(白20%) + 拍号数字；每格 = 次线(白10%) */
    private fun drawHLine(canvas: Canvas, w: Float, y: Float, gi: Long) {
        val hl = hLines.coerceAtLeast(1).toLong()
        val isBeat = Math.floorMod(gi, hl) == 0L
        if (isBeat) {
            gridPaint.alpha = 51                    // EDITOR_VIEW_HORIZONTAL_MAIN_LINE_COLOR = 0.2
            gridPaint.strokeWidth = dp(3f)
            val beatNo = Math.floorDiv(gi, hl)
            if (beatNo >= 0) {
                val label = beatNo.toString()
                beatNumPaint.textSize = dp(12f)
                beatNumPaint.style = Paint.Style.FILL
                val fm = beatNumPaint.fontMetrics
                // 数字垂直中心 = 本条粗线的 y（Phiedit 的 writeText 也是 centerY 对齐）
                val baseline = y - (fm.ascent + fm.descent) / 2f
                // 先描一圈黑边，压在线上也能看清
                beatNumPaint.style = Paint.Style.STROKE
                beatNumPaint.strokeWidth = dp(3f)
                beatNumPaint.color = intColor(0xDD000000)
                canvas.drawText(label, dp(5f), baseline, beatNumPaint)
                beatNumPaint.style = Paint.Style.FILL
                beatNumPaint.color = intColor(0xCCFFFFFF)
                canvas.drawText(label, dp(5f), baseline, beatNumPaint)
            }
        } else {
            gridPaint.alpha = 26                    // EDITOR_VIEW_HORIZONTAL_LINE_COLOR = 0.1
            gridPaint.strokeWidth = dp(1.5f)
        }
        canvas.drawLine(0f, y, w, y, gridPaint)
        gridPaint.strokeWidth = dp(2f)
    }

    // ------------------------------------------------------------ 绘制
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        bgBitmap?.let { bmp ->
            val scale = maxOf(w / bmp.width, h / bmp.height)
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            val left = (w - dw) / 2f
            val top = (h - dh) / 2f
            canvas.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), null)
            canvas.drawColor(intColor(0x99000000))
        }

        val now = playMs()
        pxPerMs = 0.16f * (h / 720f) * lineSpeed * timelineScale
        val cellW = w / vLines.toFloat()
        val cMs = cellMs().toFloat()

        // ---- 横线（Phiedit 风格：每拍主线 + 拍号，每格次线；与音符同向滚动）----
        // 音符 y = 判定线 + (now - 音符时间) × pxPerMs（过去在下、未来在上）。
        // 横线是"世界的格子"，必须用同一公式：第 gi 格的 y = 判定线 + (now - gi×格长) × pxPerMs。
        // 最近"过去"格（gi0）落在判定线下方 phasePx 处；时间前进 → 整体下移，与音符同向。
        val gridPx = cMs * pxPerMs
        val phasePx = (now % cellMs()).toFloat() * pxPerMs
        val gi0 = Math.floor(now / cellMs()).toLong()

        // 方向很重要：时间前进 → 所有线向下走。
        // 判定线下方 = 过去（格号递减），上方 = 未来（格号递增），
        // 这样拍号/粗线才会和音符所在的拍子对齐（之前上下两侧格号写反了）。
        var y = judgeLineY + phasePx      // 最近过去格（格号 gi0）
        var gi = gi0
        while (y < h + 10f) { drawHLine(canvas, w, y, gi); y += gridPx; gi-- }   // 向下 = 更早的过去
        y = judgeLineY + phasePx - gridPx
        gi = gi0 + 1
        while (y > -10f) { drawHLine(canvas, w, y, gi); y -= gridPx; gi++ }      // 向上 = 未来

        // ---- 判定线（跟随姿态：平移 + 旋转；编辑判定线时变黄）----
        val pose = linePoseAt(now)
        val lc = lineCenter(now)
        linePaint.color = if (lineEditActive) intColor(0xFFFFEB3B) else Color.WHITE
        canvas.save()
        canvas.translate(lc[0], lc[1])
        canvas.rotate(-pose[2])
        canvas.drawLine(-w, 0f, w, 0f, linePaint)

        // ---- 竖线刻度（垂直判定线，随线旋转）----
        gridPaint.alpha = 50
        for (k in 1 until vLines) {
            val x = cellW * k - w / 2f
            canvas.drawLine(x, -dp(16f), x, dp(16f), gridPaint)
        }
        gridPaint.alpha = 100
        canvas.drawLine(0f, -dp(12f), 0f, dp(12f), gridPaint)
        canvas.restore()

        // 判定线编辑模式：中心白色方框 + 旋转箭头 + 关键帧方块 + 左侧时间条
        if (lineEditActive) drawLineEditUi(canvas, lc[0], lc[1], now, w, h)

        // ---- 音符（按时间轴滚动，出屏消失）----
        if (playMode) {
            // Autoplay：全部音符满分亮度；已判定的 Hold 身体持续显示到结束
            val dh = doubleHintIdx(now)          // 双押集合（Phira multiple_hint）
            for (i in notes.indices) {
                val n = notes[i]
                if (i in judgedIdx) {
                    // 已判定的长条：整个长条移出屏幕边缘才消失（drawNoteAt 内部有出屏裁剪）
                    if (n.type == 2) drawNoteAt(canvas, n, now, pxPerMs, cellW, 255, i in dh)
                    continue
                }
                drawNoteAt(canvas, n, now, pxPerMs, cellW, 255, i in dh)
            }
            // 长条与判定线接触期间：持续打击特效（音效只响一次，见 tickAutoPlay）
            val holdFxFxBmp = hitFxBmp
            if (holdFxFxBmp != null && holdFxList.isNotEmpty()) {
                val fw = holdFxFxBmp.width / HIT_FX_COLS
                val fh = holdFxFxBmp.height / HIT_FX_ROWS
                val it = holdFxList.iterator()
                while (it.hasNext()) {
                    val fx = it.next()
                    if (fx.idx !in notes.indices || now > fx.untilMs) { it.remove(); continue }
                    val n = notes[fx.idx]
                    val lc = lineCenter(now)
                    val pose = linePoseAt(now)
                    val a = Math.toRadians(-pose[2].toDouble())
                    val xl = (n.posX + 1f) / 2f * width - width / 2f
                    val px = lc[0] + (xl * Math.cos(a)).toFloat()
                    val py = lc[1] + (xl * Math.sin(a)).toFloat()
                    // 固定中帧 + 呼吸缩放，持续存在
                    val pulse = 0.82f + 0.18f * Math.sin(now / 160.0).toFloat()
                    val size = dp(150f) * 0.9f * pulse
                    val idx = 28
                    val col = idx % HIT_FX_COLS
                    val row = idx / HIT_FX_COLS
                    texPaint.alpha = 215
                    canvas.drawBitmap(
                        holdFxFxBmp,
                        Rect(col * fw, row * fh, (col + 1) * fw, (row + 1) * fh),
                        RectF(px - size / 2, py - size / 2, px + size / 2, py + size / 2),
                        texPaint
                    )
                }
            }
            tickAutoPlay(now)
            drawJudgeOverlay(canvas, now, w, h)
        } else {
            for ((i, n) in notes.withIndex()) {
                // 不降低其它阶段音符亮度（用户明确要求）
                drawNoteAt(canvas, n, now, pxPerMs, cellW, 255)
                // 编辑模式选中：红框（多选集合，跟随判定线姿态并旋转）
                if (editMode && i in selectedSet) {
                    val p = noteScreenPos(n, now)
                    drawSelectFrame(canvas, p[0], p[1], cellW, -linePoseAt(now)[2])
                }
            }
            // 编辑模式框选：长按1s膨胀动画 + 框选白色预览框
            if (editMode) {
                val nowR = SystemClock.elapsedRealtime()
                if (boxActive) {
                    val p = ((nowR - boxStartedAt).toFloat() / 300f).coerceIn(0f, 1f)
                    val size = dp(150f) * p
                    livePaint.style = Paint.Style.STROKE
                    livePaint.strokeWidth = dp(2.5f)
                    livePaint.color = intColor(0xDDFFFFFF)
                    canvas.drawRoundRect(boxX0 - size / 2, boxY0 - size / 2, boxX0 + size / 2, boxY0 + size / 2, dp(14f) * p, dp(14f) * p, livePaint)
                    livePaint.style = Paint.Style.FILL
                    livePaint.color = ((50 * (1f - p / 2f)).toInt() shl 24) or 0xFFFFFF
                    canvas.drawRoundRect(boxX0 - size / 2, boxY0 - size / 2, boxX0 + size / 2, boxY0 + size / 2, dp(14f) * p, dp(14f) * p, livePaint)
                    livePaint.style = Paint.Style.FILL
                    if (p >= 1f) boxActive = false
                } else if (selectingBox) {
                    val rx0 = minOf(boxX0, boxX1); val ry0 = minOf(boxY0, boxY1)
                    val rx1 = maxOf(boxX0, boxX1); val ry1 = maxOf(boxY0, boxY1)
                    livePaint.color = intColor(0x40FFFFFF)
                    canvas.drawRect(rx0, ry0, rx1, ry1, livePaint)
                    livePaint.style = Paint.Style.STROKE
                    livePaint.strokeWidth = dp(2.5f)
                    livePaint.color = intColor(0xCCFFFFFF)
                    canvas.drawRect(rx0, ry0, rx1, ry1, livePaint)
                    livePaint.style = Paint.Style.FILL
                }
            }

            // 编辑模式 + 第四次：按住时画长条放置预览（起点=按下，随手指拉伸）
            if (editMode && pass == 4) {
                for (i in 0 until fingers.size()) {
                    val f = fingers.valueAt(i)
                    if (!f.active || f.mode != 1) continue
                    val y1 = f.downY
                    val y2 = f.curY
                    if (Math.abs(y2 - y1) < dp(6f)) {
                        livePaint.style = Paint.Style.STROKE
                        livePaint.strokeWidth = dp(2f)
                        livePaint.color = intColor(0xAAFFFFFF)
                        canvas.drawRoundRect(f.downX - cellW * 0.55f, y1 - dp(10f), f.downX + cellW * 0.55f, y1 + dp(10f), dp(4f), dp(4f), livePaint)
                        livePaint.style = Paint.Style.FILL
                    } else {
                        val x1 = f.downX
                        val bw = cellW * 0.55f
                        // 半透明蓝长条预览
                        drawTex(canvas, holdBmp, x1 - bw, minOf(y1, y2), x1 + bw, maxOf(y1, y2), 110)
                        // 起点/终点标记
                        livePaint.color = intColor(0xCC00E5FF)
                        canvas.drawRect(x1 - cellW * 0.6f, y1 - dp(2f), x1 + cellW * 0.6f, y1 + dp(2f), livePaint)
                        canvas.drawRect(x1 - cellW * 0.6f, y2 - dp(2f), x1 + cellW * 0.6f, y2 + dp(2f), livePaint)
                    }
                }
            }
        }

        // ---- 实时预览 ----
        val nowReal = SystemClock.elapsedRealtime()

        // 打击特效：Phira hit_fx 精灵图（8 列 x 7 行 = 56 帧）
        val fxBmp = hitFxBmp
        if (fxBmp != null && hitFxList.isNotEmpty()) {
            val fw = fxBmp.width / HIT_FX_COLS
            val fh = fxBmp.height / HIT_FX_ROWS
            val total = HIT_FX_COLS * HIT_FX_ROWS
            val size = dp(150f) * 0.9f          // Phira hitFxScale = 0.9
            val it = hitFxList.iterator()
            while (it.hasNext()) {
                val fx = it.next()
                val age = nowReal - fx.at
                if (age > HIT_FX_DUR) { it.remove(); continue }
                if (age < 0) continue
                val idx = (age * total / HIT_FX_DUR).toInt().coerceIn(0, total - 1)
                val col = idx % HIT_FX_COLS
                val row = idx / HIT_FX_COLS
                texPaint.alpha = 255
                canvas.drawBitmap(
                    fxBmp,
                    Rect(col * fw, row * fh, (col + 1) * fw, (row + 1) * fh),
                    RectF(fx.x - size / 2, fx.y - size / 2, fx.x + size / 2, fx.y + size / 2),
                    texPaint
                )
            }
        }

        // 编辑模式不显示手指光圈（避免遮挡/误认）
        if (!editMode) for (i in 0 until fingers.size()) {
            val f = fingers.valueAt(i)
            if (!f.active) continue
            val pulse = 0.5f + 0.5f * Math.sin(nowReal / 110.0).toFloat()
            livePaint.style = Paint.Style.FILL
            livePaint.color = intColor(0x3000E5FF)
            canvas.drawCircle(f.curX, f.curY, dp(34f) + dp(6f) * pulse, livePaint)
            livePaint.style = Paint.Style.STROKE
            livePaint.strokeWidth = dp(2.5f)
            livePaint.color = intColor(0xCC00E5FF)
            canvas.drawCircle(f.curX, f.curY, dp(22f), livePaint)
            livePaint.style = Paint.Style.FILL
        }

        // 长按填充（第二/三次；编辑模式暂停时不铺点）
        if (editMode) checkBoxLongPress() else tickFill()

        postInvalidateOnAnimation()
    }

    /** 音符：画在判定线局部坐标系（跟随判定线姿态平移/旋转）。
     *  局部 x = 沿线方向（-w/2..w/2），局部 y = 垂直法线方向（未来在上）。isDouble=双押提示 */
    private fun drawNoteAt(canvas: Canvas, n: RecordedNote, now: Long, pxPerMs: Float, cellW: Float, alpha: Int, isDouble: Boolean = false) {
        val h = height.toFloat()
        val lc = lineCenter(now)
        val pose = linePoseAt(now)
        val yStartL = (now - n.startMs) * pxPerMs
        if (yStartL > h + 120 || yStartL < -h - 120) return

        canvas.save()
        canvas.translate(lc[0], lc[1])
        canvas.rotate(-pose[2])
        val x = (n.posX + 1f) / 2f * width - width / 2f

        when (n.type) {
            1 -> {
                val bmp = if (isDouble) tapMh ?: tapBmp else tapBmp
                val nw = cellW * (if (isDouble) 1.25f else 1.05f)   // 四类音符宽度统一（Phira note_width ≈ 屏宽 13.2% ≈ 1 格）
                val nh = nw * (bmp?.height?.toFloat() ?: 100f) / (bmp?.width?.toFloat() ?: 998f)
                drawTex(canvas, bmp, x - nw / 2, yStartL - nh / 2, x + nw / 2, yStartL + nh / 2, alpha)
                if (isDouble && alpha > 200) drawDoubleGlow(canvas, x, yStartL, cellW)
            }
            3 -> {
                val bmp = if (isDouble) flickMh ?: flickBmp else flickBmp
                val nw = cellW * (if (isDouble) 1.32f else 1.05f)
                val nh = nw * (bmp?.height?.toFloat() ?: 172f) / (bmp?.width?.toFloat() ?: 998f)
                drawTex(canvas, bmp, x - nw / 2, yStartL - nh / 2, x + nw / 2, yStartL + nh / 2, alpha)
                if (isDouble && alpha > 200) drawDoubleGlow(canvas, x, yStartL, cellW)
            }
            4 -> {
                val bmp = if (isDouble) dragMh ?: dragBmp else dragBmp
                val nw = cellW * (if (isDouble) 1.18f else 1.05f)
                val nh = nw * (bmp?.height?.toFloat() ?: 50f) / (bmp?.width?.toFloat() ?: 948f)
                drawTex(canvas, bmp, x - nw / 2, yStartL - nh / 2, x + nw / 2, yStartL + nh / 2, alpha)
                if (isDouble && alpha > 200) drawDoubleGlow(canvas, x, yStartL, cellW)
            }
            2 -> {
                // Hold 只画 hold.png 长条：贴图本身自带头部渐变，不在上面叠 Tap，避免"长条+蓝键"
                val yEndL = (now - n.endMs) * pxPerMs
                val bodyTop = minOf(yStartL, yEndL)
                val bodyBot = maxOf(yStartL, yEndL)
                val bw = cellW * (if (isDouble) 1.05f else 1.05f)
                if (bodyBot > -300 && bodyTop < h + 300) {
                    drawTex(canvas, if (isDouble) holdMh ?: holdBmp else holdBmp, x - bw / 2, bodyTop, x + bw / 2, bodyBot, alpha)
                    if (isDouble && alpha > 200) drawDoubleGlow(canvas, x, yStartL, cellW)
                }
            }
        }
        canvas.restore()
    }

    /** 找 (x,y) 附近的音符（含下落中的），返回下标，未找到 -1 */
    private fun findNoteAt(x: Float, y: Float, now: Long): Int {
        var best = -1
        var bestD = dp(26f)          // 碰到音符才算（音符宽≈1.05格）
        for (i in notes.indices) {
            val p = noteScreenPos(notes[i], now)
            val d = Math.sqrt(((x - p[0]) * (x - p[0]) + (y - p[1]) * (y - p[1])).toDouble()).toFloat()
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** 红框矩形：与 drawSelectFrame 完全一致，保证“看到的框”就是“能点的框” */
    private fun drawSelectFrame(canvas: Canvas, x: Float, y: Float, cellW: Float, rot: Float = 0f) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(rot)                       // 红框跟随判定线旋转
        livePaint.style = Paint.Style.STROKE
        livePaint.strokeWidth = dp(3f)
        livePaint.color = intColor(0xFFFF5252)
        val halfW = cellW * 0.72f
        val halfH = dp(18f)
        canvas.drawRoundRect(-halfW, -halfH, halfW, halfH, dp(9f), dp(9f), livePaint)
        livePaint.style = Paint.Style.FILL
        canvas.restore()
    }

    /** 判定线编辑 UI：中心白色方框、旋转箭头、关键帧方块、左侧时间条 */
    private fun drawLineEditUi(canvas: Canvas, cx: Float, cy: Float, now: Long, w: Float, h: Float) {
        // 中心白色方框（拖动=移动判定线）
        livePaint.style = Paint.Style.STROKE
        livePaint.strokeWidth = dp(2.5f)
        livePaint.color = Color.WHITE
        canvas.drawRect(cx - dp(16f), cy - dp(16f), cx + dp(16f), cy + dp(16f), livePaint)
        livePaint.style = Paint.Style.FILL
        livePaint.color = intColor(0x50FFFFFF)
        canvas.drawRect(cx - dp(16f), cy - dp(16f), cx + dp(16f), cy + dp(16f), livePaint)

        // 左上/右下：旋转箭头
        drawArrow(canvas, cx - dp(40f), cy - dp(40f), -135f)
        drawArrow(canvas, cx + dp(40f), cy + dp(40f), 45f)

        // 左下/右上：关键帧小方块（点击=打关键帧）
        livePaint.style = Paint.Style.STROKE
        livePaint.strokeWidth = dp(2f)
        livePaint.color = intColor(0xFF00E5FF)
        drawDiamond(canvas, cx - dp(40f), cy + dp(40f))
        drawDiamond(canvas, cx + dp(40f), cy - dp(40f))
        livePaint.style = Paint.Style.FILL

        // 左侧关键帧时间条 + 圆点
        val barX = dp(8f)
        gridPaint.alpha = 60
        canvas.drawLine(barX, dp(40f), barX, h - dp(120f), gridPaint)
        val kfs = lineKeyframes.sortedBy { it.timeMs }
        for ((i, k) in kfs.withIndex()) {
            val ky = judgeLineY - (k.timeMs - now) * pxPerMs   // 固定时间轴位置，不随判定线移动
            if (ky < dp(30f) || ky > h - dp(130f)) continue
            livePaint.style = Paint.Style.FILL
            livePaint.color = intColor(0xFF00E5FF)
            // 关键帧标记：菱形（旋转45°方块）
            canvas.save()
            canvas.translate(barX, ky)
            canvas.rotate(45f)
            canvas.drawRect(-dp(7f), -dp(7f), dp(7f), dp(7f), livePaint)
            canvas.restore()
            if (i == selectedKf) {
                livePaint.style = Paint.Style.STROKE
                livePaint.strokeWidth = dp(3f)
                livePaint.color = intColor(0xFFFF5252)
                canvas.drawCircle(barX, ky, dp(16f), livePaint)
                livePaint.style = Paint.Style.FILL
            }
        }
        livePaint.style = Paint.Style.FILL
    }

    /** 旋转箭头（三角） */
    private fun drawArrow(canvas: Canvas, x: Float, y: Float, rot: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(rot)
        livePaint.style = Paint.Style.FILL
        livePaint.color = intColor(0xCCFFFFFF)
        val p = android.graphics.Path()
        p.moveTo(0f, -dp(12f)); p.lineTo(dp(12f), dp(6f)); p.lineTo(-dp(12f), dp(6f)); p.close()
        canvas.drawPath(p, livePaint)
        canvas.restore()
        livePaint.style = Paint.Style.FILL
    }

    /** 菱形小方块 */
    private fun drawDiamond(canvas: Canvas, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(45f)
        canvas.drawRect(-dp(8f), -dp(8f), dp(8f), dp(8f), livePaint)
        canvas.restore()
    }

    /** 红框矩形：与 drawSelectFrame 完全一致，保证“看到的框”就是“能点的框” */
    private fun frameRectFor(n: RecordedNote, now: Long): RectF {
        val p = noteScreenPos(n, now)
        val halfW = (width.toFloat().coerceAtLeast(1f) / vLines.coerceAtLeast(1)) * 0.72f
        val halfH = dp(18f)
        return RectF(p[0] - halfW, p[1] - halfH, p[0] + halfW, p[1] + halfH)
    }

    /**
     * 命中红框区域（框线 + 框内）。
     * @param onlySelected true = 只查已经框住的音符（红框兼容）；false = 查全部音符（全部兼容）
     * @return 命中的音符下标，未命中 -1
     */
    private fun findFrameNoteAt(x: Float, y: Float, now: Long, onlySelected: Boolean): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        val indices: Iterable<Int> = if (onlySelected) selectedSet else notes.indices
        for (i in indices) {
            if (i !in notes.indices) continue
            val r = frameRectFor(notes[i], now)
            if (x < r.left || x > r.right || y < r.top || y > r.bottom) continue
            val dx = x - (r.left + r.right) / 2f
            val dy = y - (r.top + r.bottom) / 2f
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** 第二/三次长按：按最小横线间隔填充 */
    private fun tickFill() {
        if (pass == 4) return
        val now = playMs()
        val slot = cellMs()
        for (i in 0 until fingers.size()) {
            val f = fingers.valueAt(i)
            if (!f.active) continue
            val held = SystemClock.elapsedRealtime() - f.downRealMs
            if (held < holdMsThreshold) continue
            val curSlot = Math.round(now / slot)
            var s = f.lastFillSlot + 1
            var guard = 0
            while (s <= curSlot && guard < 64) {
                val t = (s * slot).toLong()
                notes.add(RecordedNote(passType(pass), t, t, quantizeX(f.curX), pass))
                f.lastFillSlot = s
                s++
                guard++
            }
        }
    }

    /** 手势结束：定稿 */
    /** 阶段 -> RPE 音符类型 */
    private fun passType(p: Int): Int = when (p) {
        1 -> 1      // 蓝键 Tap
        2 -> 4      // 黄键 Drag
        3 -> 3      // 红键 Flick
        else -> 2   // 第四次：蓝长条 Hold
    }

    /** 手势结束：定稿 */
    private fun finishGesture(f: FingerState, upRealMs: Long) {
        val dur = (upRealMs - f.downRealMs).coerceAtLeast(0L)
        val upMs = if (f.upPlayMs > 0) f.upPlayMs else quantMs(playMs())

        if (pass == 4) {
            // 第四次：点按 = 1 个横线间隔的短长条；长按 = 按住时长
            val s0 = quantMs(f.downPlayMs)
            val minLen = (cellMs() * 2).toLong()   // 短长条 = 2 个横线间隔
            val e0 = if (dur >= holdMsThreshold) maxOf(quantMs(upMs), s0 + minLen) else s0 + minLen
            notes.add(RecordedNote(2, s0, e0, quantizeX(f.downX), 4))
        } else if (dur >= holdMsThreshold) {
            // 第一~三次：长按结束，补齐最后一格
            val slot = cellMs()
            val curSlot = Math.round(upMs / slot)
            var sIdx = f.lastFillSlot + 1
            var guard = 0
            while (sIdx <= curSlot && guard < 64) {
                val t = (sIdx * slot).toLong()
                if (t <= upMs) {
                    notes.add(RecordedNote(passType(pass), t, t, quantizeX(f.curX), pass))
                    f.lastFillSlot = sIdx
                }
                sIdx++
                guard++
            }
        }
    }

    fun resetAll() {
        notes.clear()
        fingers.clear()
        invalidate()
    }

    fun undoLast() {
        if (notes.isNotEmpty()) notes.removeAt(notes.lastIndex)
        invalidate()
    }

    fun countOf(p: Int): Int = notes.count { it.pass == p }

    // ---------------------------------------------------------------- 手势
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val nowReal = SystemClock.elapsedRealtime()

        // Autoplay：玩家无需操作，触摸忽略（缩放由上方拖动条控制）
        if (playMode && !editMode) return true

        // 编辑模式（暂停）：判定线编辑 / 框选多选编辑
        if (editMode) {
            if (lineEditActive) return onLineEditTouch(event, nowReal)
            return onEditTouch(event, nowReal)
        }

        // ---- 普通录制模式 ----
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val f = fingers[id] ?: FingerState().also { fingers.put(id, it) }
                f.downRealMs = nowReal
                f.downPlayMs = playMs()
                f.downX = event.getX(idx); f.downY = event.getY(idx)
                f.curX = f.downX; f.curY = f.downY
                f.minX = f.downX; f.maxX = f.downX
                f.active = true
                f.mode = 0
                f.lastFillSlot = -1

                spawnHitFx(f.curX, f.curY)
                ensureSounds()
                playHitSound(pass)

                if (pass == 4) {
                    // 第四次：长条。按下不放任何东西，抬手生成（finishGesture）
                } else {
                    // 第一~三次：按下立即放一个（1=Tap 2=Drag 3=Flick），长按按格铺
                    val q = quantMs(f.downPlayMs)
                    notes.add(RecordedNote(passType(pass), q, q, quantizeX(f.downX), pass))
                    f.lastFillSlot = Math.round(q / cellMs())
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val f = fingers[id] ?: continue
                    if (!f.active) continue
                    f.curX = event.getX(i); f.curY = event.getY(i)
                    if (f.curX < f.minX) f.minX = f.curX
                    if (f.curX > f.maxX) f.maxX = f.curX
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val f = fingers[id]
                if (f != null && f.active) {
                    f.active = false
                    f.upPlayMs = playMs()
                    f.curX = event.getX(idx); f.curY = event.getY(idx)
                    finishGesture(f, nowReal)
                    invalidate()
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    fingers.clear()
                }
                return true
            }
        }
        return true
    }

    /** 编辑模式手势：点背景=放置/搓时间；点音符=单选红框；红框内再点=删除全部；拖红框=整体移动；长按=框选；双指捏合=缩放时间轴 */
    private fun onEditTouch(event: MotionEvent, nowReal: Long): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val f = fingers[id] ?: FingerState().also { fingers.put(id, it) }
                val x = event.getX(idx); val y = event.getY(idx)
                f.downRealMs = nowReal
                f.downPlayMs = playMs()
                f.downX = x; f.downY = y
                f.curX = x; f.curY = y
                f.prevY = y
                f.active = true
                f.mode = 0; f.targetNote = -1
                f.groupDrag = false; f.groupOrigin = null; f.willDelete = false; f.moved = false
                f.lineDown = false; f.kfCandidate = -1
                // 判定线长按激活检测（编辑模式）：按在【旋转后的判定线】附近 0.5s 进入编辑
                f.lineDown = distToLine(x, y, playMs()) < dp(30f)

                val nowMs = playMs()
                var hit = findNoteAt(x, y, nowMs)
                if (hit < 0 && compatMode >= 1) {
                    val fh = findFrameNoteAt(x, y, nowMs, onlySelected = compatMode == 1)
                    if (fh >= 0) hit = fh
                }
                if (hit >= 0) {
                    spawnHitFx(x, y); ensureSounds(); playHitSound(pass)
                    if (hit in selectedSet) {
                        f.willDelete = true          // 点红框内 → 松手未拖动则删除全部
                        f.mode = 2
                        f.targetNote = hit
                        f.groupOrigin = selectedSet.associateWith { notes[it] }.toMutableMap()
                    } else {
                        selectedSet.clear(); selectedSet.add(hit)   // 首次点击：只出红框
                        f.mode = 2
                        f.targetNote = hit
                        f.groupOrigin = selectedSet.associateWith { notes[it] }.toMutableMap()
                    }
                } else {
                    if (selectedSet.isNotEmpty()) {
                        selectedSet.clear()          // 点背景（有红框）→ 只取消，不放置
                        f.mode = 0
                        invalidate()
                        return true
                    }
                    f.mode = 1                        // 点背景（无选中）→ 放置/搓时间
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val f = fingers[id] ?: continue
                    if (!f.active) continue
                    val x = event.getX(i); val y = event.getY(i)
                    f.curX = x; f.curY = y

                    when (f.mode) {
                        4 -> { boxX1 = x; boxY1 = y; selectingBox = true }
                        2 -> {
                            if (Math.abs(y - f.downY) > dp(6f) || Math.abs(x - f.downX) > dp(6f)) f.moved = true
                            if (!f.moved) continue
                            if (selectedSet.size > 1 && !f.groupDrag) {
                                f.groupDrag = true
                                f.groupOrigin = selectedSet.associateWith { notes[it] }.toMutableMap()
                            }
                            if (f.groupDrag) {
                                val o = f.groupOrigin ?: continue
                                val dT: Long
                                val dX: Float
                                if (smoothMove) {
                                    dT = ((f.downY - y) / pxPerMs).toLong()
                                    dX = (x - f.downX) / width.toFloat() * 2f
                                } else {
                                    val gridPx2 = cellMs().toFloat() * pxPerMs
                                    val cellPx = width / vLines.toFloat()
                                    val dCells = Math.round(f.downY / gridPx2) - Math.round(y / gridPx2)
                                    dT = dCells * cellMs().toLong()
                                    dX = (Math.round(x / cellPx) - Math.round(f.downX / cellPx)) * (2f / vLines)
                                }
                                for ((idx0, n0) in o) {
                                    if (idx0 !in notes.indices) continue
                                    val ns = (n0.startMs + dT).coerceAtLeast(0L)
                                    val dur = (n0.endMs - n0.startMs).coerceAtLeast(0L)
                                    notes[idx0] = n0.copy(startMs = ns, endMs = ns + dur, posX = (n0.posX + dX).coerceIn(-1f, 1f))
                                }
                            } else {
                                val idx0 = f.targetNote
                                if (idx0 in notes.indices) {
                                    val n = notes[idx0]
                                    val dur = (n.endMs - n.startMs).coerceAtLeast(0L)
                                    // 屏幕坐标 → 判定线局部坐标（先平移线中心，再反向旋转）
                                    val lc0 = lineCenter(playMs())
                                    val pose0 = linePoseAt(playMs())
                                    val ang = Math.toRadians(pose0[2].toDouble())
                                    val dx = x - lc0[0]; val dy = y - lc0[1]
                                    val xl = (dx * Math.cos(ang) - dy * Math.sin(ang)).toFloat()
                                    val yl = (dx * Math.sin(ang) + dy * Math.cos(ang)).toFloat()
                                    // 局部 y=0 即判定线；x 映射回 posX
                                    val pos = if (smoothMove) rawPosX(xl + width / 2f) else quantizeX(xl + width / 2f)
                                    val t = if (smoothMove)
                                        (playMs() - (yl / pxPerMs).toLong()).coerceAtLeast(0L)
                                    else
                                        quantMs((playMs() - (yl / pxPerMs).toLong()).coerceAtLeast(0L))
                                    notes[idx0] = n.copy(startMs = t, endMs = t + dur, posX = pos)
                                }
                            }
                        }
                        1 -> {
                            if (pass != 4 && Math.abs(y - f.downY) > dp(8f)) { f.mode = 3; f.prevY = y }
                        }
                        3 -> {
                            val dy = y - f.prevY
                            f.prevY = y
                            val dt = Math.round(dy / pxPerMs).toInt()
                            if (dt != 0) addEditSeek(dt)
                        }
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val f = fingers[id]
                if (f != null && f.active) {
                    f.active = false
                    if (f.mode == 4) finishBoxSelect()
                    else if (f.mode == 2) { if (!f.moved && f.willDelete) deleteSelected() }
                    else if (f.mode == 3) flushEditSeek()
                    else if (f.mode == 1) {
                        val yTime = { yy: Float ->
                            val raw = (playMs() + ((judgeLineY - yy) / pxPerMs).toLong()).coerceAtLeast(0L)
                            if (smoothMove) raw else quantMs(raw)
                        }
                        if (editMode && pass == 4) {
                            if (Math.abs(f.curY - f.downY) >= dp(6f)) {
                                val t1 = yTime(f.downY); val t2 = yTime(f.curY)
                                val start = minOf(t1, t2)
                                val end = maxOf(t1, t2).coerceAtLeast(start + cellMs().toLong())
                                val pos = if (smoothMove) rawPosX(f.downX) else quantizeX(f.downX)
                                notes.add(RecordedNote(2, start, end, pos, 4))
                                spawnHitFx(f.curX, f.curY)
                            }
                        } else {
                            val t = yTime(f.curY)
                            val pos = if (smoothMove) rawPosX(f.curX) else quantizeX(f.curX)
                            if (pass == 4) notes.add(RecordedNote(2, t, t + (cellMs() * 2).toLong(), pos, 4))
                            else notes.add(RecordedNote(passType(pass), t, t, pos, pass))
                            spawnHitFx(f.curX, f.curY)
                        }
                    }
                    f.mode = 0; f.groupDrag = false; f.groupOrigin = null
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    fingers.clear()
                    selectingBox = false
                }
                invalidate()
                return true
            }
        }
        return true
    }

    /** 长按检测：判定线长按 0.5s → 判定线编辑；普通长按 0.5s → 框选膨胀 */
    private fun checkBoxLongPress() {
        val nowR = SystemClock.elapsedRealtime()
        for (i in 0 until fingers.size()) {
            val f = fingers.valueAt(i)
            if (!f.active || f.mode == 0 || f.mode >= 3) continue
            val moved = Math.abs(f.curY - f.downY) > dp(6f) || Math.abs(f.curX - f.downX) > dp(6f)
            if (moved || nowR - f.downRealMs < 500) continue
            if (f.lineDown) {
                lineEditActive = true
                lineEditHand = 0
                f.lineDown = false; f.mode = 0
                invalidate()
                continue
            }
            boxActive = true
            boxStartedAt = nowR
            boxX0 = f.downX; boxY0 = f.downY
            boxX1 = f.downX; boxY1 = f.downY
            selectedSet.clear()
            selectingBox = false
            f.mode = 4
        }
    }

    /** 框选结束：框内音符全部进选中集 */
    private fun finishBoxSelect() {
        val now = playMs()
        val x0 = minOf(boxX0, boxX1); val x1 = maxOf(boxX0, boxX1)
        val y0 = minOf(boxY0, boxY1); val y1 = maxOf(boxY0, boxY1)
        selectedSet.clear()
        for (i in notes.indices) {
            val n = notes[i]
            val lc = lineCenter(now)
            val pose = linePoseAt(now)
            val a = Math.toRadians(-pose[2].toDouble())
            val xl = (n.posX + 1f) / 2f * width - width / 2f
            val yl = (now - n.startMs) * pxPerMs
            val nx = lc[0] + (xl * Math.cos(a) - yl * Math.sin(a)).toFloat()
            val ny = lc[1] + (xl * Math.sin(a) + yl * Math.cos(a)).toFloat()
            if (nx >= x0 && nx <= x1 && ny >= y0 && ny <= y1) selectedSet.add(i)
        }
        boxActive = false
        selectingBox = false
    }

    /** 删除所有被框住（红框）的音符 */
    private fun deleteSelected() {
        selectedSet.sortedDescending().forEach { if (it in notes.indices) notes.removeAt(it) }
        selectedSet.clear()
    }

    // ---------------------------------------------------------------- 判定线编辑
    /** 判定线编辑：拖中心方框=移动，拖箭头=旋转，点小方块=打关键帧，点左侧圆点=跳转/长按红框/再点删除，点背景=退出 */
    private fun onLineEditTouch(event: MotionEvent, nowReal: Long): Boolean {
        val now = playMs()
        val c = lineCenter(now)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val f = fingers[id] ?: FingerState().also { fingers.put(id, it) }
                val x = event.getX(idx); val y = event.getY(idx)
                f.downRealMs = nowReal
                f.downX = x; f.downY = y
                f.curX = x; f.curY = y
                f.active = true
                f.kfCandidate = -1

                // 1) 中心方框 → 移动
                if (Math.abs(x - c[0]) < dp(36f) && Math.abs(y - c[1]) < dp(36f)) {
                    lineEditHand = 1; return true
                }
                // 2) 旋转箭头（左上/右下）
                val arrowA = Math.hypot((x - (c[0] - dp(40f))).toDouble(), (y - (c[1] - dp(40f))).toDouble())
                val arrowB = Math.hypot((x - (c[0] + dp(40f))).toDouble(), (y - (c[1] + dp(40f))).toDouble())
                if (arrowA < dp(34f) || arrowB < dp(34f)) {
                    lineEditHand = 2
                    lineEditStartRot = Math.toDegrees(Math.atan2((y - c[1]).toDouble(), (x - c[0]).toDouble())).toFloat()
                    lineEditStartAng = lineRotate
                    return true
                }
                // 3) 关键帧小方块（左下/右上）→ 打关键帧
                val d1 = Math.hypot((x - (c[0] - dp(40f))).toDouble(), (y - (c[1] + dp(40f))).toDouble())
                val d2 = Math.hypot((x - (c[0] + dp(40f))).toDouble(), (y - (c[1] - dp(40f))).toDouble())
                if (d1 < dp(26f) || d2 < dp(26f)) {
                    addKeyframe()
                    spawnHitFx(x, y)
                    invalidate()
                    return true
                }
                // 4) 左侧关键帧圆点：点击=跳转；长按=红框；红框内再点=删除
                if (x < dp(40f)) {
                    val kfs = lineKeyframes.sortedBy { it.timeMs }
                    val hitR = if (compatMode >= 1) dp(40f) else dp(26f)
                    for ((i, k) in kfs.withIndex()) {
                        val ky = c[1] - (k.timeMs - now) * pxPerMs
                        if (ky < dp(30f) || ky > height - dp(130f)) continue
                        if (Math.abs(y - ky) < hitR) {
                            if (selectedKf == i) {
                                // 红框内再点 → 删除关键帧
                                lineKeyframes.remove(k)
                                selectedKf = -1
                                invalidate()
                                return true
                            }
                            selectedKf = -1
                            f.kfCandidate = i
                            f.kfDownReal = nowReal
                            f.kfDownX = x; f.kfDownY = y
                            // 点击关键帧 → 跳转时间并显示该帧姿态
                            onSeekAbs?.invoke(k.timeMs.toInt())
                            invalidate()
                            return true
                        }
                    }
                }
                // 5) 命中判定线任意位置 → 打关键帧（任何时间点都能打，无需先有帧）；远处背景 → 退出
                if (distToLine(x, y, playMs()) < dp(30f)) {
                    addKeyframe()
                    spawnHitFx(x, y)
                    invalidate()
                    return true
                }
                // 6) 点远处背景 → 退出判定线编辑（应用全局姿态）
                lineEditActive = false
                lineEditHand = 0
                selectedKf = -1
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val f = fingers[id] ?: continue
                    if (!f.active) continue
                    val x = event.getX(i); val y = event.getY(i)
                    f.curX = x; f.curY = y
                    when (lineEditHand) {
                        1 -> {
                            lineMoveX = ((x - width / 2f) / (width / 2f)).coerceIn(-1f, 1f)
                            lineMoveY = ((y - judgeLineY) / (height / 2f)).coerceIn(-1f, 1f)
                        }
                        2 -> {
                            val ang = Math.toDegrees(Math.atan2((y - c[1]).toDouble(), (x - c[0]).toDouble())).toFloat()
                            lineRotate = lineEditStartAng - (ang - lineEditStartRot)   // 渲染 rotate(-rot)，取反后手指顺时=线顺时
                        }
                    }
                    onLinePoseChanged?.invoke()
                    // 关键帧长按位移过大则取消候选
                    if (f.kfCandidate >= 0 && (Math.abs(x - f.kfDownX) > dp(6f) || Math.abs(y - f.kfDownY) > dp(6f))) {
                        f.kfCandidate = -1
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val f = fingers[id]
                if (f != null && f.active) {
                    // 关键帧长按 0.5s → 红框框住
                    if (f.kfCandidate >= 0 && nowReal - f.kfDownReal >= 500 && selectedKf != f.kfCandidate) {
                        selectedKf = f.kfCandidate
                    }
                    f.kfCandidate = -1
                    f.active = false
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    fingers.clear()
                    lineEditHand = 0
                }
                invalidate()
                return true
            }
        }
        return true
    }

    /** 打一个关键帧（当前时间 + 当前全局姿态） */
    private fun addKeyframe() {
        val t = playMs()
        lineKeyframes.removeAll { it.timeMs == t }
        lineKeyframes.add(LineKeyframe(t, lineMoveX, lineMoveY, lineRotate))
        onLinePoseChanged?.invoke()
    }
    // ---------------------------------------------------------------- Autoplay 判定与 HUD
    /** 双押检测（Phira parse.rs 思路）：同一时刻多个未判定音符 → 用 *_mh 大贴图 + 黄色方框 */
    private fun doubleHintIdx(now: Long): Set<Int> {
        val set = mutableSetOf<Int>()
        val win = (cellMs() * 0.5).toLong()
        val unjudged = notes.indices.filter { it !in judgedIdx }
        for (a in unjudged) {
            for (b in unjudged) {
                if (a == b) continue
                if (Math.abs(notes[a].startMs - notes[b].startMs) <= win) { set.add(a); set.add(b) }
            }
        }
        return set
    }

    /** Autoplay：音符到判定线自动 PERFECT + 特效 + 音效。
     *  只在「落线后 AUTO_HIT_WINDOW_MS 内」判定；太旧音符静默跳过；回拖进度条自动重置判定。 */
    private fun tickAutoPlay(now: Long) {
        if (playingProvider?.invoke() != true) { lastJudgeNow = now; return }
        if (now + 300 < lastJudgeNow) {
            judgedIdx.clear()
            hitCount = 0
            combo = 0
            seedPassedNotes(now)
        }
        for (i in notes.indices) {
            if (i in judgedIdx) continue
            val n = notes[i]
            val judgeAt = n.startMs + (((n.posX + 1f) / 2f) * 60.0).toLong()   // 列位置错峰
            val age = now - judgeAt
            if (age < 0) continue
            if (age > AUTO_HIT_WINDOW_MS) { judgedIdx.add(i); continue }       // 已过去太久：只标记跳过
            judgedIdx.add(i)
            hitCount++
            combo++
            showJudge("PERFECT")
            // 特效打在判定线上（音符沿线位置、线本身），避免音符过线后特效偏下
            val lc = lineCenter(now)
            val pose = linePoseAt(now)
            val a = Math.toRadians(-pose[2].toDouble())
            val xl = (n.posX + 1f) / 2f * width - width / 2f
            spawnHitFx(lc[0] + (xl * Math.cos(a)).toFloat(), lc[1] + (xl * Math.sin(a)).toFloat())
            playHitSound(n.type)
            // 长条：登记持续特效（直到尾部离开判定线）；音效只此一次
            if (n.type == 2) holdFxList.add(HoldFx(i, n.endMs))
        }
        lastJudgeNow = now
    }

    /** Phira 风格 HUD：右上角 7 位分数、上方中间 Combo（≥3）、顶端 Bar 进度条 */
    private fun drawJudgeOverlay(canvas: Canvas, now: Long, w: Float, h: Float) {
        // 顶端 Phira Bar 装饰进度条
        if (trackLengthMs > 0) {
            val p = (now.toFloat() / trackLengthMs).coerceIn(0f, 1f)
            val barH = dp(5f)
            val barY = dp(10f)
            judgePaint.color = intColor(0x99FFFFFF)
            canvas.drawRect(0f, barY, w * p, barY + barH, judgePaint)
            judgePaint.color = Color.WHITE
            canvas.drawRect(w * p - dp(1f), barY - dp(1.5f), w * p + dp(1f), barY + barH + dp(1.5f), judgePaint)
            judgePaint.alpha = 255
        }

        // 分数：从 0000000 开始，按命中比例线性涨到 1000000
        val total = notes.size
        val score = if (total == 0) 0 else {
            Math.round(1000000.0 * hitCount / total).toInt().coerceIn(0, 1000000)
        }
        judgePaint.color = Color.WHITE
        judgePaint.textSize = dp(32f)
        judgePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(String.format("%07d", score), w - dp(8f), dp(40f), judgePaint)
        judgePaint.color = intColor(0xAAFFFFFF)
        judgePaint.textSize = dp(14f)
        canvas.drawText("SCORE", w - dp(8f), dp(60f), judgePaint)
        judgePaint.alpha = 255

        // 上方中间 Combo（≥3 才显示）
        if (combo >= 3) {
            judgePaint.textAlign = Paint.Align.CENTER
            judgePaint.color = Color.WHITE
            judgePaint.textSize = dp(42f)
            canvas.drawText("$combo", w / 2f, dp(88f), judgePaint)
            judgePaint.color = intColor(0xAAFFFFFF)
            judgePaint.textSize = dp(13f)
            canvas.drawText("COMBO", w / 2f, dp(122f), judgePaint)
        }
        judgePaint.alpha = 255
    }

    private fun showJudge(text: String) {
        judgeText = text
        judgeShownAt = SystemClock.elapsedRealtime()
    }

    /** 双押提示：黄色方框包裹住音符（用户指定样式） */
    private fun drawDoubleGlow(canvas: Canvas, x: Float, y: Float, cellW: Float) {
        livePaint.style = Paint.Style.STROKE
        livePaint.strokeWidth = dp(3f)
        livePaint.color = intColor(0xFFFFEB3B)   // 亮黄
        val halfW = cellW * 0.72f
        val halfH = dp(18f)
        canvas.drawRoundRect(x - halfW, y - halfH, x + halfW, y + halfH, dp(9f), dp(9f), livePaint)
        livePaint.style = Paint.Style.FILL
    }

    /** 保存谱面草稿（JSON）：音符/关键帧/判定线姿态/流速 */
    fun saveDraft(): String {
        val doc = org.json.JSONObject()
        val arr = org.json.JSONArray()
        for (n in notes) {
            arr.put(org.json.JSONObject()
                .put("t", n.type).put("s", n.startMs).put("e", n.endMs)
                .put("x", n.posX.toDouble()).put("p", n.pass))
        }
        doc.put("notes", arr)
        val kf = org.json.JSONArray()
        for (k in lineKeyframes) {
            kf.put(org.json.JSONObject().put("t", k.timeMs).put("x", k.moveX.toDouble())
                .put("y", k.moveY.toDouble()).put("r", k.rotate.toDouble()))
        }
        doc.put("kf", kf)
        doc.put("mx", lineMoveX.toDouble()); doc.put("my", lineMoveY.toDouble())
        doc.put("rot", lineRotate.toDouble()); doc.put("spd", lineSpeed.toDouble())
        return doc.toString()
    }

    /** 加载谱面草稿（退出后重进保留改动） */
    fun loadDraft(json: String) {
        try {
            val doc = org.json.JSONObject(json)
            notes.clear()
            val arr = doc.optJSONArray("notes")
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                notes.add(RecordedNote(o.getInt("t"), o.getLong("s"), o.getLong("e"),
                    o.getDouble("x").toFloat(), o.optInt("p", 1)))
            }
            lineKeyframes.clear()
            val kf = doc.optJSONArray("kf")
            if (kf != null) for (i in 0 until kf.length()) {
                val o = kf.getJSONObject(i)
                lineKeyframes.add(LineKeyframe(o.getLong("t"), o.getDouble("x").toFloat(),
                    o.getDouble("y").toFloat(), o.getDouble("r").toFloat()))
            }
            lineMoveX = doc.optDouble("mx", 0.0).toFloat()
            lineMoveY = doc.optDouble("my", 0.0).toFloat()
            lineRotate = doc.optDouble("rot", 0.0).toFloat()
            lineSpeed = doc.optDouble("spd", 1.0).toFloat()
            invalidate()
        } catch (_: Exception) {}
    }
}
