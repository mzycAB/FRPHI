package com.piezmaker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.widget.ScrollView
import kotlin.math.abs

/**
 * 快捷操作：白色虚线焦点框（**直角、无圆角**）。
 *
 * 做法是一层全屏透明的 View 盖在 android.R.id.content 最上层，按目标 View 的
 * 屏幕坐标画一个虚线矩形。默认不可点击/不可聚焦，触摸事件会原样传给下层控件，
 * 所以不影响菜单、抽屉、列表原来的点击和滑动。
 *
 * 每帧重绘一次（仅 visible 期间），这样菜单滑入动画、ScrollView 滚动时框都跟得住。
 */
class KeyboardFocusOverlay(ctx: Context) : View(ctx) {

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(9f, 6f), 0f)
    }
    private var target: View? = null
    /** 手绘面板（判定线 / 剪贴板）没有独立 View，直接给屏幕坐标矩形 */
    private var targetRect: RectF? = null
    private val tl = IntArray(2)
    private val ol = IntArray(2)

    init {
        visibility = GONE
        isClickable = false
        isFocusable = false
    }

    /** 焦点落到 [v]；传 null = 收起框 */
    fun focus(v: View?) {
        target = v
        targetRect = null
        visibility = if (v == null) GONE else VISIBLE
        if (v != null) invalidate()
    }

    /** 焦点落到手绘面板的某一行（[r] = 屏幕坐标，直角虚线框） */
    fun focusRect(r: RectF?) {
        target = null
        targetRect = r
        visibility = if (r == null) GONE else VISIBLE
        if (r != null) invalidate()
    }

    fun clear() { target = null; targetRect = null; visibility = GONE }

    val isShowing: Boolean get() = visibility == VISIBLE && (target != null || targetRect != null)
    val focusedView: View? get() = target
    val focusedRect: RectF? get() = targetRect

    override fun onDraw(canvas: Canvas) {
        if (!isShowing) return
        val r = targetRect
        if (r != null) {
            getLocationOnScreen(ol)
            canvas.drawRect(r.left - ol[0] + 1f, r.top - ol[1] + 1f,
                r.right - ol[0] - 1f, r.bottom - ol[1] - 1f, boxPaint)
        } else {
            val t = target ?: return
            if (!t.isAttachedToWindow || t.visibility != View.VISIBLE) return
            val tw = if (t.width > 0) t.width else t.measuredWidth
            val th = if (t.height > 0) t.height else t.measuredHeight
            if (tw <= 0 || th <= 0) return
            t.getLocationOnScreen(tl)
            getLocationOnScreen(ol)
            val l = (tl[0] - ol[0]).toFloat()
            val top = (tl[1] - ol[1]).toFloat()
            canvas.drawRect(l + 1f, top + 1f, l + tw - 1f, top + th - 1f, boxPaint)
        }
        // 跟随滑入动画 / 滚动；框收起后自然停止
        if (isShowing) postInvalidateOnAnimation()
    }
}

/** 方向键焦点导航的通用几何计算（网格不规则也能用） */
object FocusNav {
    /** 可聚焦项：只取有尺寸的 ViewGroup（分组标题是 TextView，自动跳过） */
    fun itemsOf(group: android.view.ViewGroup): List<View> =
        (0 until group.childCount).mapNotNull { group.getChildAt(it) }
            .filter { it is android.view.ViewGroup && it.visibility == View.VISIBLE }

    /**
     * 按方向找离 [cur] 最近的一项；找不到就返回 [cur] 本身（到边界不跳走）。
     * 上下方向优先纵向距离、横向偏移加权惩罚，左右方向反之。
     */
    fun nearest(items: List<View>, cur: View?, dir: Int): View? {
        if (items.isEmpty()) return null
        if (cur == null) return items.firstOrNull()
        val a = IntArray(2); cur.getLocationOnScreen(a)
        val cw = if (cur.width > 0) cur.width else cur.measuredWidth
        val ch = if (cur.height > 0) cur.height else cur.measuredHeight
        val cx = a[0] + cw / 2f
        val cy = a[1] + ch / 2f
        val b = IntArray(2)
        var best: View? = null
        var bestScore = Float.MAX_VALUE
        for (v in items) {
            if (v === cur || v.visibility != View.VISIBLE) continue
            val vw = if (v.width > 0) v.width else v.measuredWidth
            val vh = if (v.height > 0) v.height else v.measuredHeight
            if (vw <= 0 || vh <= 0) continue
            v.getLocationOnScreen(b)
            val dx = b[0] + vw / 2f - cx
            val dy = b[1] + vh / 2f - cy
            val ok = when (dir) {
                KeyEvent.KEYCODE_DPAD_LEFT -> dx < -4f
                KeyEvent.KEYCODE_DPAD_RIGHT -> dx > 4f
                KeyEvent.KEYCODE_DPAD_UP -> dy < -4f
                KeyEvent.KEYCODE_DPAD_DOWN -> dy > 4f
                else -> false
            }
            if (!ok) continue
            val score = if (dir == KeyEvent.KEYCODE_DPAD_LEFT || dir == KeyEvent.KEYCODE_DPAD_RIGHT)
                abs(dx) + abs(dy) * 3f
            else
                abs(dy) + abs(dx) * 3f
            if (score < bestScore) { bestScore = score; best = v }
        }
        return best ?: cur
    }

    /**
     * 焦点项贴到 ScrollView 上/下边缘时把内容滚一格（用户：框接触边缘就向下滚，向上同理）。
     */
    fun ensureVisible(target: View, sv: ScrollView?, pad: Int = 10) {
        sv ?: return
        val a = IntArray(2); target.getLocationOnScreen(a)
        val b = IntArray(2); sv.getLocationOnScreen(b)
        val top = a[1] - b[1] + sv.scrollY
        val bottom = top + target.height
        val viewTop = sv.scrollY + pad
        val viewBottom = sv.scrollY + sv.height - pad
        val need = when {
            bottom > viewBottom -> bottom - sv.height + pad
            top < viewTop -> top - pad
            else -> return
        }
        val max = ((sv.getChildAt(0)?.height ?: sv.height) - sv.height).coerceAtLeast(0)
        sv.scrollTo(0, need.coerceIn(0, max))
    }

}

/** 键盘动作种类（"<" / ">" / "/" / Enter 共用） */
object KbAct {
    const val CLICK = 0
    const val LONG = 1
    const val SWIPE_LEFT = 2     // 左滑 1 档（判定线 = 第 1 个圆圈）
    const val SWIPE_LEFT2 = 3    // 左滑 2 档（"<"+"/" = 第 2 个圆圈）
    const val SWIPE_RIGHT = 4
    const val SWIPE_RIGHT2 = 5
}

/**
 * 把键盘动作翻译成**局部坐标的模拟触摸**，直接喂给 [v] 原有的手势逻辑。
 * 判定线圆圈、抽屉左滑删除、长按试听等几十个分支一律复用，键盘侧不必重写。
 * 长按用 postDelayed，让 View 自己的 500ms 判定先跑。
 */
object KbGesture {
    /** 连续按左右键时，同一个 View 上的旧滑动动画要先取消 */
    private val anims = java.util.WeakHashMap<View, android.animation.ValueAnimator>()

    fun send(v: View, act: Int) {
        val w = (if (v.width > 0) v.width else v.measuredWidth)
        val h = (if (v.height > 0) v.height else v.measuredHeight)
        if (w <= 0 || h <= 0) return
        val cx = w / 2f
        val cy = h / 2f
        val now = SystemClock.uptimeMillis()
        // 键盘 "/" 的即时长按：把按下时刻往前拨 600ms，让控件基于
        // (eventTime - downTime) 的长按判定立刻成立，不用真的等 0.5 秒。
        val base = if (act == KbAct.LONG) now - 600L else now
        fun fire(action: Int, x: Float, y: Float, t: Long) {
            val me = MotionEvent.obtain(base, t, action, x, y, 0)
            v.dispatchTouchEvent(me)
            try { me.recycle() } catch (_: Exception) {}
        }
        // 长按时 DOWN 自己的 eventTime 也要往前"按住"了 600ms：
        // OnTouchListener 是在 ACTION_DOWN 里判 (eventTime - downTime) 的，
        // 只把 downTime 提前、eventTime 仍等于 downTime 的话差值还是 0。
        val downAt = if (act == KbAct.LONG) now - 1 else now
        fire(MotionEvent.ACTION_DOWN, cx, cy, downAt)
        when (act) {
            KbAct.LONG -> {
                // 先走系统 OnLongClickListener（+/AI/日志 这类），立刻触发、不等 500ms；
                // 只有「既没有长按监听、也没有点击监听」的项（抽屉项目用 OnTouchListener）
                // 才补发抬手，让它按"已长按"分支收尾，不会退化成单击。
                val longHandled = v.performLongClick()
                if (!longHandled && !v.hasOnClickListeners()) {
                    fire(MotionEvent.ACTION_UP, cx, cy, now)
                } else {
                    // 不再补抬手（否则长按会变成单击），用 CANCEL 清掉 pressed 状态
                    fire(MotionEvent.ACTION_CANCEL, cx, cy, now)
                }
            }
            KbAct.SWIPE_LEFT, KbAct.SWIPE_LEFT2, KbAct.SWIPE_RIGHT, KbAct.SWIPE_RIGHT2 -> {
                val right = act == KbAct.SWIPE_RIGHT || act == KbAct.SWIPE_RIGHT2
                val second = act == KbAct.SWIPE_LEFT2 || act == KbAct.SWIPE_RIGHT2
                val tx = cx + (if (right) 1f else -1f) * w * (if (second) 0.95f else 0.8f)
                val mx = cx + (tx - cx) * 0.5f
                fire(MotionEvent.ACTION_MOVE, mx, cy, now + 16)
                fire(MotionEvent.ACTION_MOVE, tx, cy, now + 32)
                // 操作已经在上面这一下触发了（不等动画），动画只是并行的视觉回放
                fire(MotionEvent.ACTION_UP, tx, cy, now + 48)
                playSwipeAnim(v, tx - cx)
            }
            else -> fire(MotionEvent.ACTION_UP, cx, cy, now + 16)
        }
    }

    /**
     * 左右滑的可见动画：**固定 0.2 秒**。操作由模拟触摸立即完成并触发，
     * 动画只是同一时刻开始的视觉回放，不参与、也不延后任何业务逻辑。
     */
    private fun playSwipeAnim(v: View, dist: Float) {
        anims[v]?.cancel()
        val a = android.animation.ValueAnimator.ofFloat(0f, dist, 0f)
        a.duration = 200L
        a.addUpdateListener {
            v.translationX = it.animatedValue as Float
            v.invalidate()
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                v.translationX = 0f
            }
        })
        anims[v] = a
        a.start()
    }

    /** View → 屏幕坐标矩形（焦点框用） */
    fun rectOf(v: View): RectF {
        val a = IntArray(2)
        v.getLocationOnScreen(a)
        val w = if (v.width > 0) v.width else v.measuredWidth
        val h = if (v.height > 0) v.height else v.measuredHeight
        return RectF(a[0].toFloat(), a[1].toFloat(),
            (a[0] + w).toFloat(), (a[1] + h).toFloat())
    }
}
