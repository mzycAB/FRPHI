package com.piezmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.min

/**
 * HSV 色环：角度 = 色相，半径 = 饱和度（内圈淡、外圈纯）。
 *
 * 色环本身只会随尺寸变化重建一次（缓存成位图），拖动选色时每帧只需画一张位图 + 一个标记点。
 */
class ColorWheelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var color: Int = Color.WHITE
        set(v) { field = v; invalidate() }

    var onColorChanged: ((Int) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringRect = RectF()

    private var hue = 0f
    private var sat = 1f
    private var value = 1f

    private var ringBmp: Bitmap? = null
    private var ringPx = 0

    fun setHsv(h: Float, s: Float, v: Float) {
        hue = h.coerceIn(0f, 360f)
        sat = s.coerceIn(0f, 1f)
        value = v.coerceIn(0f, 1f)
        color = Color.HSVToColor(floatArrayOf(hue, sat, value))
        invalidate()
    }

    /** 色环位图只在尺寸变化时重建 */
    private fun ensureRing(size: Int) {
        if (ringBmp != null && ringPx == size) return
        ringBmp?.recycle()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val outer = size / 2f - 6f
        val inner = outer * 0.56f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (outer - inner).coerceAtLeast(2f)
        val half = paint.strokeWidth / 2f
        var deg = 0
        while (deg < 360) {
            paint.color = Color.HSVToColor(floatArrayOf(deg.toFloat(), 1f, 1f))
            ringRect.set(
                size / 2f - outer + half, size / 2f - outer + half,
                size / 2f + outer - half, size / 2f + outer - half
            )
            c.drawArc(ringRect, deg.toFloat(), 2.6f, false, paint)
            deg += 2
        }
        ringBmp = bmp
        ringPx = size
    }

    override fun onDraw(c: Canvas) {
        val size = min(width, height)
        if (size <= 8) return
        ensureRing(size)
        val bmp = ringBmp ?: return
        c.drawBitmap(bmp, (width - size) / 2f, (height - size) / 2f, null)

        val cx = width / 2f
        val cy = height / 2f
        val outer = size / 2f - 6f
        val inner = outer * 0.56f

        centerPaint.style = Paint.Style.FILL
        centerPaint.color = color
        c.drawCircle(cx, cy, inner - 4f, centerPaint)
        centerPaint.style = Paint.Style.STROKE
        centerPaint.strokeWidth = 3f
        centerPaint.color = 0x55FFFFFF
        c.drawCircle(cx, cy, inner - 4f, centerPaint)

        // 选中的色相标记（落在环上）
        val a = Math.toRadians(hue.toDouble())
        val rr = (outer + inner) / 2f
        val px = cx + (rr * Math.cos(a)).toFloat()
        val py = cy + (rr * Math.sin(a)).toFloat()
        centerPaint.strokeWidth = 3f
        centerPaint.color = Color.WHITE
        c.drawCircle(px, py, 10f, centerPaint)
        centerPaint.strokeWidth = 1.5f
        centerPaint.color = 0x99000000.toInt()
        c.drawCircle(px, py, 12f, centerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val cx = width / 2f
                val cy = height / 2f
                val dx = event.x - cx
                val dy = event.y - cy
                val r = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                val outer = min(width, height) / 2f - 6f
                val inner = outer * 0.56f
                if (r < inner * 0.85f) return true      // 中心区域不当色环（避免误触）
                var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                if (deg < 0f) deg += 360f
                val s = ((r - inner) / (outer - inner)).coerceIn(0f, 1f)
                hue = deg
                sat = if (s <= 0.02f) 0f else s
                color = Color.HSVToColor(floatArrayOf(hue, sat, value))
                onColorChanged?.invoke(color)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }
}
