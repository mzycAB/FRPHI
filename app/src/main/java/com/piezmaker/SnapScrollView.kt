package com.piezmaker

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

/**
 * 选曲页的谱面列表（用户点名：**一次滑动只落到一个项目上**）。
 *
 * 判定基准 = 项目竖向位移有没有超过一半：
 * 目标格 = `round(scrollY / stride) * stride` —— 拖过半格就吸到下一个项目，
 * 没到一半就弹回原来的项目。所以直接吃掉 `fling()`（惯性），不让它滑好几格，
 * 一切以「位移 > 50%」为准。
 *
 * ★ 吸附动画期间，选中项必须按**目标位置**（[settledScrollY]）算，不能按正在滚动的
 *   `scrollY` 算：向下切时松手的位置还在旧格子里，按 `scrollY` 会先把旧项目放大、
 *   滚到目标再放大新项目 —— 看起来就是「上切下要卡一下」的双跳；向上切时松手位置
 *   恰好已经落在目标格里，所以只有一次放大，看起来正常。
 */
class SnapScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ScrollView(context, attrs) {

    /** 一格 = 一个列表项的高度（含间隔），由调用方设 */
    var stride: Int = 0

    /** 吸附落定后的回调（参数 = 目标 scrollY） */
    var onSnapped: ((Int) -> Unit)? = null

    /** 正在吸附去的 scrollY（-1 = 没在吸附动画里） */
    var snapTarget: Int = -1
        private set

    /** 算选中项该用的位置：吸附动画期间是**目标**，否则就是当前 scrollY */
    val settledScrollY: Int get() = if (snapTarget >= 0) snapTarget else scrollY

    /** 这一次抬手是不是已经由 [fling] 处理过（慢速松手不会触发 fling，要在 UP 里补一次） */
    private var handledByFling = false

    /** 列表重新排版（renderPlayList / layoutPlayItems）时清掉，免得残留旧目标 */
    fun clearSnapTarget() {
        snapTarget = -1
    }

    override fun fling(velocityY: Int) {
        // 不惯性滑行：松手立刻按「位移过半」吸附
        handledByFling = true
        snapToNearest()
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        // 滑到目标 = 吸附落定，之后选中项重新跟随真实 scrollY
        if (snapTarget >= 0 && t == snapTarget) snapTarget = -1
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) snapTarget = -1
        val r = super.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            // 慢慢拖完松手时速度不够，框架不会调 fling()：这里补一次吸附
            if (handledByFling) handledByFling = false
            else post { snapToNearest() }
        }
        return r
    }

    /** 吸附到最近的一格 */
    fun snapToNearest() {
        val s = stride
        if (s <= 0) return
        smoothSnapTo(Math.round(scrollY.toFloat() / s) * s)
    }

    /** 带动画滚到 [targetY]（自动夹在可滚范围内），滚动期间选中项按目标算 */
    fun smoothSnapTo(targetY: Int) {
        val content = getChildAt(0)?.height ?: return
        val maxScroll = (content - height).coerceAtLeast(0)
        val target = targetY.coerceIn(0, maxScroll)
        if (target == scrollY) {
            snapTarget = -1
            onSnapped?.invoke(target)
            return
        }
        snapTarget = target          // 先记目标：选中项立刻按目标算，只放大一次
        smoothScrollTo(0, target)
        onSnapped?.invoke(target)
    }
}
