package com.piezmaker

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/**
 * 上下两页的"紧贴滑动"容器（用户点名的主界面 → 选曲页动画）。
 *
 * 两页都 layout 在 (0,0,w,h)，靠 translationY 摆成上下紧贴：
 *   progress = 0 → mainPage 在 0、playPage 在 -h（playPage 的底边正好贴着 mainPage 的顶边）
 *   progress = 1 → mainPage 在 +h（向下滑出屏幕）、playPage 在 0（从上方落进屏幕）
 *
 * 两页交界处画一条黑色直线，位置跟着 progress 走；**只在动画中间可见**，
 * 完全进入选曲页（progress=1）或完全在主界面（progress=0）时隐藏。
 * 因为背景色两页都是 #F2F4F8，黑线消失后看起来就是同一个界面。
 */
class VerticalSlideLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** 0 = 主界面，1 = 选曲页 */
    var progress = 0f
        private set

    private var mainPage: View? = null
    private var playPage: View? = null
    private var divider: View? = null
    private var playContent: View? = null
    private var animator: ValueAnimator? = null
    private var onSettled: ((Boolean) -> Unit)? = null

    /** true = 已经进入选曲页 */
    val atPlayPage: Boolean get() = progress >= 0.999f

    fun setOnSettled(cb: (Boolean) -> Unit) { onSettled = cb }

    override fun onFinishInflate() {
        super.onFinishInflate()
        mainPage = findViewById(R.id.mainPage)
        playPage = findViewById(R.id.playPage)
        divider = findViewById(R.id.slideDivider)
        playContent = findViewById(R.id.playContent)
        applyProgress()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyProgress()
    }

    /** 跟手拖动：手指往下滑 dy，就往选曲页走一点 */
    fun dragTo(p: Float) {
        animator?.cancel(); animator = null
        setProgress(p)
    }

    /** 松手：按位置 + 速度吸附到 0 / 1，带减速插值（动画时长随剩余距离变化） */
    fun settle(velocityPxPerSec: Float = 0f) {
        val target = when {
            velocityPxPerSec > 900f -> 1f
            velocityPxPerSec < -900f -> 0f
            else -> if (progress >= 0.5f) 1f else 0f
        }
        animateTo(target)
    }

    fun animateTo(target: Float) {
        val from = progress
        val to = target.coerceIn(0f, 1f)
        if (kotlin.math.abs(to - from) < 0.0005f) {
            setProgress(to)
            onSettled?.invoke(to >= 0.999f)
            return
        }
        animator?.cancel()
        val dist = kotlin.math.abs(to - from)
        val dur = (170 + 260 * dist).toLong().coerceIn(170L, 430L)
        animator = ValueAnimator.ofFloat(from, to).apply {
            duration = dur
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { setProgress(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onSettled?.invoke(to >= 0.999f)
                }
            })
            start()
        }
    }

    fun setProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        applyProgress()
    }

    private fun applyProgress() {
        val main = mainPage ?: return
        val play = playPage ?: return
        val line = divider ?: return
        val h = height.toFloat()
        if (h <= 0f) return
        val p = progress
        main.translationY = h * p
        play.translationY = -h + h * p
        // 黑线正好压在两页交界处：playPage 的底边 = h*p
        line.translationY = h * p - line.height
        val edgeFade = (p * 8f).coerceAtMost(1f).coerceAtMost((1f - p) * 8f)
        val showLine = p > 0.002f && p < 0.998f
        line.visibility = if (showLine) VISIBLE else GONE
        if (showLine) line.alpha = edgeFade.coerceAtLeast(0.15f)
        // 选曲页的内容比页面本身"晚一点落到位"，滑动过程更有层次
        playContent?.let {
            it.alpha = 0.25f + 0.75f * p
            it.translationY = (1f - p) * dpF(22f)
        }
    }

    private fun dpF(v: Float) = v * resources.displayMetrics.density
}
