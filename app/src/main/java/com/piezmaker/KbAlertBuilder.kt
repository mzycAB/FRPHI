package com.piezmaker

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button

/**
 * 弹窗统一的键盘导航入口：继承 AlertDialog.Builder 并覆写 create()/show()，
 * 于是工程里所有 `KbAlertBuilder(...)` 造出来的弹窗都自动支持
 * 「左右键选中最左/最右选项、左右移动、Enter 点击」。
 *
 * 不碰 setOnShowListener（很多弹窗自己要用它调窗口宽度）。
 */
class KbAlertBuilder(ctx: Context) : AlertDialog.Builder(ctx) {

    override fun create(): AlertDialog {
        val d = super.create()
        KbDialogs.attach(d)
        return d
    }

    override fun show(): AlertDialog {
        val d = create()
        d.show()
        return d
    }
}

object KbDialogs {

    fun attach(d: AlertDialog) {
        d.setOnKeyListener { _: DialogInterface, code: Int, ev: KeyEvent ->
            handle(d, code, ev)
        }
    }

    private fun handle(d: AlertDialog, code: Int, ev: KeyEvent): Boolean {
        val mode = try {
            d.context.getSharedPreferences("frphi_rec", 0).getInt("quickOp", 0)
        } catch (_: Exception) { 0 }
        if (mode <= 0) return false
        val left = code == KeyEvent.KEYCODE_DPAD_LEFT
        val right = code == KeyEvent.KEYCODE_DPAD_RIGHT
        val enter = KbNavUtil.isEnter(code)
        if (!left && !right && !enter) return false
        if (ev.action != KeyEvent.ACTION_DOWN || ev.repeatCount != 0) return true

        val opts = options(d)
        if (opts.isEmpty()) return true
        // ★ 安卓原生焦点样式：只移动系统焦点，不加任何自定义 View。
        //   （之前懒加载一层全屏虚线框 View 到 decorView，会触发弹窗重新测量 → 底部变大，
        //     框也画不准，这就是「左右键选不了、弹窗还变大」的根因。）
        val focus = d.window?.currentFocus
        val idx = opts.indexOf(focus)
        if (enter) {
            (opts.getOrNull(idx) ?: opts.first()).performClick()
            return true
        }
        // 第一次按左 = 最左，第一次按右 = 最右；之后按 x 顺序左右移动
        val next = when {
            idx < 0 -> if (left) opts.first() else opts.last()
            left -> opts[(idx - 1).coerceAtLeast(0)]
            else -> opts[(idx + 1).coerceAtMost(opts.size - 1)]
        }
        next.requestFocus()
        return true
    }

    /** 底部三个标准按钮 + 自定义布局里的 Button，按屏幕 x 从左到右排 */
    private fun options(d: AlertDialog): List<View> {
        val out = ArrayList<View>()
        for (w in intArrayOf(
            AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_POSITIVE
        )) {
            d.getButton(w)?.let { if (it.visibility == View.VISIBLE) out.add(it) }
        }
        val decor = d.window?.decorView
        if (decor is ViewGroup) collectButtons(decor, out)
        return out.distinct().sortedBy { KbGesture.rectOf(it).left }
    }

    private fun collectButtons(g: ViewGroup, out: MutableList<View>) {
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c is Button && c.visibility == View.VISIBLE && c.width > 0 && c !in out) out.add(c)
            if (c is ViewGroup) collectButtons(c, out)
        }
    }
}
