package com.piezmaker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * 色卡（正方形平滑选色面板）：横轴 = 饱和度，纵轴 = 明度，色相由外层色环决定。
 *
 * 用三层渐变（纯色相 → 横向白→透明 → 纵向透明→黑）直接绘制，
 * 不重建位图，拖动色环时可以每帧重绘而不卡。
 */
class ColorSquareView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 色相 0..360 */
    var hue: Float = 0f
        set(v) { field = v; invalidate() }

    var sat: Float = 1f
        set(v) { field = v; invalidate() }

    var value: Float = 1f
        set(v) { field = v; invalidate() }

    var onColorChanged: ((Int) -> Unit)? = null

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val satPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val valPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 渐变只在尺寸/色相变化时重建，拖动选色时不再每帧分配两个 LinearGradient
    private var valShaderWidth = -1f
    private var valShaderHeight = -1f
    private var satShaderWidth = -1f
    private var satShaderHeight = -1f
    private var satShaderHue = Float.NaN

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        basePaint.shader = null
        basePaint.color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        c.drawRect(0f, 0f, w, h, basePaint)

        if (w != satShaderWidth || h != satShaderHeight || hue != satShaderHue) {
            satPaint.shader = LinearGradient(
                0f, 0f, w, 0f, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP
            )
            satShaderWidth = w
            satShaderHeight = h
            satShaderHue = hue
        }
        c.drawRect(0f, 0f, w, h, satPaint)

        if (w != valShaderWidth || h != valShaderHeight) {
            valPaint.shader = LinearGradient(
                0f, 0f, 0f, h, 0x00000000, Color.BLACK, Shader.TileMode.CLAMP
            )
            valShaderWidth = w
            valShaderHeight = h
        }
        c.drawRect(0f, 0f, w, h, valPaint)

        // 选中点
        val px = sat.coerceIn(0f, 1f) * w
        val py = (1f - value.coerceIn(0f, 1f)) * h
        val r = min(w, h) * 0.04f + 6f
        markPaint.style = Paint.Style.STROKE
        markPaint.strokeWidth = 3f
        markPaint.color = Color.WHITE
        c.drawCircle(px, py, r, markPaint)
        markPaint.strokeWidth = 1.5f
        markPaint.color = 0x99000000.toInt()
        c.drawCircle(px, py, r + 1.5f, markPaint)
        markPaint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                sat = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                value = (1f - event.y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                onColorChanged?.invoke(Color.HSVToColor(floatArrayOf(hue, sat, value)))
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }
}
