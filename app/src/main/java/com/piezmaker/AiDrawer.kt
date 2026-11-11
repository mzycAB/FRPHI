package com.piezmaker

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * 编辑页的 AI 抽屉（从屏幕上方往下拉出来，宽度 = 主界面左抽屉 + 右抽屉）。
 *
 * 和主界面的 AI 是**同一个 AI**：同一份接口配置（[AiStore]）、同一份聊天记录（[AiChatStore]）。
 * 能做：
 *   - 聊天 / 解释功能；
 *   - 一句话写谱（边收边套用，流式）；
 *   - 改谱（fix / del / fake / rev）、加特效（kf / line / nl）；
 *   - 本地 BPM Detect 查 BPM（不需要联网），一键应用（写回项目 BPM + 偏移）；
 *   - 联网搜索 / 抓网页；改项目信息；撤回。
 *
 * 玩家话里的手感要求（「蓝键多点」这种）由 [ChartStyle] 解析，并在 AI 写完之后强制套用。
 */
class AiDrawer(
    private val act: Activity,
    private val root: ViewGroup,
    private val hooks: Hooks
) {

    interface Hooks {
        /** 当前项目的音乐（空 = 没选音乐） */
        fun musicUri(): Uri
        fun durationMs(): Long
        fun bpm(): Double
        fun bpmOffset(): Double
        fun level(): String

        fun lines(): List<Line>
        fun notes(): List<RecordedNote>
        /** 噪域列表（AI 快照用；调用方不得改元素内容的引用） */
        fun noises(): List<NoiseArea>
        /** 空图层声明（AI 写回草稿时要带上，否则存档丢空图层） */
        fun declaredLayerValues(): Set<Int>
        fun declaredNoiseLayerValues(): Set<String>
        /** 视频背景（AI 写回草稿时要带上，否则一整轮改动会把视频清掉） */
        fun videos(): List<VideoBg> = emptyList()

        /** 把草稿文本套进编辑器（**保留**编辑器自己的撤销历史，改动前的状态由编辑器自己拍快照） */
        fun applyDraft(text: String)

        /**
         * 一轮 AI 改动收尾：[keep]=true 把「改动前」的状态记成编辑器的一个撤销步
         * （和手改的步骤同一条历史，编辑页撤销按钮 / 抽屉「撤回」都能回退）；
         * false = 这一轮没改谱面或被撤回打断，丢弃快照。
         */
        fun finishUndo(keep: Boolean) {}

        /**
         * 撤销一步 —— **编辑页那个撤销按钮的同一个功能**（同一个 [RecordView] 撤销栈）。
         * 抽屉左上角「撤回」= 它，长按 = [redoChart]。返回 false 表示没有可撤销的步骤。
         */
        fun undoChart(): Boolean = false

        /** 重做一步（抽屉「撤回」长按） */
        fun redoChart(): Boolean = false

        /**
         * AI 这一轮把内容写在了 [fromMs]~[toMs]（噪域 / 关键帧 / 音符）。
         *
         * 编辑器是**按播放头时间**渲染的：内容写在第 40 秒、播放头还停在 0 秒，
         * 屏幕上就什么都没有 —— 用户看到的是「AI 说做好了，但是我什么都没看到」（点名）。
         * 实现方在播放头看不到这段内容时把它跳过去。
         */
        fun revealChart(fromMs: Long, toMs: Long) {}

        /** 一键应用 BPM；[offsetMs] 不为 null 时一起写回 BPM 偏移 */
        fun applyBpm(bpm: Double, offsetMs: Double?)

        /**
         * 改项目信息：只改不为 null 的字段。
         * @param speed 流速（编辑页里就是判定线流速）
         * @param linePos 判定线位置 0~100
         * @param delay 延迟毫秒
         */
        fun applyMeta(
            name: String? = null, composer: String? = null, charter: String? = null,
            level: String? = null, bpm: Double? = null, bpmOffset: Double? = null,
            speed: Double? = null, linePos: Int? = null, delay: Int? = null,
            hLines: Int? = null, vLines: Int? = null
        )

        /** 改难度文本 */
        fun setLevel(level: String)

        /** 顶部信息行的一句话提示 */
        fun status(msg: String)

        /** 现在能不能改谱面（打歌中 / 联机中不行） */
        fun canEdit(): Boolean

        /**
         * 把当前谱面导出成 .pez（zip：chart.json + 背景 + 音频），**不弹保存框**，
         * 直接落到用户能拿到的地方，返回一句给用户看的结果（含落盘位置）。
         *
         * 用户点名「为什么 ai 不能导出谱面」——以前编辑页的 AI 根本没有导出命令，
         * 它只能回答"我做不到"。现在 `{"cmd":"export"}` 走这里。
         */
        fun exportChart(): String = ""
    }

    // ---------------------------------------------------------------- 视图
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var scrim: View
    private lateinit var panel: LinearLayout
    private lateinit var col: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var titleView: TextView

    private var open = false
    private var busy = false
    private var cancel: AiClient.Cancel? = null
    private var gen = 0
    private var lastPush = 0L
    private var lastBubble: TextView? = null

    private val history = mutableListOf<Pair<Boolean, String>>()
    private var beatsCache: List<AudioBeats.Beat>? = null

    val isOpen: Boolean get() = open

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density).toInt()

    init {
        build()
    }

    // ---------------------------------------------------------------- 构建界面
    private fun build() {
        val dm = act.resources.displayMetrics
        // 主界面左抽屉 152dp + 中间黑线 2dp + 右抽屉 304dp = 458dp；窄屏时按比例收一点
        val w = minOf(dp(458), (dm.widthPixels * 0.86f).toInt())

        scrim = View(act).apply {
            // 用户要求：抽屉外的背景不要变暗 —— 全透明，但依然点一下就关
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
            // 自己吃掉触摸：点在抽屉空白处/输入条留白上时，不要漏给后面的 scrim 把抽屉关掉。
            // 注意不要设 isFocusable —— 那会把输入框的焦点（和键盘）抢走。
            isClickable = true
            setOnClickListener { }
            visibility = View.GONE
        }
        // 用户要求：窗口放在右侧（原来在左侧，x 坐标镜像过去）
        val plp = FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.END)
        root.addView(panel, plp)

        // 标题栏：左上角「撤回 / 清空」，右边「AI 助手」
        val header = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(4))
        }
        titleView = TextView(act).apply {
            text = "AI 助手"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            includeFontPadding = false
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        // 「撤回」= 编辑页那个撤销按钮（同一个撤销栈），**长按 = 重做**（和编辑页长按撤销一致）
        header.addView(smallBtn("撤回", { undoLast() }, { redoLast() }))
        header.addView(smallBtn("清空", { confirmClear() }))
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
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 输入条
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

        // 键盘弹出：整个抽屉上移，输入条跟着键盘走
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            act.window.decorView.setOnApplyWindowInsetsListener { v, insets ->
                val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                applyIme(ime)
                v.onApplyWindowInsets(insets)
            }
        } else {
            // Android 10 及以下没有 ime insets：用可见区域高度差估算键盘高度
            val decor = act.window.decorView
            decor.viewTreeObserver.addOnGlobalLayoutListener {
                val r = android.graphics.Rect()
                decor.getWindowVisibleDisplayFrame(r)
                applyIme((decor.height - r.bottom).coerceAtLeast(0))
            }
        }
        panel.post { panel.translationY = -(panel.height + dp(20)).toFloat() }
    }

    /**
     * 键盘弹出时让输入条悬浮在键盘上沿（和主界面的 AI 输入条同一个效果）：
     *   - 系统已经 adjustResize 把内容顶起来（content 比 decor 矮）→ 什么都不用做，
     *     panel 是 MATCH_PARENT，本来就贴着键盘上沿；
     *   - 沉浸式全屏下系统不 resize → 手动把 panel 高度收到「窗口高度 - 键盘高度」。
     * 两种情况下都**不能再平移** panel：平移 + 缩高会双重偏移，把抽屉顶出屏幕。
     */
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

    /** 键盘高度（键盘收起时是 0），抽屉重新打开时要用它再算一次 */
    private var lastIme = 0

    /** 收起软键盘（点抽屉外关闭时一起收掉，不然键盘会留在编辑页上） */
    private fun hideIme() {
        try {
            val imm = act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(panel.windowToken, 0)
        } catch (_: Exception) {}
    }

    private fun inputBg() = android.graphics.drawable.GradientDrawable().apply {
        setColor(0x33FFFFFF)
        setStroke(dp(1), 0x66FFFFFF)
        cornerRadius = dp(4).toFloat()
    }

    /**
     * 标题栏的小按钮。[onLongClick] 不为 null 时长按可用（「撤回」用它做重做，和编辑页一致）。
     */
    private fun smallBtn(
        label: String,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null
    ): TextView = TextView(act).apply {
        text = label
        setTextColor(0xCCFFFFFF.toInt())
        textSize = 12f
        includeFontPadding = false
        gravity = Gravity.CENTER
        setPadding(dp(9), dp(5), dp(9), dp(5))
        setOnClickListener { onClick() }
        if (onLongClick != null) {
            isLongClickable = true
            setOnLongClickListener { onLongClick(); true }
        }
    }

    // ---------------------------------------------------------------- 开关抽屉
    /** 从上往下拉出（和主界面抽屉同一套观感） */
    fun show() {
        if (open) return
        open = true
        // 每次都从本地读一遍：主界面那边可能刚聊过，两边必须是同一段对话
        loadHistory()
        // 上一次"收起"的动画可能还在跑：先取消，否则它的收尾动作会把刚打开的面板/遮罩又藏起来
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

    /** 重新从本机读聊天记录并重建气泡（正在回复时不动，避免把流式内容冲掉） */
    private fun loadHistory() {
        if (busy) return
        history.clear()
        history.addAll(AiChatStore.load(act))
        col.removeAllViews()
        detachProgress()
        // 历史气泡不带进度条（进度条只在"正在干活"时出现）
        for ((mine, text) in history) addBubble(mine, text)
    }

    fun close() {
        if (!open) return
        open = false
        cancel?.cancelled = true
        input.clearFocus()
        hideIme()
        // 高度还原成铺满（键盘弹出时被收短过），下次打开才是正常的一整屏
        val lp = panel.layoutParams
        if (lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            panel.layoutParams = lp
        }
        panel.animate().translationY(-(panel.height.coerceAtLeast(dp(240)) + dp(20)).toFloat())
            .setDuration(180)
            // 收尾动作要再看一眼 open：动画期间又点开了，就别把面板/遮罩藏起来
            .withEndAction { if (!open) panel.visibility = View.GONE }.start()
        scrim.animate().alpha(0f).setDuration(160)
            .withEndAction { if (!open) scrim.visibility = View.GONE }.start()
    }

    // ---------------------------------------------------------------- 气泡
    /**
     * [bar] = true 时在气泡下面挂一条进度条（只有"正在干活"的回复才需要：
     * 等首包 / 写整首 / 本地分析）。干完 `finishProgress()` 会把它撤掉。
     */
    private fun addBubble(mine: Boolean, text: String, bar: Boolean = false): TextView {
        val tv = TextView(act).apply {
            this.text = (if (mine) "我：" else "AI：") + text
            setTextColor(if (mine) 0xFF00E5FF.toInt() else 0xFFFFFFFF.toInt())
            textSize = 12f
            includeFontPadding = false
            setLineSpacing(0f, 1.15f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (mine) 0x2200E5FF else 0x22FFFFFF)
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(dp(4), 0, dp(4), dp(6)) }
        }
        col.addView(tv)
        if (!mine && bar) {
            val row = makeBar()
            insertBarAfter(tv, row)
            progRow = row
            progBar = row.tag as? android.widget.ProgressBar
            progLastAt = 0L
            progScan = 0
            progMaxMs = 0L
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return tv
    }

    /** 可点的操作气泡（比如「一键应用 BPM 128」） */
    private fun addAction(label: String, action: () -> Unit) {
        val tv = TextView(act).apply {
            text = label
            setTextColor(0xFF00E5FF.toInt())
            textSize = 12f
            includeFontPadding = false
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x1A00E5FF)
                setStroke(dp(1), 0x6600E5FF)
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(dp(4), 0, dp(4), dp(6)) }
            setOnClickListener { action() }
        }
        col.addView(tv)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun remember(mine: Boolean, text: String) {
        history.add(mine to text)
        while (history.size > AiChatStore.MAX) history.removeAt(0)
        AiChatStore.save(act, history)
    }

    private fun confirmClear() {
        android.app.AlertDialog.Builder(act)
            .setTitle("清空聊天记录")
            .setMessage("清空所有 AI 聊天记录？")
            .setPositiveButton("清空") { _, _ ->
                cancel?.cancelled = true
                gen++
                history.clear()
                AiChatStore.clear(act)
                col.removeAllViews()
                detachProgress()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------------------------------------------------------- 发送
    private fun sendChat(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        if (busy) { hooks.status("AI 还在回复…"); return }
        // 明确要写谱但项目还没有音乐（时长 0）才拦；闲聊一律放行
        if (isWritingRequest(text) && hooks.durationMs() <= 0) {
            hooks.status("这个项目还没有音乐，先选音乐再让我写谱")
            addBubble(false, "这个项目还没有音乐。先回主界面选一首音乐，我才能按节拍写谱。")
            return
        }
        val ep = AiStore.active(act)
        if (ep == null || !ep.usable) {
            localAnswer(text)
            return
        }
        remember(true, text)
        addBubble(true, text)
        val bubble = addBubble(false, "正在回复…", bar = true)
        lastBubble = bubble
        lastPush = 0L
        busy = true
        val c = AiClient.Cancel()
        cancel = c
        val myGen = gen
        // 不再在发消息前把整份谱面 encode 成草稿字符串（大谱面几 MB，纯聊天也要卡一下）：
        // "改动前"的快照由编辑器在第一次 applyDraft 时用内存里的实时数据自己拍（见 RecordView.pendingUndo）。
        Thread { aiTurn(text, ep, c, bubble, myGen) }.start()
    }

    private fun isWritingRequest(text: String): Boolean = AiIntent.isWritingRequest(text)

    /** 这一轮需不需要给它谱面上下文/JSON 协议 */
    private fun chartIntent(text: String): Boolean = AiIntent.chartIntent(text)

    // ---------------------------------------------------------------- 一轮对话（后台线程）
    private fun aiTurn(
        userText: String,
        ep: AiEndpoint,
        c: AiClient.Cancel,
        bubble: TextView,
        myGen: Int
    ) {
        val dur = hooks.durationMs().coerceAtLeast(1L)
        val bpm = hooks.bpm()
        val writing = isWritingRequest(userText) && hooks.canEdit()
        val spec = ChartStyle.parse(userText)
        var changedChart = false
        var holder: AiChart.Holder? = null
        var parsedNotes = 0
        var scanFrom = 0

        fun post(block: () -> Unit) = handler.post { block() }

        fun applyNow() {
            val h = holder ?: return
            // 续写/多轮容易产出重复音符：按 类型+时间+结束+线+位置 去重
            val seen = HashSet<String>()
            val uniq = h.notes.filter {
                seen.add(it.type.toString() + "|" + it.startMs + "|" + it.endMs + "|" +
                    it.lineId + "|" + Math.round(it.posX * 1000f))
            }.sortedBy { it.startMs }
            val text = DraftCodec.encode(
                h.lines, uniq,
                noise = h.noises,
                declaredLayers = h.declaredLayers,
                declaredNoiseLayers = h.declaredNoiseLayers,
                videos = h.videos
            )
            // 已经点了「撤回/停止」就别再把结果套回去（否则撤回完又被这一帧覆盖，等于没撤）
            post { if (!c.cancelled) hooks.applyDraft(text) }
            changedChart = true
        }

        fun ensureHolder() {
            if (holder != null) return
            val baseLines = AiChart.copyLines(hooks.lines())
            val baseNotes = if (writing) ArrayList() else ArrayList(hooks.notes())
            holder = AiChart.Holder(
                baseLines, baseNotes,
                (baseLines.maxOfOrNull { it.id } ?: 0) + 1,
                noises = hooks.noises().toMutableList(),
                declaredLayers = hooks.declaredLayerValues(),
                declaredNoiseLayers = hooks.declaredNoiseLayerValues(),
                videos = hooks.videos(),
                writeMode = writing, diversify = writing, difficulty = levelValue()
            )
        }

        /** 逐对象扫描（流式增量；半截的对象留给下一段） */
        fun consume(full: String) {
            var i = scanFrom
            while (i < full.length) {
                if (full[i] != '{') { i++; continue }
                var depth = 0
                var inStr = false
                var esc = false
                var j = i
                var end = -1
                while (j < full.length) {
                    val ch = full[j]
                    if (inStr) {
                        if (esc) esc = false
                        else if (ch == '\\') esc = true
                        else if (ch == '"') inStr = false
                    } else when (ch) {
                        '"' -> inStr = true
                        '{' -> depth++
                        '}' -> { depth--; if (depth == 0) { end = j; break } }
                    }
                    j++
                }
                if (end < 0) { scanFrom = i; return }
                val obj = full.substring(i, end + 1)
                i = end + 1
                scanFrom = i
                val o = try { JSONObject(obj) } catch (_: Exception) { null } ?: continue
                if (o.has("cmd")) continue                  // 命令在结果回来后统一执行
                if (!hooks.canEdit()) continue
                ensureHolder()
                val h = holder ?: continue
                if (AiChart.applyJsonLine(obj, h, dur, bpm, h.lines.size)) {
                    parsedNotes++
                    // 写整首时推进度条（气泡下方）：按"最后一个音符的时间 / 总长"
                    if (writing) tickWriteProgress(h, dur)
                    if (now() - lastApplyAt > 900) { lastApplyAt = now(); applyNow() }
                }
            }
            scanFrom = i
        }

        try {
            // 编辑页的 AI 每一轮都带谱面上下文与 JSON 协议。
            // 以前只在那句话里出现"谱/音符/判定线"等关键词时才带，用户说
            // "改一下""继续""这样呢"时模型就看不到谱面，表现得像"识别不到谱子"。
            // 要不要动手仍由模型决定，这里只是把真实的谱面数据给它看。
            val chartTurn = true
            val msgs = ArrayList<Pair<String, String>>()
            msgs.add("system" to AiPrompts.systemEditorChat(chartTurn, ep.persona))
            // 只带最近 8 条，省 token
            // 当前这句在下面的上下文里再带一次（带曲目信息），历史里不要重复
            for ((mine, t) in history.dropLast(1).takeLast(8)) msgs.add((if (mine) "user" else "assistant") to t)
            // 当前这句一定要发给模型：
            //  - 要动谱面/查 BPM → 带上曲目上下文（里面已有玩家原话，不再重复）
            //  - 只是聊天 → 原样发这句（以前这里漏了，闲聊时模型根本收不到用户的话）
            val withChartData = true
            if (withChartData) {
                if (writing) post { bubble.text = "AI：正在分析音频…" }
                val beats = if (writing) loadBeats(c) else emptyList()
                msgs.add("user" to AiPrompts.editorContext(
                    writing, dur, bpm, hooks.bpmOffset(), hooks.level(),
                    hooks.lines(), hooks.notes(), hooks.noises(), beats, userText, spec
                ))
            } else {
                msgs.add("user" to userText)
            }

            var round = 0
            var lastReply = ""
            while (true) {
                val full = AiClient.chat(
                    ep, msgs, c,
                    maxTokens = if (writing) 8192 else 4096,
                    // 闲聊/写文案别压得太死板：只有真正铺谱面时才用低一点的随机度
                    temperature = if (writing) 0.75 else 0.85,
                    onDelta = { s ->
                        consume(s)
                        val now = now()
                        if (now - lastPush > 160) {
                            lastPush = now
                            val shown = stripJson(s)
                            post { if (shown.isNotBlank()) bubble.text = "AI：" + shown }
                        }
                    }
                )
                consume(full)
                val cmds = splitCommands(full).second
                // 气泡只显示人话（谱面 JSON 不往聊天里塞）
                lastReply = stripJson(full).trim()
                if (lastReply.isNotBlank()) {
                    post { bubble.text = "AI：" + lastReply }
                }
                if (myGen != gen) return     // 期间被清空了

                // 工具类命令：bpm / search / fetch —— 结果回灌给 AI 再回答一轮
                val toolResults = ArrayList<String>()
                for (cmd in cmds) {
                    when (cmd.optString("cmd")) {
                        "bpm" -> {
                            val r = runBpm(cmd)
                            if (r != null) toolResults.add(r) else toolResults.add("【工具结果】BPM 检测失败（没选音乐或解码失败）")
                        }
                        "search", "fetch" -> {
                            val r = AgentTools.run(cmd)
                            toolResults.add(r ?: "【工具结果】联网失败（网络受限或页面打不开），请如实告诉用户。")
                        }
                    }
                }
                if (toolResults.isNotEmpty() && round < 2 && !c.cancelled) {
                    post { bubble.text = "AI：正在查资料…" }
                    msgs.add("assistant" to full)
                    msgs.add("user" to "【工具结果】\n" + toolResults.joinToString("\n\n") +
                        "\n请基于上面的结果直接回答用户；如果用户要应用 BPM，就输出 {\"cmd\":\"bpm\",\"apply\":1} 或 {\"cmd\":\"meta\",\"bpm\":数值}。")
                    round++
                    continue
                }

                // 其余命令在 UI 线程执行
                for (cmd in cmds) {
                    val name = cmd.optString("cmd")
                    if (name == "bpm" || name == "search" || name == "fetch") continue
                    runLocalCommand(cmd)
                }
                break
            }

            // 玩家提了手感要求、AI 却只回了一句话（没动谱面）→ 本地也要照做
            // （写整首时 AI 一个字都没输出，就不要动用户现有谱面）
            if (holder == null && spec.any && hooks.canEdit() && !writing) ensureHolder()

            // 手感要求（蓝键多点等）落地：AI 写完之后按玩家原话强制校正
            if (holder != null && hooks.canEdit()) {
                val h = holder!!
                // ★ 写整首时 holder 的音符是**从零开始攒**的（见 ensureHolder）：一个都没攒到就
                //   applyNow = 把用户现有谱面清空。AI 输出一句不认识的对象（{"meta":…} 之类）
                //   也会让 holder 不是 null，所以这里必须挡一下。
                val emptyWrite = writing && h.notes.isEmpty() && !spec.any
                if (spec.any) {
                    val fixed = ChartStyle.enforce(h.notes, spec, dur, bpm, h.lines.firstOrNull()?.id ?: 0)
                    h.notes.clear()
                    h.notes.addAll(fixed)
                }
                if (!emptyWrite) applyNow()
                val n = h.notes.size
                // ★ 「AI 说做好了，但其实一个 JSON 都没给」要如实说，别再报"谱面已更新"
                //   （用户点名的问题就是"他说做好了，但是我什么都没有看到"）。
                val noOps = emptyWrite || (parsedNotes <= 0 && !spec.any)
                // 说清「到底加了什么」：噪域/关键帧数不是音符，以前的总结只报音符数，
                // 玩家看到"已更新 N 个音符"却找不到新画的噪域（用户点名）。
                val extras = ArrayList<String>(3)
                if (h.addedNoises > 0) extras.add("噪域 +" + h.addedNoises)
                if (h.addedKeyframes > 0) extras.add("关键帧 +" + h.addedKeyframes)
                if (spec.any) extras.add("已按「" + spec.describe() + "」校正")
                val summary = if (noOps) "这一轮没有改动谱面（没有生成可套用的内容）"
                    else "谱面已更新：共 $n 个音符" +
                        if (extras.isEmpty()) "" else "（" + extras.joinToString(" · ") + "）"
                val shown = if (lastReply.isBlank()) summary
                            else lastReply + "\n" + summary
                // 新内容写在了播放头看不到的地方 → 把播放头带过去（否则「说做好了但什么都没有」）
                val rvFrom = if (h.hasReveal) h.revealFrom else -1L
                val rvTo = if (h.hasReveal) h.revealTo else -1L
                post {
                    if (c.cancelled) return@post      // 已撤回/停止：别再说"谱面已更新"
                    bubble.text = "AI：" + shown
                    remember(false, shown)
                    finishProgress()
                    if (!noOps && rvFrom >= 0 && rvTo >= rvFrom) hooks.revealChart(rvFrom, rvTo)
                }
            } else if (lastReply.isNotBlank()) {
                // 只是聊天（没动谱面）：也要写进历史，下次接着聊
                post { remember(false, lastReply); finishProgress() }
            } else {
                post { finishProgress() }
            }
            // 收尾：这一轮真的改了谱面就把"改动前"记成编辑器的一个撤销步
            // （同一个撤销栈：编辑页按钮和抽屉「撤回」都能回退）；没改 / 被撤回打断就丢弃。
            post { hooks.finishUndo(changedChart && !c.cancelled) }
        } catch (e: Exception) {
            post {
                bubble.text = "AI：出错了：" + (e.message ?: e.javaClass.simpleName)
            }
        } finally {
            busy = false
            cancel = null
            post {
                hooks.status("")
                // 兜底：无论正常结束、出错还是取消，进度条都收尾（满格留着，不会一直转）
                finishProgress()
            }
        }
    }

    private var lastApplyAt = 0L
    private fun now(): Long = android.os.SystemClock.elapsedRealtime()

    // ---------------------------------------------------------------- 进度条
    // 用户点名：
    //   1.「ai操作时在ai发的消息气泡下方加一个进度条」；
    //   2.「进度条右侧百分比不需要，直接进度条和气泡等长」；
    //   3.「进度条走到头就消失，只保留中间的那个动画」。
    // ⇒ 只有**正在干活**的那条回复挂进度条（宽 = 气泡宽，没有百分比文字）：
    //   干活时是中间那块来回滑的动画，写整首时按「最后一个音符的时间 / 曲长」推进，
    //   走完就填满并**把整条撤掉**（不再留在气泡下面）。
    private var progRow: LinearLayout? = null
    private var progBar: android.widget.ProgressBar? = null
    private var progLastAt = 0L
    private var progScan = 0
    private var progMaxMs = 0L

    /** 气泡下面那条进度条（宽高跟气泡完全一致：同一套左右 4dp 边距、零内边距） */
    private fun makeBar(): LinearLayout {
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val tint = android.content.res.ColorStateList.valueOf(0xFF00E5FF.toInt())
        val pb = android.widget.ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            isIndeterminate = true              // 只保留"中间那块动画"
            indeterminateTintList = tint
            progressTintList = tint
            backgroundTintList = android.content.res.ColorStateList.valueOf(0x22FFFFFF)
        }
        row.addView(
            pb,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6))
        )
        row.tag = pb
        return row
    }

    /** 把进度条插在气泡正下方（边距和气泡一致 ⇒ 两者等长） */
    private fun insertBarAfter(bubble: TextView, row: LinearLayout) {
        val idx = col.indexOfChild(bubble)
        col.addView(
            row, if (idx >= 0) idx + 1 else -1,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(dp(4), 0, dp(4), dp(6)) }
        )
    }

    /** [p] 有值 = 确定进度 0..1；null = 不确定态 */
    private fun setProgress(p: Float?) {
        val pb = progBar ?: return
        if (p == null) {
            if (!pb.isIndeterminate) pb.isIndeterminate = true
            return
        }
        val v = (p.coerceIn(0f, 1f) * pb.max).toInt()
        if (pb.isIndeterminate) pb.isIndeterminate = false
        pb.progress = v
    }

    private fun detachProgress() {
        progRow = null
        progBar = null
    }

    /** 收尾：走到头 → 消失（用户点名「进度条走到头就消失」） */
    private fun finishProgress() {
        val pb = progBar ?: return
        val row = progRow
        pb.isIndeterminate = false
        pb.progress = pb.max
        detachProgress()
        if (row != null) {
            handler.postDelayed({ if (row.parent === col) col.removeView(row) }, 220)
        }
    }

    /** 写整首时：把新追加的音符扫一遍，取最大时间当进度（只扫新增的那段，O(增量)） */
    private fun tickWriteProgress(h: AiChart.Holder, durMs: Long) {
        if (durMs <= 1000L) return
        var i = progScan
        val list = h.notes
        while (i < list.size) {
            val t = list[i].startMs
            if (t > progMaxMs) progMaxMs = t
            i++
        }
        progScan = i
        val t = now()
        if (t - progLastAt < 220) return
        progLastAt = t
        val p = (progMaxMs.toFloat() / durMs.toFloat())
        handler.post { setProgress(p) }
    }

    private fun levelValue(): Double {
        val s = hooks.level()
        val m = Regex("\\d+(\\.\\d+)?").find(s) ?: return 8.0
        return (m.value.toDoubleOrNull() ?: 8.0).coerceIn(0.0, 20.0)
    }

    private fun loadBeats(c: AiClient.Cancel): List<AudioBeats.Beat> {
        beatsCache?.let { return it }
        val uri = hooks.musicUri()
        if (uri == Uri.EMPTY) return emptyList()
        val r = AudioBeats.analyze(act, uri, isCancelled = { c.cancelled }) ?: return emptyList()
        beatsCache = r.beats
        return r.beats
    }

    // ---------------------------------------------------------------- BPM
    private fun bpmKeyword(text: String): Boolean = AiIntent.bpmKeyword(text)

    /**
     * 跑一次本地 BPM 检测。要不要写回项目只看 AI 给的 `apply` 字段——
     * 执行什么操作由 AI 判断，本地不按用户句子里的关键词去"猜"。
     */
    private fun runBpm(cmd: JSONObject?): String? {
        val uri = hooks.musicUri()
        if (uri == Uri.EMPTY) return null
        val r = BpmDetect.detect(act, uri) ?: return null
        val apply = cmd?.optInt("apply", 0) == 1
        handler.post {
            val label = "检测完成：" + r.shortText() + "，置信度 " + r.confidenceText()
            remember(false, label)
            addBubble(false, label)
            if (apply) {
                hooks.applyBpm(r.bpm, null)
                addBubble(false, "已应用：BPM " + r.bpmText())
                remember(false, "已应用：BPM " + r.bpmText())
            } else {
                addAction("一键应用 BPM " + r.bpmText()) {
                    hooks.applyBpm(r.bpm, null)
                    hooks.status("已应用 BPM " + r.bpmText())
                    addBubble(false, "已应用：BPM " + r.bpmText())
                    remember(false, "已应用：BPM " + r.bpmText())
                }
            }
        }
        return "本地 BPM Detect 结果：BPM " + r.bpmText() + "，首个重拍 " + r.offsetText() +
            "，置信度 " + r.confidenceText() + (if (apply) "（已应用到项目）" else "（尚未应用）")
    }

    /** 本地命令（UI 线程执行） */
    private fun onUi(fn: () -> Unit) {
        if (act.isFinishing || act.isDestroyed) return
        (act as Activity).runOnUiThread { if (!act.isFinishing && !act.isDestroyed) fn() }
    }

    /**
     * 导出谱面：放到 UI 线程做（打包 + 落盘都是本地 IO，和右上角「导出谱面」按钮同一套），
     * 结果（含落盘位置）单独回一条系统气泡，AI 不用猜路径。
     */
    private fun doExport() = onUi {
        val msg = try {
            hooks.exportChart()
        } catch (e: Exception) {
            "导出失败：" + (e.message ?: e.javaClass.simpleName)
        }
        if (msg.isNotBlank()) {
            addBubble(false, msg)
            remember(false, msg)
        }
    }

    private fun runLocalCommand(cmd: JSONObject) {
        when (cmd.optString("cmd")) {
            "undo" -> onUi { undoLast() }
            "redo" -> onUi { redoLast() }
            // 导出谱面（.pez zip）：不弹保存框，直接落到「下载/FRPHI」
            "export" -> doExport()
            // 主界面那套命令 {"cmd":"project","action":"export"} 也认（同一个 AI、同一份聊天记录，
            // 模型经常照主界面的写法发）——以前编辑页不认，用户就得到"AI 导不出谱面"。
            "project" -> if (cmd.optString("action") == "export") doExport()
            "meta" -> {
                // 项目信息 11 个字段：想改哪个给哪个（不改的传 null）
                val name = if (cmd.has("name")) cmd.optString("name") else null
                val composer = if (cmd.has("composer")) cmd.optString("composer") else null
                val charter = if (cmd.has("charter")) cmd.optString("charter") else null
                val level = if (cmd.has("level")) cmd.optString("level") else null
                val bpm = if (cmd.has("bpm")) cmd.optDouble("bpm", hooks.bpm()) else null
                val off = if (cmd.has("bpmOffset")) cmd.optDouble("bpmOffset", hooks.bpmOffset()) else null
                val speed = if (cmd.has("speed")) cmd.optDouble("speed", 1.0) else null
                val linePos = if (cmd.has("linePos")) cmd.optInt("linePos", 60) else null
                val delay = if (cmd.has("delay")) cmd.optInt("delay", 0) else null
                val hLines = if (cmd.has("hLines")) cmd.optInt("hLines", 4) else null
                val vLines = if (cmd.has("vLines")) cmd.optInt("vLines", 8) else null
                onUi {
                    hooks.applyMeta(
                        name = name, composer = composer, charter = charter, level = level,
                        bpm = bpm, bpmOffset = off, speed = speed, linePos = linePos,
                        delay = delay, hLines = hLines, vLines = vLines
                    )
                    val parts = ArrayList<String>()
                    name?.let { parts.add("曲名「" + it + "」") }
                    composer?.let { parts.add("曲师「" + it + "」") }
                    charter?.let { parts.add("谱师「" + it + "」") }
                    level?.let { parts.add("难度「" + it + "」") }
                    bpm?.let { parts.add("BPM " + it) }
                    off?.let { parts.add("BPM偏移 " + it + "ms") }
                    speed?.let { parts.add("流速 " + it) }
                    linePos?.let { parts.add("判定线位置 " + it + "%") }
                    delay?.let { parts.add("延迟 " + it + "ms") }
                    hLines?.let { parts.add("横线 " + it) }
                    vLines?.let { parts.add("竖线 " + it) }
                    if (parts.isNotEmpty()) {
                        val msg = "已修改：" + parts.joinToString("，")
                        addBubble(false, msg)
                        remember(false, msg)
                    }
                }
            }
            "clear_chart" -> {
                if (!hooks.canEdit()) return
                // 只清音符：判定线 / 素材线 / 噪域 / 空图层声明原样留着（以前这里会把噪域一起丢掉）
                val empty = DraftCodec.encode(
                    AiChart.copyLines(hooks.lines()), emptyList(),
                    noise = hooks.noises(),
                    declaredLayers = hooks.declaredLayerValues(),
                    declaredNoiseLayers = hooks.declaredNoiseLayerValues(),
                    videos = hooks.videos()
                )
                onUi {
                    hooks.applyDraft(empty)     // 编辑器自己拍"改动前"快照
                    hooks.finishUndo(true)      // 清空谱面也能用同一个撤销按钮退回来
                }
            }
        }
    }

    // ---------------------------------------------------------------- 本地兜底（没接 AI）
    private fun localAnswer(text: String) {
        remember(true, text)
        addBubble(true, text)
        if (bpmKeyword(text)) {
            val bubble = addBubble(false, "未接入 AI：正在用本地 BPM Detect 分析音频…", bar = true)
            Thread {
                var r: BpmDetect.Result? = null
                try { if (hooks.musicUri() != Uri.EMPTY) r = BpmDetect.detect(act, hooks.musicUri()) } catch (_: Exception) {}
                onUi {
                    if (r == null) {
                        bubble.text = "AI：本地检测失败（这个项目还没选音乐，或音频解码不出来）。"
                        remember(false, "本地检测失败（没选音乐或音频解码不出来）")
                    } else {
                        val t = "检测完成：" + r.shortText() + "，置信度 " + r.confidenceText()
                        bubble.text = "AI：" + t
                        remember(false, t)
                        addAction("一键应用 BPM " + r.bpmText()) {
                            hooks.applyBpm(r.bpm, null)
                            hooks.status("已应用 BPM " + r.bpmText())
                        }
                    }
                    finishProgress()
                }
            }.start()
            return
        }
        if (chartIntent(text)) {
            val bubble = addBubble(false, "未接入 AI：用本地节拍引擎写谱…", bar = true)
            Thread {
                val uri = hooks.musicUri()
                val beats = if (uri != Uri.EMPTY) AudioBeats.analyze(act, uri)?.beats ?: emptyList() else emptyList()
                val dur = hooks.durationMs().coerceAtLeast(1L)
                val lineId = hooks.lines().firstOrNull()?.id ?: 0
                var notes = if (beats.isEmpty()) emptyList()
                else AutoChart.build(beats, levelValue(), lineId, dur, hooks.bpm())
                val spec = ChartStyle.parse(text)
                if (notes.isNotEmpty() && spec.any) notes = ChartStyle.enforce(notes, spec, dur, hooks.bpm(), lineId)
                if (notes.isEmpty()) {
                    onUi {
                        bubble.text = "AI：本地引擎没拿到节拍（没选音乐或音频解码不出来）。配置 AI 接口后可以让我按描述写谱。"
                        finishProgress()
                    }
                    return@Thread
                }
                val draft = DraftCodec.encode(
                    AiChart.copyLines(hooks.lines()), notes,
                    noise = hooks.noises(),
                    declaredLayers = hooks.declaredLayerValues(),
                    declaredNoiseLayers = hooks.declaredNoiseLayerValues()
                )
                onUi {
                    hooks.applyDraft(draft)
                    hooks.finishUndo(true)     // 记成编辑器的撤销步（和手改共用一条历史）
                    val t = "已用本地节拍引擎写入 " + notes.size + " 个音符" +
                        (if (spec.any) "（" + spec.describe() + "）" else "")
                    bubble.text = "AI：" + t
                    remember(false, t)
                    finishProgress()
                }
            }.start()
            return
        }
        val hit = AiKnowledge.search(text)
        val reply = if (hit != null) "（未接入 AI，以下是内置知识库里最相关的内容）\n" + hit
        else "（未接入 AI）没找到相关内容。长按主界面 AI 按钮配置接口后就能自然语言对话、让我写谱、查 BPM。"
        addBubble(false, reply)
        remember(false, reply)
    }

    // ------------------------------------------------ 撤回 / 重做（= 编辑页那个撤销按钮）
    /**
     * 用户点名：「撤回」按钮和编辑页的撤销**是同一个功能**——所以这里不再维护自己的一套
     * 草稿栈，直接驱动 [Hooks.undoChart]（同一个 RecordView 撤销栈，200 步、跟手改的步骤混在一起）。
     * 长按 = 重做（[redoLast]），和编辑页「长按撤销 = 重做」完全一致。
     */
    private fun undoLast() {
        cancel?.cancelled = true
        if (hooks.undoChart()) {
            hooks.status("已撤销")
            addBubble(false, "已撤销一步（长按「撤回」= 重做）")
        } else {
            hooks.status("没有可撤销的操作")
            addBubble(false, "没有可撤销的操作")
        }
    }

    private fun redoLast() {
        cancel?.cancelled = true
        if (hooks.redoChart()) {
            hooks.status("已重做")
            addBubble(false, "已重做一步")
        } else {
            hooks.status("没有可重做的操作")
            addBubble(false, "没有可重做的操作")
        }
    }

    // ---------------------------------------------------------------- 文本 / JSON 拆分
    /** 去掉回复里的 JSON 行，只保留人话（流式刷新气泡用） */
    private fun stripJson(full: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < full.length) {
            if (full[i] != '{') { sb.append(full[i]); i++; continue }
            var depth = 0
            var inStr = false
            var esc = false
            var j = i
            var end = -1
            while (j < full.length) {
                val ch = full[j]
                if (inStr) {
                    if (esc) esc = false
                    else if (ch == '\\') esc = true
                    else if (ch == '"') inStr = false
                } else when (ch) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) { end = j; break } }
                }
                j++
            }
            if (end < 0) break
            i = end + 1
        }
        return sb.toString().trim()
    }

    private fun splitCommands(full: String): Pair<String, List<JSONObject>> {
        val sb = StringBuilder()
        val cmds = ArrayList<JSONObject>()
        var i = 0
        while (i < full.length) {
            if (full[i] != '{') { sb.append(full[i]); i++; continue }
            var depth = 0
            var inStr = false
            var esc = false
            var j = i
            var end = -1
            while (j < full.length) {
                val ch = full[j]
                if (inStr) {
                    if (esc) esc = false
                    else if (ch == '\\') esc = true
                    else if (ch == '"') inStr = false
                } else when (ch) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) { end = j; break } }
                }
                j++
            }
            if (end < 0) break
            val obj = full.substring(i, end + 1)
            val o = try { JSONObject(obj) } catch (_: Exception) { null }
            if (o != null && o.has("cmd")) cmds.add(o) else sb.append(obj)
            i = end + 1
        }
        return sb.toString() to cmds
    }
}
