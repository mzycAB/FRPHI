package com.piezmaker

import android.graphics.RectF
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs

/**
 * 键盘预选择框的公共几何/键位工具。
 * 面板（手绘矩形）、菜单格子、列表 View 三者统一成「一组 RectF + 一个下标」来导航。
 */
object KbNavUtil {

    /** 方向键 → -2 左 / -1 上 / 1 下 / 2 右；0 = 不是方向键 */
    fun dirOf(kc: Int): Int = when (kc) {
        KeyEvent.KEYCODE_DPAD_UP -> -1
        KeyEvent.KEYCODE_DPAD_DOWN -> 1
        KeyEvent.KEYCODE_DPAD_LEFT -> -2
        KeyEvent.KEYCODE_DPAD_RIGHT -> 2
        else -> 0
    }

    fun isEnter(kc: Int): Boolean =
        kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            kc == KeyEvent.KEYCODE_DPAD_CENTER

    /** "<" = Shift+逗号、">" = Shift+句号，物理键盘的 keyCode 就是 COMMA / PERIOD */
    fun swipeOf(kc: Int): Int = when (kc) {
        KeyEvent.KEYCODE_COMMA -> KbAct.SWIPE_LEFT
        KeyEvent.KEYCODE_PERIOD -> KbAct.SWIPE_RIGHT
        else -> -1
    }

    /** 收集一个窗口/抽屉里可点的条目：可点就收下不再深入，避免父子重复框 */
    fun collect(root: View?): List<View> {
        val out = ArrayList<View>()
        if (root == null) return out
        val rh = (if (root.height > 0) root.height else root.measuredHeight).coerceAtLeast(1)
        fun walk(v: View, depth: Int) {
            if (depth > 8 || v.visibility != View.VISIBLE) return
            val w = if (v.width > 0) v.width else v.measuredWidth
            val h = if (v.height > 0) v.height else v.measuredHeight
            if (w <= 0 || h <= 0) return
            // 遮罩 / 通栏面板（高度接近全屏）即使可点也不能当条目，否则子项全被挡住。
            // 只按高度判：抽屉里的列表项宽度常常就等于面板宽度，用宽度判会误伤。
            val containerLike = h >= rh * 0.8f
            if (!containerLike && (v.isClickable || v.hasOnClickListeners())) { out.add(v); return }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
            }
        }
        walk(root, 0)
        return out.distinct()
    }

    /** 按方向找离当前矩形最近的一项；到边界就停在原地（不跳回另一端） */
    fun nearest(rects: List<RectF>, cur: Int, dir: Int): Int {
        if (rects.isEmpty()) return -1
        if (cur !in rects.indices) return if (dir > 0) 0 else rects.lastIndex
        val a = rects[cur]
        val cx = (a.left + a.right) / 2f
        val cy = (a.top + a.bottom) / 2f
        var best = cur
        var bestScore = Float.MAX_VALUE
        for (i in rects.indices) {
            if (i == cur) continue
            val b = rects[i]
            val dx = (b.left + b.right) / 2f - cx
            val dy = (b.top + b.bottom) / 2f - cy
            val ok = when (dir) {
                -1 -> dy < -4f
                1 -> dy > 4f
                -2 -> dx < -4f
                2 -> dx > 4f
                else -> false
            }
            if (!ok) continue
            val score = if (abs(dir) == 2) abs(dx) + abs(dy) * 3f else abs(dy) + abs(dx) * 3f
            if (score < bestScore) { bestScore = score; best = i }
        }
        return best
    }

    fun rects(views: List<View>): List<RectF> = views.map { KbGesture.rectOf(it) }
}
