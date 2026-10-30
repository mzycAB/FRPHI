package com.piezmaker

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** 一个项目标签的数据（含全部输入与素材 URI），支持 JSON 持久化 */
private class TabData {
    var id: String = java.util.UUID.randomUUID().toString()
    var name = ""
    var composer = ""
    var charter = ""
    var level = ""
    var bpm = "120"
    var speed = "1.0"
    var linePos = "60"
    var delay = "0"
    /** BPM 偏移（毫秒，正数 = 谱面整体比音乐延后；和 Phiedit 的"bpm偏移"一致） */
    var bpmOffset = "0"
    var hLines = "4"
    var vLines = "8"
    var music: String? = null
    var image: String? = null
    /** 联机角色：null=普通项目 "host"=服务端副本（蓝框） "client"=客户端房间（绿框，不落盘） */
    var roomRole: String? = null
    /** 服务端副本对应的原始项目 id（"退出保存至项目"要用） */
    var roomSource: String? = null
    /** 最后一次修改时间（草稿文件 mtime 优先，这里兜底） */
    var lastMod: Long = 0L

    fun toJson(): org.json.JSONObject {
        val o = org.json.JSONObject()
        o.put("id", id); o.put("name", name); o.put("composer", composer); o.put("charter", charter)
        o.put("level", level); o.put("bpm", bpm); o.put("speed", speed); o.put("linePos", linePos)
        o.put("delay", delay); o.put("hLines", hLines); o.put("vLines", vLines)
        o.put("bpmOffset", bpmOffset)
        music?.let { o.put("music", it) }; image?.let { o.put("image", it) }
        o.put("lastMod", lastMod)
        roomRole?.let { o.put("roomRole", it) }
        roomSource?.let { o.put("roomSource", it) }
        return o
    }

        companion object {
        fun fromJson(o: org.json.JSONObject): TabData {
            val t = TabData()
            // 空 id 不能直接用：会和别的标签共用 drafts/ 与 imported/ 下的同一路径
            val savedId = o.optString("id", "")
            if (savedId.isNotBlank()) t.id = savedId
            t.name = o.optString("name", ""); t.composer = o.optString("composer", ""); t.charter = o.optString("charter", "")
            t.level = o.optString("level", ""); t.bpm = o.optString("bpm", "120"); t.speed = o.optString("speed", "1.0")
            t.linePos = o.optString("linePos", "60"); t.delay = o.optString("delay", "0")
            t.bpmOffset = o.optString("bpmOffset", "0")
            t.hLines = o.optString("hLines", "4"); t.vLines = o.optString("vLines", "8")
            if (o.has("music")) t.music = o.optString("music")
            if (o.has("image")) t.image = o.optString("image")
            t.lastMod = o.optLong("lastMod", 0L)
            if (o.has("roomRole")) t.roomRole = o.optString("roomRole")
            if (o.has("roomSource")) t.roomSource = o.optString("roomSource")
            return t
        }
    }
}

/** 设置页：标签栏多项目 + 选素材填信息（可全部留空直接进入编辑） */
class MainActivity : Activity() {

    private companion object {
        /** 客户端「找房间 / 连房间」窗口（秒）：到点就结束，再按一次也结束 */
        const val JOIN_WINDOW_SEC = 60
        const val REQ_MUSIC = 1001
        const val REQ_IMAGE = 1002
        const val REQ_CALIB = 1003
        const val REQ_IMPORT = 1004
        const val REQ_EXPORT_TAB = 3001
        const val REQ_MAIN_LOG = 3002
        const val REQ_RESPACK = 3003
    }

    private lateinit var tabBar: LinearLayout
    // 左侧灰条 + 项目抽屉
    private lateinit var sideBar: View
    private lateinit var drawerScrim: View
    private lateinit var drawerPanel: LinearLayout
    private lateinit var drawerList: LinearLayout
    private var drawerOpen = false
    /** 主界面右滑导出的待写内容 */
    private class PendExport(val meta: ChartMeta, val lines: List<Line>, val notes: List<RecordedNote>)
    private var pendingExport: PendExport? = null
    /** 项目缩略图缓存：按访问顺序的 LRU（最多 24 个，避免项目一多就把内存占满） */
    private val drawerThumbs = object : java.util.LinkedHashMap<String, android.graphics.Bitmap?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap?>?): Boolean =
            size > 24
    }
    private val drawerItems = ArrayList<View>()
    private var drawerDragTo = -1
    // 项目单击框选 / 右侧抽屉
    private var framedTab: String? = null
    private var rightOpen = false
    private lateinit var rightPanel: ScrollView          // 外层：负责滑出/滑入动画
    private lateinit var rightPanelBody: LinearLayout    // 内层：真正的内容容器
    private lateinit var drawerDivider: View
    private lateinit var drawerScroll: ScrollView
    private lateinit var editName: EditText
    private lateinit var editComposer: EditText
    private lateinit var editCharter: EditText
    private lateinit var editLevel: EditText
    private lateinit var editBpm: EditText
    private lateinit var editSpeed: EditText
    private lateinit var editLinePos: EditText
    private lateinit var editDelay: EditText
    private lateinit var editBpmOffset: EditText
    private lateinit var editHLines: EditText
    private lateinit var editVLines: EditText
    private lateinit var txtMusic: TextView
    private lateinit var txtImage: TextView
    private lateinit var txtLastMod: TextView
    // 联机面板
    private var coopPw = ""
    private var coopLen = 4                  // 密码位数（可调 1~8）
    private var coopPanelMode = "host"       // "host"=从谱子进来（只能服务端） "client"=从＋进来（只能客户端）
    private var coopLenView: TextView? = null
    private var coopPwView: TextView? = null
    private var coopStatus: TextView? = null
    private var coopIpBox: EditText? = null
    private var coopPortBox: EditText? = null
    private var quickHostBtn: TextView? = null
    private var clientJoinBtn: TextView? = null
    private var quickJoinBtn: TextView? = null
    private var coopPlayersBox: LinearLayout? = null
    // 快速服务端倒计时
    private var quickHosting = false
    private var quickLeft = 0
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    // AI 面板（聊天记录长期保存在本地，右上角可清空）
    private var aiChatMessages = mutableListOf<Pair<Boolean, String>>()   // true=我 false=AI
    private var aiChatBusy = false
    private var aiChatCancel: AiClient.Cancel? = null
    private var aiChatCol: LinearLayout? = null
    /** 聊天记录是否已经从本地读过（避免每次重建面板都重复读盘） */
    private var aiChatLoaded = false
    /** 会话代数：清空记录时 +1，异步回复回来时据此判断要不要丢弃 */
    private var aiChatGen = 0
    /** 悬浮在右侧抽屉底部的输入条（键盘弹出时跟着上移） */
    private var aiBarView: LinearLayout? = null
    /** 悬浮在右侧抽屉右上角的「撤回 / 清空」（不随聊天记录滚动） */
    private var aiHeaderView: LinearLayout? = null
    /** AI 代理操作的撤回栈（每项 = 说明 + 逆向操作） */
    private val agentUndo = ArrayDeque<Pair<String, () -> Unit>>()

    private val tabs = mutableListOf<TabData>()
    private var current = 0
    /** 与 tabs 一一对应的标签控件（用来就地刷新标签名） */
    private val tabChips = ArrayList<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyFullscreen()
        setContentView(R.layout.activity_main)

        tabBar = findViewById(R.id.tabBar)
        sideBar = findViewById(R.id.sideBar)
        drawerScrim = findViewById(R.id.drawerScrim)
        drawerPanel = findViewById(R.id.drawerPanel)
        drawerList = findViewById(R.id.drawerList)
        drawerScroll = findViewById(R.id.drawerScroll)
        rightPanel = findViewById(R.id.rightPanel)
        rightPanelBody = findViewById(R.id.rightPanelBody)
        drawerDivider = findViewById(R.id.drawerDivider)
        editName = findViewById(R.id.editName)
        editComposer = findViewById(R.id.editComposer)
        editCharter = findViewById(R.id.editCharter)
        editLevel = findViewById(R.id.editLevel)
        editBpm = findViewById(R.id.editBpm)
        editSpeed = findViewById(R.id.editSpeed)
        editLinePos = findViewById(R.id.editLinePos)
        editDelay = findViewById(R.id.editDelay)
        editBpmOffset = findViewById(R.id.editBpmOffset)
        editHLines = findViewById(R.id.editHLines)
        editVLines = findViewById(R.id.editVLines)
        txtMusic = findViewById(R.id.txtMusic)
        txtImage = findViewById(R.id.txtImage)
        txtLastMod = findViewById(R.id.txtLastMod)
        Coop.appContext = applicationContext
        // 上次被划掉时可能留下房间媒体（客户端本地不该留东西），启动时清掉
        if (!Coop.isInRoom()) {
            try { File(filesDir, "roommedia").deleteRecursively() } catch (_: Exception) {}
        }
        bindCoopCallbacks()

        findViewById<View>(R.id.btnPickMusic).setOnClickListener {
            pendingTabId = tabs.getOrNull(current)?.id
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(i, REQ_MUSIC)
        }
        findViewById<View>(R.id.btnPickImage).setOnClickListener {
            pendingTabId = tabs.getOrNull(current)?.id
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            startActivityForResult(i, REQ_IMAGE)
        }
        findViewById<View>(R.id.btnCalibrate).setOnClickListener {
            startActivityForResult(Intent(this, CalibrationActivity::class.java), REQ_CALIB)
        }
        findViewById<View>(R.id.btnStart).setOnClickListener { startRecord() }
        findViewById<View>(R.id.btnImport).setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(i, REQ_IMPORT)
        }

        // 只有第一次用（没有存档）才自动建一个项目；用户把标签全删了要能保持空着
        val hadSaved = loadTabs()
        if (!hadSaved) tabs.add(TabData())
        cleanupCaches()                  // 启动时清掉临时产物与已删项目留下的孤儿缓存
        if (tabs.isEmpty()) clearInputs() else restore(tabs[current])
        renderTabs()
        setupProjectDrawer()
        applySideBarTouchArea()
        // 灰条判定区跟随"触摸兼容"：编辑页切换后回到主界面要生效
        findViewById<View>(R.id.sideBarTouch).post { applySideBarTouchArea() }
        maybeShowPrivacy()
    }

    /** 首次启动必须先同意隐私政策；同意后记录在本地，不再重复弹出 */
    private fun maybeShowPrivacy() {
        if (getSharedPreferences("frphi_privacy", 0).getBoolean("accepted", false)) return
        showPrivacyDialog(true)
    }

    /** 安卓原生风格隐私政策弹窗：首次启动=同意/不同意；从关于页查看=仅关闭 */
    private fun showPrivacyDialog(firstRun: Boolean) {
        val policy = try {
            assets.open("privacy_policy.txt").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "隐私政策读取失败：" + e.message
        }
        val tv = TextView(this).apply {
            text = policy
            textSize = 12f
            setTextColor(0xFF222222.toInt())
            setLineSpacing(dp(2).toFloat(), 1.2f)
            setPadding(dp(22), dp(12), dp(22), dp(12))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        val builder = AlertDialog.Builder(this)
            .setTitle("FRPHI 隐私政策")
            .setView(scroll)
            .setCancelable(!firstRun)
        if (firstRun) {
            builder.setPositiveButton("同意") { d, _ ->
                getSharedPreferences("frphi_privacy", 0).edit()
                    .putBoolean("accepted", true).apply()
                d.dismiss()
            }
            builder.setNegativeButton("不同意") { _, _ -> finishAffinity() }
        } else {
            builder.setPositiveButton("关闭", null)
        }
        val dialog = builder.create()
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.72f).toInt(),
            (resources.displayMetrics.heightPixels * 0.82f).toInt()
        )
    }

    /** 从 SharedPreferences 恢复标签列表（退出重进保留项目与草稿 id） */
    private fun loadTabs(): Boolean {
        try {
            val raw = getSharedPreferences("frphi_tabs", 0).getString("tabs", null) ?: return false
            val arr = org.json.JSONArray(raw)
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val t = TabData.fromJson(arr.getJSONObject(i))
                // id 是草稿/素材目录的唯一键：空或重复会导致两个标签共用同一份文件，
                // 删一个就把另一个的草稿和素材一起清掉。这里统一补成新的唯一 id。
                if (!seen.add(t.id)) t.id = java.util.UUID.randomUUID().toString()
                seen.add(t.id)
                tabs.add(t)
            }
            return true
        } catch (_: Exception) {
            return false
        }
    }

    /** 保存标签列表 */
    private fun saveTabs() {
        try {
            val arr = org.json.JSONArray()
            // 客户端房间项目不落盘（本地什么都不存）
            for (t in tabs) if (t.roomRole != "client") arr.put(t.toJson())
            getSharedPreferences("frphi_tabs", 0).edit().putString("tabs", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    /**
     * 清理不再需要的缓存（启动时跑一次，全部 best-effort）：
     *  1) cacheDir 里的一次性产物（导出 pez / 剪贴板 / 视频导出的音频 / 校音 wav / 资源包解压临时目录）
     *  2) 项目已删除后剩下的草稿与素材目录
     *  3) 没有任何项目再引用的 AI 生成歌曲
     *  4) 内存缩略图缓存里已经不存在的项目
     */
    private fun cleanupCaches() {
        val ids = tabs.map { it.id }.toHashSet()
        try {
            for (n in listOf("pez", "respack_tmp")) File(cacheDir, n).deleteRecursively()
            for (n in listOf("export.pez", "clip.frclip", "imported_audio.wav", "calibration.wav")) {
                File(cacheDir, n).delete()
            }
        } catch (_: Exception) {}
        try {
            File(filesDir, "drafts").listFiles()?.forEach { f ->
                if (f.name.endsWith(".json") && f.name.removeSuffix(".json") !in ids) f.delete()
            }
        } catch (_: Exception) {}
        try {
            File(filesDir, "imported").listFiles()?.forEach { f ->
                if (f.isDirectory && f.name !in ids) f.deleteRecursively()
            }
        } catch (_: Exception) {}
        try {
            val refs = tabs.mapNotNull { it.music }.toHashSet()
            File(filesDir, "music").listFiles()?.forEach { f ->
                if (f.name.startsWith("ai_song_") && android.net.Uri.fromFile(f).toString() !in refs) f.delete()
            }
        } catch (_: Exception) {}
        drawerThumbs.keys.toList().forEach { if (it !in ids) drawerThumbs.remove(it) }
    }

    // ---------------- 标签栏 ----------------
    /** 标签栏（浏览器式）：显示项目标签 + ＋ 新建；点标签切换、长按标签删除 */
    // ---------------------------------------------------------------- 左侧项目抽屉
    private fun setupProjectDrawer() {
        // 点背景 / 点别的项目 = 一级一级关：① 右抽屉 ② 取消框选（也算一级）③ 收左抽屉
        drawerScrim.setOnClickListener { if (!dismissOneLevel()) openDrawer(false) }
        // 点抽屉里的空白处也按"一级一级关"来（右抽屉 → 取消框选 → 收抽屉）
        drawerPanel.setOnClickListener { if (!dismissOneLevel()) openDrawer(false) }
        drawerScroll.setOnClickListener { if (!dismissOneLevel()) openDrawer(false) }
        buildCoopPanel()
        // 灰条：按住上下滑 → 调出抽屉；松手也算（点一下也能开）
        // 判定区域默认就是灰条本身（左右各放宽一点点）；「触摸兼容」开启时再明显放大，
        // 和编辑页里的触摸兼容是同一个开关（frphi_rec 的 compatMode）。
        findViewById<View>(R.id.sideBarTouch).setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { drawerBarDownY = ev.rawY; drawerBarPressed = true; true }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!drawerOpen && Math.abs(ev.rawY - drawerBarDownY) > dp(6)) openDrawer(true)
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (drawerBarPressed && !drawerOpen) openDrawer(true)
                    drawerBarPressed = false
                    true
                }
                else -> false
            }
        }
        // 先摆到屏幕左侧外面，打开时才有滑入动画
        drawerPanel.post { if (!drawerOpen) drawerPanel.translationX = -(drawerPanel.width + dp(20)).toFloat() }
        openDrawer(false)
    }

    private var drawerBarDownY = 0f
    private var drawerBarPressed = false

    /** 灰条判定区：默认只有灰条本身那么大，触摸兼容开启时放大（跟随编辑页的那个开关） */
    private fun applySideBarTouchArea() {
        val v = findViewById<View>(R.id.sideBarTouch) ?: return
        val compat = try {
            getSharedPreferences("frphi_rec", 0).getInt("compatMode", 0) >= 1
        } catch (_: Exception) { false }
        val lp = v.layoutParams ?: return
        lp.width = dp(if (compat) 36 else 12)
        lp.height = dp(if (compat) 324 else 264)
        v.layoutParams = lp
    }

    private fun openDrawer(open: Boolean) {
        if (drawerOpen == open) return
        drawerOpen = open
        if (open) {
            renderProjectDrawer()
            drawerScrim.visibility = View.VISIBLE
            drawerPanel.visibility = View.VISIBLE
            if (drawerPanel.translationX == 0f) drawerPanel.translationX = -(drawerPanel.width + dp(20)).toFloat()
            drawerPanel.animate().translationX(0f).setDuration(170).start()
            drawerSideBarFade(true)
        } else {
            closeRightPanel()
            framedTab = null
            drawerScrim.visibility = View.GONE
            drawerPanel.animate().translationX(-drawerPanel.width.toFloat() - dp(20)).setDuration(160)
                .withEndAction { drawerPanel.visibility = View.GONE }.start()
            drawerSideBarFade(false)
        }
    }

    /**
     * 主界面抽屉"点别处"的一级：① 右抽屉开着 → 只关右抽屉；② 有红框 → 只取消框选（也算一级）；
     * ③ 都没有 → 返回 false，由调用方去收左抽屉。
     * 返回 true = 这次点击已经被"关一层"吃掉了（不再切项目/不再做别的）。
     */
    private fun dismissOneLevel(): Boolean = when {
        rightOpen -> { closeRightPanel(); true }
        // 蓝框（房间副本）还在时红框不能消失 —— 两个是绑定的，跳过这一级
        framedTab != null && framedTab != boundSourceId() -> {
            framedTab = null; renderProjectDrawer(); true
        }
        else -> false
    }

    /** 右侧抽屉的实际宽度：GONE 的 View 还没测量时 getWidth() 是 0，用 XML 里的 304dp 兜底 */
    private fun rightPanelW(): Float =
        if (rightPanel.width > 0) rightPanel.width.toFloat() else dp(304).toFloat()

    /** 打开右侧抽屉（宽度 = 左抽屉 2 倍，紧贴左抽屉右边，中间黑线分隔） */
    private fun openRightPanel() {
        // 按 coopPanelMode 重建：host=服务端 / client=客户端 / about=版权声明 / respack=资源包
        aiBarView?.visibility = View.GONE
        aiHeaderView?.visibility = View.GONE
        when (coopPanelMode) {
            "about" -> buildAboutPanel()
            "respack" -> buildRespackPanel()
            "aiChat" -> buildAiChatPanel()
            "aiSettings" -> buildAiSettingsPanel()
            else -> buildCoopPanel()
        }
        val leftW = drawerPanel.width.toFloat()
        val target = leftW + dp(2)
        drawerDivider.translationX = leftW          // 黑线紧贴左抽屉右边
        drawerDivider.visibility = View.VISIBLE
        rightPanel.visibility = View.VISIBLE
        // 收起过（GONE）之后 getWidth() = 0，直接用 width 算"收起位置"会得到 target 本身
        // → 起始位置 = 终点位置，看起来就是"瞬间出现、没有滑出动画"。所以必须用兜底宽度。
        val w = rightPanelW()
        rightPanel.animate().cancel()
        rightPanel.translationX = target - w        // 先摆在左抽屉背后（收起）
        rightOpen = true
        rightPanel.alpha = 1f
        rightPanel.animate().translationX(target).setDuration(170).start()
        renderProjectDrawer()
    }

    private fun closeRightPanel() {
        if (!rightOpen) return
        rightOpen = false
        aiBarView?.visibility = View.GONE
        aiHeaderView?.visibility = View.GONE
        val leftW = drawerPanel.width.toFloat()
        // 收回时只滑到左抽屉右边（它的右边缘正好贴着左抽屉），同时淡出，避免从左抽屉的
        // 半透明背景里透出来
        rightPanel.animate()
            .translationX(leftW + dp(2) - rightPanelW())
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                rightPanel.visibility = View.GONE
                rightPanel.alpha = 1f
                drawerDivider.visibility = View.GONE
            }.start()
    }

    private fun drawerSideBarFade(open: Boolean) {
        sideBar.animate().alpha(if (open) 0f else 1f).setDuration(120).start()
    }

    /** 缩略图（背景图）；没有就画一个占位色块 + 首字 */
    private fun thumbFor(t: TabData): android.graphics.Bitmap? {
        val key = t.id
        if (drawerThumbs.containsKey(key)) return drawerThumbs[key]
        var bmp: android.graphics.Bitmap? = null
        val uriStr = t.image
        if (!uriStr.isNullOrEmpty()) {
            try {
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                contentResolver.openInputStream(android.net.Uri.parse(uriStr))?.use { ins ->
                    bmp = android.graphics.BitmapFactory.decodeStream(ins, null, opts)
                }
            } catch (_: Exception) { bmp = null }
        }
        if (bmp == null) {
            val size = dp(120)
            val b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(b)
            c.drawColor(0xFF3A4150.toInt())
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt(); textSize = size * 0.42f; textAlign = android.graphics.Paint.Align.CENTER
            }
            val label = t.name.ifEmpty { "?" }.take(1)
            val fm = p.fontMetrics
            c.drawText(label, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, p)
            bmp = b
        }
        drawerThumbs[key] = bmp
        return bmp
    }

    private fun renderProjectDrawer() {
        drawerList.removeAllViews()
        drawerItems.clear()
        // 最上面：和"没设置图片的项目"一样的占位块，只有一个「+」，点开联机（只作为客户端）
        run {
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.setPadding(dp(4), dp(6), dp(4), dp(8))     // 和普通项目项一致 → 宽度也一致
            val plus = TextView(this)
            plus.text = "+"
            plus.gravity = android.view.Gravity.CENTER
            plus.setTextColor(0x66FFFFFF.toInt())
            plus.textSize = 28f
            plus.includeFontPadding = false
            plus.setBackgroundColor(0x22FFFFFF)             // 没设图片的项目就是这块底色（无圆角）
            plus.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)   // 高度 = 项目图片的一半
            )
            item.addView(plus)
            item.setOnClickListener {
                coopPanelMode = "client"
                openRightPanel()
            }
            item.setOnLongClickListener {
                coopPanelMode = "respack"
                openRightPanel()
                true
            }
            drawerList.addView(item)
        }
        // 「联机」下面：同款占位块，写着「日志」，点一下导出全部日志；长按看版权声明
        run {
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.setPadding(dp(4), 0, dp(4), dp(8))
            val tv = TextView(this)
            tv.text = "日志"
            tv.gravity = android.view.Gravity.CENTER
            tv.setTextColor(0xAAFFFFFF.toInt())
            tv.textSize = 13f
            tv.includeFontPadding = false
            tv.setBackgroundColor(0x22FFFFFF)
            tv.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)   // 高度 = 项目图片的一半
            )
            item.addView(tv)
            item.setOnClickListener { exportAllLogs() }
            item.setOnLongClickListener {
                coopPanelMode = "about"
                openRightPanel()
                true
            }
            drawerList.addView(item)
        }
        if (tabs.isEmpty()) {
            val tv = TextView(this)
            tv.text = "还没有项目"
            tv.setTextColor(0x99FFFFFF.toInt())
            tv.textSize = 12f
            drawerList.addView(tv)
            addAiDrawerItem()
            return
        }
        for ((i, t) in tabs.withIndex()) {
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.setPadding(dp(4), dp(6), dp(4), dp(8))
            val name = TextView(this)
            name.text = t.name.ifEmpty { "未命名" }
            name.setTextColor(if (i == current) 0xFF00E5FF.toInt() else 0xFFFFFFFF.toInt())
            name.textSize = 13f
            name.maxLines = 1
            name.ellipsize = android.text.TextUtils.TruncateAt.END
            item.addView(name)
            // 框的颜色：蓝=服务端副本（始终蓝框）/ 绿=客户端加入的房间 / 红=被框选
            val frameColor = when {
                t.roomRole == "host" -> FRAME_BLUE
                t.roomRole == "client" -> FRAME_GREEN
                // 蓝框副本绑定的那个项目：红框一直留着（两个框是一对的）
                t.id == framedTab || t.id == boundSourceId() -> 0xFFFF5252.toInt()
                else -> 0
            }
            if (frameColor != 0) {
                val gd = android.graphics.drawable.GradientDrawable()
                gd.setColor((frameColor and 0x00FFFFFF) or 0x22000000)
                gd.setStroke(dp(2), frameColor)
                gd.cornerRadius = dp(8).toFloat()
                item.background = gd
            }
            if (t.roomRole != null) {
                val sub = TextView(this)
                sub.text = if (t.roomRole == "host") "联机 · 服务端副本" else "联机 · 已加入房间"
                sub.setTextColor(if (t.roomRole == "host") 0xFF7FB6FF.toInt() else 0xFF7CFF9B.toInt())
                sub.textSize = 10f
                item.addView(sub)
            }
            val img = android.widget.ImageView(this)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76))
            lp.topMargin = dp(4)
            img.layoutParams = lp
            img.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            img.setBackgroundColor(0x22FFFFFF)
            img.setImageBitmap(thumbFor(t))
            item.addView(img)
            drawerList.addView(item)
            drawerItems.add(item)
            attachDrawerItem(item, i)
        }
        addAiDrawerItem()
    }

    /** 项目列表最下方的「AI」按钮：点=聊天抽屉，长按=接口设置抽屉 */
    private fun addAiDrawerItem() {
        val item = LinearLayout(this)
        item.orientation = LinearLayout.VERTICAL
        item.setPadding(dp(4), dp(2), dp(4), dp(8))
        val tv = TextView(this)
        tv.text = "AI"
        tv.gravity = android.view.Gravity.CENTER
        tv.setTextColor(0xAAFFFFFF.toInt())
        tv.textSize = 13f
        tv.includeFontPadding = false
        tv.setBackgroundColor(0x22FFFFFF)
        tv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(38)
        )
        item.addView(tv)
        item.setOnClickListener {
            coopPanelMode = "aiChat"
            openRightPanel()
        }
        item.setOnLongClickListener {
            coopPanelMode = "aiSettings"
            openRightPanel()
            true
        }
        drawerList.addView(item)
    }

    /** 单项手势：点=切换项目；长按0.5s=试听；长按0.3s后上下拖=排序；左滑=删除 */
    /** 单项手势：点=切项目；长按0.5s后上下拖=排序；左滑=删除；右滑=导出 pez（阈值 80% 项宽） */
    private fun attachDrawerItem(item: View, index: Int) {
        var downRawX = 0f
        var downRawY = 0f
        var dragging = false
        var swiping = false
        var canDragY = false
        var armedLong = false          // 长按 0.5s 触发（松手时用来"框住"）
        var pending: Runnable? = null
        item.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    dragging = false
                    swiping = false
                    canDragY = false
                    armedLong = false
                    // 先不抢手势：竖滑交给外层 ScrollView（列表能滚）；长按到点才接管
                    pending = Runnable {
                        canDragY = true
                        armedLong = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        v.translationZ = dp(12).toFloat()      // 抬到最上层：否则拖最上面那个时缩略图会被下面的项盖住
                        v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(90).start()
                    }.also { v.postDelayed(it, 500) }
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!dragging && !swiping) {
                        if (Math.abs(dx) > dp(10) && Math.abs(dx) > Math.abs(dy)) {
                            swiping = true                        // 左右：秒拖
                            pending?.let { v.removeCallbacks(it) }
                        } else if (canDragY && Math.abs(dy) > dp(8)) {
                            dragging = true                       // 上下：长按之后才拖
                        }
                    }
                    if (dragging) {
                        v.translationY = dy
                        val newTo = (index + Math.round(dy / dp(96).toFloat())).coerceIn(0, tabs.lastIndex)
                        // 只有"插入位真的变了"才让其它项动，且用 130ms 动画让位（原来是瞬间贴过去）
                        if (newTo != drawerDragTo) {
                            drawerDragTo = newTo
                            val itemH = dp(96).toFloat()
                            for ((k, c) in drawerItems.withIndex()) {
                                if (c === v) continue
                                val target = when {
                                    index < drawerDragTo && k in (index + 1)..drawerDragTo -> -itemH
                                    index > drawerDragTo && k in drawerDragTo until index -> itemH
                                    else -> 0f
                                }
                                c.animate().translationY(target).setDuration(130).start()
                            }
                        }
                        true
                    }
                    else if (swiping) { v.translationX = dx; true }
                    else true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    pending?.let { v.removeCallbacks(it) }
                    val up = ev.actionMasked == android.view.MotionEvent.ACTION_UP
                    v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                    v.translationZ = 0f
                    val need = v.width.toFloat().coerceAtLeast(dp(80).toFloat()) * 0.6f
                    if (dragging) {
                        v.animate().translationY(0f).setDuration(120).start()
                        for (c in drawerItems) if (c !== v) c.animate().translationY(0f).setDuration(120).start()
                        if (up && drawerDragTo != index) {
                            val curId = tabs.getOrNull(current)?.id
                            val it2 = tabs.removeAt(index)
                            tabs.add(drawerDragTo.coerceIn(0, tabs.size), it2)
                            current = tabs.indexOfFirst { it.id == curId }.coerceAtLeast(0)
                            saveTabs(); renderTabs(); renderProjectDrawer()
                        }
                    } else if (swiping) {
                        val outL = v.translationX <= -need
                        val outR = v.translationX >= need
                        val roomRole = tabs.getOrNull(index)?.roomRole
                        if (roomRole != null) {
                            v.animate().translationX(0f).setDuration(120).start()
                            if (up && outL) {
                                // 左滑：蓝框 = 服务端退出弹窗；绿框 = 客户端退出弹窗
                                if (roomRole == "host") askExitHostRoom() else askExitClientRoom()
                            } else if (up && outR && roomRole == "client") {
                                // 右滑绿框：把服务端当前谱面拷一份到本地（放最后）
                                copyRoomChartToLocal()
                            } else if (up && outR && roomRole == "host") {
                                // 右滑蓝框：把副本内容同步回被红框框住的那个项目（要确认）
                                askSaveRoomCopyToSource()
                            }
                            return@setOnTouchListener true
                        }
                        if (up && outL) {
                            android.app.AlertDialog.Builder(this)
                                .setTitle("删除项目")
                                .setMessage("删除「" + tabs[index].name.ifEmpty { "未命名" } + "」？")
                                .setPositiveButton("删除") { _, _ -> deleteTab(index); renderProjectDrawer() }
                                .setNegativeButton("取消", null)
                                .show()
                        } else if (up && outR) {
                            // 右滑：原生弹窗（底部一排选项）选「复制 / 导出 .pez」
                            val t = tabs[index]
                            android.app.AlertDialog.Builder(this)
                                .setTitle("谱面操作")
                                .setMessage(t.name.ifEmpty { "未命名" })
                                .setNeutralButton("取消", null)
                                .setNegativeButton("复制") { _, _ -> duplicateTab(index) }
                                .setPositiveButton("导出 .pez") { _, _ ->
                                    if (!exportTabPez(t)) toast("缺少图片/音频或还没有草稿，无法导出")
                                }
                                .show()
                        }
                        v.animate().translationX(0f).setDuration(140).start()
                    } else if (up) {
                        val id = tabs[index].id
                        val room = tabs[index].roomRole
                        if (room != null) {
                            // 联机项目：点一下直接进编辑（客户端进的是服务端那份谱面）
                            openRecordAt(index)
                            openDrawer(false)
                            return@setOnTouchListener true
                        }
                        when {
                            armedLong -> {          // 长按 0.5s = 框住
                                framedTab = if (framedTab == id) null else id
                                renderProjectDrawer()
                            }
                            framedTab == id || id == boundSourceId() -> {   // 点已框住/绑定的 → 右侧抽屉（只作为服务端）
                                coopPanelMode = "host"
                                openRightPanel()
                            }
                            else -> {
                                // 点别的项目 = 和点背景一个意思：一级一级关（右抽屉 → 取消框选 → 收抽屉），
                                // 关掉一层就到此为止，不切项目
                                if (!dismissOneLevel()) {
                                    switchTab(index)              // 没东西可关 → 正常单击切项目
                                    openDrawer(false)
                                }
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** 复制一个项目（草稿一起复制），放在项目列表末尾 */
    private fun duplicateTab(index: Int) {
        val src = tabs.getOrNull(index) ?: return
        val t = TabData()
        t.name = src.name            // 复制的项目保持同名（用户要求）
        t.composer = src.composer
        t.charter = src.charter
        t.level = src.level
        t.bpm = src.bpm
        t.speed = src.speed
        t.linePos = src.linePos
        t.delay = src.delay
        t.bpmOffset = src.bpmOffset
        t.hLines = src.hLines
        t.vLines = src.vLines
        t.music = src.music
        t.image = src.image
        // 草稿复制一份（id 不同）
        try {
            val a = File(filesDir, "drafts/" + src.id + ".json")
            if (a.exists()) {
                val b = File(filesDir, "drafts/" + t.id + ".json")
                b.parentFile?.mkdirs()
                b.writeText(a.readText())
            }
        } catch (_: Exception) {}
        tabs.add(t)                      // 复制到列表末尾
        saveTabs()
        renderTabs()
        renderProjectDrawer()
    }

    /**
     * 标签列表插入/删除之后，按 **id** 把「当前项目」找回来。
     * 以前只改下标：在当前项目的上面插一个标签，current 就指到别的项目上，
     * 下一次 saveCurrent() 会把「界面上那一份」写进刚插进来的副本里
     * —— 表现就是"开的是 light 的房间，进房间看到的却是别的项目"，
     * 导出也会因为这个副本的素材被抹空而失败。
     */
    private fun rebindCurrent(prevId: String?) {
        if (prevId != null) {
            val i = tabs.indexOfFirst { it.id == prevId }
            if (i >= 0) current = i
        }
        if (tabs.isEmpty()) current = 0
        else current = current.coerceIn(0, tabs.size - 1)
    }

    /** 直接从项目导出 .pez（读草稿 + 设置）；条件不足返回 false */
    private fun exportTabPez(t: TabData): Boolean {
        // 缺什么写进联机日志：导出失败时能直接看出是素材没了还是根本没草稿
        fun no(why: String): Boolean {
            val nm = t.name.ifEmpty { "未命名" }
            Coop.log("导出「" + nm + "」失败：" + why)
            return false
        }
        val img = t.image ?: return no("这个项目没有背景图")
        val music = t.music ?: return no("这个项目没有音频")
        val draftFile = File(filesDir, "drafts/" + t.id + ".json")
        if (!draftFile.exists()) return no("还没有草稿（这个项目没进过编辑页）")
        val draft = DraftCodec.decode(draftFile.readText()) ?: return no("草稿文件读不出来")
        val meta = ChartMeta(
            name = t.name, composer = t.composer, charter = t.charter, level = t.level,
            bpm = t.bpm.toDoubleOrNull() ?: 120.0,
            speed = t.speed.toFloatOrNull()?.toDouble() ?: 1.0,
            linePosPct = t.linePos.toIntOrNull() ?: 60,
            delayMs = t.delay.toIntOrNull() ?: 0,
            hLines = t.hLines.toIntOrNull() ?: 4,
            vLines = t.vLines.toIntOrNull() ?: 8,
            musicUri = android.net.Uri.parse(music),
            imageUri = android.net.Uri.parse(img),
            tabId = t.id,
            bpmOffsetMs = t.bpmOffset.toIntOrNull() ?: 0
        )
        pendingExport = PendExport(meta, draft.lines, draft.notes)
        val safe = t.name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").ifEmpty { "FRPHI" }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, safe + ".pez")
        }
        startActivityForResult(intent, REQ_EXPORT_TAB)
        return true
    }

    // ---------------------------------------------------------------- 标签拖动排序
    private var dragChip: View? = null
    private var dragFrom = -1
    private var dragTo = -1
    private var dragBaseLeft = 0f
    private var dragDownRawX = 0f
    private var lastRawX = 0f
    private var dragActive = false
    private var dragEndedAt = 0L
    private var dragMoved = false
    private var dragDownAt = 0L
    private var dragPending: Runnable? = null

    /** 手指靠近标签栏左右边缘时持续滚动（越靠边越快） */
    private val dragAutoScroll = object : Runnable {
        override fun run() {
            if (!dragActive) return
            val sv = tabBar.parent as? android.widget.HorizontalScrollView
            if (sv != null) {
                val loc = IntArray(2)
                sv.getLocationOnScreen(loc)
                val left = loc[0].toFloat()
                val right = left + sv.width
                val edge = dp(64).toFloat()
                var dx = 0f
                if (lastRawX < left + edge) {
                    dx = -(((left + edge - lastRawX) / edge).coerceIn(0f, 1f) * dp(20))
                } else if (lastRawX > right - edge) {
                    dx = ((lastRawX - (right - edge)) / edge).coerceIn(0f, 1f) * dp(20)
                }
                if (dx != 0f) {
                    sv.scrollBy(dx.toInt(), 0)
                    updateDragTarget()
                }
            }
            tabBar.postDelayed(this, 16)
        }
    }

    /** 按当前手指位置算出"插到第几个"，并让其它标签让位（实时跟手） */
    private fun updateDragTarget() {
        val chip = dragChip ?: return
        if (chip.width == 0) return
        val curCenter = dragBaseLeft + chip.width / 2f + chip.translationX
        var to = 0
        for (c in tabChips) {
            if (c === chip) continue
            if (c.left + c.width / 2f < curCenter) to++
        }
        val newTo = to.coerceIn(0, tabChips.size - 1)
        if (newTo == dragTo) return                       // 插入位没变：不动其它标签
        dragTo = newTo
        val w = (chip.width + dp(6)).toFloat()
        for ((k, c) in tabChips.withIndex()) {
            if (c === chip) continue
            val target = when {
                dragFrom < dragTo && k in (dragFrom + 1)..dragTo -> -w
                dragFrom > dragTo && k in dragTo until dragFrom -> w
                else -> 0f
            }
            c.animate().translationX(target).setDuration(130).start()
        }
    }

    /** 给一个标签挂上"长按 0.26s 后左右拖 = 换位置"（松手没拖动 = 删除确认） */
    private fun attachDrag(chip: View, index: Int, label: String) {
        chip.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dragDownRawX = ev.rawX
                    lastRawX = ev.rawX
                    dragBaseLeft = v.left.toFloat()
                    dragFrom = index
                    dragTo = index
                    dragActive = false
                    dragMoved = false
                    dragDownAt = System.currentTimeMillis()
                    // 长按 0.5s 才进入拖动（放大 10%）；这期间的横滑交给标签栏滚动
                    dragPending = Runnable {
                        dragActive = true
                        dragChip = v
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(90).start()
                        tabBar.post(dragAutoScroll)
                    }.also { tabBar.postDelayed(it, 500) }
                    false          // 点击照常走
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    lastRawX = ev.rawX
                    val dx = ev.rawX - dragDownRawX
                    if (!dragActive) {
                        // 没进入拖动前横滑 = 滚动标签栏（取消长按）
                        if (Math.abs(dx) > dp(10)) {
                            dragPending?.let { tabBar.removeCallbacks(it); dragPending = null }
                        }
                        return@setOnTouchListener false
                    }
                    if (Math.abs(dx) > dp(2)) dragMoved = true
                    v.translationX = dx
                    updateDragTarget()
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    dragPending?.let { tabBar.removeCallbacks(it); dragPending = null }
                    if (!dragActive) {
                        // 没拖动：按得够久（>500ms）就当作"删除标签"
                        if (ev.actionMasked == android.view.MotionEvent.ACTION_UP &&
                            System.currentTimeMillis() - dragDownAt > 500
                        ) {
                            android.app.AlertDialog.Builder(this)
                                .setTitle("删除标签")
                                .setMessage("删除「$label」？该项目的配置将一并清除。")
                                .setPositiveButton("删除") { _, _ -> deleteTab(index) }
                                .setNegativeButton("取消", null)
                                .show()
                            return@setOnTouchListener true
                        }
                        return@setOnTouchListener false
                    }
                    dragActive = false
                    tabBar.removeCallbacks(dragAutoScroll)
                    dragEndedAt = System.currentTimeMillis()
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    val from = dragFrom
                    val to = dragTo
                    if (ev.actionMasked == android.view.MotionEvent.ACTION_UP && dragMoved && to != from) {
                        val item = tabs.removeAt(from)
                        tabs.add(to, item)
                        current = when {
                            current == from -> to
                            from < current && current <= to -> current - 1
                            to <= current && current < from -> current + 1
                            else -> current
                        }
                        saveTabs()
                        renderTabs()
                    } else {
                        v.animate().translationX(0f).setDuration(120).start()
                        for (c in tabChips) if (c !== v) c.animate().translationX(0f).setDuration(120).start()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun renderTabs() {
        tabBar.removeAllViews()
        tabChips.clear()
        for ((i, t) in tabs.withIndex()) {
            val chip = TextView(this)
            val label = t.name.ifEmpty { "未命名" }
            chip.text = label
            chip.setTextSize(14f)
            chip.setPadding(dp(14), dp(8), dp(14), dp(8))
            chip.setTextColor(if (i == current) 0xFF1E88E5.toInt() else 0xFF667085.toInt())
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(6)
            chip.layoutParams = lp
            chip.setOnClickListener {
                // 刚拖完的那一下不要当成点击
                if (System.currentTimeMillis() - dragEndedAt < 250) return@setOnClickListener
                switchTab(i)
            }
            attachDrag(chip, i, label)
            if (i == current) chip.setBackgroundResource(R.drawable.bg_btn_outline)
            tabChips.add(chip)
            tabBar.addView(chip)
        }
        // ＋ 新建标签
        val addBtn = TextView(this)
        addBtn.text = "＋"
        addBtn.setTextSize(18f)
        addBtn.setTextColor(0xFF1E88E5.toInt())
        addBtn.setPadding(dp(10), dp(6), dp(10), dp(6))
        addBtn.setOnClickListener {
            saveCurrent()
            tabs.add(TabData())
            current = tabs.lastIndex
            restore(tabs[current])
            saveTabs()
            renderTabs()
        }
        tabBar.addView(addBtn)
        // 标签多了之后自动滑到当前标签（标签栏外面套了 HorizontalScrollView）
        tabBar.post {
            val chip = tabChips.getOrNull(current) ?: return@post
            val sv = tabBar.parent as? android.widget.HorizontalScrollView ?: return@post
            val x = chip.left - (sv.width - chip.width) / 2
            sv.smoothScrollTo(x.coerceAtLeast(0), 0)
        }
    }

    /**
     * 删除第 i 个标签。
     * 顺序很重要：**先**把界面上正在编辑的内容存回当前标签，再移除、再切索引、再 restore。
     * （原来先 removeAt 再 saveCurrent，会把被删标签的输入框内容写进"顶上来"的那个标签，
     *   等于把别的项目覆盖掉——就是"删一个标签把另一个删了"的现象。）
     */
    private fun deleteTab(i: Int) {
        if (i !in tabs.indices) return
        val sizeBefore = tabs.size
        val removed = tabs[i]

        saveCurrent()                 // 界面上的编辑内容先落到当前标签
        val keepId = tabs.getOrNull(current)?.id
        tabs.removeAt(i)
        if (tabs.isEmpty()) {
            // 允许把标签删光：只剩标签栏里的「＋」占位，输入框恢复成空白项目
            current = 0
            clearInputs()
        } else {
            current = TabOps.afterDelete(sizeBefore, i, current)
            rebindCurrent(keepId)
            restore(tabs[current])    // 切到相邻标签，输入框随之刷新
        }
        saveTabs()
        renderTabs()
        purgeTabFiles(removed.id)     // 只清被删标签自己的草稿与导入素材
    }

    /** 一个标签都不剩时，把界面上的输入框/素材清空（像一个空白新项目） */
    private fun clearInputs() {
        editName.setText("")
        editComposer.setText("")
        editCharter.setText("")
        editLevel.setText("")
        editBpm.setText("120")
        editSpeed.setText("1.0")
        editLinePos.setText("60")
        editDelay.setText("0")
        editBpmOffset.setText("0")
        editHLines.setText("4")
        editVLines.setText("8")
        musicUri = null
        imageUri = null
        txtMusic.text = "音频：未选择（可不填）"
        txtImage.text = "背景：未选择（可不填）"
    }

    /** 删除标签时清掉它自己的草稿与导入素材（只动应用私有目录，且只针对该标签 id） */
    private fun purgeTabFiles(id: String) {
        // id 直接拼进路径：空 id 会变成 imported/ 目录本身（一删全没），带分隔符会跑到目录外
        if (!TabOps.isSafeId(id)) return
        try {
            File(filesDir, "drafts/$id.json").delete()
            File(filesDir, "imported/$id").deleteRecursively()
        } catch (_: Exception) {}
    }

    private fun switchTab(i: Int) {
        if (i !in tabs.indices || i == current) return
        saveCurrent()
        saveTabs()
        current = i
        restore(tabs[i])
        renderTabs()
    }

    private fun saveCurrent() {
        if (tabs.isEmpty() || current !in tabs.indices) return
        val t = tabs[current]
        t.name = editName.text.toString().trim()
        t.composer = editComposer.text.toString().trim()
        t.charter = editCharter.text.toString().trim()
        t.level = editLevel.text.toString().trim()
        t.bpm = editBpm.text.toString().trim()
        t.speed = editSpeed.text.toString().trim()
        t.linePos = editLinePos.text.toString().trim()
        t.delay = editDelay.text.toString().trim()
        t.bpmOffset = editBpmOffset.text.toString().trim()
        t.hLines = editHLines.text.toString().trim()
        t.vLines = editVLines.text.toString().trim()
        // 素材也以界面上的状态为准（选素材时可能已经切过标签）
        t.music = musicUri?.toString()
        t.image = imageUri?.toString()
        // 标签名改了立刻反映到标签栏，不用等切换
        if (current < tabChips.size) {
            val label = t.name.ifEmpty { "未命名" }
            if (tabChips[current].text.toString() != label) tabChips[current].text = label
        }
    }

    private fun restore(t: TabData) {
        editName.setText(t.name)
        editComposer.setText(t.composer)
        editCharter.setText(t.charter)
        editLevel.setText(t.level)
        editBpm.setText(t.bpm.ifEmpty { "120" })
        editSpeed.setText(t.speed.ifEmpty { "1.0" })
        editLinePos.setText(t.linePos.ifEmpty { "60" })
        editDelay.setText(t.delay.ifEmpty { "0" })
        editBpmOffset.setText(t.bpmOffset.ifEmpty { "0" })
        editHLines.setText(t.hLines.ifEmpty { "4" })
        editVLines.setText(t.vLines.ifEmpty { "8" })
        musicUri = t.music?.let { runCatching { Uri.parse(it) }.getOrNull() }
        imageUri = t.image?.let { runCatching { Uri.parse(it) }.getOrNull() }
        txtMusic.text = "音频：${musicUri?.lastPathSegment ?: "未选择（可不填）"}"
        txtImage.text = "背景：${imageUri?.lastPathSegment ?: "未选择（可不填）"}"
        refreshLastMod(t)
    }

    /** 项目详情页显示最后一次修改时间（精确到秒） */
    private fun refreshLastMod(t: TabData?) {
        val f = t?.let { File(filesDir, "drafts/" + it.id + ".json") }
        val ms = f?.takeIf { it.exists() }?.lastModified() ?: (t?.lastMod ?: 0L)
        txtLastMod.text = if (ms <= 0L) "最后修改：—" else
            "最后修改：" + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(ms))
    }

    private var musicUri: Uri? = null
    private var imageUri: Uri? = null
    /** 发起"选素材"时所在的标签 id（结果回来时按它落位，避免写到别的标签） */
    private var pendingTabId: String? = null

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_EXPORT_TAB && resultCode == RESULT_OK && data?.data != null) {
            val pe = pendingExport ?: return
            try {
                val tmp = File(cacheDir, "export.pez")
                if (PezExporter.export(pe.meta, pe.lines, pe.notes, contentResolver, tmp)) {
                    contentResolver.openOutputStream(data.data!!)?.use { out ->
                        tmp.inputStream().use { it.copyTo(out, 65536) }
                    }
                    toast("已导出 .pez")
                } else {
                    toast("导出失败")
                }
            } catch (e: Exception) {
                toast("导出失败：" + e.message)
            }
            return
        }
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == REQ_CALIB) {
            // 注意：延迟可以是负数（提前点），原来用 `if (v >= 0)` 会把负数结果直接丢掉
            val v = data.getIntExtra("delay", Int.MIN_VALUE)
            if (v != Int.MIN_VALUE) {
                editDelay.setText(v.toString())
                saveCurrent()
                saveTabs()
                toast("已应用延迟：${v} ms")
            }
            return
        }
        if (requestCode == REQ_IMPORT) {
            startImport(data.data ?: return)
            return
        }
        if (requestCode == REQ_RESPACK) {
            importResPack(data.data ?: return)
            return
        }
        if (requestCode == REQ_MAIN_LOG) {
            val text = pendingLogText ?: return
            try {
                contentResolver.openOutputStream(data.data!!)?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                }
                toast("日志已导出")
            } catch (e: Exception) {
                Coop.log("导出日志失败：" + (e.message ?: ""))
            }
            pendingLogText = null
            return
        }
        val uri = data.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        when (requestCode) {
            REQ_MUSIC -> {
                if (hasVideoTrack(uri)) {
                    // 视频：提取音轨 → 解码 PCM → 写 WAV（不重编码画面，Phira 兼容）
                    val wav = File(cacheDir, "imported_audio.wav")
                    wav.delete()
                    if (extractAudioToWav(uri, wav)) {
                        applyPickedAsset(REQ_MUSIC, Uri.fromFile(wav), "${lastSegment(uri)}（已提取音轨）")
                    } else {
                        toast("视频中未找到可用音轨，无法导入")
                    }
                } else {
                    applyPickedAsset(REQ_MUSIC, uri, lastSegment(uri))
                }
            }
            REQ_IMAGE -> applyPickedAsset(REQ_IMAGE, uri, lastSegment(uri))
        }
    }

    /**
     * 把选好的素材写到**发起选择时**的那个标签上（按 id 找，不是按 current）。
     * 选素材期间界面可能被系统回收重建、标签可能被切走，按 current 写会写到别的项目里。
     */
    private fun applyPickedAsset(kind: Int, uri: Uri, label: String) {
        val id = pendingTabId
        val target = (if (id != null) tabs.firstOrNull { it.id == id } else null)
            ?: tabs.getOrNull(current) ?: return
        if (kind == REQ_MUSIC) target.music = uri.toString() else target.image = uri.toString()

        // 只有当它还是当前标签时才刷新界面状态
        if (target === tabs.getOrNull(current)) {
            if (kind == REQ_MUSIC) {
                musicUri = uri
                txtMusic.text = "音频：$label"
            } else {
                imageUri = uri
                txtImage.text = "背景：$label"
            }
        }
        saveTabs()
    }

    private fun lastSegment(uri: Uri): String = uri.lastPathSegment ?: "已选择"

    /** 探测视频：含 video 轨道即视为视频（有音频轨道则提取，否则提示失败） */
    private fun hasVideoTrack(uri: Uri): Boolean {
        return try {
            val ex = android.media.MediaExtractor()
            ex.setDataSource(this, uri, null)
            var hasVideo = false
            var hasAudio = false
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) hasVideo = true
                if (mime.startsWith("audio/")) hasAudio = true
            }
            ex.release()
            hasVideo && hasAudio
        } catch (_: Exception) { false }
    }

    /** 提取音轨 → MediaCodec 解码 PCM → 写 WAV（仅音频，不重编码视频） */
    private fun extractAudioToWav(uri: Uri, dst: File): Boolean {
        return try {
            val ex = android.media.MediaExtractor()
            ex.setDataSource(this, uri, null)
            var audioIdx = -1
            var fmt: android.media.MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioIdx = i; fmt = f; break
                }
            }
            if (audioIdx < 0 || fmt == null) { ex.release(); return false }
            ex.selectTrack(audioIdx)

            val name = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).findDecoderForFormat(fmt)
            if (name == null) { ex.release(); return false }
            val codec = android.media.MediaCodec.createByCodecName(name)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val info = android.media.MediaCodec.BufferInfo()
            val pcm = java.io.ByteArrayOutputStream()
            var sampleRate = 44100
            var channels = 2
            var inputEos = false
            var outputEos = false

            while (!outputEos) {
                // 喂输入
                if (!inputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                // 收输出
                when (val outIdx = codec.dequeueOutputBuffer(info, 10_000)) {
                    android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    android.media.MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    else -> if (outIdx >= 0) {
                        if (info.size > 0) {
                            val ob = codec.getOutputBuffer(outIdx)!!
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            val arr = ByteArray(info.size)
                            ob.get(arr)
                            pcm.write(arr)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputEos = true
                    }
                }
            }
            codec.stop(); codec.release(); ex.release()

            val bytes = pcm.toByteArray()
            // 写 WAV
            val byteData = ByteArray(44 + bytes.size)
            fun putStr(off: Int, s4: String) { for (j in 0 until 4) byteData[off + j] = s4[j].code.toByte() }
            fun putInt(off: Int, v: Int) { byteData[off] = (v and 0xFF).toByte(); byteData[off+1] = ((v shr 8) and 0xFF).toByte(); byteData[off+2] = ((v shr 16) and 0xFF).toByte(); byteData[off+3] = ((v shr 24) and 0xFF).toByte() }
            fun putShort(off: Int, v: Int) { byteData[off] = (v and 0xFF).toByte(); byteData[off+1] = ((v shr 8) and 0xFF).toByte() }
            putStr(0, "RIFF"); putInt(4, 36 + bytes.size); putStr(8, "WAVE")
            putStr(12, "fmt "); putInt(16, 16); putShort(20, 1); putShort(22, channels)
            putInt(24, sampleRate); putInt(28, sampleRate * channels * 2); putShort(32, channels * 2); putShort(34, 16)
            putStr(36, "data"); putInt(40, bytes.size)
            System.arraycopy(bytes, 0, byteData, 44, bytes.size)
            dst.writeBytes(byteData)
            true
        } catch (_: Exception) { false }
    }

    /**
     * 导入 pez 谱面：解析 RPE JSON（音符 + BPM + 流速 + 判定线位置/动画 + 横线竖线）
     * 并抽取包内的音频/背景图，然后新建标签 + 写入草稿。
     * 解析在后台线程做（大谱面包可能要读写几 MB 素材）。
     */
    // ---------------------------------------------------------------- 导入谱面（大弹窗 + 进度条）
    private var importDlg: android.app.AlertDialog? = null
    private var importBar: android.widget.ProgressBar? = null
    private var importInfo: TextView? = null
    private var importOk: android.widget.Button? = null
    private var importTicker: Runnable? = null
    private var importCancelled = false
    private var importId: String? = null
    private var importTabId: String? = null

    private fun startImport(uri: Uri) {
        importCancelled = false
        importTabId = null
        val id = java.util.UUID.randomUUID().toString()
        importId = id

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(4))
        }
        importBar = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(14)
            ).also { it.topMargin = dp(16) }
        }
        box.addView(importBar)
        importInfo = TextView(this).apply {
            text = "0%"
            setTextColor(0xFF777777.toInt())
            textSize = 12f
            gravity = android.view.Gravity.END
            setPadding(0, dp(6), 0, 0)
        }
        box.addView(importInfo)

        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle("导入谱面")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("确认", null)
            .setNegativeButton("取消", null)
            .create()
        importDlg = dlg
        dlg.setOnShowListener {
            // 进度没走完，"确认"是灰的按不了
            importOk = dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).apply {
                isEnabled = false
                setOnClickListener {
                    dlg.dismiss()
                    importDlg = null
                }
            }
            dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                .setOnClickListener { cancelImport() }
        }
        dlg.show()
        startImportTicker()
        runImport(uri, id)
    }

    private fun startImportTicker() {
        importTicker?.let { ui.removeCallbacks(it) }
        importTicker = object : Runnable {
            override fun run() {
                val bar = importBar ?: return
                if (bar.progress < 90) {          // 90% 之前是"估的"，真正完成才跳到 100%
                    bar.progress += 1
                    importInfo?.text = bar.progress.toString() + "%"
                    ui.postDelayed(this, 70)
                }
            }
        }
        ui.postDelayed(importTicker!!, 70)
    }

    /** 取消导入：删掉这次产生的临时文件；如果已经导完了，把刚建的项目也一起删掉 */
    private fun cancelImport() {
        importCancelled = true
        importTicker?.let { ui.removeCallbacks(it) }
        val done = importTabId
        if (done != null) {
            removeTabById(done)
            purgeTabFiles(done)
        } else {
            importId?.let { purgeTabFiles(it) }
        }
        importDlg?.dismiss()
        importDlg = null
        Coop.log("已取消导入，临时文件已删除")
    }

    private fun finishImport(msg: String) {
        importTicker?.let { ui.removeCallbacks(it) }
        importBar?.progress = 100
        importInfo?.text = msg
        importOk?.isEnabled = true
    }

    private fun runImport(uri: Uri, id: String) {
        Thread {
            try {
                val dir = File(filesDir, "imported/$id")
                if (!dir.exists()) dir.mkdirs()
                val chart = PezImporter.import(
                    openZip = {
                        val ins = contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("无法读取该文件")
                        val buf = java.io.BufferedInputStream(ins)
                        // pez 就是 zip：后缀叫 .pez / .zip / 甚至没有后缀都无所谓，
                        // 只看包头（"PK"）。不是压缩包时给一句人话，而不是抛 zip 内部异常。
                        buf.mark(4)
                        val head = ByteArray(4)
                        var n = 0
                        while (n < 4) {
                            val r = buf.read(head, n, 4 - n)
                            if (r < 0) break
                            n += r
                        }
                        buf.reset()
                        if (n < 2 || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) {
                            throw IllegalStateException("这不是 pez/zip 压缩包（文件头不对）")
                        }
                        java.util.zip.ZipInputStream(buf)
                    },
                    saveAsset = { name, ins ->
                        val f = File(dir, name.replace('\\', '/').substringAfterLast('/'))
                        f.outputStream().use { out -> ins.copyTo(out, 64 * 1024) }
                        true
                    }
                )
                val musicFile = chart.musicName?.let {
                    File(dir, it.replace('\\', '/').substringAfterLast('/')).takeIf { f -> f.length() > 0 }
                }
                val imageFile = chart.imageName?.let {
                    File(dir, it.replace('\\', '/').substringAfterLast('/')).takeIf { f -> f.length() > 0 }
                }
                // 草稿在后台线程序列化 + 落盘：上万音符的谱面在 UI 线程做这件事会明显卡一下
                try {
                    val draftFile = File(filesDir, "drafts/$id.json")
                    draftFile.parentFile?.mkdirs()
                    val draftLines = chart.lines.map { il ->
                        Line(il.id).apply {
                            name = il.name
                            posPct = il.posPct
                            moveX = il.moveX
                            moveY = il.moveY
                            rotate = il.rotate
                            speed = il.speed
                            alpha = il.alpha
                            keyframes.addAll(il.keyframes)
                            invalidate()
                        }
                    }
                    draftFile.writeText(DraftCodec.encode(draftLines, chart.notes))
                } catch (e: Exception) {
                    Coop.log("草稿写入失败：" + (e.message ?: ""))
                }
                runOnUiThread {
                    if (importCancelled) {
                        purgeTabFiles(id)
                        return@runOnUiThread
                    }
                    applyImported(chart, id, musicFile, imageFile)
                    importTabId = id
                    finishImport("")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (importCancelled) purgeTabFiles(id)
                    else finishImport("导入失败：" + (e.message ?: e.javaClass.simpleName))
                }
            }
        }.start()
    }

    /** 把解析结果落到项目标签 + 谱面草稿 */
    private fun applyImported(chart: ImportedChart, id: String, music: File?, image: File?) {
        if (isFinishing || isDestroyed) return
        val t = TabData()
        t.id = id
        t.name = chart.name
        t.composer = chart.composer
        t.charter = chart.charter
        t.level = chart.level
        t.bpm = numText(chart.bpm)
        t.speed = numText(chart.speed)
        t.linePos = chart.linePosPct.toString()
        t.hLines = chart.hLines.toString()
        t.vLines = chart.vLines.toString()
        t.delay = "0"
        music?.let { t.music = Uri.fromFile(it).toString() }
        image?.let { t.image = Uri.fromFile(it).toString() }

        // 草稿在后台线程已经用 DraftCodec 写好了（见 importPez），这里不再做任何序列化

        // 导入结果作为**新标签**加入：绝不动已有标签（原来 tabs.clear() 会把所有项目清空）
        saveCurrent()
        tabs.add(t)
        current = tabs.lastIndex
        restore(t)
        saveTabs()
        renderTabs()

        val parts = ArrayList<String>()
        parts.add("${chart.notes.size} 个音符")
        parts.add(if (music != null) "音频" else "无音频")
        parts.add(if (image != null) "背景图" else "无背景图")
        parts.add("判定线 ${chart.lineCount} 条")
        val kfTotal = chart.lines.sumOf { it.keyframes.size }
        if (kfTotal > 0) parts.add("关键帧 $kfTotal 个")
        toast("导入成功：${t.name}（${parts.joinToString(" · ")}）· 已新建标签")

        // 导入提示窗口已按用户要求去掉（warnings 仍保留在 ImportedChart 里）
    }

    /** 数字转文本（整数不带小数点） */
    private fun numText(v: Double): String {
        if (!v.isFinite()) return "1"
        val r = Math.round(v * 1000.0) / 1000.0
        return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
    }


    // ---------------------------------------------------------------- 联机面板
    private fun pwText(): String = coopPw

    private fun roomStatusText(): String {
        val who = if (Coop.role == Coop.ROLE_HOST) "服务端" else "客户端"
        return "$who · ${Coop.hostIp}:${Coop.port} · ${Coop.players.size} 人"
    }

    /** 右侧抽屉内容：ip / 端口 / 4 位密码（小键盘）+ 作为服务端 / 作为客户端 */
    private fun buildCoopPanel() {
        rightPanelBody.removeAllViews()
        val hostMode = coopPanelMode != "client"
        val prevIp = coopIpBox?.text?.toString()?.trim()
        val prevPort = coopPortBox?.text?.toString()?.trim()

        fun cap(txt: String, size: Float, color: Int, top: Int = 6): TextView = TextView(this).apply {
            text = txt; setTextColor(color); textSize = size
            setPadding(dp(2), dp(top), 0, dp(2))
        }
        fun box(def: String, numeric: Boolean, h: Int = 34): EditText = EditText(this).apply {
            setText(def)
            setTextColor(0xFFFFFFFF.toInt()); setTextSize(13f)
            includeFontPadding = false
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x33FFFFFF); setStroke(dp(1), 0x66FFFFFF); cornerRadius = dp(4).toFloat()
            }
            setPadding(dp(8), dp(4), dp(8), dp(4))
            inputType = if (numeric) android.text.InputType.TYPE_CLASS_NUMBER
            else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(h))
        }
        /**
         * 大按钮：配色直接取"房间框"那一套 —— 底色 = 框色的淡化(0x22)，2dp 框色描边，
         * 文字 = 框色淡化后的亮色。服务端传蓝框色、客户端传绿框色，不另外定义颜色。
         */
        fun wideBtn(txt: String, frame: Int, h: Int, onClick: () -> Unit): TextView = TextView(this).apply {
            text = txt
            gravity = android.view.Gravity.CENTER
            setTextColor(frameTint(frame, 0.45f)); setTextSize(13f)
            includeFontPadding = false
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor((frame and 0x00FFFFFF) or 0x33000000)      // 框色 + 淡化
                setStroke(dp(2), frame)                              // 框色本身（就是"蓝框/绿框"）
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(h)
            ).also { it.setMargins(dp(2), dp(6), dp(2), 0) }
            setOnClickListener { onClick() }
        }

        rightPanelBody.addView(
            cap(if (hostMode) "联机 · 作为服务端" else "联机 · 作为客户端", 14f, 0xFFFFFFFF.toInt(), 0)
        )
        rightPanelBody.addView(cap("IP", 10f, 0x99FFFFFF.toInt()))
        coopIpBox = box(prevIp?.takeIf { it.isNotEmpty() } ?: Coop.localIp(), false)
        rightPanelBody.addView(coopIpBox)
        rightPanelBody.addView(cap("端口", 10f, 0x99FFFFFF.toInt()))
        coopPortBox = box(prevPort?.takeIf { it.isNotEmpty() } ?: Coop.DEFAULT_PORT.toString(), true)
        rightPanelBody.addView(coopPortBox)

        // ---- 密码：输入框样式的显示条 + 位数调节 ----
        val pwRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        coopPwView = TextView(this).apply {
            text = pwText()
            setTextColor(0xFFFFFFFF.toInt()); setTextSize(16f)
            gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
            setPadding(dp(10), 0, dp(10), 0)
            includeFontPadding = false
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x33FFFFFF); setStroke(dp(1), 0x66FFFFFF); cornerRadius = dp(4).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).also {
                it.setMargins(dp(2), dp(6), dp(4), 0)
            }
        }
        val lenBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)
            ).also { it.setMargins(0, dp(6), dp(2), 0) }
        }
        fun lenBtn(t: String, d: Int): TextView = TextView(this).apply {
            text = t
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt()); setTextSize(14f)
            includeFontPadding = false
            setBackgroundColor(0x33FFFFFF)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            setOnClickListener { changePwLen(d) }
        }
        coopLenView = TextView(this).apply {
            text = coopLen.toString()
            setTextColor(0xFF00E5FF.toInt()); textSize = 13f
            gravity = android.view.Gravity.CENTER
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        lenBox.addView(lenBtn("−", -1)); lenBox.addView(coopLenView); lenBox.addView(lenBtn("+", 1))
        pwRow.addView(coopPwView); pwRow.addView(lenBox)
        rightPanelBody.addView(pwRow)

        // ---- 小键盘：所有键同高同字号、关掉 fontPadding（不然"清空"会偏下）----
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "清空", "0", "⌫")
        var row: LinearLayout? = null
        for ((i, k) in keys.withIndex()) {
            if (i % 3 == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                rightPanelBody.addView(row)
            }
            val b = TextView(this).apply {
                text = k
                gravity = android.view.Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(15f)
                includeFontPadding = false
                setBackgroundColor(0x26FFFFFF)
                layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).also {
                    it.setMargins(dp(2), dp(2), dp(2), dp(2))
                }
                setOnClickListener { onCoopKey(k) }
            }
            row?.addView(b)
        }

        // ---- 主按钮：从谱子进来只有服务端，从＋进来只有客户端 ----
        // 颜色不写死：直接用房间框的蓝 #2D7FF9 / 绿 #22C55E，按钮描边就是框本身，底色是它的淡化
        if (hostMode) {
            rightPanelBody.addView(wideBtn("作为服务端", FRAME_BLUE, 38) { doHostRoom() })
            quickHostBtn = wideBtn("快速服务端", FRAME_BLUE, 34) { toggleQuickHost() }
            if (quickHosting) quickHostBtn?.text = "快速服务端 ${quickLeft}s"
            rightPanelBody.addView(quickHostBtn)
            quickJoinBtn = null
            clientJoinBtn = null
        } else {
            clientJoinBtn = wideBtn("作为客户端", FRAME_GREEN, 38) { doJoinRoom() }
            rightPanelBody.addView(clientJoinBtn)
            quickJoinBtn = wideBtn("快速客户端", FRAME_GREEN, 34) { doQuickJoin() }
            rightPanelBody.addView(quickJoinBtn)
            quickHostBtn = null
        }

        coopStatus = TextView(this).apply {
            text = if (Coop.isInRoom()) roomStatusText() else "未联机"
            setTextColor(0x99FFFFFF.toInt()); textSize = 10f
            setPadding(dp(2), dp(6), 0, 0)
        }
        rightPanelBody.addView(coopStatus)

        rightPanelBody.addView(cap("玩家列表", 11f, 0xFFFFFFFF.toInt()))
        coopPlayersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rightPanelBody.addView(coopPlayersBox)
        renderCoopPlayers()
        if (Coop.isInRoom()) {
            coopPw = Coop.password
            coopLen = coopPw.length.coerceIn(1, 8)
            coopLenView?.text = coopLen.toString()
            coopPwView?.text = pwText()
        }
    }

    /** 当前 APK 版本（从 PackageManager 读取，保证显示的版本号和构建配置一致） */
    private fun appVersionText(): String {
        return try {
            val info = packageManager.getPackageInfo(packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "v${info.versionName}（Build $code）"
        } catch (_: Exception) {
            "未知"
        }
    }

    // ---------------------------------------------------------------- 资源包面板
    /** 选 Phira 资源包压缩包 */
    private fun pickResPack() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_RESPACK)
    }

    /** 解压 + 校验 + 落盘到 filesDir/respacks，成功后设为当前资源包 */
    private fun importResPack(uri: Uri) {
        Thread {
            var ok = false
            var msg = "导入失败"
            try {
                val tmp = File(cacheDir, "respack_tmp")
                tmp.deleteRecursively()
                tmp.mkdirs()
                val rootPath = tmp.canonicalPath
                contentResolver.openInputStream(uri)?.use { ins ->
                    java.util.zip.ZipInputStream(ins).use { z ->
                        var total = 0L
                        var entry = z.nextEntry
                        while (entry != null) {
                            val name = entry.name.replace('\\', '/')
                            if (name.isNotEmpty() && !name.startsWith("/") && !name.contains("..")) {
                                val f = File(tmp, name)
                                if (f.canonicalPath.startsWith(rootPath)) {
                                    if (entry.isDirectory) {
                                        f.mkdirs()
                                    } else {
                                        total += entry.size.coerceAtLeast(0)
                                        if (total > 200L * 1024 * 1024) throw Exception("资源包过大")
                                        f.parentFile?.mkdirs()
                                        f.outputStream().use { out -> z.copyTo(out, 16384) }
                                    }
                                }
                            }
                            z.closeEntry()
                            entry = z.nextEntry
                        }
                    }
                }
                val pack = ResPack.find(tmp)
                if (pack == null) {
                    msg = "不是有效的 Phira 资源包"
                } else {
                    val dirName = pack.name.replace(Regex("[^0-9A-Za-z\\u4e00-\\u9fa5_-]"), "_")
                        .take(40).ifEmpty { "pack" }
                    val target = File(ResPackStore.root(filesDir), dirName)
                    target.deleteRecursively()
                    val moved = pack.dir.renameTo(target) || pack.dir.copyRecursively(target, true)
                    if (moved) {
                        ResPackStore.setActive(this, ResPack(target))
                        msg = "已导入：" + pack.name
                        ok = true
                    } else {
                        msg = "写入资源包失败"
                    }
                }
                tmp.deleteRecursively()
            } catch (e: Exception) {
                msg = "导入失败：" + (e.message ?: "")
            }
            runOnUiThread {
                if (ok && coopPanelMode == "respack") buildRespackPanel()
                toast(msg)
            }
        }.start()
    }

    /** 资源包条目向左滑出删除（滑过 60dp 松手即删） */
    private fun attachSwipeDelete(v: View, onDelete: () -> Unit) {
        var downX = 0f
        var downY = 0f
        var dragging = false
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                    view.animate().cancel()
                    view.translationX = 0f
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    // 先判断是不是横向滑动，避免和抽屉上下滚动打架
                    if (!dragging && dx < -dp(8) && Math.abs(dx) > Math.abs(dy)) dragging = true
                    if (dragging) view.translationX = dx.coerceAtMost(0f)
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        view.performClick()
                    } else if (view.translationX <= -dp(60)) {
                        view.animate().translationX(-view.width.toFloat()).alpha(0f)
                            .setDuration(130).withEndAction { onDelete() }.start()
                    } else {
                        view.animate().translationX(0f).setDuration(130).start()
                    }
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    view.animate().translationX(0f).setDuration(130).start()
                    true
                }
                else -> false
            }
        }
    }

    /** 右侧抽屉内容：资源包（顶部导入加号 → 自带默认包 → 已导入包，左滑删除） */
    private fun buildRespackPanel() {
        rightPanelBody.removeAllViews()
        val active = ResPackStore.active(this)
        val packs = ResPackStore.list(filesDir)

        rightPanelBody.addView(TextView(this).apply {
            text = "资源包"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
            setPadding(dp(2), 0, dp(2), dp(2))
        })
        rightPanelBody.addView(TextView(this).apply {
            text = "当前：" + (active?.name ?: "默认")
            setTextColor(0x99FFFFFF.toInt())
            textSize = 11f
            includeFontPadding = false
            setPadding(dp(2), 0, dp(2), dp(6))
        })

        fun box(txt: String, selected: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
            text = txt
            textSize = 12f
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER_VERTICAL
            setTextColor(if (selected) 0xFF00E5FF.toInt() else 0xFFFFFFFF.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (selected) 0x2200E5FF else 0x22FFFFFF)
                setStroke(dp(1), if (selected) 0xFF00E5FF.toInt() else 0x66FFFFFF)
                cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)   // 高度 = 左侧「+」按钮高度
            ).also { it.setMargins(dp(4), dp(4), dp(4), 0) }
            setOnClickListener { onClick() }
        }

        // 最上面：加号，点击导入资源包
        rightPanelBody.addView(TextView(this).apply {
            text = "+"
            gravity = android.view.Gravity.CENTER
            setTextColor(0x66FFFFFF.toInt())
            textSize = 28f
            includeFontPadding = false
            setBackgroundColor(0x22FFFFFF)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)
            ).also { it.setMargins(dp(4), dp(4), dp(4), 0) }
            setOnClickListener { pickResPack() }
        })
        // 第二个：应用自带资源包（不可删除）
        rightPanelBody.addView(box("默认", active == null) {
            ResPackStore.setActive(this, null)
            buildRespackPanel()
            toast("已切换到默认资源包")
        })
        // 下面才是导入的资源包：按导入先后排列，新导入的在后面；左滑删除
        for (p in packs) {
            val v = box(p.name, active?.dir?.name == p.dir.name) {
                ResPackStore.setActive(this, p)
                buildRespackPanel()
                toast("已使用：" + p.name)
            }
            attachSwipeDelete(v) {
                ResPackStore.remove(this, p)
                buildRespackPanel()
                toast("已删除：" + p.name)
            }
            rightPanelBody.addView(v)
        }
    }

    // ---------------------------------------------------------------- AI 面板
    /** 右侧抽屉：AI 聊天（点 AI 按钮）/ AI 接口设置（长按 AI 按钮） */
    private fun aiTitle(panelTitle: String, sub: String) {
        rightPanelBody.addView(TextView(this).apply {
            text = panelTitle
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
            setPadding(dp(2), 0, dp(2), dp(2))
        })
        rightPanelBody.addView(TextView(this).apply {
            text = sub
            setTextColor(0x99FFFFFF.toInt())
            textSize = 11f
            includeFontPadding = false
            setPadding(dp(2), 0, dp(2), dp(6))
        })
    }

    private fun addAiBubble(parent: LinearLayout, mine: Boolean, text: String): TextView {
        val tv = TextView(this).apply {
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
        parent.addView(tv)
        return tv
    }

    private fun buildAiChatPanel() {
        rightPanelBody.removeAllViews()
        // 第一次打开：从本地读回聊天记录（重启应用也还在）
        if (!aiChatLoaded) {
            aiChatMessages = AiChatStore.load(this)
            aiChatLoaded = true
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rightPanelBody.addView(col)
        aiChatCol = col
        for ((mine, text) in aiChatMessages) addAiBubble(col, mine, text)

        // 标题 + 撤回/清空 做成悬浮层：固定在抽屉右上角，聊天记录再多也滚不走
        rightPanelBody.setPadding(dp(10), dp(46), dp(10), dp(52))
        ensureAiHeader()
        aiHeaderView?.visibility = View.VISIBLE
        // 输入条做成悬浮层（在右侧抽屉底部、压住聊天记录上，键盘弹出时整体上移）
        ensureAiInputBar()
        aiBarView?.visibility = View.VISIBLE
        rightPanel.post { rightPanel.fullScroll(View.FOCUS_DOWN) }
    }

    /** 悬浮标题栏：左边「AI 助手」，右上角「撤回 / 清空」 */
    private fun ensureAiHeader() {
        if (aiHeaderView != null) return
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(6), dp(4))
        }
        bar.addView(TextView(this).apply {
            text = "撤回"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 12f
            includeFontPadding = false
            setPadding(dp(8), dp(4), dp(2), dp(4))
            setOnClickListener { aiUndoLast() }
        })
        bar.addView(TextView(this).apply {
            text = "清空"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 12f
            includeFontPadding = false
            setPadding(dp(10), dp(4), dp(2), dp(4))
            setOnClickListener { confirmClearAiChat() }
        })
        aiHeaderView = bar
        val root = findViewById<ViewGroup>(android.R.id.content)
        val lp = android.widget.FrameLayout.LayoutParams(
            dp(304), dp(40),
            android.view.Gravity.TOP or android.view.Gravity.START
        )
        lp.leftMargin = dp(152) + dp(2)
        lp.topMargin = dp(6)
        root.addView(bar, lp)
        bar.visibility = View.GONE
    }

    /** 半透明输入框底色（和联机密码输入框同一套） */
    private fun aiInputBackground(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(0x33FFFFFF)
            setStroke(dp(1), 0x66FFFFFF)
            cornerRadius = dp(4).toFloat()
        }

    /** 创建悬浮输入条：贴在右侧抽屉底部，键盘弹出时跟着键盘上沿走 */
    private fun ensureAiInputBar() {
        if (aiBarView != null) return
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        val input = EditText(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setSingleLine(true)
            background = aiInputBackground()
            setPadding(dp(8), 0, dp(8), 0)
        }
        val send = TextView(this).apply {
            text = "发送"
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            includeFontPadding = false
            background = aiInputBackground()
            setOnClickListener {
                val t = input.text.toString()
                input.setText("")
                sendAiChat(t)
            }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, dp(30), 1f))
        bar.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        bar.addView(send, LinearLayout.LayoutParams(dp(56), dp(30)))
        aiBarView = bar
        val root = findViewById<ViewGroup>(android.R.id.content)
        val lp = android.widget.FrameLayout.LayoutParams(
            dp(304), ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.START
        )
        lp.leftMargin = dp(152) + dp(2)
        lp.bottomMargin = dp(6)
        root.addView(bar, lp)
        bar.visibility = View.GONE
        // 键盘弹出时：输入条抬到键盘上沿，同时把右侧抽屉缩短到键盘上方（聊天记录跟着上移）
        val decor = window.decorView
        fun applyIme(ime: Int) {
            bar.translationY = -ime.toFloat()
            val lp = rightPanel.layoutParams
            lp.height = if (ime > 0) (decor.height - ime).coerceAtLeast(dp(120))
            else ViewGroup.LayoutParams.MATCH_PARENT
            rightPanel.layoutParams = lp
            rightPanel.requestLayout()
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            decor.setOnApplyWindowInsetsListener { v, insets ->
                applyIme(insets.getInsets(android.view.WindowInsets.Type.ime()).bottom)
                v.onApplyWindowInsets(insets)
            }
        } else {
            decor.viewTreeObserver.addOnGlobalLayoutListener {
                val r = android.graphics.Rect()
                decor.getWindowVisibleDisplayFrame(r)
                applyIme((decor.height - r.bottom).coerceAtLeast(0))
            }
        }
    }

    /** 记一条聊天消息（内存里最多留 MAX 条，和本地存盘一致，避免越聊越占内存） */
    private fun rememberChat(mine: Boolean, text: String) {
        aiChatMessages.add(mine to text)
        while (aiChatMessages.size > AiChatStore.MAX) aiChatMessages.removeAt(0)
        AiChatStore.save(this, aiChatMessages)
    }

    /** 没接 AI 时：把问题里的词逐个和内置知识库比对，返回所有相关的原文 */
    private fun answerFromKnowledge(text: String) {
        if (aiChatBusy) return
        rememberChat(true, text)
        val hit = AiKnowledge.search(text)
        val reply = if (hit != null) {
            "（未接入 AI，以下是内置知识库里最相关的内容）\n" + hit
        } else {
            "（未接入 AI）知识库里没找到相关内容。长按 AI 按钮配置接口后，可以用自然语言提问，也能让我帮你操作应用。"
        }
        rememberChat(false, reply)
        aiChatCol?.let {
            addAiBubble(it, true, text)
            addAiBubble(it, false, reply)
            rightPanel.post { rightPanel.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** 发一条聊天消息（流式刷新同一个气泡；搜索/抓网页会多跑一轮再回答） */
    private fun sendAiChat(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val ep = AiStore.active(this)
        // 没接 AI：不走网络，直接在本地知识库里按关键词检索；接入了 AI 就强制用 AI
        if (ep == null || !ep.usable) {
            answerFromKnowledge(text)
            return
        }
        if (aiChatBusy) { toast("AI 还在回复…"); return }
        rememberChat(true, text)
        val col = aiChatCol ?: return
        addAiBubble(col, true, text)
        val bubble = addAiBubble(col, false, "正在回复…")
        aiChatBusy = true
        val cancel = AiClient.Cancel()
        aiChatCancel = cancel
        val history = aiChatMessages.takeLast(10)
        val gen = aiChatGen
        Thread {
            try {
                val msgs = ArrayList<Pair<String, String>>()
                msgs.add("system" to AiPrompts.systemAgentChat())
                for ((mine, t) in history) msgs.add((if (mine) "user" else "assistant") to t)

                var round = 0
                var lastPush = 0L
                while (true) {
                    if (round > 0) ui.post { bubble.text = "AI：正在查资料…" }
                    val full = AiClient.chat(
                        ep, msgs, cancel, maxTokens = 2048, temperature = 0.7,
                        onDelta = { s ->
                            // 流式增量很多，限流到 ~150ms 一次，避免频繁整段重排版
                            if (round == 0) {
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (now - lastPush > 150) {
                                    lastPush = now
                                    ui.post { bubble.text = "AI：" + splitAgentCommands(s).first }
                                }
                            }
                        }
                    )
                    val (clean, cmds) = splitAgentCommands(full)
                    // 联网类命令在后台跑，结果回灌给 AI 再回答一轮
                    val tools = ArrayList<String>()
                    for (c in cmds) {
                        if (c.optString("cmd") in setOf("search", "fetch")) {
                            val r = AgentTools.run(c)
                            tools.add(r ?: "【工具结果】联网失败（网络受限或页面打不开），请如实告诉用户没查到。")
                        }
                    }
                    if (tools.isNotEmpty() && round < 2 && !cancel.cancelled) {
                        msgs.add("assistant" to full)
                        msgs.add(
                            "user" to "【工具结果】\n" + tools.joinToString("\n\n") +
                                "\n请基于上面的资料直接回答用户，不要再输出命令。"
                        )
                        round++
                        continue
                    }
                    // 普通命令在 UI 线程执行，其余文本照常显示
                    ui.post {
                        val results = ArrayList<String>()
                        for (c in cmds) {
                            if (c.optString("cmd") in setOf("search", "fetch")) continue
                            runAgentCommand(c)?.let { results.add(it) }
                        }
                        val shown = clean.trim()
                        bubble.text = "AI：" + shown.ifEmpty { if (results.isEmpty()) full else "（已执行操作）" }
                        // 中途被「清空」了：这条回复直接丢掉，别再写回记录
                        if (gen == aiChatGen) {
                            if (shown.isNotEmpty()) rememberChat(false, shown)
                            if (results.isNotEmpty()) rememberChat(false, "已执行：" + results.joinToString("；"))
                        }
                        if (results.isNotEmpty()) {
                            aiChatCol?.let { addAiBubble(it, false, "已执行：" + results.joinToString("；")) }
                        }
                        rightPanel.fullScroll(View.FOCUS_DOWN)
                    }
                    return@Thread
                }
            } catch (e: Exception) {
                ui.post { bubble.text = "AI：出错了：" + (e.message ?: "未知错误") }
            } finally {
                aiChatBusy = false
                aiChatCancel = null
            }
        }.start()
    }

    /** 清空聊天记录（带确认弹窗；正在回复时先停掉请求） */
    private fun confirmClearAiChat() {
        android.app.AlertDialog.Builder(this)
            .setTitle("清空聊天记录")
            .setMessage("清空所有 AI 聊天记录？")
            .setPositiveButton("清空") { _, _ ->
                aiChatCancel?.cancelled = true
                aiChatGen++
                aiChatMessages.clear()
                AiChatStore.clear(this)
                aiChatLoaded = true
                buildAiChatPanel()
                toast("已清空聊天记录")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------------------------------------------------------- AI 代理（agent）
    /** 把回复里的命令 JSON 摘出来（带 cmd 的对象），其余文本原样保留 */
    private fun splitAgentCommands(full: String): Pair<String, List<org.json.JSONObject>> {
        val sb = StringBuilder()
        val cmds = ArrayList<org.json.JSONObject>()
        var i = 0
        val n = full.length
        while (i < n) {
            if (full[i] != '{') { sb.append(full[i]); i++; continue }
            var depth = 0
            var inStr = false
            var esc = false
            var j = i
            var end = -1
            while (j < n) {
                val c = full[j]
                if (inStr) {
                    if (esc) esc = false
                    else if (c == '\\') esc = true
                    else if (c == '"') inStr = false
                } else when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) { end = j; break } }
                }
                j++
            }
            if (end < 0) { sb.append(full.substring(i)); break }
            val objStr = full.substring(i, end + 1)
            val o = try { org.json.JSONObject(objStr) } catch (_: Exception) { null }
            if (o != null && o.has("cmd")) cmds.add(o) else sb.append(objStr)
            i = end + 1
        }
        return sb.toString() to cmds
    }

    /** 执行一条 AI 命令，返回一句执行结果（null = 不认识/没执行） */
    private fun runAgentCommand(o: org.json.JSONObject): String? {
        // 撤回栈最多留 10 步，删项目的快照不小，别让它无限长
        while (agentUndo.size > 10) agentUndo.removeFirst()
        return when (o.optString("cmd", "")) {
            "setting" -> runAgentSetting(o)
            "meta" -> runAgentMeta(o)
            "room" -> runAgentRoom(o)
            "project" -> runAgentProject(o)
            "song" -> runAgentSong(o)
            "clear_chat" -> { confirmClearAiChat(); "正在清空聊天记录" }
            "undo" -> runAgentUndo()
            else -> null
        }
    }

    /** AI 生成一段简单旋律（本地正弦合成 WAV）并设为当前项目的音乐 */
    private fun runAgentSong(o: org.json.JSONObject): String? {
        val t = tabs.getOrNull(current) ?: return "当前没有项目"
        saveCurrent()
        val old = t.music
        val secs = o.optInt("seconds", 30).coerceIn(5, 180)
        val bpm = o.optDouble("bpm", 120.0)
        val dir = File(filesDir, "music")
        val f = File(dir, "ai_song_" + System.currentTimeMillis() + ".wav")
        if (!ToneSynth.write(f, secs, bpm)) return "生成歌曲失败"
        t.music = android.net.Uri.fromFile(f).toString()
        refreshLastMod(t)
        saveTabs()
        restore(t)
        renderProjectDrawer()
        agentUndo.addLast(("生成歌曲（" + secs + " 秒）") to {
            t.music = old
            try {
                val uriStr = android.net.Uri.fromFile(f).toString()
                if (tabs.none { it.music == uriStr }) f.delete()   // 没有项目再引用才删文件
            } catch (_: Exception) {}
            saveTabs()
            if (tabs.isNotEmpty()) restore(t)
            renderProjectDrawer()
        })
        return "已生成 " + secs + " 秒的简单旋律，并设为当前项目的音乐"
    }

    /** 改当前项目信息（难度/名称/BPM/线数等），改完立刻刷新 */
    private fun runAgentMeta(o: org.json.JSONObject): String? {
        val t = tabs.getOrNull(current) ?: return "当前没有项目"
        val before = t.toJson().toString()
        if (o.has("name")) editName.setText(o.optString("name", t.name))
        if (o.has("composer")) editComposer.setText(o.optString("composer", t.composer))
        if (o.has("charter")) editCharter.setText(o.optString("charter", t.charter))
        if (o.has("level")) editLevel.setText(o.optString("level", t.level))
        if (o.has("bpm")) editBpm.setText(o.optString("bpm", t.bpm))
        if (o.has("speed")) editSpeed.setText(o.optString("speed", t.speed))
        if (o.has("linePos")) editLinePos.setText(o.optString("linePos", t.linePos))
        if (o.has("delay")) editDelay.setText(o.optString("delay", t.delay))
        if (o.has("bpmOffset")) editBpmOffset.setText(o.optString("bpmOffset", t.bpmOffset))
        if (o.has("hLines")) editHLines.setText(o.optString("hLines", t.hLines))
        if (o.has("vLines")) editVLines.setText(o.optString("vLines", t.vLines))
        saveCurrent()
        refreshLastMod(t)
        saveTabs()
        renderTabs()
        renderProjectDrawer()
        agentUndo.addLast(("修改项目信息「" + t.name.ifEmpty { "未命名" } + "」") to {
            val old = try { TabData.fromJson(org.json.JSONObject(before)) } catch (_: Exception) { null }
            val cur = tabs.firstOrNull { it.id == t.id }
            if (old != null && cur != null) {
                copyTabFields(old, cur)
                saveTabs()
                restore(cur)
                renderTabs()
                renderProjectDrawer()
            }
        })
        return "已更新项目信息"
    }

    private fun copyTabFields(src: TabData, dst: TabData) {
        dst.name = src.name; dst.composer = src.composer; dst.charter = src.charter
        dst.level = src.level; dst.bpm = src.bpm; dst.speed = src.speed
        dst.linePos = src.linePos; dst.delay = src.delay; dst.bpmOffset = src.bpmOffset
        dst.hLines = src.hLines; dst.vLines = src.vLines
        dst.music = src.music; dst.image = src.image
    }

    private fun asBool(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> when {
            v.contains("开") || v.contains("true") || v.contains("启用") || v == "1" -> true
            v.contains("关") || v.contains("false") || v.contains("停") || v == "0" -> false
            else -> null
        }
        else -> null
    }

    private fun asInt(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is Boolean -> if (v) 1 else 0
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    private fun snapshotRecPrefs(): Map<String, Any?> =
        getSharedPreferences("frphi_rec", 0).all.toMap()

    private fun restoreRecPrefs(m: Map<String, Any?>) {
        val e = getSharedPreferences("frphi_rec", 0).edit().clear()
        for ((k, v) in m) when (v) {
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is Float -> e.putFloat(k, v)
            is String -> e.putString(k, v)
        }
        e.apply()
    }

    /** 设置类命令：改编辑页的开关（存在 frphi_rec，下次进编辑页生效） */
    private fun runAgentSetting(o: org.json.JSONObject): String? {
        val key = o.optString("key", "")
        if (key.isEmpty()) return null
        val bools = setOf(
            "smoothMove", "autoEdgeScroll", "doubleHint", "sound", "overlapFrame",
            "markReverse", "lineHi", "placeLines", "quickSwitch", "hRotate",
            "ghostPlace", "ghostFrame", "fullMode"
        )
        val ints = mapOf(
            "compatMode" to (0..2), "gridMode" to (0..3),
            "ghostShow" to (0..1), "realProcess" to (0..2)
        )
        val before = snapshotRecPrefs()
        val v = o.opt("value")
        return when {
            key in bools -> {
                val b = asBool(v) ?: return null
                getSharedPreferences("frphi_rec", 0).edit().putBoolean(key, b).apply()
                agentUndo.addLast(("设置 " + key) to { restoreRecPrefs(before) })
                "已" + (if (b) "开启 " else "关闭 ") + key + "（进编辑页生效）"
            }
            key in ints -> {
                val iv = asInt(v) ?: return null
                val r = ints[key]!!
                val nv = iv.coerceIn(r.first, r.last)
                getSharedPreferences("frphi_rec", 0).edit().putInt(key, nv).apply()
                agentUndo.addLast(("设置 " + key) to { restoreRecPrefs(before) })
                "已把 " + key + " 设为 " + nv + "（进编辑页生效）"
            }
            else -> null
        }
    }

    private fun runAgentRoom(o: org.json.JSONObject): String? {
        when (o.optString("action", "")) {
            "open" -> {
                coopPanelMode = if (tabs.isEmpty()) "client" else "host"
                openRightPanel()
                return "已打开联机面板"
            }
            "close" -> {
                if (!Coop.isInRoom()) return "当前没有联机房间"
                if (Coop.role == Coop.ROLE_HOST) askExitHostRoom() else askExitClientRoom()
                return "正在关闭联机房间"
            }
        }
        return null
    }

    private fun runAgentProject(o: org.json.JSONObject): String? {
        val act = o.optString("action", "")
        if (act == "new") {
            saveCurrent()
            tabs.add(TabData())
            current = tabs.lastIndex
            restore(tabs[current])
            saveTabs()
            renderTabs()
            renderProjectDrawer()
            val created = tabs.lastOrNull() ?: return "新建失败"
            agentUndo.addLast(("新建项目") to { removeTabById(created.id) })
            return "已新建一个空项目"
        }
        if (act == "delete_all") {
            if (tabs.isEmpty()) return "当前没有项目"
            val snapshot = ArrayList(tabs)
            val drafts = snapshot.map { it.id to draftTextOf(it.id) }
            val n = snapshot.size
            drawerThumbs.keys.toList().forEach { drawerThumbs.remove(it) }
            tabs.clear()
            current = 0
            clearInputs()
            saveTabs()
            renderTabs()
            renderProjectDrawer()
            agentUndo.addLast(("清空所有项目（$n 个）") to {
                tabs.clear()
                tabs.addAll(snapshot)
                for ((id, txt) in drafts) if (txt != null) writeDraft(id, txt)
                current = 0
                saveTabs()
                renderTabs()
                renderProjectDrawer()
                if (tabs.isNotEmpty()) restore(tabs[0])
            })
            return "已清空所有项目（$n 个，可撤回）"
        }
        if (act == "delete_except" || act == "keep") {
            // 只保留 names 里列出的项目，其余全删（不写 names 就保留当前项目）
            val keepNames = ArrayList<String>()
            val arr = o.optJSONArray("names")
            if (arr != null) {
                for (i in 0 until arr.length()) keepNames.add(arr.optString(i, ""))
            } else {
                val single = o.optString("name", "")
                if (single.isNotEmpty()) keepNames.add(single)
                else tabs.getOrNull(current)?.let { keepNames.add(it.name) }
            }
            if (keepNames.isEmpty()) return "没有指定要保留的项目"
            val victims = tabs.filter { it.name !in keepNames }
            if (victims.isEmpty()) return "没有需要删除的项目"
            val drafts = victims.map { it.id to draftTextOf(it.id) }
            val idxMap = victims.map { tabs.indexOf(it) }
            for (v in victims) removeTabById(v.id)
            agentUndo.addLast(("删除 " + victims.size + " 个项目") to {
                val pos = victims.mapIndexed { k, v -> idxMap[k] to v }
                for ((idx, v) in pos.sortedBy { it.first }) {
                    tabs.add(idx.coerceIn(0, tabs.size), v)
                }
                for ((id, txt) in drafts) if (txt != null) writeDraft(id, txt)
                saveTabs(); renderTabs(); renderProjectDrawer()
            })
            return "已删除 " + victims.size + " 个项目（保留 " + keepNames.joinToString("、") + "）"
        }
        if (act == "delete" && (o.has("name") || o.has("id"))) {
            val byId = o.optString("id", "")
            val byName = o.optString("name", "")
            val target = tabs.firstOrNull { it.id == byId }
                ?: tabs.firstOrNull { it.name == byName }
                ?: return "没找到该项目"
            val oldDraft = draftTextOf(target.id)
            val oldIdx = tabs.indexOf(target)
            removeTabById(target.id)
            agentUndo.addLast(("删除项目「" + target.name.ifEmpty { "未命名" } + "」") to {
                tabs.add(oldIdx.coerceIn(0, tabs.size), target)
                if (oldDraft != null) writeDraft(target.id, oldDraft)
                saveTabs(); renderTabs(); renderProjectDrawer()
            })
            return "已删除项目「" + target.name.ifEmpty { "未命名" } + "」"
        }
        val t = tabs.getOrNull(current) ?: return "当前没有项目"
        // 先把界面上的输入框内容写回项目，再做操作
        switchTab(current)
        when (act) {
            "export" -> return if (exportTabPez(t)) {
                "已导出「" + t.name.ifEmpty { "未命名" } + "」"
            } else {
                "导出失败：缺少背景图/音频或没有草稿"
            }
            "copy" -> {
                val before = tabs.map { it.id }.toHashSet()
                duplicateTab(current)
                val created = tabs.lastOrNull { it.id !in before }
                if (created != null) {
                    agentUndo.addLast(("复制项目「" + created.name.ifEmpty { "未命名" } + "」") to {
                        try { File(filesDir, "drafts/" + created.id + ".json").delete() } catch (_: Exception) {}
                        removeTabById(created.id)
                    })
                }
                return "已复制项目「" + t.name.ifEmpty { "未命名" } + "」"
            }
            "delete" -> {
                val idx = current
                val draft = draftTextOf(t.id)
                removeTabById(t.id)
                agentUndo.addLast(("删除项目「" + t.name.ifEmpty { "未命名" } + "」") to {
                    tabs.add(idx.coerceIn(0, tabs.size), t)
                    if (draft != null) writeDraft(t.id, draft)
                    saveTabs()
                    renderTabs()
                    renderProjectDrawer()
                    if (tabs.isNotEmpty()) switchTab(idx.coerceIn(0, tabs.size - 1))
                })
                return "已删除项目「" + t.name.ifEmpty { "未命名" } + "」（可撤回）"
            }
        }
        return null
    }

    private fun runAgentUndo(): String? {
        val top = agentUndo.removeLastOrNull() ?: return "没有可撤回的 AI 操作"
        top.second.invoke()
        return "已撤回：" + top.first
    }

    /** 聊天标题栏的「撤回」按钮：撤掉上一条 AI 代理操作 */
    private fun aiUndoLast() {
        val top = agentUndo.removeLastOrNull()
        if (top == null) { toast("没有可撤回的 AI 操作"); return }
        top.second.invoke()
        val msg = "已撤回：" + top.first
        aiChatMessages.add(false to msg)
        AiChatStore.save(this, aiChatMessages)
        buildAiChatPanel()
        toast(msg)
    }

    /** 右侧抽屉：AI 接口设置（官方 + 自定义 N；左滑删/清空、右滑编辑、点击选用） */
    private fun buildAiSettingsPanel() {
        rightPanelBody.removeAllViews()
        val list = AiStore.load(this)
        val activeIdx = AiStore.activeIndex(this, list)
        aiTitle("AI 接口", "点一下选用 · 左滑删除（官方=清空） · 右滑编辑")
        for ((i, ep) in list.withIndex()) {
            val selected = i == activeIdx
            val desc = if (ep.usable) ep.name + " · " + ep.model else ep.name + " · 未配置"
            val v = TextView(this).apply {
                text = desc
                textSize = 12f
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = android.view.Gravity.CENTER_VERTICAL
                setTextColor(if (selected) 0xFF00E5FF.toInt() else 0xFFFFFFFF.toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (selected) 0x2200E5FF else 0x22FFFFFF)
                    setStroke(dp(1), if (selected) 0xFF00E5FF.toInt() else 0x66FFFFFF)
                    cornerRadius = dp(6).toFloat()
                }
                setPadding(dp(10), 0, dp(10), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(38)
                ).also { it.setMargins(dp(4), dp(4), dp(4), 0) }
                setOnClickListener {
                    AiStore.setActive(this@MainActivity, list, i)
                    buildAiSettingsPanel()
                    toast("已选用：" + ep.name)
                }
            }
            attachSwipeBoth(v,
                onLeft = { if (ep.official) confirmClearOfficial(i) else confirmDeleteEndpoint(i, ep) },
                onRight = { editAiEndpoint(i) })
            rightPanelBody.addView(v)
        }
        // 最下面：加号 → 新建「自定义N」
        rightPanelBody.addView(TextView(this).apply {
            text = "+"
            gravity = android.view.Gravity.CENTER
            setTextColor(0x66FFFFFF.toInt())
            textSize = 28f
            includeFontPadding = false
            setBackgroundColor(0x22FFFFFF)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)
            ).also { it.setMargins(dp(4), dp(4), dp(4), 0) }
            setOnClickListener { createAiEndpoint() }
        })
    }

    private fun confirmDeleteEndpoint(index: Int, ep: AiEndpoint) {
        android.app.AlertDialog.Builder(this)
            .setTitle("删除接口")
            .setMessage("删除「" + ep.name + "」？")
            .setPositiveButton("删除") { _, _ ->
                val list = AiStore.load(this)
                if (index in list.indices) list.removeAt(index)
                var active = AiStore.activeIndex(this, list)
                if (active >= list.size) active = list.size - 1
                AiStore.save(this, list, active)
                buildAiSettingsPanel()
                toast("已删除：" + ep.name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearOfficial(index: Int) {
        android.app.AlertDialog.Builder(this)
            .setTitle("清空接口内容")
            .setMessage("官方接口不能删除。清空它的 API Key 等内容？（地址和模型会恢复默认）")
            .setPositiveButton("清空") { _, _ ->
                val list = AiStore.load(this)
                if (index in list.indices) {
                    list[index].apiKey = ""
                    list[index].apiUrl = AiStore.OFFICIAL_URL
                    list[index].model = AiStore.OFFICIAL_MODEL
                    list[index].name = "DeepSeek 官方"
                }
                AiStore.save(this, list, AiStore.activeIndex(this, list))
                buildAiSettingsPanel()
                toast("已清空官方接口内容")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createAiEndpoint() {
        val list = AiStore.load(this)
        val ep = AiEndpoint(AiStore.nextCustomName(list), "", "", "deepseek-chat", false)
        list.add(ep)
        AiStore.save(this, list, AiStore.activeIndex(this, list))
        editAiEndpoint(list.size - 1)
    }

    /** 编辑接口：名称 / 地址 / API Key / 模型 */
    private fun editAiEndpoint(index: Int) {
        val list = AiStore.load(this)
        val ep = list.getOrNull(index) ?: return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), 0)
        }
        fun field(hint: String, value: String): EditText = EditText(this).apply {
            this.hint = hint
            setText(value)
            textSize = 12f
            setSingleLine(true)
        }
        val fName = field("名称", ep.name)
        val fUrl = field("接口地址（…/chat/completions）", ep.apiUrl)
        val fKey = field("API Key", ep.apiKey)
        val fModel = field("模型名（如 deepseek-chat）", ep.model)
        box.addView(TextView(this).apply {
            text = "名称"; textSize = 11f; setTextColor(0xFF333333.toInt())
        })
        box.addView(fName)
        box.addView(TextView(this).apply {
            text = "接口地址"; textSize = 11f; setTextColor(0xFF333333.toInt())
        })
        box.addView(fUrl)
        box.addView(TextView(this).apply {
            text = "API Key"; textSize = 11f; setTextColor(0xFF333333.toInt())
        })
        box.addView(fKey)
        box.addView(TextView(this).apply {
            text = "模型"; textSize = 11f; setTextColor(0xFF333333.toInt())
        })
        box.addView(fModel)
        android.app.AlertDialog.Builder(this)
            .setTitle("编辑接口")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val l = AiStore.load(this)
                val t = l.getOrNull(index) ?: return@setPositiveButton
                val name = fName.text.toString().trim()
                t.name = if (name.isEmpty()) t.name else name
                val url = fUrl.text.toString().trim()
                t.apiUrl = if (t.official && url.isEmpty()) AiStore.OFFICIAL_URL else url
                t.apiKey = fKey.text.toString().trim()
                t.model = if (fModel.text.toString().trim().isEmpty())
                    (if (t.official) AiStore.OFFICIAL_MODEL else "") else fModel.text.toString().trim()
                AiStore.save(this, l, AiStore.activeIndex(this, l))
                buildAiSettingsPanel()
                toast(if (t.usable) "已保存：" + t.name else "已保存（还缺地址或 API Key）")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 左右滑同一个 View：左滑回调 [onLeft]（删除/清空），右滑回调 [onRight]（编辑） */
    private fun attachSwipeBoth(v: View, onLeft: () -> Unit, onRight: () -> Unit) {
        var downX = 0f
        var downY = 0f
        var dragging = false
        var dir = 0                       // -1 = 左滑，+1 = 右滑
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    dragging = false; dir = 0
                    view.animate().cancel()
                    view.translationX = 0f
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && Math.abs(dx) > dp(8) && Math.abs(dx) > Math.abs(dy)) {
                        dragging = true
                        dir = if (dx < 0) -1 else 1
                    }
                    if (dragging) view.translationX = dx
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        view.performClick()
                    } else if (Math.abs(view.translationX) >= dp(60)) {
                        val w = view.width.toFloat().coerceAtLeast(1f)
                        view.animate()
                            .translationX(if (dir < 0) -w else w).alpha(0f)
                            .setDuration(130)
                            .withEndAction {
                                view.translationX = 0f
                                view.alpha = 1f
                                if (dir < 0) onLeft() else onRight()
                            }.start()
                    } else {
                        view.animate().translationX(0f).setDuration(130).start()
                    }
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    view.animate().translationX(0f).setDuration(130).start()
                    true
                }
                else -> false
            }
        }
    }

    /** GPL-3.0 许可证全文（res/raw/gpl3.txt），读不到时给网页链接兜底 */
    private fun gplText(): String = try {
        resources.openRawResource(R.raw.gpl3).bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        "许可证全文加载失败，请访问 https://www.gnu.org/licenses/gpl-3.0.html"
    }

    /** 右侧抽屉内容：版权声明 / 开源致谢（长按左侧「日志」打开） */
    private fun buildAboutPanel() {
        rightPanelBody.removeAllViews()

        fun head(txt: String, top: Int = 12): TextView = TextView(this).apply {
            text = txt
            setTextColor(0xFF00E5FF.toInt())
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
            setPadding(dp(2), dp(top), dp(2), dp(3))
        }
        fun body(txt: String, size: Float = 11f): TextView = TextView(this).apply {
            text = txt
            setTextColor(0xD9FFFFFF.toInt())
            textSize = size
            includeFontPadding = false
            setLineSpacing(0f, 1.16f)
            setPadding(dp(2), dp(1), dp(2), 0)
        }

        rightPanelBody.addView(TextView(this).apply {
            text = "版权声明 · Credits"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
            setPadding(dp(2), 0, dp(2), dp(4))
        })
        rightPanelBody.addView(body(
            "FRPHI 是面向 Phira 谱面生态的手机端录制 / 编辑工具。以下开源项目、资源与作者" +
                "为本项目提供了重要参考与素材，在此致谢。"
        ))

        rightPanelBody.addView(head("隐私政策"))
        rightPanelBody.addView(TextView(this).apply {
            text = "点此查看《FRPHI 隐私政策》全文"
            setTextColor(0xFF00E5FF.toInt())
            textSize = 11f
            includeFontPadding = false
            setPadding(dp(2), dp(2), dp(2), dp(4))
            setOnClickListener { showPrivacyDialog(false) }
        })

        rightPanelBody.addView(head("Phira"))
        rightPanelBody.addView(body(
            """
            版权所有：TeamFlos 及 Phira 贡献者
            许可证：GNU GPL-3.0
            源码：https://github.com/TeamFlos/phira（参考版本 0.8.2）

            借鉴内容：RPE 谱面解析（prpr/src/parse/rpe.rs）、判定与音效映射（judge.rs）、
            判定线高度、双押提示、hit_fx 等渲染参数，以及 PEZ 谱面格式的兼容实现。

            说明：本项目中参考 / 改编自 Phira 的部分按 GPL-3.0 分发。
            许可证全文：https://www.gnu.org/licenses/gpl-3.0.html
            """.trimIndent()
        ))

        rightPanelBody.addView(head("Re:PhiEdit（Phiedit-2573）"))
        rightPanelBody.addView(body(
            """
            版权所有：Copyright © 2025 程序小袁_2573
            许可证：MIT
            源码：https://github.com/Chengxuxiaoyuan-2573/Phiedit-2573（参考版本 0.2.2）

            借鉴内容：RPE 谱面格式、note type（1=Tap / 2=Hold / 3=Flick / 4=Drag）定义、
            moveX / moveY / rotate 坐标与缓动映射。
            """.trimIndent()
        ))

        rightPanelBody.addView(head("FRPHI"))
        rightPanelBody.addView(body(
            """
            版本号：${appVersionText()}
            开源协议：GNU GPL-3.0
            Copyright © 2026 FRPHI contributors

            本项目原创代码以 GNU GPL-3.0 开源，可自由使用、修改和分发；
            分发修改后的版本时须同样以 GPL-3.0 开放源码，并保留版权与许可声明。

            说明：项目中参考 / 改编自 Phira 的部分同样遵循 GPL-3.0；
            Re:PhiEdit 相关部分遵循其 MIT 许可证。
            """.trimIndent()
        ))
        rightPanelBody.addView(body(
            "本应用为社区工具，与 Phira、Re:PhiEdit 官方无隶属关系。" +
                "谱面中使用的音乐、曲绘等素材版权归各自权利人所有。"
        ))

        rightPanelBody.addView(head("GNU GPL-3.0 许可证全文"))
        rightPanelBody.addView(body(gplText(), 9.5f))

        rightPanelBody.addView(head("MIT 许可证全文（Re:PhiEdit）"))
        rightPanelBody.addView(body(
            """
            Copyright © 2025 程序小袁_2573（Re:PhiEdit）

            Permission is hereby granted, free of charge, to any person obtaining a copy
            of this software and associated documentation files (the "Software"), to deal
            in the Software without restriction, including without limitation the rights
            to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
            copies of the Software, and to permit persons to whom the Software is
            furnished to do so, subject to the following conditions:

            The above copyright notice and this permission notice shall be included in all
            copies or substantial portions of the Software.

            THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
            IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
            FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
            AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
            LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
            OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
            SOFTWARE.
            """.trimIndent(),
            9.5f
        ))

        rightPanelBody.addView(TextView(this).apply {
            text = "点空白处关闭"
            setTextColor(0x66FFFFFF.toInt())
            textSize = 10f
            includeFontPadding = false
            setPadding(dp(2), dp(14), dp(2), dp(6))
        })
    }

    /** 改密码位数（1~8） */
    private fun changePwLen(delta: Int) {
        coopLen = (coopLen + delta).coerceIn(1, 8)
        if (coopPw.length > coopLen) coopPw = coopPw.take(coopLen)
        coopLenView?.text = coopLen.toString()
        coopPwView?.text = pwText()
    }

    /** 右侧抽屉里的玩家列表（设备名 + ip），房间变化时实时刷新 */
    private fun renderCoopPlayers() {
        val box = coopPlayersBox ?: return
        box.removeAllViews()
        if (!Coop.isInRoom() || Coop.players.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "未联机"
                setTextColor(0x66FFFFFF.toInt()); textSize = 11f
                setPadding(dp(2), dp(4), 0, 0)
            })
            return
        }
        val ordered = Coop.orderedPlayers()
        for ((idx, p) in ordered.withIndex()) {
            box.addView(TextView(this).apply {
                text = (idx + 1).toString() + ". " + p.name + "  " + p.ip + when (p.color) {
                    Coop.COLOR_BLUE -> "  [蓝]"
                    Coop.COLOR_GREEN -> "  [绿]"
                    else -> ""
                } + if (p.id == Coop.myId) "  (我)" else ""
                setTextColor(
                    when (p.color) {
                        Coop.COLOR_BLUE -> 0xFF7FB6FF.toInt()
                        Coop.COLOR_GREEN -> 0xFF7CFF9B.toInt()
                        else -> 0xCCFFFFFF.toInt()
                    }
                )
                textSize = 11f
                setPadding(dp(2), dp(3), 0, dp(3))
            })
        }
    }

    /** 抽屉里的「日志」按钮：一键导出全部日志（联机日志 + 最近操作日志） */
    private var pendingLogText: String? = null

    private fun exportAllLogs() {
        val df = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val sb = StringBuilder(8192)
        sb.append("FRPHI 日志\n")
        sb.append("时间: ").append(df.format(java.util.Date())).append('\n')
        sb.append("设备: ").append(Coop.deviceName()).append('\n')
        sb.append("本机 IP: ").append(Coop.localIp()).append('\n')
        sb.append("网卡: ").append(Coop.ipCandidates().joinToString("，")).append('\n')
        sb.append("房间: ").append(
            if (Coop.isInRoom())
                (if (Coop.role == Coop.ROLE_HOST) "服务端" else "客户端") + " " + Coop.hostIp + ":" + Coop.port
            else "未联机"
        ).append('\n')
        sb.append("项目: ").append(tabs.getOrNull(current)?.name?.ifEmpty { "未命名" } ?: "-")
            .append("（共 ").append(tabs.size).append(" 个）\n")
        sb.append("\n==== 联机日志 ====\n").append(Coop.logText()).append('\n')
        sb.append("\n==== 最近操作日志 ====\n").append(DebugLog.dump()).append('\n')
        pendingLogText = sb.toString()
        // 文件名：frclog_20260505_120303.log（和编辑页原来的导出格式一致）
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "frclog_$stamp.log")
        }
        startActivityForResult(intent, REQ_MAIN_LOG)
    }

    private fun onCoopKey(k: String) {
        when (k) {
            "清空" -> coopPw = ""
            "⌫" -> if (coopPw.isNotEmpty()) coopPw = coopPw.dropLast(1)
            else -> if (coopPw.length < coopLen) coopPw += k
        }
        coopPwView?.text = pwText()
    }

    /** 读 ip / 端口（不合法就提示并返回 null） */
    private fun coopInputs(): Pair<String, Int>? {
        val ip = coopIpBox?.text.toString().trim()
        val p = coopPortBox?.text.toString().trim().toIntOrNull()
        if (ip.isEmpty()) { toast("请填写 IP"); return null }
        if (p == null || p !in 1..65535) { toast("端口要填 1~65535"); return null }
        return ip to p
    }

    // ---------------------------------------------------------------- 开房 / 加入
    private fun doHostRoom() {
        if (coopPw.length != coopLen) { toast("先输入 $coopLen 位密码（不输密码无法开启房间）"); return }
        val src = tabs.firstOrNull { it.id == framedTab } ?: boundSourceTab()
        if (src == null) { toast("先在抽屉里点一下要联机的谱子（红框）"); return }
        val inp = coopInputs() ?: return
        val (ip, p) = inp
        // 同一个 ip/端口/密码 再开一次 = 通道被占；改任意一个就能继续
        if (Coop.isInRoom() && Coop.hostIp == ip && Coop.port == p && Coop.password == coopPw) {
            toast("此通道已被占用"); return
        }
        if (hostWith(src, p, coopPw)) toast("房间已开启：$ip:$p")
    }

    /** 真正开房 + 造蓝框副本（服务端 / 快速服务端都走这里）；成功返回 true */
    private fun hostWith(src: TabData, p: Int, pw: String): Boolean {
        // 先把界面上正在编辑的内容落回当前项目：刚选完素材就开房时，src.music/image 才是最新的
        saveCurrent()
        if (Coop.isInRoom()) { demoteRoomTabs(); leaveRoomSilently() }
        val chart = draftTextOf(src.id).orEmpty()
        val err = Coop.host(
            pw, p, chart, metaJsonOf(src),
            src.music ?: "", src.image ?: ""
        )
        if (err != null) { toast(err); return false }
        // 副本：紧跟在被点击的谱子下面（不是末尾）
        val copy = copyOfTab(src)
        copy.roomRole = "host"
        copy.roomSource = src.id
        val srcIdx = tabs.indexOfFirst { it.id == src.id }
        if (srcIdx < 0) {                 // 素材/草稿都按 id 走：源不在列表里宁可报错，也别开错房间
            toast("找不到要联机的项目，重新在抽屉里长按它一下再开房")
            Coop.leave(notify = false)
            return false
        }
        val prevCurId = tabs.getOrNull(current)?.id
        tabs.add(srcIdx + 1, copy)
        rebindCurrent(prevCurId)          // 插入点在当前项目上面时，current 已经指错人了
        current = tabs.indexOfFirst { it.id == copy.id }.coerceAtLeast(0)
        restore(copy)                     // 主界面跟着房间副本走，不再停在原来那个项目上
        Coop.roomTabId = copy.id
        Coop.roomSourceTabId = src.id
        writeDraft(copy.id, chart)
        framedTab = src.id                 // 红框和蓝框绑定，红框不再消失
        saveTabs(); renderTabs(); renderProjectDrawer()
        coopStatus?.text = roomStatusText()
        renderCoopPlayers()
        return true
    }

    // ---------------------------------------------------------------- 快速客户端：60 秒找房窗口
    /**
     * 只有「快速客户端」是 60 秒窗口：按一下开始倒计时（按钮上显示秒数），
     * 期间每秒在局域网里搜一次；**再按一下 = 立刻结束**，60 秒到也结束。
     * 「作为客户端」是手填 IP 直连，只连一次，不是搜索。
     */
    @Volatile
    private var quickJoining = false
    private var quickJoinLeft = 0
    /** 每次开始/结束都 +1：后台循环靠它判断"这一轮是不是已经被取消或超时了" */
    @Volatile
    private var quickJoinSeq = 0

    private val quickJoinTick = object : Runnable {
        override fun run() {
            if (!quickJoining) return
            quickJoinLeft--
            if (quickJoinLeft <= 0) { endQuickJoin(); return }
            quickJoinBtn?.text = "快速客户端 " + quickJoinLeft + "s"
            ui.postDelayed(this, 1000)
        }
    }

    private fun beginQuickJoin() {
        quickJoinSeq++
        quickJoining = true
        quickJoinLeft = JOIN_WINDOW_SEC
        quickJoinBtn?.text = "快速客户端 " + quickJoinLeft + "s"
        ui.removeCallbacks(quickJoinTick)
        ui.postDelayed(quickJoinTick, 1000)
    }

    private fun endQuickJoin() {
        quickJoinSeq++
        quickJoining = false
        ui.removeCallbacks(quickJoinTick)
        quickJoinBtn?.text = "快速客户端"
    }

    private fun quickJoinAlive(seq: Int): Boolean = quickJoining && quickJoinSeq == seq

    /** 快速客户端：60 秒内不停找快速房间；找不到就静默结束 */
    private fun doQuickJoin() {
        if (Coop.isInRoom()) { toast("已经在房间里了，先退出"); return }
        if (quickJoining) { endQuickJoin(); return }        // 再点 = 结束
        beginQuickJoin()
        val seq = quickJoinSeq
        Thread {
            var room: Coop.QuickRoom? = null
            val deadline = System.currentTimeMillis() + JOIN_WINDOW_SEC * 1000L
            while (System.currentTimeMillis() < deadline) {
                if (!quickJoinAlive(seq)) break
                room = Coop.quickDiscover(1000)
                if (room != null) break
            }
            val found = room
            runOnUiThread {
                if (!quickJoinAlive(seq)) return@runOnUiThread    // 已取消 / 已超时
                endQuickJoin()
                if (found == null) return@runOnUiThread            // 搜不到：就这么结束
                coopIpBox?.setText(found.ip)
                coopPortBox?.setText(found.port.toString())
                coopPw = found.pw
                coopPwView?.text = pwText()
                joinWith(found.ip, found.port, found.pw)
            }
        }.start()
    }

    /** 作为客户端：手填 IP 直连，只连一次（连不上就结束，不占 60 秒） */
    private fun doJoinRoom() {
        if (coopPw.length != coopLen) { toast("先输入 $coopLen 位密码"); return }
        val inp = coopInputs() ?: return
        val ip = inp.first
        if (ip == Coop.localIp() || ip == "127.0.0.1" || ip == "localhost") {
            toast("这是本机 IP，要填「服务端设备」上显示的 IP")
            return
        }
        joinWith(ip, inp.second, coopPw)
    }

    /** 手动 / 快速 都只是"连上之后"共用这一段 */
    private fun joinWith(ip: String, p: Int, pw: String) {
        if (Coop.isInRoom()) { demoteRoomTabs(); leaveRoomSilently() }
        clientJoinBtn?.text = "连接中…"
        Thread {
            val err = Coop.join(ip, p, pw)
            runOnUiThread {
                clientJoinBtn?.text = "作为客户端"
                if (err != null) toast(err) else onJoinedOk()
            }
        }.start()
    }

    /** 连上了：建一个绿框临时项目并切过去 */
    private fun onJoinedOk() {
        saveCurrent()             // 先把界面上的内容落回原项目，再在最前面插新项
        val t = TabData()
        t.roomRole = "client"
        t.lastMod = System.currentTimeMillis()
        applyMetaJson(t, Coop.metaJson)
        t.name = t.name.ifEmpty { "联机房间" }
        tabs.add(0, t)
        current = 0
        restore(t); renderTabs()
        Coop.roomTabId = t.id
        renderProjectDrawer()
        coopStatus?.text = roomStatusText()
        renderCoopPlayers()
        toast("已加入房间")
    }

    // ---------------------------------------------------------------- 房间退出
    /**
     * 房间操作用弹窗：普通安卓弹窗，选项放在**底部一排按钮**上（主题默认的青绿色）。
     * 2 项：负 = options[0]，正 = options[1]；3 项：负 = options[0]，正 = options[1]，中 = options[2]。
     */
    private fun showRoomDialog(title: String, options: List<String>, onPick: (Int) -> Unit) {
        val b = android.app.AlertDialog.Builder(this).setTitle(title)
        when (options.size) {
            0 -> return
            1 -> b.setPositiveButton(options[0]) { _, _ -> onPick(0) }
            2 -> b.setNegativeButton(options[0]) { _, _ -> onPick(0) }
                .setPositiveButton(options[1]) { _, _ -> onPick(1) }
            else -> b.setNeutralButton(options[2]) { _, _ -> onPick(2) }
                .setNegativeButton(options[0]) { _, _ -> onPick(0) }
                .setPositiveButton(options[1]) { _, _ -> onPick(1) }
        }
        b.show()
    }

    private fun askExitHostRoom() {
        // 大退（划掉后台）之后 Coop 状态是空的，但项目上还留着蓝框 → 用 tab 兜底
        val copy = tabs.firstOrNull { it.id == Coop.roomTabId && it.roomRole == "host" }
            ?: tabs.firstOrNull { it.roomRole == "host" }
            ?: run { toast("没有可退出的房间副本"); return }
        showRoomDialog("关闭房间", listOf("不修改", "存到项目", "存到副本")) { which ->
            val chart = Coop.currentChart.ifEmpty { draftTextOf(copy.id).orEmpty() }
            when (which) {
                0 -> { leaveRoomSilently(); removeTabById(copy.id) }
                1 -> {
                    Coop.roomSourceTabId.takeIf { it.isNotEmpty() }
                        ?.let { src -> writeDraft(src, chart) }
                    leaveRoomSilently(); removeTabById(copy.id)
                }
                else -> { leaveRoomSilently(); copy.roomRole = null; copy.roomSource = null; saveTabs() }
            }
            renderTabs(); renderProjectDrawer()
            toast("已退出房间")
        }
    }

    /** 右滑蓝框：把房间副本的内容写回它绑定的那个项目（红框） */
    private fun askSaveRoomCopyToSource() {
        val copy = tabs.firstOrNull { it.id == Coop.roomTabId && it.roomRole == "host" }
            ?: tabs.firstOrNull { it.roomRole == "host" } ?: run {
            toast("房间副本不存在"); return
        }
        val srcId = copy.roomSource ?: return
        val src = tabs.firstOrNull { it.id == srcId } ?: return
        android.app.AlertDialog.Builder(this)
            .setTitle("保存至项目")
            .setPositiveButton("保存") { _, _ ->
                // 内存里的房间快照才是最新的（服务端没开编辑页时，草稿文件可能是旧的）
                val chart = Coop.currentChart.ifEmpty { draftTextOf(copy.id) ?: "" }
                if (chart.isEmpty()) { toast("副本还没有内容"); return@setPositiveButton }
                writeDraft(srcId, chart)
                refreshLastMod(src)
                renderProjectDrawer()
                toast("已保存到项目")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun askExitClientRoom() {
        val t = tabs.firstOrNull { it.id == Coop.roomTabId && it.roomRole == "client" }
            ?: tabs.firstOrNull { it.roomRole == "client" }
            ?: run { toast("没有可退出的房间"); return }
        showRoomDialog("退出房间", listOf("不保留副本", "保留副本")) { which ->
            val chart = Coop.currentChart
            if (which == 1) adoptRoomMedia(t)     // 先搬媒体，再退房（退房会删 roommedia/）
            leaveRoomSilently()
            if (which == 0) {
                removeTabById(t.id)
            } else {
                t.roomRole = null; t.roomSource = null
                t.lastMod = System.currentTimeMillis()
                writeDraft(t.id, chart)
                saveTabs()
            }
            renderTabs(); renderProjectDrawer()
            toast("已退出房间")
        }
    }

    /** 右滑绿框：把服务端当前谱面拷一份到本地（放最后，不覆盖同名项目） */
    private fun copyRoomChartToLocal() {
        val t = TabData()
        applyMetaJson(t, Coop.metaJson)
        t.name = t.name.ifEmpty { "联机房间" }
        t.roomRole = null
        t.lastMod = System.currentTimeMillis()
        adoptRoomMedia(t)
        writeDraft(t.id, Coop.currentChart)
        tabs.add(t)
        saveTabs(); renderTabs(); renderProjectDrawer()
        toast("已把服务端谱面复制到本地")
    }

    /** 蓝框副本绑定的那个项目 id（红框要一直留着） */
    private fun boundSourceId(): String? =
        (tabs.firstOrNull { it.id == Coop.roomTabId } ?: tabs.firstOrNull { it.roomRole == "host" })
            ?.roomSource

    private fun boundSourceTab(): TabData? =
        boundSourceId()?.let { id -> tabs.firstOrNull { it.id == id } }

    // ---------------------------------------------------------------- 快速联机
    /** 按一下开始 60s 倒计时开房；再按一下取消 */
    private fun toggleQuickHost() {
        if (quickHosting) { stopQuickHost(cancelRoom = true); return }
        val src = tabs.firstOrNull { it.id == framedTab } ?: boundSourceTab()
        if (src == null) { toast("先在抽屉里点一下要联机的谱子（红框）"); return }
        if (Coop.isInRoom()) { toast("已经在房间里了，先退出"); return }
        val p = coopPortBox?.text.toString().trim().toIntOrNull() ?: Coop.DEFAULT_PORT
        // 先听一下局域网里有没有别人已经开了快速房间 —— 有就"此通道已被占用"
        quickHostBtn?.text = "检查中…"
        Thread {
            val busy = Coop.quickDiscover(700) != null
            runOnUiThread {
                quickHostBtn?.text = "快速服务端"
                if (busy) { toast("此通道已被占用"); return@runOnUiThread }
                startQuickHostNow(src, p)
            }
        }.start()
    }

    private fun startQuickHostNow(src: TabData, p: Int) {
        // 快速模式不用手输密码：内部随机一个（**不写进输入框**，输入框保持空）
        val qpw = if (coopPw.length == coopLen) coopPw
        else (1..coopLen).map { (0..9).random() }.joinToString("")
        if (!hostWith(src, p, qpw)) return
        quickHosting = true
        quickLeft = 60
        // 广播里只放一次性令牌：真密码不上网（同网段抓包也拿不到房间密码）
        val token = (1..10).map { "abcdefghijkmnpqrstuvwxyz23456789".random() }.joinToString("")
        Coop.quickBeaconStart(p, token)
        tickQuick()
        toast("快速房间已开启，60 秒内让另一台设备点「快速客户端」")
    }

    private fun tickQuick() {
        if (!quickHosting) return
        quickHostBtn?.text = "快速服务端 ${quickLeft}s"
        if (Coop.players.size > 1) { stopQuickHost(cancelRoom = false); return }   // 有人进来了 → 停
        if (quickLeft <= 0) { stopQuickHost(cancelRoom = true); return }
        ui.postDelayed({ if (quickHosting) { quickLeft--; tickQuick() } }, 1000)
    }

    private fun stopQuickHost(cancelRoom: Boolean) {
        val wasHosting = quickHosting
        quickHosting = false
        Coop.quickBeaconStop()
        quickHostBtn?.text = "快速服务端"
        if (cancelRoom && Coop.isInRoom()) {
            val copyId = Coop.roomTabId
            leaveRoomSilently()
            if (copyId.isNotEmpty()) removeTabById(copyId)
            renderTabs(); renderProjectDrawer()
        }
        if (wasHosting) {
            coopStatus?.text = if (Coop.isInRoom()) roomStatusText() else "未联机"
            renderCoopPlayers()
        }
    }

    /** 换房间前清理上一间房的标记：服务端副本变回普通项目，客户端临时项删掉 */
    private fun demoteRoomTabs() {
        val keepId = tabs.getOrNull(current)?.id
        val drop = tabs.filter { it.roomRole == "client" }
        if (drop.isNotEmpty()) tabs.removeAll(drop)
        for (t in tabs) if (t.roomRole == "host") { t.roomRole = null; t.roomSource = null }
        rebindCurrent(keepId)
    }

    /** 客户端保留副本时：把房间里的音频/背景图搬进这个项目自己的目录（否则退房会一起删掉） */
    private fun adoptRoomMedia(t: TabData) {
        val dir = File(filesDir, "imported/" + t.id)
        dir.mkdirs()
        fun adopt(path: String?): String? {
            if (path.isNullOrEmpty()) return null
            val src = File(path)
            if (!src.exists()) return null
            val dst = File(dir, src.name)
            return try {
                src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                android.net.Uri.fromFile(dst).toString()
            } catch (_: Exception) {
                null
            }
        }
        adopt(Coop.mediaMusicPath)?.let { t.music = it }
        adopt(Coop.mediaImagePath)?.let { t.image = it }
    }

    private fun leaveRoomSilently() {
        Coop.leave(notify = false)
        coopStatus?.text = "未联机"
        coopPw = ""                       // 密码默认空
        coopPwView?.text = pwText()
        renderCoopPlayers()
    }

    /** 房间被动关闭（被踢/断线）：绿框临时项目直接撤掉 */
    private fun dropClientRoomTabs() {
        val before = tabs.size
        tabs.removeAll { it.roomRole == "client" }
        coopStatus?.text = if (Coop.isInRoom()) roomStatusText() else "未联机"
        renderCoopPlayers()
        if (tabs.size != before) {
            current = current.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
            if (tabs.isNotEmpty()) restore(tabs[current]) else clearInputs()
            renderTabs()
        }
        renderProjectDrawer()
    }

    private fun removeTabById(id: String) {
        val i = tabs.indexOfFirst { it.id == id }
        if (i < 0) return
        val keepId = tabs.getOrNull(current)?.id
        tabs.removeAt(i)
        if (tabs.isEmpty()) { current = 0; clearInputs() }
        else {
            rebindCurrent(keepId)
            restore(tabs[current])
        }
        drawerThumbs.remove(id)          // 删掉缩略图缓存，避免"幽灵图标"
        renderProjectDrawer()            // 抽屉立刻刷新
        saveTabs(); renderTabs()
    }
    private fun draftTextOf(id: String): String? = try {
        val f = File(filesDir, "drafts/" + id + ".json")
        if (f.exists()) f.readText() else null
    } catch (_: Exception) { null }

    private fun writeDraft(id: String, text: String) {
        if (!TabOps.isSafeId(id)) return
        try {
            val f = File(filesDir, "drafts/" + id + ".json")
            f.parentFile?.mkdirs()
            f.writeText(text)
        } catch (_: Exception) {}
    }

    /** 只做数据副本（不插进列表），草稿一起复制 */
    private fun copyOfTab(src: TabData): TabData {
        val t = TabData()
        t.name = src.name; t.composer = src.composer; t.charter = src.charter; t.level = src.level
        t.bpm = src.bpm; t.speed = src.speed; t.linePos = src.linePos; t.delay = src.delay
        t.bpmOffset = src.bpmOffset
        t.hLines = src.hLines; t.vLines = src.vLines
        t.music = src.music; t.image = src.image
        t.lastMod = System.currentTimeMillis()
        draftTextOf(src.id)?.let { writeDraft(t.id, it) }
        return t
    }

    /** 房间元信息（bpm/流速/判定线位置/横竖线…）：客户端要靠它才能正确渲染 */
    private fun metaJsonOf(t: TabData): String = org.json.JSONObject().apply {
        put("name", t.name); put("composer", t.composer); put("charter", t.charter)
        put("level", t.level); put("bpm", t.bpm); put("speed", t.speed)
        put("linePos", t.linePos); put("delay", t.delay)
        put("hLines", t.hLines); put("vLines", t.vLines)
    }.toString()

    private fun applyMetaJson(t: TabData, json: String) {
        if (json.isBlank()) return
        try {
            val o = org.json.JSONObject(json)
            t.name = o.optString("name", t.name)
            t.composer = o.optString("composer", t.composer)
            t.charter = o.optString("charter", t.charter)
            t.level = o.optString("level", t.level)
            t.bpm = o.optString("bpm", t.bpm)
            t.speed = o.optString("speed", t.speed)
            t.linePos = o.optString("linePos", t.linePos)
            t.delay = o.optString("delay", t.delay)
            t.bpmOffset = o.optString("bpmOffset", t.bpmOffset)
            t.hLines = o.optString("hLines", t.hLines)
            t.vLines = o.optString("vLines", t.vLines)
        } catch (_: Exception) {}
    }

    /** 打开某个项目的编辑页（联机项目也走这里） */
    private fun openRecordAt(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs[index]
        if (tab.roomRole == "client" && tab.music == null && Coop.mediaPending) {
            toast("正在接收音频，稍等一下…")
            return
        }
        saveCurrent()
        current = index
        restore(tabs[index])
        renderTabs()
        startRecordAt(index)
    }

    private fun startRecord() {
        if (tabs.isEmpty() || current !in tabs.indices) {
            toast("先点上面的 ＋ 新建一个项目")
            return
        }
        startRecordAt(current)
    }

    private fun startRecordAt(index: Int) {
        if (index !in tabs.indices) return
        current = index
        saveCurrent()
        val t = tabs[index]
        val name = t.name.ifEmpty { "未命名" }
        val composer = t.composer
        val charter = t.charter
        val level = t.level          // 未填写难度就不填
        val bpm = t.bpm.toDoubleOrNull()?.takeIf { it > 0 } ?: 120.0
        val speed = t.speed.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
        val linePos = t.linePos.toIntOrNull()?.coerceIn(0, 100) ?: 60
        val delay = t.delay.toIntOrNull() ?: 0
        val bpmOffset = t.bpmOffset.toIntOrNull() ?: 0
        val h = t.hLines.toIntOrNull()?.coerceAtLeast(1) ?: 4
        val v = t.vLines.toIntOrNull()?.coerceAtLeast(1) ?: 8
        // 素材可空：音频空 → 进入但无播放；背景空 → 纯色底
        val mu = t.music?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: Uri.EMPTY
        val iu = t.image?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: Uri.EMPTY

        val meta = ChartMeta(name, composer, charter, level, bpm, speed, linePos, delay, h, v, mu, iu, 0f, 0f, 0f, t.id, bpmOffset)
        saveTabs()
        startActivity(Intent(this, RecordActivity::class.java).putExtra("meta", meta))
    }

    /** 离开界面时落盘，避免被系统回收后丢失编辑内容与素材选择 */
    /** 隐藏系统通知栏（和录制页同一套沉浸式设置） */
    private fun applyFullscreen() {
        // 状态栏 + 底部三大金刚键一起隐藏；上滑底部会临时唤出（IMMERSIVE_STICKY 会自动再收起）
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()   // 下拉通知栏 / 从别的界面回来后重新隐藏
    }

    /** 联机回调：从编辑页回来后要重新绑（编辑页会覆盖同名回调） */
    private fun bindCoopCallbacks() {
        Coop.onChart = null          // 主界面不接谱面
        Coop.onStart = null
        Coop.onPlayers = {
            coopStatus?.text = if (Coop.isInRoom()) roomStatusText() else "未联机"
            renderCoopPlayers()
            // 有人进房间了 → 快速服务端的倒计时停掉
            if (quickHosting && Coop.players.size > 1) stopQuickHost(cancelRoom = false)
            renderProjectDrawer()
        }
        Coop.onMedia = { kind, path ->
            val t = tabs.firstOrNull { it.roomRole == "client" }
            if (t != null) {
                val uri = android.net.Uri.fromFile(File(path)).toString()
                if (kind == "image") {
                    t.image = uri
                    drawerThumbs.remove(t.id)      // 缩略图缓存要清掉，否则还是之前的占位图
                    toast("已接收背景图")
                } else {
                    t.music = uri
                    toast("已接收音频")
                }
                if (tabs.getOrNull(current)?.id == t.id) restore(t)
                renderProjectDrawer()
            }
        }
        Coop.onClosed = { msg ->
            toast(msg)
            dropClientRoomTabs()
        }
    }

    override fun onResume() {
        super.onResume()
        // 房间在别处被关掉（比如房主被踢）→ 回来时把残留的蓝/绿框项目降级成普通项目
        if (!Coop.isInRoom() && tabs.any { it.roomRole != null }) {
            demoteRoomTabs()
            saveTabs(); renderTabs()
        }
        bindCoopCallbacks()
        applySideBarTouchArea()      // 编辑页里改过"触摸兼容" → 回来重新算灰条判定区
        coopStatus?.text = if (Coop.isInRoom()) roomStatusText() else "未联机"
        if (tabs.isNotEmpty() && current in tabs.indices) {
            refreshLastMod(tabs[current])
            renderProjectDrawer()
        }
    }

    override fun onDestroy() {
        ui.removeCallbacks(quickJoinTick)
        quickJoining = false
        super.onDestroy()
        // 大退（从后台划掉 / 退出应用）→ 自动退出房间；服务端一退，所有客户端连接都会断
        if (isFinishing && Coop.isInRoom()) {
            if (quickHosting) { quickHosting = false }
            Coop.leave(notify = false)
        }
    }

    override fun onPause() {
        super.onPause()
        if (tabs.isNotEmpty()) {
            saveCurrent()
            saveTabs()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    /**
     * 用户要求：**不要底部 toast 弹窗**。
     * 提示改成写进右侧抽屉的状态行 + 联机日志（导出日志时能看到）。
     */
    private fun toast(msg: String) {
        Coop.log("提示：" + msg)
        try { coopStatus?.text = msg } catch (_: Exception) {}
    }
}