package com.piezmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * 资源包贴图编辑画布。
 *
 * - 单指：按当前工具绘画（画笔 / 橡皮 / 油漆桶 / 吸色）
 * - 双指：缩放 + 平移
 * - 缩放到 8 倍以上显示像素框；正常缩放显示坐标系和坐标细分线
 */
class PixelCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val TOOL_BRUSH = 0
        const val TOOL_ERASER = 1
        const val TOOL_BUCKET = 2
        const val TOOL_PICKER = 3
        /** 缩放倍率达到这个值后显示像素框 */
        private const val PIXEL_GRID_ZOOM = 8f
    }

    var bitmap: Bitmap? = null
        private set

    var tool: Int = TOOL_BRUSH
        set(v) { field = v; invalidate() }

    /** 画笔直径（位图像素） */
    var brushSize: Float = 8f
        set(v) { field = v.coerceIn(1f, 128f); invalidate() }

    var brushColor: Int = Color.WHITE
        set(v) { field = v; invalidate() }

    /** 画笔透明度（0~100，100 = 不透明） */
    var alphaPercent: Int = 100
        set(v) { field = v.coerceIn(0, 100); invalidate() }

    /** 精灵图分帧：>1 时在每帧之间画直线分隔（打击特效精灵图用） */
    var splitCols: Int = 0
        set(v) { field = v.coerceIn(0, 64); invalidate() }
    var splitRows: Int = 0
        set(v) { field = v.coerceIn(0, 64); invalidate() }

    /** 吸色笔取到颜色时回调 */
    var onColorPicked: ((Int) -> Unit)? = null
    /** 一次笔画开始（外部先拍撤销快照） */
    var onStrokeBegin: (() -> Unit)? = null
    /** 一次笔画结束（外部把快照压入撤销栈） */
    var onStrokeEnd: (() -> Unit)? = null
    /**
     * 一次笔画被取消（例如第二根手指落下、开始双指缩放）。
     * 外部应把画布恢复到 [onStrokeBegin] 时的状态 —— 这样缩放不会留下误画的笔迹。
     */
    var onStrokeCancel: (() -> Unit)? = null

    private var scale = 1f
    private var fitScale = 1f
    private var tx = 0f
    private var ty = 0f

    private val rectTmp = RectF()
    private val bmpPaint = Paint(Paint.DITHER_FLAG)
    private val checkerPaint = Paint()
    private var checkerInited = false
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        textSize = 34f
        color = 0x99FFFFFF.toInt()
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var canvasCache: Canvas? = null
    private var brushPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var drawing = false
    private var lastPx = Float.NaN
    private var lastPy = Float.NaN
    private var twoFinger = false
    private var startDist = 1f
    private var startScale = 1f
    private var startTx = 0f
    private var startTy = 0f
    private var startMidX = 0f
    private var startMidY = 0f
    private var cursorX = 0f
    private var cursorY = 0f
    private var showCursor = false
    /** 油漆桶：按下先记位置，抬起且没变成双指缩放时才真正填充（避免缩放误填） */
    private var bucketPending = false
    private var bucketX = 0f
    private var bucketY = 0f

    init {
        isFocusable = true
        eraserPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        eraserPaint.style = Paint.Style.FILL
    }

    fun setBitmap(b: Bitmap?) {
        bitmap = b
        canvasCache = null
        lastPx = Float.NaN
        resetFit()
        invalidate()
    }

    /** 只换图、不动当前缩放/位移（撤销/重做用，避免每撤一步视图跳回默认缩放） */
    fun replaceBitmap(b: Bitmap?) {
        bitmap = b
        canvasCache = null
        lastPx = Float.NaN
        invalidate()
    }

    fun resetFit() {
        val b = bitmap ?: return
        val cw = contentW
        val ch = contentH
        if (b.width <= 0 || b.height <= 0 || cw <= 0 || ch <= 0) return
        fitScale = min(cw * 0.92f / b.width, ch * 0.92f / b.height).coerceAtLeast(0.05f)
        scale = fitScale
        tx = 0f
        ty = 0f
    }

    /** 画布内容区（扣掉左右工具栏的内边距） */
    private val contentW: Int get() = width - paddingLeft - paddingRight
    private val contentH: Int get() = height - paddingTop - paddingBottom
    private val centerX: Float get() = paddingLeft + contentW / 2f
    private val centerY: Float get() = paddingTop + contentH / 2f

    private val zoom: Float get() = if (fitScale > 0f) scale / fitScale else 1f

    private fun bmpCanvas(): Canvas? {
        val b = bitmap ?: return null
        val c = canvasCache
        if (c != null) return c
        val nc = Canvas(b)
        canvasCache = nc
        return nc
    }

    private fun dstRect(): RectF {
        val b = bitmap ?: run { rectTmp.set(0f, 0f, 0f, 0f); return rectTmp }
        val hw = b.width / 2f * scale
        val hh = b.height / 2f * scale
        val cx = centerX + tx
        val cy = centerY + ty
        rectTmp.set(cx - hw, cy - hh, cx + hw, cy + hh)
        return rectTmp
    }

    private fun toBmpX(vx: Float): Float {
        val b = bitmap ?: return 0f
        return (vx - centerX - tx) / scale + b.width / 2f
    }

    private fun toBmpY(vy: Float): Float {
        val b = bitmap ?: return 0f
        return (vy - centerY - ty) / scale + b.height / 2f
    }

    // ------------------------------------------------------------------ 绘制

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetFit()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF101218.toInt())
        val b = bitmap ?: return
        val r = dstRect()
        drawChecker(c, r)
        bmpPaint.isFilterBitmap = zoom < 3f
        drawBitmapVisible(c, b, r)
        if (zoom >= PIXEL_GRID_ZOOM) drawPixelGrid(c, r, b) else drawCoords(c, r, b)
        drawBorder(c, r)
        drawSplitLines(c, r)
        drawCursor(c)
    }

    /** 精灵图每帧之间的分隔线（打击特效） */
    private fun drawSplitLines(c: Canvas, r: RectF) {
        val cols = splitCols
        val rows = splitRows
        if (cols <= 1 && rows <= 1) return
        gridPaint.color = 0xCC00E5FF.toInt()
        gridPaint.strokeWidth = 2f
        for (i in 1 until cols.coerceAtLeast(1)) {
            val x = r.left + r.width() * i / cols
            c.drawLine(x, r.top, x, r.bottom, gridPaint)
        }
        for (j in 1 until rows.coerceAtLeast(1)) {
            val y = r.top + r.height() * j / rows
            c.drawLine(r.left, y, r.right, y, gridPaint)
        }
    }

    /**
     * 只把"当前可见的那一块"画出来。
     * 缩放到很大时目标矩形会远超屏幕，直接 drawBitmap 缩放到巨大矩形会分配超大纹理 → OOM 崩溃。
     */
    private fun drawBitmapVisible(c: Canvas, b: Bitmap, r: RectF) {
        if (r.width() <= 0f || r.height() <= 0f) return
        val vx0 = maxOf(r.left, 0f)
        val vy0 = maxOf(r.top, 0f)
        val vx1 = minOf(r.right, width.toFloat())
        val vy1 = minOf(r.bottom, height.toFloat())
        if (vx1 <= vx0 || vy1 <= vy0) return
        val px = r.width() / b.width
        val sx0 = ((vx0 - r.left) / px).toInt().coerceIn(0, b.width - 1)
        val sy0 = ((vy0 - r.top) / px).toInt().coerceIn(0, b.height - 1)
        val sx1 = Math.ceil(((vx1 - r.left) / px).toDouble()).toInt().coerceIn(sx0 + 1, b.width)
        val sy1 = Math.ceil(((vy1 - r.top) / px).toDouble()).toInt().coerceIn(sy0 + 1, b.height)
        c.drawBitmap(b, android.graphics.Rect(sx0, sy0, sx1, sy1), RectF(vx0, vy0, vx1, vy1), bmpPaint)
    }

    private fun drawChecker(c: Canvas, r: RectF) {
        // 用一张 28×28 的小图平铺（BitmapShader）：不再每帧画几千个格子
        if (!checkerInited) {
            val n = 28
            val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            val bc = Canvas(bmp)
            bc.drawColor(0xFF1B1E26.toInt())
            val p = Paint()
            p.color = 0xFF23272F.toInt()
            bc.drawRect(0f, 0f, 14f, 14f, p)
            bc.drawRect(14f, 14f, 28f, 28f, p)
            checkerPaint.shader = android.graphics.BitmapShader(
                bmp, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT
            )
            checkerInited = true
        }
        val x0 = maxOf(r.left, 0f)
        val y0 = maxOf(r.top, 0f)
        val x1 = minOf(r.right, width.toFloat())
        val y1 = minOf(r.bottom, height.toFloat())
        if (x1 <= x0 || y1 <= y0) return
        c.drawRect(x0, y0, x1, y1, checkerPaint)
    }

    private fun drawPixelGrid(c: Canvas, r: RectF, b: Bitmap) {
        val px = r.width() / b.width
        if (px < 2.5f) return
        gridPaint.color = 0x33FFFFFF.toInt()
        gridPaint.strokeWidth = 1f
        val step = if (px < 6f) 8 else if (px < 12f) 4 else 1
        // 只画可见区间内的线（放大后线可能有上千条，全画会明显卡顿）
        val x0 = maxOf(0, ((maxOf(r.left, 0f) - r.left) / px).toInt())
        val x1 = minOf(b.width, Math.ceil(((minOf(r.right, width.toFloat()) - r.left) / px).toDouble()).toInt())
        var x = x0 - x0 % step
        while (x <= x1) {
            val vx = r.left + x * px
            c.drawLine(vx, maxOf(r.top, 0f), vx, minOf(r.bottom, height.toFloat()), gridPaint)
            x += step
        }
        val y0 = maxOf(0, ((maxOf(r.top, 0f) - r.top) / px).toInt())
        val y1 = minOf(b.height, Math.ceil(((minOf(r.bottom, height.toFloat()) - r.top) / px).toDouble()).toInt())
        var y = y0 - y0 % step
        while (y <= y1) {
            val vy = r.top + y * px
            c.drawLine(maxOf(r.left, 0f), vy, minOf(r.right, width.toFloat()), vy, gridPaint)
            y += step
        }
    }

    private fun drawCoords(c: Canvas, r: RectF, b: Bitmap) {
        gridPaint.color = 0x40FFFFFF.toInt()
        gridPaint.strokeWidth = 1f
        for (i in 1..3) {
            val x = r.left + r.width() * i / 4f
            c.drawLine(x, r.top, x, r.bottom, gridPaint)
            val y = r.top + r.height() * i / 4f
            c.drawLine(r.left, y, r.right, y, gridPaint)
        }
        textPaint.textSize = 30f
        textPaint.color = 0xAAFFFFFF.toInt()
        c.drawText("${b.width} × ${b.height}", r.left, r.top - 14f, textPaint)
        c.drawText("0,0", r.left + 6f, r.top + 34f, textPaint)
    }

    private fun drawBorder(c: Canvas, r: RectF) {
        gridPaint.color = 0x88FFFFFF.toInt()
        gridPaint.strokeWidth = 2f
        c.drawRect(r, gridPaint)
    }

    private fun drawCursor(c: Canvas) {
        val b = bitmap ?: return
        if (!showCursor || tool == TOOL_PICKER || twoFinger) return
        val px = toBmpX(cursorX)
        val py = toBmpY(cursorY)
        if (px < 0f || py < 0f || px > b.width || py > b.height) return
        val radius = (brushSize / 2f * scale).coerceIn(3f, 400f)
        cursorPaint.color = 0xCCFFFFFF.toInt()
        cursorPaint.strokeWidth = 2f
        c.drawCircle(cursorX, cursorY, radius, cursorPaint)
        cursorPaint.color = 0x66FFFFFF.toInt()
        c.drawCircle(cursorX, cursorY, 2.5f, cursorPaint)
    }

    // ------------------------------------------------------------------ 触摸

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                showCursor = true
                cursorX = event.x
                cursorY = event.y
                if (tool == TOOL_PICKER) {
                    pickColor(event.x, event.y)
                    return true
                }
                if (tool == TOOL_BUCKET) {
                    // 不立刻填充：等抬手再填，双指缩放时直接作废
                    bucketPending = true
                    bucketX = event.x
                    bucketY = event.y
                    invalidate()
                    return true
                }
                beginStroke()
                lastPx = Float.NaN
                paintFrom(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二根手指落下 = 用户要缩放：作废油漆桶、回滚单指误画的笔迹
                bucketPending = false
                cancelStroke()
                twoFinger = true
                startDist = twoDist(event)
                startScale = scale
                startTx = tx
                startTy = ty
                startMidX = twoMidX(event)
                startMidY = twoMidY(event)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                cursorX = event.x
                cursorY = event.y
                if (twoFinger && event.pointerCount >= 2) {
                    applyZoom(event)
                    invalidate()
                    return true
                }
                if (drawing) {
                    paintFrom(event.x, event.y)
                    invalidate()
                    return true
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount >= 3) {
                    startDist = twoDist(event)
                    startScale = scale
                    startTx = tx
                    startTy = ty
                    startMidX = twoMidX(event)
                    startMidY = twoMidY(event)
                } else {
                    twoFinger = false
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (bucketPending) {
                    bucketPending = false
                    beginStroke()
                    bucketAt(bucketX, bucketY)
                    endStroke()
                    twoFinger = false
                    showCursor = false
                    invalidate()
                    return true
                }
                endStroke()
                twoFinger = false
                showCursor = false
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                bucketPending = false
                endStroke()
                twoFinger = false
                showCursor = false
                invalidate()
                return true
            }
        }
        return true
    }

    private fun twoDist(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 1f
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return Math.hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
    }

    private fun twoMidX(e: MotionEvent): Float =
        if (e.pointerCount < 2) e.x else (e.getX(0) + e.getX(1)) / 2f

    private fun twoMidY(e: MotionEvent): Float =
        if (e.pointerCount < 2) e.y else (e.getY(0) + e.getY(1)) / 2f

    private fun applyZoom(e: MotionEvent) {
        val d = twoDist(e)
        if (d <= 1f || startDist <= 1f) return
        // 上限：最多 24 倍适配比例，且单个位图像素不超过 32 屏幕像素（再大意义不大，还容易卡）
        val maxScale = minOf(fitScale * 24f, 32f)
        val newScale = (startScale * (d / startDist)).coerceIn(fitScale * 0.4f, maxScale)
        val midX = twoMidX(e)
        val midY = twoMidY(e)
        // 缩放前后保持中点在位图上的位置不变
        val bx = (startMidX - centerX - startTx) / startScale
        val by = (startMidY - centerY - startTy) / startScale
        scale = newScale
        tx = midX - centerX - bx * newScale
        ty = midY - centerY - by * newScale
    }

    // ------------------------------------------------------------------ 绘画

    private fun beginStroke() {
        if (drawing) return
        drawing = true
        onStrokeBegin?.invoke()
    }

    private fun endStroke() {
        if (!drawing) return
        drawing = false
        lastPx = Float.NaN
        onStrokeEnd?.invoke()
    }

    /** 取消当前笔画（不记撤销、不保留笔迹） */
    private fun cancelStroke() {
        if (!drawing) return
        drawing = false
        lastPx = Float.NaN
        canvasCache = null
        onStrokeCancel?.invoke()
    }

    private fun effectiveBrushColor(): Int {
        val base = brushColor
        val a = (255 * alphaPercent / 100).coerceIn(0, 255)
        return Color.argb(a, Color.red(base), Color.green(base), Color.blue(base))
    }

    private fun paintFrom(vx: Float, vy: Float) {
        val c = bmpCanvas() ?: return
        val px = toBmpX(vx)
        val py = toBmpY(vy)
        val r = brushSize / 2f
        if (tool == TOOL_ERASER) {
            eraserPaint.strokeWidth = brushSize
            eraserPaint.strokeCap = Paint.Cap.ROUND
            eraserPaint.style = Paint.Style.STROKE
            if (lastPx.isNaN()) {
                eraserPaint.style = Paint.Style.FILL
                c.drawCircle(px, py, r, eraserPaint)
                eraserPaint.style = Paint.Style.STROKE
            } else {
                c.drawLine(lastPx, lastPy, px, py, eraserPaint)
            }
        } else {
            brushPaint.color = effectiveBrushColor()
            brushPaint.strokeCap = Paint.Cap.ROUND
            brushPaint.style = Paint.Style.STROKE
            brushPaint.strokeWidth = brushSize
            if (lastPx.isNaN()) {
                brushPaint.style = Paint.Style.FILL
                c.drawCircle(px, py, r, brushPaint)
                brushPaint.style = Paint.Style.STROKE
            } else {
                c.drawLine(lastPx, lastPy, px, py, brushPaint)
            }
        }
        lastPx = px
        lastPy = py
    }

    private fun pickColor(vx: Float, vy: Float) {
        val b = bitmap ?: return
        val px = toBmpX(vx).toInt()
        val py = toBmpY(vy).toInt()
        if (px < 0 || py < 0 || px >= b.width || py >= b.height) return
        val color = b.getPixel(px, py)
        // 吸到透明像素时强制不透明，否则画笔会"画不出来"（让人以为坏了）
        val opaque = Color.argb(255, Color.red(color), Color.green(color), Color.blue(color))
        brushColor = opaque
        alphaPercent = 100
        onColorPicked?.invoke(opaque)
        invalidate()
    }

    private fun bucketAt(vx: Float, vy: Float) {
        val b = bitmap ?: return
        val px = toBmpX(vx).toInt()
        val py = toBmpY(vy).toInt()
        if (px < 0 || py < 0 || px >= b.width || py >= b.height) return
        floodFill(b, px, py, effectiveBrushColor())
        canvasCache = null
    }

    /** 扫描线油漆桶：每行只入栈一次，内存很小（以前逐像素入栈在大图上会直接吃爆内存） */
    private fun floodFill(b: Bitmap, x0: Int, y0: Int, color: Int) {
        val w = b.width
        val h = b.height
        val pixels = IntArray(w * h)
        b.getPixels(pixels, 0, w, 0, 0, w, h)
        val target = pixels[y0 * w + x0]
        if (target == color) return
        var stack = IntArray(h * 4 + 64)
        var sp = 0
        fun push(v: Int) {
            if (sp >= stack.size) stack = stack.copyOf(stack.size * 2)
            stack[sp++] = v
        }
        push(y0 * w + x0)
        while (sp > 0) {
            val idx = stack[--sp]
            if (pixels[idx] != target) continue
            val y = idx / w
            val x = idx % w
            val row = y * w
            var xl = x
            while (xl > 0 && pixels[row + xl - 1] == target) xl--
            var xr = x
            while (xr < w - 1 && pixels[row + xr + 1] == target) xr++
            for (i in xl..xr) pixels[row + i] = color
            if (y > 0) {
                val up = (y - 1) * w
                var i = xl
                while (i <= xr) {
                    if (pixels[up + i] == target) {
                        push(up + i)
                        while (i <= xr && pixels[up + i] == target) i++
                    } else i++
                }
            }
            if (y < h - 1) {
                val down = (y + 1) * w
                var i = xl
                while (i <= xr) {
                    if (pixels[down + i] == target) {
                        push(down + i)
                        while (i <= xr && pixels[down + i] == target) i++
                    } else i++
                }
            }
        }
        b.setPixels(pixels, 0, w, 0, 0, w, h)
    }
}
