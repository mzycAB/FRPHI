package com.piezmaker

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * 联机玩家聊天抽屉 —— [AiDrawer] 的**镜像版**：窗口、遮罩、气泡、输入条、动画、
 * 键盘处理全部照抄 AI 抽屉，只有下面四处不同（用户点名）：
 *
 *  1. 位置镜像：AI 抽屉在 Gravity.END（右），这里在 Gravity.START（左）；
 *     标题栏保持 AI 的同款顺序（左边「清空」、右边标题），不翻转。
 *  2. 标题「AI 助手」→「玩家聊天」。
 *  3. 删掉「撤回」按钮（聊天没有撤销栈，不实现 undo/redo）。
 *  4. 「清空」只清**本机**聊天记录：不发广播、不影响房间里其他人。
 *
 * 打开方式不变：编辑页顶部「退出 ~ 玩家列表」区域任意方向滑动（仅联机时有效）。
 * 聊天记录只存在本机 SharedPreferences（[CoopChatStore]），重新打开抽屉会读回来。
 */
class ChatDrawer(
    private val act: Activity,
    private val root: ViewGroup,
    private val onSend: (String) -> Unit
) {

    /** 快捷操作：键盘预选择框遍历的根节点 */
    val navRoot: android.view.View get() = if (::panel.isInitialized) panel else root
    private lateinit var scrim: View
    private lateinit var panel: LinearLayout
    private lateinit var col: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText

    private var open = false
    private var lastIme = 0
    /** (是否自己发的, 玩家名, 内容) */
    private val history = mutableListOf<Triple<Boolean, String, String>>()

    val isOpen: Boolean get() = open

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density).toInt()

    init {
        build()
    }

    // ---------------------------------------------------------------- 构建界面
    private fun build() {
        val dm = act.resources.displayMetrics
        // 和 AI 抽屉同宽：主界面左抽屉 152dp + 中间黑线 2dp + 右抽屉 304dp = 458dp
        val w = minOf(dp(458), (dm.widthPixels * 0.86f).toInt())

        scrim = View(act).apply {
            // 和 AI 抽屉一样：抽屉外的背景不变暗，但依然点一下就关
            setBackgroundColor(0x00000000)
            isClickable = true
            setOnClickListener { close() }
            visibility = View.GONE
        }
        root.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x99000000.toInt())
            // 自己吃掉触摸：点在抽屉空白处不要漏给后面的 scrim 把抽屉关掉
            isClickable = true
            setOnClickListener { }
            visibility = View.GONE
        }
        // 镜像：AI 抽屉用 Gravity.TOP or Gravity.END（右侧），这里放左侧
        root.addView(panel, FrameLayout.LayoutParams(
            w, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.START
        ))

        // 标题栏和 AI 抽屉同款顺序：左边「清空」，右边标题（撤回按钮删掉）
        val header = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(4))
        }
        val titleView = TextView(act).apply {
            text = "玩家聊天"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            includeFontPadding = false
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        header.addView(smallBtn("清空") { confirmClear() })
        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        scroll = ScrollView(act).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(2), dp(10), dp(8))
        }
        scroll.addView(col, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 输入条（和 AI 抽屉完全同款）
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        input = EditText(act).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0x88FFFFFF.toInt())
            hint = null
            textSize = 12f
            isSingleLine = true
            background = inputBg()
            setPadding(dp(10), 0, dp(10), 0)
            // 回车发送（UI 与 AI 抽屉一致，只是多一个快捷入口）
            setOnEditorActionListener { v, _, _ -> send(); v.clearFocus(); true }
        }
        val send = TextView(act).apply {
            text = "发送"
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            includeFontPadding = false
            background = inputBg()
            setOnClickListener {
                val t = input.text.toString()
                input.setText("")
                sendChat(t)
            }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, dp(32), 1f))
        bar.addView(View(act), LinearLayout.LayoutParams(dp(6), 1))
        bar.addView(send, LinearLayout.LayoutParams(dp(56), dp(32)))
        panel.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 键盘处理：照抄 AI 抽屉的效果，但**不抢 decorView 的 listener** ——
        // AiDrawer 已经给 act.window.decorView 设了 setOnApplyWindowInsetsListener，
        // 这里再用 decorView 会把它覆盖掉。改用 panel 自己的 View 级 listener 共存。
        if (Build.VERSION.SDK_INT >= 30) {
            panel.setOnApplyWindowInsetsListener { v, insets ->
                applyIme(insets.getInsets(WindowInsets.Type.ime()).bottom)
                insets
            }
        } else {
            val decor = act.window.decorView
            decor.viewTreeObserver.addOnGlobalLayoutListener {
                val r = android.graphics.Rect()
                decor.getWindowVisibleDisplayFrame(r)
                applyIme((decor.height - r.bottom).coerceAtLeast(0))
            }
        }
        panel.post { panel.translationY = -(panel.height + dp(20)).toFloat() }
        // 先把本机记录读回来：抽屉从没打开过时收到消息，append 才不会把旧记录覆盖掉
        loadHistory()
    }

    /** 键盘弹出时让输入条贴在键盘上沿（和 AI 抽屉同一套逻辑） */
    private fun applyIme(ime: Int) {
        lastIme = ime
        if (!open) return
        val decorH = act.window.decorView.height
        val contentH = root.height
        val resized = ime > 0 && decorH > 0 && contentH > 0 && contentH < decorH - dp(8)
        val lp = panel.layoutParams
        val target = if (ime > 0 && !resized)
            (decorH - ime).coerceAtLeast(dp(160))
        else ViewGroup.LayoutParams.MATCH_PARENT
        panel.translationY = 0f
        if (lp.height != target) {
            lp.height = target
            panel.layoutParams = lp
        }
        panel.requestLayout()
    }

    private fun hideIme() {
        try {
            val imm = act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(panel.windowToken, 0)
        } catch (_: Exception) {}
    }

    private fun inputBg() = GradientDrawable().apply {
        setColor(0x33FFFFFF)
        setStroke(dp(1), 0x66FFFFFF)
        cornerRadius = dp(4).toFloat()
    }

    /** 标题栏小按钮（AI 抽屉同款；聊天这边没有长按重做，所以不需要 onLongClick） */
    private fun smallBtn(label: String, onClick: () -> Unit): TextView = TextView(act).apply {
        text = label
        setTextColor(0xCCFFFFFF.toInt())
        textSize = 12f
        includeFontPadding = false
        gravity = Gravity.CENTER
        setPadding(dp(9), dp(5), dp(9), dp(5))
        setOnClickListener { onClick() }
    }

    // ---------------------------------------------------------------- 开关抽屉
    /** 从上往下拉出（和 AI 抽屉同一套动画） */
    fun show() {
        if (open) return
        open = true
        loadHistory()
        scrim.animate().cancel()
        panel.animate().cancel()
        scrim.visibility = View.VISIBLE
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(160).start()
        panel.visibility = View.VISIBLE
        panel.translationY = -(panel.height.coerceAtLeast(dp(240)) + dp(20)).toFloat()
        panel.animate().translationY(0f).setDuration(220).start()
        if (lastIme > 0) panel.post { applyIme(lastIme) }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    fun close() {
        if (!open) return
        open = false
        input.clearFocus()
        hideIme()
        val lp = panel.layoutParams
        if (lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            panel.layoutParams = lp
        }
        panel.animate().translationY(-(panel.height.coerceAtLeast(dp(240)) + dp(20)).toFloat())
            .setDuration(180)
            .withEndAction { if (!open) panel.visibility = View.GONE }.start()
        scrim.animate().alpha(0f).setDuration(160)
            .withEndAction { if (!open) scrim.visibility = View.GONE }.start()
    }

    // ---------------------------------------------------------------- 消息
    /** 重新从本机读聊天记录并重建气泡 */
    private fun loadHistory() {
        history.clear()
        history.addAll(CoopChatStore.load(act))
        col.removeAllViews()
        for ((mine, name, text) in history) addBubble(mine, name, text)
    }

    /** 气泡：和 AI 抽屉同款（自己青色、别人白色，圆角 6dp，12f 字） */
    private fun addBubble(mine: Boolean, name: String, text: String) {
        val tv = TextView(act).apply {
            this.text = (if (mine) "我：" else name + "：") + text
            setTextColor(if (mine) 0xFF00E5FF.toInt() else 0xFFFFFFFF.toInt())
            textSize = 12f
            includeFontPadding = false
            setLineSpacing(0f, 1.15f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(if (mine) 0x2200E5FF else 0x22FFFFFF)
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(dp(4), 0, dp(4), dp(6)) }
        }
        col.addView(tv)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /**
     * 追加一条消息（[Coop.onChatMessage] 和本机发送都走这里）。
     * [mine] 自己发的用「我：」，否则用 [name] 作为前缀。
     */
    fun append(name: String, text: String, mine: Boolean = false) {
        if (text.isBlank()) return
        addBubble(mine, name, text)
        history.add(Triple(mine, name, text))
        while (history.size > CoopChatStore.MAX) history.removeAt(0)
        CoopChatStore.save(act, history)
    }

    private fun sendChat(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        // Coop.chat 不会把消息再回显给自己，所以本机先显示一条
        append(Coop.deviceName(), text, mine = true)
        onSend(text)
    }

    private fun send() {
        val t = input.text.toString()
        input.setText("")
        sendChat(t)
    }

    /** 只清本机：不调用 [Coop.chat]、不发任何广播，其他玩家的记录不受影响 */
    private fun confirmClear() {
        KbAlertBuilder(act)
            .setTitle("清空聊天记录")
            .setMessage("清空本机玩家聊天记录？（不会影响其他玩家）")
            .setPositiveButton("清空") { _, _ ->
                history.clear()
                CoopChatStore.clear(act)
                col.removeAllViews()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

/**
 * 玩家聊天记录持久化（只在本机，SharedPreferences "frphi_coop_chat"）。
 * 和 [AiChatStore] 同款结构，多存一个玩家名；最多保留最近 [MAX] 条。
 */
private object CoopChatStore {
    private const val PREF = "frphi_coop_chat"
    private const val K_MSG = "msgs"
    const val MAX = 60

    fun load(ctx: android.content.Context): MutableList<Triple<Boolean, String, String>> {
        val out = mutableListOf<Triple<Boolean, String, String>>()
        val raw = ctx.getSharedPreferences(PREF, 0).getString(K_MSG, null) ?: return out
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("t", "")
                if (t.isNotEmpty()) {
                    out.add(Triple(o.optBoolean("me", false), o.optString("n", "玩家"), t))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    fun save(ctx: android.content.Context, msgs: List<Triple<Boolean, String, String>>) {
        val arr = JSONArray()
        for ((me, n, t) in msgs.takeLast(MAX)) {
            arr.put(JSONObject().put("me", me).put("n", n).put("t", t))
        }
        ctx.getSharedPreferences(PREF, 0).edit().putString(K_MSG, arr.toString()).apply()
    }

    fun clear(ctx: android.content.Context) {
        ctx.getSharedPreferences(PREF, 0).edit().remove(K_MSG).apply()
    }
}
