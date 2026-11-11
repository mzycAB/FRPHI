package com.piezmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.max

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

        /** 笔刷形状：自由 = 原来的连续画笔，其余是拖拽出一笔图形 */
        const val SHAPE_FREE = 0
        const val SHAPE_LINE = 1
        const val SHAPE_SQUARE = 2
        const val SHAPE_RECT = 3
        const val SHAPE_CIRCLE = 4
        const val SHAPE_ELLIPSE = 5
        const val SHAPE_TRI_EQ = 6      // 正三角形
        const val SHAPE_TRI = 7         // 等腰三角形
        /** 缩放倍率达到这个值后显示像素框 */
        private const val PIXEL_GRID_ZOOM = 8f
    }

    var bitmap: Bitmap? = null
        private set

    /**
     * PS 式多图层（用户点名）：**底 → 顶**，[bitmap] 必须是其中的当前层。
     * 空列表 = 单图层（老行为，只画 [bitmap]）。
     * 画笔/橡皮/油漆桶/黏贴都只改当前层，别的层只是叠着显示。
     */
    var layerStack: List<Bitmap> = emptyList()
        private set
    var layerVisible: BooleanArray = BooleanArray(0)
        private set
    var activeLayer: Int = 0
        private set

    /** 换整套图层（[active] 那一层必须就是 [activeBmp]） */
    fun setLayerStack(stack: List<Bitmap>, visible: BooleanArray, active: Int, activeBmp: Bitmap?) {
        layerStack = stack
        layerVisible = visible
        activeLayer = active
        if (activeBmp != null) { bitmap = activeBmp; canvasCache = null }
        invalidate()
    }

    /** 把可见层拍平成一张（保存 / 复制选区用）；只有一层时直接返回它 */
    fun flattenVisible(): Bitmap? {
        val b = bitmap ?: return null
        val stack = layerStack
        if (stack.size <= 1) return b
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in stack.indices) {
            if (i < layerVisible.size && !layerVisible[i]) continue
            c.drawBitmap(stack[i], 0f, 0f, p)
        }
        return out
    }

    var tool: Int = TOOL_BRUSH
        set(v) { field = v; invalidate() }

    /** 笔刷形状：SHAPE_FREE 之外的形状都是「按下起点 → 拖到终点 → 抬手落笔」 */
    var shape: Int = SHAPE_FREE
        set(v) { field = v; invalidate() }

    private var shapeDrawing = false
    private var sx0 = 0f
    private var sy0 = 0f
    private var sx1 = 0f
    private var sy1 = 0f
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shapePathTmp = Path()

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

    /**
     * 双指捏合缩放**选区**结束时回调（缩放倍数，>1 放大）。
     * 外部负责真正重采样 + 记撤销（[scaleSelection]），画布这里只做框的实时预览。
     */
    var onPinchScaleSelection: ((Float) -> Unit)? = null

    /** 双指缩放选区：起点指距、当前倍数、原始框 */
    private var selPinch = false
    private var selPinchStartDist = 1f
    private var selPinchFactor = 1f
    private var selBaseRect: Rect? = null
    /** 这次按下之前已经有框：抬手=关框（不落笔），拖动=关框后正常画 */
    private var tapCloseArmed = false

    private var scale = 1f
    private var fitScale = 1f
    private var tx = 0f
    private var ty = 0f

    private val rectTmp = RectF()
    private val bmpPaint = Paint(Paint.DITHER_FLAG)
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

    // ---------------------------------------------------------------- 框选（长按 0.5s）
    /** 长按 0.5 秒 → 出方框 → 拖动框住一块区域（之后可复制/黏贴/缩放） */
    var onSelectionChanged: ((Boolean) -> Unit)? = null
    /** 选区（位图坐标）；null = 没框 */
    private var selRect: Rect? = null
    private var selDragging = false
    private var selAnchorX = 0f
    private var selAnchorY = 0f
    private var lpArmed = false            // 这次按下允许长按出框
    private var lpFired = false            // 已经长按出框（接下来的拖动是框选，不是画）
    private var lpDownX = 0f
    private var lpDownY = 0f
    private val lpRunnable = Runnable {      // 500ms 到点：撤销刚点出的那一个点，开始框选
        if (!lpArmed) return@Runnable
        lpFired = true
        selDragging = true
        cancelStroke()                    // 长按不是落笔：把 DOWN 时点出来的那个像素回滚
        selAnchorX = toBmpX(lpDownX)
        selAnchorY = toBmpY(lpDownY)
        selRect = Rect(selAnchorX.toInt(), selAnchorY.toInt(), selAnchorX.toInt(), selAnchorY.toInt())
        onSelectionChanged?.invoke(true)
        invalidate()
    }

    /** 有没有框住东西 */
    val hasSelection: Boolean get() = selRect?.let { !it.isEmpty } == true

    fun clearSelection() {
        selRect = null
        selDragging = false
        lpFired = false
        tapCloseArmed = false
        selPinch = false
        selBaseRect = null
        onSelectionChanged?.invoke(false)
        invalidate()
    }

    fun selectionRect(): Rect? = selRect?.let { Rect(it) }

    /** 复制选区 → 返回一张新位图（没选区返回 null）。多图层时取「看到的」那一张 */
    fun copySelection(): Bitmap? {
        val b = bitmap ?: return null
        val r = selRect?.let { Rect(it) } ?: return null
        val src = flattenVisible() ?: b
        r.intersect(0, 0, src.width, src.height)
        if (r.isEmpty) return null
        return Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
    }

    /**
     * 把剪贴板贴到选区左上角（没选区就贴到画面中心），[factor] 缩放倍数。
     * 贴完选区跟着变成贴上去的这块，方便继续放大/缩小。
     */
    fun pasteBitmap(src: Bitmap, factor: Float = 1f): Boolean {
        val b = bitmap ?: return false
        val f = factor.coerceIn(0.1f, 8f)
        val w = (src.width * f).toInt().coerceIn(1, b.width)
        val h = (src.height * f).toInt().coerceIn(1, b.height)
        val s = if (w == src.width && h == src.height) src
        else Bitmap.createScaledBitmap(src, w, h, false)
        val x = selRect?.left ?: ((b.width - w) / 2)
        val y = selRect?.top ?: ((b.height - h) / 2)
        val c = bmpCanvas() ?: return false
        beginStroke()
        c.drawBitmap(s, x.toFloat(), y.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG))
        endStroke()
        selRect = Rect(x, y, x + w, y + h)
        onSelectionChanged?.invoke(true)
        invalidate()
        return true
    }

    /**
     * 放大 / 缩小**选中的那块**（以选区中心为基准，原地缩放）。
     * [factor] > 1 放大，< 1 缩小。
     */
    fun scaleSelection(factor: Float): Boolean {
        val b = bitmap ?: return false
        val r = selRect?.let { Rect(it) } ?: return false
        r.intersect(0, 0, b.width, b.height)
        if (r.isEmpty || r.width() < 1 || r.height() < 1) return false
        val part = Bitmap.createBitmap(b, r.left, r.top, r.width(), r.height())
        val nw = (r.width() * factor).toInt().coerceAtLeast(1)
        val nh = (r.height() * factor).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(part, nw, nh, false)
        val cx = r.exactCenterX()
        val cy = r.exactCenterY()
        val left = (cx - nw / 2f).toInt()
        val top = (cy - nh / 2f).toInt()
        val c = bmpCanvas() ?: return false
        beginStroke()
        // 先把原区域擦成透明，再把缩放后的贴回中心（原地缩放）
        val clear = Paint()
        clear.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        c.drawRect(r, clear)
        c.drawBitmap(scaled, left.toFloat(), top.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG))
        endStroke()
        selRect = Rect(left, top, left + nw, top + nh)
        onSelectionChanged?.invoke(true)
        invalidate()
        return true
    }

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
        // ★ 不再画背景棋盘（用户点名：棋盘马赛克和像素网格混在一起很乱），只留纯色底
        bmpPaint.isFilterBitmap = zoom < 3f
        // PS 式图层：底 → 顶逐层叠着画（隐藏层跳过）；单图层时就是老行为
        val stack = layerStack
        if (stack.isEmpty()) {
            drawBitmapVisible(c, b, r)
        } else {
            for (i in stack.indices) {
                if (i < layerVisible.size && !layerVisible[i]) continue
                drawBitmapVisible(c, stack[i], r)
            }
        }
        if (zoom >= PIXEL_GRID_ZOOM) drawPixelGrid(c, r, b) else drawCoords(c, r, b)
        drawBorder(c, r)
        drawSplitLines(c, r)
        drawShapePreview(c)
        drawSelection(c, r, b)
        drawCursor(c)
    }

    /** 框选框（长按 0.5s 之后拖出来的那个方块） */
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private fun drawSelection(c: Canvas, r: RectF, b: Bitmap) {
        val s = selRect ?: return
        if (b.width <= 0 || b.height <= 0) return
        val kx = r.width() / b.width
        val ky = r.height() / b.height
        val l = r.left + s.left * kx
        val t = r.top + s.top * ky
        val rr = r.left + s.right * kx
        val bb = r.top + s.bottom * ky
        selPaint.color = 0xFF00E5FF.toInt()
        c.drawRect(l, t, rr, bb, selPaint)
        // 四角小方块，提示"这块可以复制/黏贴/缩放"
        selPaint.style = Paint.Style.FILL
        val d = 4f
        for ((x, y) in listOf(l to t, rr to t, l to bb, rr to bb)) {
            c.drawRect(x - d, y - d, x + d, y + d, selPaint)
        }
        selPaint.style = Paint.Style.STROKE
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
        val radius = (brushSize / 2f * scale).coerceIn(1f, 400f)
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
                // 长按 0.5s 出方框（只在自由画笔/橡皮下；图形笔刷的拖动是画图）
                lpArmed = tool != TOOL_PICKER && tool != TOOL_BUCKET && shape == SHAPE_FREE
                lpFired = false
                lpDownX = event.x
                lpDownY = event.y
                removeCallbacks(lpRunnable)
                if (lpArmed) postDelayed(lpRunnable, 500L)
                // ★ 已经有框选时先不落笔：抬手 = 关掉框（不画点、不记撤销），
                //   拖动 = 关框后照常画，双指 = 缩放这个框（用户点名）
                tapCloseArmed = selRect != null
                if (tapCloseArmed) return true
                startPaintAt(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二根手指落下
                removeCallbacks(lpRunnable)
                lpArmed = false
                lpFired = false
                tapCloseArmed = false
                selDragging = false
                bucketPending = false
                if (selRect != null) {
                    // 有框选：双指 = 缩放框（不是缩放画布）
                    cancelStroke()
                    selPinch = true
                    selPinchStartDist = twoDist(event)
                    selPinchFactor = 1f
                    selBaseRect = Rect(selRect!!)
                    twoFinger = true
                    invalidate()
                    return true
                }
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
                if (tapCloseArmed) {
                    // 有框、还没画：动了 → 先关框，再从按下点补一笔（跟手，不留缺口）
                    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop * 1.5f
                    if (Math.hypot((event.x - lpDownX).toDouble(), (event.y - lpDownY).toDouble()) > slop) {
                        tapCloseArmed = false
                        selRect = null
                        selDragging = false
                        onSelectionChanged?.invoke(false)
                        startPaintAt(lpDownX, lpDownY)
                    } else {
                        return true
                    }
                }
                // 长按还没到点就动了 → 是正常绘画，撤掉长按回调
                if (lpArmed && !lpFired) {
                    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop * 1.5f
                    if (Math.hypot((event.x - lpDownX).toDouble(), (event.y - lpDownY).toDouble()) > slop) {
                        removeCallbacks(lpRunnable)
                        lpArmed = false
                    }
                }
                if (lpFired && selDragging) {
                    // 拖框：锚点 → 当前点（位图坐标）
                    val x0 = toBmpX(event.x).toInt()
                    val y0 = toBmpY(event.y).toInt()
                    selRect = Rect(
                        Math.min(selAnchorX.toInt(), x0), Math.min(selAnchorY.toInt(), y0),
                        Math.max(selAnchorX.toInt(), x0), Math.max(selAnchorY.toInt(), y0)
                    )
                    invalidate()
                    return true
                }
                if (selPinch && event.pointerCount >= 2) {
                    // 框跟着手指实时放大/缩小（内容等抬手再重采样）
                    val d = twoDist(event)
                    val f = if (selPinchStartDist > 1f) (d / selPinchStartDist).coerceIn(0.1f, 8f) else 1f
                    selPinchFactor = f
                    val base = selBaseRect
                    if (base != null) {
                        val nw = (base.width() * f).toInt().coerceAtLeast(1)
                        val nh = (base.height() * f).toInt().coerceAtLeast(1)
                        val l = (base.centerX() - nw / 2f).toInt()
                        val t = (base.centerY() - nh / 2f).toInt()
                        selRect = Rect(l, t, l + nw, t + nh)
                    }
                    invalidate()
                    return true
                }
                if (twoFinger && event.pointerCount >= 2) {
                    applyZoom(event)
                    invalidate()
                    return true
                }
                if (shapeDrawing) {
                    sx1 = toBmpX(event.x)
                    sy1 = toBmpY(event.y)
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
                if (selPinch) {
                    // 抬一根手指 = 缩放结束：框复原 → 交给外部按倍数重采样（带撤销）
                    selPinch = false
                    twoFinger = false
                    selRect = selBaseRect?.let { Rect(it) }
                    val f = selPinchFactor
                    selBaseRect = null
                    selPinchFactor = 1f
                    if (f > 0f && Math.abs(f - 1f) > 0.02f) onPinchScaleSelection?.invoke(f)
                    invalidate()
                    return true
                }
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
                removeCallbacks(lpRunnable)
                lpArmed = false
                if (tapCloseArmed) {
                    // 点一下屏幕 = 关掉框：不落笔、不记撤销（用户点名）
                    tapCloseArmed = false
                    selRect = null
                    selDragging = false
                    onSelectionChanged?.invoke(false)
                    showCursor = false
                    invalidate()
                    return true
                }
                if (lpFired) {
                    // 框选结束：框太小（等于没框）就当取消
                    lpFired = false
                    selDragging = false
                    val r = selRect
                    if (r == null || r.width() < 1 || r.height() < 1) {
                        selRect = null
                        onSelectionChanged?.invoke(false)
                    } else {
                        onSelectionChanged?.invoke(true)
                    }
                    showCursor = false
                    invalidate()
                    return true
                }
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
                if (shapeDrawing) {
                    shapeDrawing = false
                    if (Math.abs(sx1 - sx0) < 0.5f && Math.abs(sy1 - sy0) < 0.5f) {
                        // 只是点了一下、没有拖出图形：不留笔迹、不记撤销
                        cancelStroke()
                    } else {
                        commitShape()
                        endStroke()
                    }
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
                removeCallbacks(lpRunnable)
                lpArmed = false
                lpFired = false
                tapCloseArmed = false
                selPinch = false
                selBaseRect = null
                selPinchFactor = 1f
                selDragging = false
                bucketPending = false
                shapeDrawing = false
                endStroke()
                twoFinger = false
                showCursor = false
                invalidate()
                return true
            }
        }
        return true
    }

    /** 按下（或关框后拖动）从这一点开始真正落笔：吸色 / 油漆桶 / 图形笔刷 / 自由画笔 */
    private fun startPaintAt(vx: Float, vy: Float) {
        if (tool == TOOL_PICKER) {
            pickColor(vx, vy)
            return
        }
        if (tool == TOOL_BUCKET) {
            // 不立刻填充：等抬手再填，双指缩放时直接作废
            bucketPending = true
            bucketX = vx
            bucketY = vy
            invalidate()
            return
        }
        if (shape != SHAPE_FREE) {
            // 图形笔刷：先不起笔，只记录起点；拖到终点、抬手时才真正落笔
            beginStroke()
            lastPx = Float.NaN
            sx0 = toBmpX(vx)
            sy0 = toBmpY(vy)
            sx1 = sx0
            sy1 = sy0
            shapeDrawing = true
            invalidate()
            return
        }
        beginStroke()
        lastPx = Float.NaN
        paintFrom(vx, vy)
        invalidate()
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
        shapeDrawing = false
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
        // 吸附到像素中心：粗细 = N 时正好覆盖 N 个像素，并与像素网格线的位置对齐
        val px = Math.floor(toBmpX(vx).toDouble()).toFloat() + 0.5f
        val py = Math.floor(toBmpY(vy).toDouble()).toFloat() + 0.5f
        val r = brushSize / 2f
        if (brushSize <= 1.01f) {
            // 1 像素：用整数边界矩形精确定位，保证恰好一个格子大小
            val paint = if (tool == TOOL_ERASER) eraserPaint else brushPaint
            paint.style = Paint.Style.FILL
            if (tool != TOOL_ERASER) paint.color = effectiveBrushColor()
            if (lastPx.isNaN()) {
                c.drawRect(px - 0.5f, py - 0.5f, px + 0.5f, py + 0.5f, paint)
            } else {
                linePixels(c, lastPx, lastPy, px, py, paint)
            }
        } else if (tool == TOOL_ERASER) {
            eraserPaint.isAntiAlias = true
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
            brushPaint.isAntiAlias = true
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

    /** 像素级连线（Bresenham）：每个格子画一个 1×1 整数矩形，不留缝也不过界 */
    private fun linePixels(c: Canvas, x0f: Float, y0f: Float, x1f: Float, y1f: Float, p: Paint) {
        var x0 = Math.floor(x0f.toDouble()).toInt()
        var y0 = Math.floor(y0f.toDouble()).toInt()
        val x1 = Math.floor(x1f.toDouble()).toInt()
        val y1 = Math.floor(y1f.toDouble()).toInt()
        val dx = Math.abs(x1 - x0)
        val dy = -Math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            c.drawRect(x0.toFloat(), y0.toFloat(), x0 + 1f, y0 + 1f, p)
            if (x0 == x1 && y0 == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x0 += sx }
            if (e2 <= dx) { err += dx; y0 += sy }
        }
    }

    // ------------------------------------------------------------------ 图形笔刷

    private fun toViewX(bx: Float): Float {
        val b = bitmap ?: return 0f
        return centerX + tx + (bx - b.width / 2f) * scale
    }

    private fun toViewY(by: Float): Float {
        val b = bitmap ?: return 0f
        return centerY + ty + (by - b.height / 2f) * scale
    }

    /** 按当前形状和起止点构建路径（复用同一个 Path，拖拽时不再每帧分配） */
    private fun shapePath(x0: Float, y0: Float, x1: Float, y1: Float): Path {
        val p = shapePathTmp
        p.reset()
        when (shape) {
            SHAPE_LINE -> {
                p.moveTo(x0, y0)
                p.lineTo(x1, y1)
            }
            SHAPE_SQUARE -> {
                val d = min(Math.abs(x1 - x0), Math.abs(y1 - y0))
                val x2 = x0 + if (x1 >= x0) d else -d
                val y2 = y0 + if (y1 >= y0) d else -d
                p.addRect(min(x0, x2), min(y0, y2), max(x0, x2), max(y0, y2), Path.Direction.CW)
            }
            SHAPE_RECT -> p.addRect(
                min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1), Path.Direction.CW
            )
            SHAPE_CIRCLE -> {
                val r = Math.hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
                p.addCircle(x0, y0, r, Path.Direction.CW)
            }
            SHAPE_ELLIPSE -> p.addOval(
                min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1), Path.Direction.CW
            )
            SHAPE_TRI_EQ -> {
                // 正三角形：底边宽度 = 拖动横向距离，高 = 0.866 × 底边；顶点朝起点那一侧
                val left = min(x0, x1)
                val right = max(x0, x1)
                val top = min(y0, y1)
                val bottom = max(y0, y1)
                val w = right - left
                val hgt = w * 0.866f
                val apexY = if (y0 <= y1) top else bottom
                val baseY = if (y0 <= y1) top + hgt else bottom - hgt
                p.moveTo(left, baseY)
                p.lineTo(right, baseY)
                p.lineTo((left + right) / 2f, apexY)
                p.close()
            }
            SHAPE_TRI -> {
                // 等腰三角形：底边 = 拖出来的矩形下边，顶点 = 上边中点
                val left = min(x0, x1)
                val right = max(x0, x1)
                val top = min(y0, y1)
                val bottom = max(y0, y1)
                val apexY = if (y1 >= y0) top else bottom
                val baseY = if (y1 >= y0) bottom else top
                p.moveTo(left, baseY)
                p.lineTo(right, baseY)
                p.lineTo((left + right) / 2f, apexY)
                p.close()
            }
        }
        return p
    }

    /** 拖拽过程中的半透明预览（不写进贴图） */
    private fun drawShapePreview(c: Canvas) {
        if (!shapeDrawing) return
        previewPaint.color =
            if (tool == TOOL_ERASER) 0xAAFF5252.toInt() else 0xCCFFFFFF.toInt()
        previewPaint.strokeWidth = (brushSize * scale).coerceIn(1f, 260f)
        c.drawPath(
            shapePath(toViewX(sx0), toViewY(sy0), toViewX(sx1), toViewY(sy1)),
            previewPaint
        )
    }

    /** 抬手时把图形真正画进贴图 */
    private fun commitShape() {
        val c = bmpCanvas() ?: return
        val paint = if (tool == TOOL_ERASER) eraserPaint else brushPaint
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = brushSize
        paint.strokeCap = Paint.Cap.ROUND
        // 1 像素的图形线不能开抗锯齿，否则会糊成 2 个格子
        paint.isAntiAlias = brushSize > 1.05f
        if (tool != TOOL_ERASER) paint.color = effectiveBrushColor()
        // 起止点吸附到像素中心，和像素网格对齐
        val ax = Math.floor(sx0.toDouble()).toFloat() + 0.5f
        val ay = Math.floor(sy0.toDouble()).toFloat() + 0.5f
        val bx = Math.floor(sx1.toDouble()).toFloat() + 0.5f
        val by = Math.floor(sy1.toDouble()).toFloat() + 0.5f
        c.drawPath(shapePath(ax, ay, bx, by), paint)
        paint.isAntiAlias = true
    }

    private fun pickColor(vx: Float, vy: Float) {
        val b = bitmap ?: return
        val px = toBmpX(vx).toInt()
        val py = toBmpY(vy).toInt()
        if (px < 0 || py < 0 || px >= b.width || py >= b.height) return
        // 多图层：吸「看到的」颜色 —— 从最上层往下找第一个不透明的像素
        var color = b.getPixel(px, py)
        val stack = layerStack
        if (stack.size > 1) {
            color = 0
            for (i in stack.indices.reversed()) {
                if (i < layerVisible.size && !layerVisible[i]) continue
                val c = stack[i].getPixel(px, py)
                if (Color.alpha(c) != 0) { color = c; break }
            }
            if (Color.alpha(color) == 0) return
        }
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
