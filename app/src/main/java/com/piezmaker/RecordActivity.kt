package com.piezmaker

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.util.Log
import java.io.File

/** 录制页：三阶段制谱（drag/hold → click → flick）+ 播放控制 + 导出谱面（zip） */
class RecordActivity : Activity() {

    private companion object {
        const val TAG = "FRPHI"
        const val REQ_SAVE = 2001
        const val REQ_CLIP_IMPORT = 2004
        const val REQ_CLIP_EXPORT = 2003
        /** 选"图片线"的贴图 */
        const val REQ_LINE_IMAGE = 2005
        /** 选"视频背景"的视频文件 */
        const val REQ_VIDEO = 2006
        /** 图片线贴图在 filesDir 下的目录（不导出路径，只导出文件名） */
        const val LINE_IMG_DIR = "lineimg"
        const val KEY_META = "meta"
        /** 「其他」窗口（顶部遮罩同色）里的文字色：关=白，开=黄 */
        const val MENU_OFF = 0xFFFFFFFF.toInt()
        const val MENU_ON = 0xFFFFEB3B.toInt()
    }

    private lateinit var recordView: RecordView
    private lateinit var txtTitle: TextView
    private lateinit var txtInfo: TextView
    private lateinit var txtTime: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPause: TextView
    private lateinit var btnPass1: TextView
    private lateinit var btnPass2: TextView
    private lateinit var btnPass3: TextView
    private lateinit var btnPass4: TextView
    private lateinit var noiseEditPanel: android.widget.LinearLayout
    private lateinit var btnNoiseKey: TextView
    private lateinit var btnNoiseVisible: TextView
    private lateinit var btnNoiseWarning: TextView
    private lateinit var noiseX: EditText
    private lateinit var noiseY: EditText
    private lateinit var btnPlay: TextView
    /** 从主界面「选曲页」点进来的：自动进自己打，结束后出 Phira 风格结算 */
    private var fromPlayPage = false
    private var endingOverlay: View? = null
    /** 结算页键盘：ESC 重来、Enter 继续（指向 EndingOverlay 的两个回调） */
    private var endingRetry: (() -> Unit)? = null
    private var endingProceed: (() -> Unit)? = null
    /**
     * 这一局是「自己打」还是 Autoplay（进打歌时记下来）。
     * 结算「重来」要按原样重开，不能一律变成自己打。
     */
    private var roundManualPlay = false
    /** 第一排右边第 1 个「PLAY」：点一下进「自己打」，打歌中它变成 STOP */
    private lateinit var btnPlayManual: TextView
    private lateinit var btnSound: TextView
    private lateinit var btnSmooth: TextView
    private lateinit var btnCompat: TextView
    private lateinit var btnPinchZoom: TextView
    /** 「其他 → 操作 → 快捷操作」：0=关闭 1=开启 2=快速（全应用通用，frphi_rec/quickOp 持久化） */
    private lateinit var btnQuickOp: TextView
    private var quickOpMode: Int = 0
    /** 快速模式：只关掉"本次按键自己打开的"窗口，用户手动开着的窗口不动 */
    private var qHeldOther = false
    private var qHeldPlayer = false
    private var qHeldClip = false
    private var qHeldLine = false
    /** Q/E 长按期间用预览偏移累积，松手才真正 seek（和手指搓时间轴一套机制） */
    private var qSeekPending = false
    /** Ctrl+字母组合的 UP 也要吃掉，别漏给系统 */
    private var qCtrlCombo = false
    /** 键盘预选择框：作用域、当前下标、"/" 修饰键状态 */
    private var navScopeKey = ""
    private var kbNavIndex = -1
    private var kbSlashHeld = false
    private var kbSlashUsed = false
    /** 判定线左滑：按住 "<" 期间显示两个圆圈，松手选第 1 个、按 "/" 选第 2 个 */
    private var kbLineLeftPending = false
    private var kbLineLeftIndex = -1
    private var kbLineLeftDone = false
    private var kbLineLeftCircle = 1     // 1=不带音符，2=带音符；松 "<" 时才按它执行
    /** 「更多」菜单/抽屉的白色虚线焦点框（懒加载） */
    private var focusOverlay: KeyboardFocusOverlay? = null
    /** 谱面参数（更多 → 编辑 分类）：BPM / BPM偏移 / 横线数 / 竖线数 */
    private lateinit var bpmMenuEdit: EditText
    private lateinit var bpmOffsetMenuEdit: EditText
    private lateinit var hLinesMenuEdit: EditText
    private lateinit var vLinesMenuEdit: EditText
    /** 「更多 → 流速」：和 BPM 同尺寸，BPM 下方（用户点名：流速要能反复改，不限次数） */
    private lateinit var menuSpeedEdit: EditText
    private lateinit var btnDoubleHint: TextView
    private lateinit var btnPlaceLines: TextView
    private lateinit var btnLineHi: TextView
    private lateinit var btnGrid: TextView
    private lateinit var btnQuick: TextView
    private lateinit var btnHRotate: TextView
    private lateinit var btnReverse: TextView
    private lateinit var btnShowReverse: TextView
    private lateinit var btnGhostPlace: TextView
    private lateinit var btnGhostShow: TextView
    private lateinit var btnGhostFrame: TextView
    private lateinit var btnOverlapFrame: TextView
    private lateinit var btnCalib: TextView
    private lateinit var btnPlayer: TextView
    private lateinit var btnHideBars: TextView
    private lateinit var btnCopy: TextView
    private lateinit var btnPaste: TextView
    private var clipTmp: File? = null
    private lateinit var btnOther: TextView
    private lateinit var btnAutoScroll: TextView
    private lateinit var btnRealProcess: TextView
    private lateinit var menuScrim: View
    private lateinit var menuPanel: View
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var txtSpeedLabel: TextView
    private lateinit var speedEdit: EditText
    private lateinit var lineEditPanel: View
    /** 文字动画窗口（在判定线窗口下方，中间一条黑线，下滑划出） */
    private lateinit var textAnimPanel: View
    private lateinit var textAnimDivider: View
    private lateinit var linePanelGroup: View
    private lateinit var btnEnterFx: TextView
    private lateinit var btnExitFx: TextView
    private lateinit var editEnterMs: EditText
    private lateinit var editExitMs: EditText
    private lateinit var btnApplyAllText: TextView
    private var textAnimShown = false
    private var textAnimAnimator: ValueAnimator? = null
    private lateinit var editAngle: EditText
    private lateinit var editLayer: EditText
    private lateinit var editAlpha: EditText
    private lateinit var editLX: EditText
    private lateinit var editLY: EditText
    private lateinit var editLineSpeed: EditText
    private lateinit var editLineScale: EditText
    private lateinit var btnLineText: TextView
    private lateinit var scaleEdit: EditText
    private var curSpeed = 1.0f
    /** 进 Autoplay 之前的「制作线显示」模式（Autoplay 强制隐藏，退出时还原） */
    private var gridBeforePlay: Int? = null
    /** 进 Autoplay 之前的自定义缩放（打歌用 Phira 风格的固定缩放，退出时还原） */
    private var scaleBeforePlay: Float? = null
    /** 进 Autoplay 之前的上下遮罩状态（打歌自动收起，退出时还原） */
    private var barsBeforePlay: Boolean? = null
    private var draftFile: File? = null

    private lateinit var meta: ChartMeta
    // ---------------------------------------------------------------- 联机
    private var coopRole = 0              // 0=不在房间 1=服务端（蓝框副本） 2=客户端（绿框房间）
    /** 玩家聊天抽屉（联机）：顶部「退出~玩家列表」区域滑动拉出，与 AI 抽屉同款交互 */
    private var chatDrawer: ChatDrawer? = null
    private var chatSwipeTracking = false
    private var chatSwipeX = 0f
    private var chatSwipeY = 0f
    private var chatSwipePulled = false
    private val chatLoc = IntArray(2)              // 0=不在房间 1=服务端（蓝框副本） 2=客户端（绿框房间）
    /** 「你写我打」这一局是否正在跑（蓝方按了继续 / 绿方收到开始）。退出按钮看它，不看 playMode：
     *  这个模式其实不点亮 playMode（两边都只是跟着音乐放），所以以前用 playMode 判断永远进不去列表。 */
    private var coopGameOn = false
    /** 已发出「你写我打」开始请求、正在等各位点「接受」→ 把「玩家列表」按钮框住 */
    private var coopAskWaiting = false
    /** 接受/不接受弹窗正在显示（挡住重复的 ask） */
    private var coopAskDialogOn = false
    private var suppressSync = false      // 正在应用远端草稿 → 不要再推回去
    private var lastSynced = ""           // 最近一次收发过的草稿文本（去重用）
    private var syncPending = false
    private val syncRunnable = Runnable { pushChart() }
    /** 联机同步：单线程后台做全谱 encode，主线程只做引用快照 */
    private val syncExec by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }
    @Volatile private var syncSeq = 0L
    /** 用 hash + 长度短路，避免主线程对几十 MB 草稿做逐字符 equals */
    private var lastSyncedHash = 0
    private var lastSyncedLen = -1
    private var coopDelayEdit: EditText? = null
    private var playerListBox: LinearLayout? = null
    private var mediaPlayer: MediaPlayer? = null
    private var exportTmp: File? = null
    // 导出谱面：后台打包 + 进度弹窗（和导入谱面同一套交互）
    private var exportDlg: android.app.AlertDialog? = null
    private var exportBar: ProgressBar? = null
    private var exportInfo: TextView? = null
    private var exportOk: android.widget.Button? = null
    private var exportTicker: Runnable? = null
    private var exporting = false
    private var durationMs: Int = 0
    private var seeking = false
    /** 草稿是否有未保存改动（保存后清零；退出时没改动就不弹窗） */
    private var chartDirty = false

    private val handler = Handler(Looper.getMainLooper())
    // 播放头：不再每帧调 MediaPlayer.currentPosition（Binder 调用 + 抖动），
    // 改成"锚点 + 墙钟"推算，每 0.5 秒校正一次；seek/变速时立刻重置锚点。
    private var phBaseMs = 0L
    private var phBaseAt = 0L
    private var phLastSync = 0L
    /**
     * 设备音频输出延迟（**只做诊断，不参与时钟计算**）。
     * MediaPlayer 没有 AudioContext.getOutputTimestamp() 那种「正在被听到的帧」接口，
     * 只能按 Android 官方属性估算。FRPHI 的 meta.delayMs 是导出时对音符时间的平移，
     * 校准值里已经含了这一项；再在 currentPlayhead() 里减一次会双重补偿，
     * 所以这里仅写进日志，供「延迟偏大」时对照。
     */
    private val audioOutputLatencyMs: Long by lazy {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val frames = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
            val rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
            AudioLatency.outputLatencyMs(frames, rate)
        } catch (_: Exception) {
            0L
        }
    }
    /** 拖进度条松手后要等播放器 seek 到的目标位置（到位后撤掉预览偏移） */
    private var cursorSeekTarget: Int? = null
    /**
     * 没选音乐时的虚拟播放头。
     * 以前 mediaPlayer == null 时 currentPlayhead() 恒返回 0，而 flushEditSeek() 会清掉
     * editSeekOffset ⇒ 纯手写谱（无音频）搓完时间轴一松手，画面整个弹回 0。
     */
    private var virtualPlayheadMs: Long = 0L

    private fun resetPlayheadAnchor() {
        phLastSync = 0L
    }

    private fun currentPlayhead(): Long {
        val mp = mediaPlayer ?: return virtualPlayheadMs
        val now = android.os.SystemClock.elapsedRealtime()
        val playing = try { mp.isPlaying } catch (_: Exception) { false }
        if (!playing || now - phLastSync > 500L) {
            val pos = try { mp.currentPosition.toLong() } catch (_: Exception) { 0L }
            // 播完 / seek 到末尾后 MediaPlayer 可能把 currentPosition 回绕成 0：
            // 这时若滑块还停在后面，以滑块为准，别让谱面「跳回开头」
            phBaseMs = if (pos <= 0L && !playing && durationMs > 0 && seekBar.progress > 0)
                seekBar.progress.toLong() else pos
            phBaseAt = now
            phLastSync = now
            // 拖动进度条后：等播放器真的到了目标位置，再撤掉"预览偏移"，画面不会弹回去
            cursorSeekTarget?.let { t ->
                if (Math.abs(phBaseMs - t) <= 120L) {
                    cursorSeekTarget = null
                    if (::recordView.isInitialized) recordView.dropSeekPreview()
                } else {
                    // seekTo 是异步的：部分 ROM 暂停态下 currentPosition 不会立刻更新，
                    // 这一帧若把旧位置返回去，松手瞬间画面会弹回搓动前的位置。
                    phBaseMs = t.toLong()
                    phBaseAt = now
                }
            }
            return phBaseMs
        }
        return phBaseMs + Math.round((now - phBaseAt) * curSpeed.toDouble())
    }

    private val ticker = object : Runnable {
        override fun run() {
            val mp = mediaPlayer
            // 拖动中 / 松手后播放器还没 seek 到位时，别用播放器的旧位置把滑块拽回去
            if (mp != null && !seeking && cursorSeekTarget == null) {
                try {
                    val pos = mp.currentPosition
                    // 播放完成后 currentPosition 可能回绕成 0：不要用它把末尾的滑块拽回开头
                    if (!(pos <= 0 && seekBar.progress > 0 && !mp.isPlaying)) {
                        if (durationMs > 0) seekBar.progress = pos
                    }
                } catch (_: Exception) {}
            }
            updateTimeDisplay()
            handler.postDelayed(this, 120)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 进程被回收后可能直接重建编辑页（不经过主界面），这里也要装崩溃落盘
        CrashLog.install(this)

        // 进程被系统回收后可能直接重建编辑页（不经过主界面），这里也要保证项目路径已初始化
        ProjectStorage.init(getExternalFilesDir(null) ?: filesDir, filesDir)
        ProjectStorage.migrateLegacy()

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        setContentView(R.layout.activity_record)
        DebugLog.add("boot", "RecordActivity: setContentView ok")

        // ★ 以前 getParcelableExtra 返回 null 就直接 finish()，用户看到的就是
        //   「点开始录制后闪退」，而且这条分支只写联机日志、DebugLog 一片空白。
        //   现在：API 33+ 走带 Class 的重载、捕获 unparcel 异常、拿不到就用默认参数
        //   继续进编辑页（不再闪退），并把现场写进日志供崩溃落盘带走。
        meta = readMeta() ?: ChartMeta(
            "未命名", "", "", "", 120.0, 1.0, 60, 0, 4, 8, Uri.EMPTY, Uri.EMPTY
        )
        DebugLog.add("boot", "meta ok: name=${meta.name} bpm=${meta.bpm} tab=${meta.tabId}")

        recordView = findViewById(R.id.recordView)
        // 当前资源包（在设置页导入/切换的那个）；没有就用内置贴图与音效
        recordView.setResourcePack(ResPackStore.active(this))
        recordView.preloadSounds()      // 音效提前加载，避免第一下有延迟
        txtTitle = findViewById(R.id.txtTitle)
        txtInfo = findViewById(R.id.txtInfo)
        txtTime = findViewById(R.id.txtTime)
        seekBar = findViewById(R.id.seekBar)
        btnPause = findViewById(R.id.btnPause)
        btnPass1 = findViewById(R.id.btnPass1)
        btnPass2 = findViewById(R.id.btnPass2)
        btnPass3 = findViewById(R.id.btnPass3)
        btnPass4 = findViewById(R.id.btnPass4)
        btnPlay = findViewById(R.id.btnRestart)
        btnPlayManual = findViewById(R.id.btnPlayManual)
        btnSound = findViewById(R.id.btnSound)
        btnSmooth = findViewById(R.id.btnSmooth)
        btnCompat = findViewById(R.id.btnCompat)
        btnPinchZoom = findViewById(R.id.btnPinchZoom)
        btnQuickOp = findViewById(R.id.btnQuickOp)
        btnQuickOp.setOnClickListener {
            quickOpMode = (quickOpMode + 1) % 3
            // 立即落盘：全应用通用，重启（含杀进程）不丢；不依赖 onPause 的批量保存
            try {
                getSharedPreferences("frphi_rec", 0).edit()
                    .putInt("quickOp", quickOpMode).apply()
            } catch (_: Exception) {}
            refreshQuickOpButton()
        }
        refreshQuickOpButton()
        btnDoubleHint = findViewById(R.id.btnDoubleHint)
        btnPlaceLines = findViewById(R.id.btnPlaceLines)
        txtSpeedLabel = findViewById(R.id.txtSpeedLabel)
        applyRecorderSettings()          // 「更多」里的开关：沿用上次的设置，不要每次进编辑页重置
        if (::btnQuickOp.isInitialized) refreshQuickOpButton()   // 持久值恢复后同步三态按钮文字
        // 自动校准：把"离粗线一格"的音符吸到粗线上（可撤销）
        // 玩家列表：房间内显示玩家（设备名 + ip），左滑踢出 / 右滑框蓝绿
        btnPlayer = findViewById(R.id.btnPlayer)
        btnPlayer.setOnClickListener { onPlayerButton() }
        // 校准挪进「更多」菜单
        btnCalib = findViewById(R.id.btnCalib)
        btnCalib.setOnClickListener { askAutoCalibrate() }
        // 隐藏遮罩 / 恢复遮罩
        btnHideBars = findViewById(R.id.btnHideBars)
        btnHideBars.setOnClickListener {
            recordView.hideBars = !recordView.hideBars
            refreshBarsButton()
            // 只在用户点按钮时落盘：播放/退出打歌会临时改 hideBars，批量 save 会把临时值写进去
            try {
                getSharedPreferences("frphi_rec", 0).edit()
                    .putBoolean("hideBars", recordView.hideBars).apply()
            } catch (_: Exception) {}
        }
        // 复制 / 粘贴（长按复制 = 剪切；长按粘贴 = 剪贴板面板，下一步做）
        btnCopy = findViewById(R.id.btnCopy)
        // 复制/剪切/粘贴都不弹提示（用户要求静默）
        btnCopy.setOnClickListener {
            recordView.copySelection()
            refreshInfo()
        }
        btnCopy.setOnLongClickListener {
            recordView.cutSelection()
            refreshInfo()
            true
        }
        btnPaste = findViewById(R.id.btnPaste)
        btnPaste.setOnClickListener {
            recordView.pasteClipboard()
            refreshInfo()
        }
        // 剪贴板面板（左侧灰条滑出）：导入 / 导出
        recordView.onClipImport = { pickClipImport() }
        recordView.onClipExport = { idx -> exportClipEntry(idx) }
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        refreshBarsButton()          // 必须等上面两个 lateinit 赋值之后再刷新
        // 侧边窗口（剪贴板 / 判定线）现在**贴着屏幕边、上下顶到边缘**，会盖住顶部底部两条栏：
        // 窗口开着时 refreshBarsButton() 会自动把两条栏（连同触摸热区）收起来，关掉再恢复。
        recordView.onPanelStateChanged = { refreshBarsButton() }
        // 遮罩高度**每帧**现取：不依赖 OnLayoutChangeListener 的回调时序（以前靠它灌值，
        // 某些时序下压根不回调，遮罩就整条不画了 —— 用户报的"遮罩怎么没了"）。
        recordView.maskBandsProvider = { out ->
            if (recordView.hideBars) {
                out[0] = 0f; out[1] = 0f
            } else {
                out[0] = if (topBar.visibility == View.VISIBLE) topBar.height.toFloat() else 0f
                out[1] = if (bottomBar.visibility == View.VISIBLE)
                    (recordView.height - bottomBar.top).toFloat() else 0f
            }
        }

        // 判定线放置线：单个 = 只画选中的那条；全部 = 每条判定线都画
        btnPlaceLines.setOnClickListener {
            recordView.placeLinesAll = !recordView.placeLinesAll
            refreshPlaceLinesButton()
        }
        refreshPlaceLinesButton()
        // 线高亮：开=编辑模式下所有判定线都正常显示；关=只显示选中那条（其它变淡）
        btnLineHi = findViewById(R.id.btnLineHi)
        btnLineHi.setOnClickListener {
            recordView.highlightAllLines = !recordView.highlightAllLines
            refreshLineHiButton()
        }
        refreshLineHiButton()
        // 制作线显示：横线 → 竖线 → 全部 → 隐藏
        btnGrid = findViewById(R.id.btnGrid)
        btnGrid.setOnClickListener {
            recordView.gridMode = (recordView.gridMode + 1) % 4
            refreshGridButton()
        }
        refreshGridButton()
        // 快速切换：开=编辑模式下点判定线就切过去；关=只能用右侧抽屉换线
        btnQuick = findViewById(R.id.btnQuick)
        btnQuick.setOnClickListener {
            recordView.quickSwitch = !recordView.quickSwitch
            refreshQuickButton()
        }
        refreshQuickButton()
        // 横向转动：横向时间网格跟着选中的判定线一起转
        btnHRotate = findViewById(R.id.btnHRotate)
        btnHRotate.setOnClickListener {
            recordView.hGridRotate = !recordView.hGridRotate
            refreshHRotateButton()
        }
        refreshHRotateButton()
        // 反面音符：开=线下方放反面/上方放正面；关=点哪儿都是正面
        btnReverse = findViewById(R.id.btnReverse)
        btnReverse.setOnClickListener {
            recordView.reverseEnabled = !recordView.reverseEnabled
            refreshReverseButton()
            try {
                getSharedPreferences("frphi_rec", 0).edit()
                    .putBoolean("reverse", recordView.reverseEnabled).apply()
            } catch (_: Exception) {}
        }
        refreshReverseButton()
        // 显示反面音符：开启后反面音符套蓝框
        btnShowReverse = findViewById(R.id.btnShowReverse)
        btnShowReverse.setOnClickListener {
            recordView.markReverse = !recordView.markReverse
            refreshShowReverseButton()
        }
        refreshShowReverseButton()
        // 假音符：开启后新放的音符都是假音符（fake，只显示不判定）
        btnGhostPlace = findViewById(R.id.btnGhostPlace)
        btnGhostPlace.setOnClickListener {
            recordView.ghostPlace = !recordView.ghostPlace
            refreshGhostPlaceButton()
        }
        refreshGhostPlaceButton()
        // 显示假音符：显示 → 淡化（没有"隐藏"）
        btnGhostShow = findViewById(R.id.btnGhostShow)
        btnGhostShow.setOnClickListener {
            recordView.ghostShow = (recordView.ghostShow + 1) % 2
            refreshGhostShowButton()
        }
        refreshGhostShowButton()
        // 框住假音符（绿框）
        btnGhostFrame = findViewById(R.id.btnGhostFrame)
        btnGhostFrame.setOnClickListener {
            recordView.ghostFrame = !recordView.ghostFrame
            refreshGhostFrameButton()
        }
        refreshGhostFrameButton()
        // 重叠开关：开=框住重叠音符（橙框）；关=只斜 45°
        btnOverlapFrame = findViewById(R.id.btnOverlapFrame)
        btnOverlapFrame.setOnClickListener {
            recordView.overlapFrame = !recordView.overlapFrame
            refreshOverlapFrameButton()
        }
        refreshOverlapFrameButton()
        // 「其他」二级窗口：以屏幕右上角为右上角，点背景关闭
        btnOther = findViewById(R.id.btnOther)
        menuScrim = findViewById(R.id.menuScrim)
        menuPanel = findViewById(R.id.menuPanel)
        btnOther.setOnClickListener { showOtherMenu(menuPanel.visibility != View.VISIBLE) }
        // 长按「其他」= 切换噪域：显示+断触 → 只显示不断触 → 隐藏 → 显示+断触
        btnOther.setOnLongClickListener {
            when {
                !recordView.noiseOverlay -> { recordView.noiseOverlay = true; recordView.noiseJamEnabled = true }
                recordView.noiseJamEnabled -> { recordView.noiseJamEnabled = false }
                else -> { recordView.noiseOverlay = false }
            }
            recordView.invalidate()
            toast("UI执行成功")
            true
        }
        menuScrim.setOnClickListener { showOtherMenu(false) }
        addAiMenuButtons()
        addFullModeButton()
        addNoteInfoButton()
        setupExtraLines()
        setupAiDrawer()
        // 触摸兼容：按一下切换 关闭 → 框架兼容 → 全部兼容（改善手机端误触）
        btnCompat.setOnClickListener {
            recordView.compatMode = (recordView.compatMode + 1) % 3
            // 立刻落盘：主界面的灰条判定区要跟着它放大/还原（不能等退出页面才保存）
            try {
                getSharedPreferences("frphi_rec", 0).edit()
                    .putInt("compatMode", recordView.compatMode).apply()
            } catch (_: Exception) {}
            refreshCompatButton()
            // 兼容模式切换不再弹 toast（用户要求）
        }
        // 双指缩放开关：「触摸」分类里（双指近似同时按下张合 = 缩放谱面 0.01~100）
        btnPinchZoom.setOnClickListener {
            recordView.pinchEnabled = !recordView.pinchEnabled
            refreshPinchZoomButton()
            // ★ 双指缩放是全局设置：和「更多」里其它开关一样落盘，多项目通用、重启不丢
            try {
                getSharedPreferences("frphi_rec", 0).edit()
                    .putBoolean("pinchEnabled", recordView.pinchEnabled).apply()
            } catch (_: Exception) {}
        }
        refreshPinchZoomButton()
        // 双押贴图开关：只在制作界面生效（打歌界面始终用 mh 贴图）
        btnDoubleHint.setOnClickListener {
            recordView.editDoubleHint = !recordView.editDoubleHint
            refreshDoubleHintButton()
        }
        // 平滑移动开关：开启后放置/移动不吸附横竖线交叉点
        btnSmooth.setOnClickListener {
            recordView.smoothMove = !recordView.smoothMove
            refreshSmoothButton()
        }
        refreshSmoothButton()
        // 音符自动移动：拖动音符贴到遮罩边缘时是否自动滚时间轴（默认开）
        btnAutoScroll = findViewById(R.id.btnAutoScroll)
        btnAutoScroll.setOnClickListener {
            recordView.autoEdgeScroll = !recordView.autoEdgeScroll
            refreshAutoScrollButton()
        }
        refreshAutoScrollButton()
        // 真实处理：编辑模式里"打过的音符"显示 / 淡化 / 隐藏（默认显示）
        btnRealProcess = findViewById(R.id.btnRealProcess)
        btnRealProcess.setOnClickListener {
            recordView.realProcess = (recordView.realProcess + 1) % 3
            refreshRealProcessButton()
        }
        refreshRealProcessButton()
        speedEdit = findViewById(R.id.speedEdit)
        // 「编辑」类「倍速」「帧率」默认填白字：倍速 = 当前流速；
        // 帧率框当前只是**占位**（渲染/全局帧率功能已删除，用户点名保留占位框）
        speedEdit.setText(curSpeed.toString())
        speedEdit.setTextColor(0xFFFFFFFF.toInt())
        findViewById<EditText>(R.id.renderFpsEdit)?.let { fe ->
            fe.setText(deviceRefreshRate().toString())
            fe.setTextColor(0xFFFFFFFF.toInt())
        }
        lineEditPanel = findViewById(R.id.lineEditPanel)
        linePanelGroup = findViewById(R.id.linePanelGroup)
        textAnimPanel = findViewById(R.id.textAnimPanel)
        textAnimDivider = findViewById(R.id.textAnimDivider)
        btnEnterFx = findViewById(R.id.btnEnterFx)
        btnExitFx = findViewById(R.id.btnExitFx)
        editEnterMs = findViewById(R.id.editEnterMs)
        editExitMs = findViewById(R.id.editExitMs)
        btnApplyAllText = findViewById(R.id.btnApplyAllText)
        bindTextAnimPanel()          // ★ 文字动画绑定必须在这批 findViewById 之后
        editAngle = findViewById(R.id.editAngle)
        editAlpha = findViewById(R.id.editAlpha)
        editLX = findViewById(R.id.editLX)
        editLY = findViewById(R.id.editLY)
        editLineSpeed = findViewById(R.id.editLineSpeed)
        editLineScale = findViewById(R.id.editLineScale)
        // 噪域编辑面板（长按噪域进入编辑时显示）
        noiseEditPanel = findViewById(R.id.noiseEditPanel)
        btnNoiseKey = findViewById(R.id.btnNoiseKey)
        btnNoiseVisible = findViewById(R.id.btnNoiseVisible)
        btnNoiseWarning = findViewById(R.id.btnNoiseWarning)
        noiseX = findViewById(R.id.noiseX)
        noiseY = findViewById(R.id.noiseY)
        recordView.lineSpeed = meta.speed.toFloat()

        // 判定线编辑输入面板：进入显示 / 退出隐藏；姿态变化实时同步输入框
        fun bindLineInput(v: EditText, apply: (Float?) -> Unit) {
            v.setOnEditorActionListener { _, _, _ -> apply(v.text.toString().toFloatOrNull()); v.clearFocus(); true }
            v.setOnFocusChangeListener { _, has -> if (!has) apply(v.text.toString().toFloatOrNull()) }
        }
        bindLineInput(editAngle) { it?.let { f -> recordView.lineRotate = f; recordView.invalidate() } }
        bindLineInput(editAlpha) { it?.let { f ->
            // 输入按 0~100（%）；填 >100 的按 0~255 归一，方便直接抄 RPE 的 alpha
            recordView.lineAlpha = if (f > 100f) (f / 255f).coerceIn(0f, 1f) else (f / 100f).coerceIn(0f, 1f)
        } }
        bindLineInput(editLX) { it?.let { f -> recordView.lineMoveX = f.coerceIn(-1f, 1f); recordView.invalidate() } }
        bindLineInput(editLY) { it?.let { f -> recordView.lineMoveY = f.coerceIn(-1f, 1f); recordView.invalidate() } }
        bindLineInput(editLineSpeed) { it?.let { f ->
            if (f in 0f..100f) { recordView.lineSpeed = f; refreshInfo() }
        } }
        bindLineInput(editLineScale) { it?.let { f ->
            // 1 = 默认大小；<1 缩小，>1 放大（图片线以"铺满谱面宽度"为 1）
            if (f > 0f) { recordView.lineScale = f; recordView.invalidate() }
        } }
        // 噪域编辑：X/Y 改当前选中的角（写入当前时刻的矩形关键帧）
        bindLineInput(noiseX) { it?.let { f -> recordView.setNoiseCornerX(f) } }
        bindLineInput(noiseY) { it?.let { f -> recordView.setNoiseCornerY(f) } }
        btnNoiseKey.setOnClickListener {
            recordView.addNoiseKeyframe()
            toast("已在当前时间打关键帧")
        }
        btnNoiseVisible.setOnClickListener { recordView.toggleNoiseVisible() }
        btnNoiseWarning.setOnClickListener { recordView.toggleNoiseWarning() }
        // 图层（zOrder，整数）：大的画在上层；多条线可以同图层
        editLayer = findViewById(R.id.editLayer)
        // ★ 右上角窗口所有输入框：第一次点击全选内容，再点一下光标定位到点击处（系统原生
        //   行为）。必须在 editLayer 等 **全部 findViewById 之后**执行 —— 以前放在这行
        //   之前，直接访问未初始化的 editLayer 崩（真机日志 UninitializedPropertyAccessException）。
        listOf(editLayer, editAlpha, editAngle, editLineScale, editLineSpeed, editLX, editLY,
            noiseX, noiseY, editEnterMs, editExitMs).forEach { it.setSelectAllOnFocus(true) }
        editLayer.setOnEditorActionListener { _, _, _ ->
            editLayer.text.toString().trim().toIntOrNull()?.let { recordView.setLineZOrder(recordView.activeLineIdForEdit, it) }
            editLayer.clearFocus()
            true
        }
        editLayer.setOnFocusChangeListener { _, has ->
            if (!has) editLayer.text.toString().trim().toIntOrNull()?.let { recordView.setLineZOrder(recordView.activeLineIdForEdit, it) }
        }
        // 退出判定线/关键帧编辑前（点背景、拖时间轴）把输入框里还没失焦的数值写回这一帧/这条线
        recordView.onLineEditSave = {
            editAngle.clearFocus()
            editLX.clearFocus()
            editLY.clearFocus()
            editAlpha.clearFocus()
            editLineSpeed.clearFocus()
            editLineScale.clearFocus()
            noiseX.clearFocus()
            noiseY.clearFocus()
        }
        // 缩放：仅输入框（0.01~100），默认 0.1
        scaleEdit = findViewById(R.id.scaleEdit)
        recordView.timelineScale = 0.1f
        scaleEdit.setText("0.1")
        fun applyScale(sc: Float) {
            val v = sc.coerceIn(0.01f, 100f)
            recordView.timelineScale = v
            scaleEdit.setText(fmtNum(v))
            recordView.invalidate()
        }
        scaleEdit.setOnEditorActionListener { v, _, _ ->
            v.text.toString().toFloatOrNull()?.let { applyScale(it) }
            v.clearFocus()
            true
        }
        scaleEdit.setOnFocusChangeListener { _, has ->
            if (!has) { scaleEdit.text.toString().toFloatOrNull()?.let { applyScale(it) } }
        }

        // 双指缩放同步输入框（正在手输时不打扰）
        recordView.onScaleChanged = { v ->
            if (!scaleEdit.hasFocus()) scaleEdit.setText(fmtNum(v))
        }

        // 谱面参数（更多 → 编辑）：BPM / BPM偏移 / 流速 / 横线数 / 竖线数。
        // 每份项目独立（saveMetaToTab 落盘），重启应用不会重置；输入框也和「缩放」一样
        // 首次点击全选（XML 里 selectAllOnFocus）。
        bpmMenuEdit = findViewById(R.id.bpmMenuEdit)
        bpmOffsetMenuEdit = findViewById(R.id.bpmOffsetMenuEdit)
        hLinesMenuEdit = findViewById(R.id.hLinesMenuEdit)
        vLinesMenuEdit = findViewById(R.id.vLinesMenuEdit)
        fun menuNum(e: EditText, show: () -> String, apply: (Float) -> Unit) {
            e.setText(show())
            e.setOnEditorActionListener { v, _, _ -> v.text.toString().toFloatOrNull()?.let(apply); v.clearFocus(); true }
            e.setOnFocusChangeListener { _, has -> if (!has) e.text.toString().toFloatOrNull()?.let(apply) }
        }
        // ★ 谱面级参数（BPM/偏移/横线/竖线）统一走 recordView.editMeta：同步网格 + 记一步撤销
        menuNum(bpmMenuEdit, { fmtNum(meta.bpm.toFloat()) }) { v ->
            recordView.editMeta(bpm = v.toDouble(), segments = emptyList())
            meta = meta.copy(bpm = v.toDouble(), bpmSegments = emptyList())
            saveMetaToTab(); refreshInfo(); recordView.invalidate()
        }
        menuNum(bpmOffsetMenuEdit, { fmtNum(meta.bpmOffsetMs.toFloat()) }) { v ->
            recordView.editMeta(bpmOffsetMs = v.toDouble())
            meta = meta.copy(bpmOffsetMs = v.toDouble())
            saveMetaToTab(); refreshInfo(); recordView.invalidate()
        }
        menuNum(hLinesMenuEdit, { meta.hLines.toString() }) { v ->
            val n = v.toInt().coerceIn(1, 64)
            recordView.editMeta(hLines = n)
            meta = meta.copy(hLines = n)
            saveMetaToTab(); refreshInfo(); recordView.invalidate()
        }
        menuNum(vLinesMenuEdit, { meta.vLines.toString() }) { v ->
            val n = v.toInt().coerceIn(1, 64)
            recordView.editMeta(vLines = n)
            meta = meta.copy(vLines = n)
            saveMetaToTab(); refreshInfo(); recordView.invalidate()
        }
        // 流速：写当前判定线（框住多条时一起改）+ 项目设置；每次输入都能再改，不限次数
        menuSpeedEdit = findViewById(R.id.menuSpeedEdit)
        menuNum(menuSpeedEdit, { fmtNum(recordView.lineSpeed) }) { v ->
            val sp = clampLineSpeed(v)
            recordView.lineSpeed = sp
            meta = meta.copy(speed = sp.toDouble())
            saveMetaToTab(); refreshInfo(); recordView.invalidate()
            if (!menuSpeedEdit.hasFocus()) menuSpeedEdit.setText(fmtNum(sp))
        }

        recordView.onLineEditChanged = { show ->
            try {
                lineEditPanel.visibility = if (show) View.VISIBLE else View.GONE
                if (show) {
                    noiseEditPanel.visibility = View.GONE
                    refreshLineInputs()
                }
                // ★ 双层窗口：进入编辑**只显示第一层**（图层/透明度…）；
                //    第二层（文字动画）由「滑动第一层窗口」打开，点背景逐层关闭。
                //    动作延后到本轮布局结束，避免 onCreate / inflate 期间改 LayoutParams。
                recordView.post {
                    try {
                        if (!show) slideTextAnimPanel(false)
                        else refreshTextAnimInputs()
                    } catch (e: Throwable) {
                        Log.w(TAG, "text anim panel", e)
                        DebugLog.add("crash", "text anim panel: $e")
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "line edit panel", e)
                DebugLog.add("crash", "line edit panel: $e")
            }
        }
        // ★ 双层窗口逐层关闭：点画布背景先关第二层（文字动画），第二层没开才允许退出编辑；
        //    两个窗口之间用黑色直线分隔（像主界面左右抽屉那样）。
        recordView.onLineEditBackgroundTap = {
            if (::textAnimPanel.isInitialized && textAnimShown) {
                slideTextAnimPanel(false)
                true
            } else false
        }
        // ★ 滑动第一层窗口（判定线编辑面板）：见 setupLinePanelHotZone() —— 用全屏透明热区
        //    （照 AI 抽屉的做法），任意方向滑动都开合第二层，点击按坐标转发给输入框/按钮。
        // 噪域编辑：进入/退出切右上角输入面板 + 底部四个按钮（左上角/右上角/左下角/右下角）
        recordView.onNoiseEditChanged = { on ->
            noiseEditPanel.visibility = if (on) View.VISIBLE else View.GONE
            if (on) {
                lineEditPanel.visibility = View.GONE
            } else {
                noiseX.clearFocus()
                noiseY.clearFocus()
            }
            refreshPassButtons()
        }
        recordView.onNoiseEditInfo = { a, _ ->
            val v = recordView.currentNoiseCornerXY()
            if (!noiseX.hasFocus()) noiseX.setText(fmtNum(v[0]))
            if (!noiseY.hasFocus()) noiseY.setText(fmtNum(v[1]))
            btnNoiseVisible.text = if (a.visible) "显示" else "隐藏"
            btnNoiseVisible.setTextColor(if (a.visible) MENU_ON else MENU_OFF)
            btnNoiseWarning.text = if (a.warning) "警示区" else "真噪域"
            btnNoiseWarning.setTextColor(if (a.warning) 0xFFFFB340.toInt() else MENU_ON)
            refreshPassButtons()
        }
        // 长按判定线进编辑（画布上的判定线 / 抽屉里的判定线框）：正在播就先暂停，按钮文字同步
        recordView.onRequestPauseForEdit = {
            try { if (mediaPlayer?.isPlaying == true) mediaPlayer?.pause() } catch (_: Exception) {}
            btnPause.text = "继续"
        }
        // 噪域编辑要从 Autoplay 里进入时，先把这一把正常退出（HUD/播放状态不能带进编辑）
        recordView.onRequestExitPlay = { exitPlay() }
        // 打歌时点左上角暂停按钮（Phira 同款）：暂停音乐 + 弹出 继续/重来/退出 菜单
        recordView.onPauseTap = {
            // 你写我打的打方双击左上角暂停 → 让写方也一起停
            if (coopGameOn && coopRole != 0 && recordView.manualPlay) Coop.pauseGame()
            showPlayMenu()
        }
        recordView.onLinePoseChanged = { refreshLineInputs() }
        // 撤销/重做把 BPM / 偏移 / 横线 / 竖线 还原后，写回 meta 并落盘 + 刷新「其他」输入框
        recordView.onMetaSnapRestore = { s ->
            meta = meta.copy(
                bpm = s.bpm, bpmOffsetMs = s.bpmOffsetMs,
                hLines = s.hLines, vLines = s.vLines, bpmSegments = s.bpmSegments
            )
            bpmMenuEdit.setText(fmtNum(s.bpm.toFloat()))
            bpmOffsetMenuEdit.setText(fmtNum(s.bpmOffsetMs.toFloat()))
            hLinesMenuEdit.setText(s.hLines.toString())
            vLinesMenuEdit.setText(s.vLines.toString())
            saveMetaToTab()
            refreshInfo()
        }
        speedEdit.setOnEditorActionListener { v, _, _ ->
            applySpeedFromInput()
            v.clearFocus()
            true
        }
        speedEdit.setOnFocusChangeListener { v, has ->
            if (!has) { applySpeedFromInput(); v.clearFocus() }
        }

        txtTitle.text = meta.name

        recordView.bpm = meta.bpm
        recordView.bpmOffsetMs = meta.bpmOffsetMs      // BPM 偏移：网格/吸附按它平移
        recordView.syncMetaSegments(meta.bpmSegments)  // 撤销 BPM 改动时能连带还原分段
        recordView.hLines = meta.hLines
        recordView.vLines = meta.vLines
        recordView.linePosPct = meta.linePosPct
        recordView.pass = 1
        recordView.playheadProvider = { currentPlayhead() }
        recordView.playingProvider = { mediaPlayer?.isPlaying == true }
        // 打击音效提前量 = 设备输出延迟 + 一帧余量（判定只能落在帧边界上，见 RecordView.hitSoundLeadMs）。
        // 夹到 [0, 80]：蓝牙耳机这类估算不到的情况最多提前 80ms，不至于把音效甩到拍子前面。
        recordView.hitSoundLeadMs = (audioOutputLatencyMs + 16L).coerceIn(0L, 80L)
        DebugLog.add("audio", "hitSoundLead=${recordView.hitSoundLeadMs}ms (outputLatency=$audioOutputLatencyMs)")
        recordView.setBackgroundImage(meta.imageUri)

        btnPass1.setOnClickListener { if (recordView.noiseEditActive) recordView.selectNoiseCorner(0) else switchPass(1) }
        btnPass2.setOnClickListener { if (recordView.noiseEditActive) recordView.selectNoiseCorner(1) else switchPass(2) }
        btnPass3.setOnClickListener { if (recordView.noiseEditActive) recordView.selectNoiseCorner(2) else switchPass(3) }
        btnPass4.setOnClickListener { if (recordView.noiseEditActive) recordView.selectNoiseCorner(3) else switchPass(4) }
        // 噪域编辑：**长按**"左上角/右上角…"才把视角放大并居中到该角（短按只换角）
        btnPass1.setOnLongClickListener { if (recordView.noiseEditActive) { recordView.zoomToNoiseCorner(0); true } else false }
        btnPass2.setOnLongClickListener { if (recordView.noiseEditActive) { recordView.zoomToNoiseCorner(1); true } else false }
        btnPass3.setOnLongClickListener { if (recordView.noiseEditActive) { recordView.zoomToNoiseCorner(2); true } else false }
        btnPass4.setOnLongClickListener { if (recordView.noiseEditActive) { recordView.zoomToNoiseCorner(3); true } else false }
        refreshPassButtons()

        // 打击音效开关（在「其他」二级窗口里）
        btnSound.setOnClickListener {
            recordView.soundEnabled = !recordView.soundEnabled
            refreshSoundButton()
        }
        refreshSoundButton()
        refreshCompatButton()
        refreshDoubleHintButton()

        // 暂停 = 进入编辑模式（自由放置/拖动/删除/搓时间轴）；继续 = 退出编辑
        btnPause.setOnClickListener {
            val mp = mediaPlayer ?: return@setOnClickListener
            if (mp.isPlaying) {
                mp.pause()
                btnPause.text = "继续"
                recordView.editMode = true
                recordView.invalidate()          // 暂停后画面静止，补一帧把状态画出来
                // 你写我打：写方点这个「暂停」→ 打方也跟着停（不再另加浮动按钮）
                if (coopWriterRunning()) Coop.pauseGame()
            } else {
                // 框蓝/绿只是"你写我打"游戏才需要；普通联机编辑时直接播
                // ★ coopGameOn 已经开始过就不再重复广播（否则再按一次「继续」会把打方的一局重开）
                if (coopRole != 0 && Coop.bothFramed() && !coopGameOn) {
                    if (Coop.me()?.color == Coop.COLOR_GREEN) {
                        toast("绿方等蓝方开始"); return@setOnClickListener
                    }
                    askCoopStart()                                // 蓝方：也要先走「接受」弹窗
                    return@setOnClickListener
                }
                // 你写我打：这一局已经在跑（上次是写方点「暂停」停的）→ 继续时打方也一起继续
                if (coopWriterRunning()) Coop.resumeGame()
                mp.start()
                btnPause.text = "暂停"
                recordView.editMode = false
                applySpeed(curSpeed)          // 从暂停恢复时补设速度
                recordView.invalidate()       // 画面重新开始动起来（补一帧启动渲染循环）
            }
            refreshInfo()
        }

        // 编辑模式：松手后把虚拟时间写回播放器（拖动过程平滑，不逐帧 seek）
        recordView.onSeekAbs = { t ->
            // 没选音乐时 durationMs == 0，不能把目标夹成 0（否则搓完弹回开头）
            val maxPos = if (durationMs > 0) durationMs - 1 else Int.MAX_VALUE
            val c = t.coerceIn(0, maxPos)
            virtualPlayheadMs = c.toLong()
            val mp = mediaPlayer
            if (mp != null) {
                try {
                    mp.seekTo(c)
                    seekBar.progress = c
                    // 和进度条一样：等播放器真的到位再撤预览，防止部分 ROM 的
                    // 暂停态 seekTo 异步导致松手瞬间画面弹回搓动前的位置
                    cursorSeekTarget = c
                    resetPlayheadAnchor()
                } catch (_: Exception) {}
            } else {
                seekBar.progress = c
            }
            recordView.invalidate()
            updateTimeDisplay(c)
        }

        seekBar.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                // 拖动过程中只改"预览时间"，不 seek 播放器：谱面跟着手指走，又不会因为反复 seek 卡顿
                if (fromUser) {
                    recordView.seekPreviewTo(progress.toLong())
                    updateTimeDisplay(progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                seeking = true
                mediaPlayer?.let { mp ->
                    try { if (mp.isPlaying) { mp.pause(); btnPause.text = "继续" } } catch (_: Exception) {}
                }
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val raw = sb?.progress ?: 0
                // seekTo(duration) 会让 MediaPlayer 进入播放完成状态、currentPosition 回绕成 0，
                // 表现就是「拖到最右侧一下跳回最初」。停在末尾前 1ms 即可，肉眼无差别。
                val target = if (durationMs > 0) raw.coerceAtMost(durationMs - 1) else raw
                seeking = false
                val mp = mediaPlayer
                if (mp != null) {
                    try { mp.seekTo(target) } catch (_: Exception) {}
                    // 播放器 seek 是异步的：等它真的到位（见 currentPlayhead）再撤掉预览偏移
                    cursorSeekTarget = target
                    resetPlayheadAnchor()
                } else {
                    // 没播放器可等：直接把虚拟播放头钉在目标，并撤掉预览偏移，
                    // 否则 playMs() 会把「虚拟头 + 预览偏移」算成两倍。
                    virtualPlayheadMs = target.toLong()
                    cursorSeekTarget = null
                    recordView.dropSeekPreview()
                }
                recordView.invalidate()
                updateTimeDisplay(target)
            }
        })

        findViewById<View>(R.id.btnBack).setOnClickListener {
            if (recordView.hideBars) { recordView.hideBars = false; refreshBarsButton() }
            // ★ 从选曲页进来的曲子：退出就是「回主界面」，绝不落到编辑界面（用户点名，仅限选曲页的曲子）
            if (fromPlayPage) { backToMainPage(); return@setOnClickListener }
            askSaveAndExit()
        }
        // 「播放」：从当前位置进入 Phira 打歌模式（再点一次退出）
        btnPlay.setOnClickListener {
            // AUTOPLAY 永远是自动打：从「自己打」退出来之后 manualPlay 还是 true，这里必须清掉
            if (recordView.playMode) exitPlay() else { recordView.manualPlay = false; enterPlay() }
        }
        // 「PLAY」= 进入「自己打」模式（单机版你写我打，用户点名用按钮而不是长按 AUTOPLAY）
        btnPlayManual.setOnClickListener {
            // 打歌中再点 = STOP（退出这一局并回到编辑态）；否则进自己打
            if (recordView.playMode && recordView.manualPlay) {
                exitPlay()
            } else {
                if (recordView.playMode) exitPlay()
                enterManualPlay()
            }
        }
        // 第一排右边的「其他」：和顶部「其他」是同一个二级窗口（菜单位置不变，仍在右上角）
        findViewById<View>(R.id.btnOther2).setOnClickListener {
            showOtherMenu(menuPanel.visibility != View.VISIBLE)
        }
        // 保存：把当前改动写进草稿（没有改动就不重复写盘）
        findViewById<View>(R.id.btnSave).setOnClickListener {
            if (chartDirty) saveDraft()
        }
        // 撤销：一次回退一步（放置 / 拖动 / 删除 / 判定线 / 关键帧），可连续撤销
        // ★ 联机中不撤销（用户点名：把联机的撤销功能删掉），但按钮照旧留在界面上
        findViewById<View>(R.id.btnUndo).setOnClickListener {
            if (recordView.playMode) { toast("打歌中不能撤销，先按 STOP"); return@setOnClickListener }
            if (recordView.coopLocked) { toast("联机中不能撤销"); return@setOnClickListener }
            if (recordView.undoLast()) {
                refreshInfo()
                toast("已撤销（还可撤销 ${recordView.undoDepth} 步）")
            } else {
                toast("没有可撤销的操作")
            }
        }
        // 长按撤销 = 重做（保留这个快捷方式；右侧「重做」按钮也是同一个操作）
        findViewById<View>(R.id.btnUndo).setOnLongClickListener {
            if (recordView.playMode) { toast("打歌中不能重做，先按 STOP"); return@setOnLongClickListener true }
            if (recordView.coopLocked) { toast("联机中不能重做"); return@setOnLongClickListener true }
            if (recordView.redo()) {
                refreshInfo()
                toast("已重做（还可重做 ${recordView.redoDepth} 步）")
            } else {
                toast("没有可重做的操作")
            }
            true
        }
        // 重做：撤销|重做 按钮的右半边
        findViewById<View>(R.id.btnRedo).setOnClickListener {
            if (recordView.playMode) { toast("打歌中不能重做，先按 STOP"); return@setOnClickListener }
            if (recordView.coopLocked) { toast("联机中不能重做"); return@setOnClickListener }
            if (recordView.redo()) {
                refreshInfo()
                toast("已重做（还可重做 ${recordView.redoDepth} 步）")
            } else {
                toast("没有可重做的操作")
            }
        }
        findViewById<View>(R.id.btnExport).setOnClickListener { exportPez() }

        // 图片线贴图解析目录：**只有项目导入目录**。
        // 导入铺面的图（zip 解包）和本机新建图片线的图都在这里——图片跟着项目走，
        // 跨项目/全局目录的同名图一律不允许兜底（用户点名：铺面图片只和铺面文件有关）。
        // 根 lineimg 只为兼容本次改动**之前**创建的旧项目（新图不再放那里），排最后。
        recordView.lineTextureDirs = buildList {
            if (meta.tabId.isNotEmpty()) add(ProjectStorage.importedDir(meta.tabId))
            add(File(filesDir, LINE_IMG_DIR))
        }
        // 视频背景：页脚「新建视频」弹文件选择；长按视频行打开属性编辑
        recordView.onRequestVideo = { pickVideo() }
        recordView.onEditVideo = { idx -> editVideoProperties(idx) }

        // 谱面草稿：退出后重进保留改动（按标签 id 持久化）
        // 联机：这个页面是不是房间里的那一份
        coopRole = when {
            Coop.role == Coop.ROLE_HOST && Coop.roomTabId == meta.tabId -> 1
            Coop.role == Coop.ROLE_CLIENT && Coop.roomTabId == meta.tabId -> 2
            else -> 0
        }
        if (coopRole == 2) {
            // 客户端：本地不存任何东西，内容只来自服务端
            draftFile = null
            if (Coop.currentChart.isNotEmpty()) {
                try { recordView.loadDraft(Coop.currentChart) } catch (_: Exception) {}
            }
        } else if (coopRole == 1 && Coop.currentChart.isNotEmpty()) {
            // 服务端：房间里那份以内存里的最新快照为准（别的设备可能刚改过）
            draftFile = ProjectStorage.draftFile(meta.tabId)
            try { recordView.loadDraft(Coop.currentChart) } catch (_: Exception) {}
        } else if (meta.tabId.isNotEmpty()) {
            draftFile = ProjectStorage.draftFile(meta.tabId)
            draftFile?.takeIf { it.exists() }?.let { f ->
                // 同步解析（DraftCodec 后 1 万音符也只要十几毫秒）。
                // 之前试过放后台线程，但"载入中保存"会用空谱面覆盖草稿（在 UI 线程等它又会死锁），
                // 一次十几毫秒的解析不值得冒这个风险。
                try { recordView.loadDraft(f.readText()) } catch (_: Exception) {}
            }
        }

        setupCoop()
        DebugLog.add("boot", "onCreate: views ready, before startPlayback")
        startPlayback()
        DebugLog.add("boot", "onCreate: startPlayback ok")
        switchPass(1)
        // 默认暂停并进入编辑模式（可立即放置/搓时间/编辑判定线）
        recordView.editMode = true
        btnPause.text = "继续"
        // ★ 从选曲页点进来的曲子：**不要先摆出编辑界面**（用户点名：以前会闪一下编辑区约 0.5s）。
        //   立刻进打歌态并藏起上下工具条，然后等播放器就绪（每 40ms 试一次）直接开打。
        fromPlayPage = intent.getBooleanExtra("autoManualPlay", false)
        if (fromPlayPage) {
            recordView.editMode = false
            if (barsBeforePlay == null) barsBeforePlay = false
            recordView.hideBars = true
            refreshBarsButton()
        }
        refreshInfo()
        // 噪域提示：让谱师知道这谱的噪域已经读到了（但没有噪域就一个字都不出）
        val noiseN = recordView.noiseAreas.size
        if (noiseN > 0) toast("已读取 $noiseN 块噪域（右侧「噪域」页签可编辑）")
        DebugLog.add("boot", "onCreate done")
        handler.postDelayed(ticker, 200)
        // 选曲页点项目进来 = 直接开打（不等用户再按 AUTOPLAY / PLAY）
        if (fromPlayPage) {
            findViewById<View>(android.R.id.content).post { tryAutoManualPlay(0) }
        }
    }

    /**
     * 选曲页进来的自动开打：播放器还没 prepare 好就每 40ms 再试（最多 3s），
     * 一旦就绪立刻 enterManualPlay —— 不再用固定的 400ms，也就不会先露出编辑界面。
     */
    private fun tryAutoManualPlay(tries: Int) {
        if (isFinishing || isDestroyed || recordView.playMode) return
        if (mediaPlayer == null) {
            if (tries < 75) handler.postDelayed({ tryAutoManualPlay(tries + 1) }, 40)
            return
        }
        enterManualPlay()
    }

    /** 刷新“放置兼容”按钮文字/高亮 */
    /** 刷新判定线输入面板数值（跟随拖动/旋转/打帧/选帧） */
    private fun refreshLineInputs() {
        val rv = recordView
        val kf = rv.selectedKeyframeMs
        editAngle.setText(fmtNum(rv.lineRotate))
        editLX.setText(fmtNum(rv.lineMoveX))
        editLY.setText(fmtNum(rv.lineMoveY))
        editLineSpeed.setText(fmtNum(rv.lineSpeed))
        // 「更多 → 流速」跟着当前判定线同步（正在手输时不打扰）
        if (::menuSpeedEdit.isInitialized && !menuSpeedEdit.hasFocus()) {
            menuSpeedEdit.setText(fmtNum(rv.lineSpeed))
        }
        editAlpha.setText(fmtNum(rv.lineAlpha * 100f))
        editLineScale.setText(fmtNum(rv.lineScale))
        // 图层同步：正在输入时不打扰（别的输入框跟随拖动刷新，图层框是整数，盖掉很烦）
        if (!editLayer.hasFocus()) {
            editLayer.setText(rv.activeLineZOrder()?.toString() ?: "")
        }
        // 关键帧时间提示（编辑某个关键帧时显示在流速框上方；普通状态固定显示「流速」）
        txtSpeedLabel.text = if (kf >= 0) "帧 " + String.format("%.2f", kf / 1000f) + "s" else "流速"
        // 活动线是文字线才显示"改文字"按钮
        if (::btnLineText.isInitialized) {
            btnLineText.visibility = if (rv.activeLineKind == LineKind.Text) View.VISIBLE else View.GONE
        }
        // 切到另一条文字线时，下方动画窗口跟着刷新（正在手输的不打扰）
        if (textAnimShown) refreshTextAnimInputs()
    }

    /** 文字动画窗口：从上方下滑展开（高度 0 → 实测高度 + 淡入），收起时反向 */
    private fun slideTextAnimPanel(show: Boolean) {
        if (!::textAnimPanel.isInitialized || !::textAnimDivider.isInitialized ||
            !::editEnterMs.isInitialized || !::editExitMs.isInitialized) return
        if (show == textAnimShown) return
        textAnimShown = show
        textAnimAnimator?.cancel()
        textAnimAnimator = null
        val p = textAnimPanel
        if (show) {
            p.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val target = p.measuredHeight.coerceAtLeast(1)
            p.visibility = View.VISIBLE
            textAnimDivider.visibility = View.VISIBLE
            val lp0 = p.layoutParams
            lp0.height = 0
            p.layoutParams = lp0
            p.alpha = 0f
            val va = ValueAnimator.ofInt(0, target)
            textAnimAnimator = va
            va.duration = 240L
            va.interpolator = DecelerateInterpolator()
            va.addUpdateListener { a ->
                val lp = p.layoutParams
                lp.height = a.animatedValue as Int
                p.layoutParams = lp
                p.alpha = 0.3f + 0.7f * a.animatedFraction
            }
            va.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (textAnimAnimator !== va) return
                    val lp = p.layoutParams
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    p.layoutParams = lp
                    p.alpha = 1f
                }
            })
            va.start()
        } else {
            val va = ValueAnimator.ofInt(p.height.coerceAtLeast(0), 0)
            textAnimAnimator = va
            va.duration = 180L
            va.addUpdateListener { a ->
                val lp = p.layoutParams
                lp.height = a.animatedValue as Int
                p.layoutParams = lp
                p.alpha = 0.3f + 0.7f * (1f - a.animatedFraction)
            }
            va.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (textAnimAnimator !== va) return
                    p.visibility = View.GONE
                    textAnimDivider.visibility = View.GONE
                    val lp = p.layoutParams
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    p.layoutParams = lp
                    p.alpha = 1f
                    editEnterMs.clearFocus()
                    editExitMs.clearFocus()
                }
            })
            va.start()
        }
    }

    /** 把当前文字线的动画设置填进下方窗口 */
    private fun refreshTextAnimInputs() {
        if (!::btnEnterFx.isInitialized || !::btnExitFx.isInitialized ||
            !::editEnterMs.isInitialized || !::editExitMs.isInitialized) return
        val l = recordView.activeTextLine() ?: return
        btnEnterFx.text = when (l.textEnterFx) {
            TextFx.TYPE -> "打字"
            TextFx.FADE -> "渐显"
            else -> "无"
        }
        btnExitFx.text = when (l.textExitFx) {
            TextFx.TYPE -> "打字"
            TextFx.FADE -> "渐隐"
            else -> "无"
        }
        if (!editEnterMs.hasFocus()) editEnterMs.setText(fmtNum(l.textEnterMs.toFloat() / 1000f))
        if (!editExitMs.hasFocus()) editExitMs.setText(fmtNum(l.textExitMs.toFloat() / 1000f))
    }

    /** 从下方窗口读值写回当前文字线（秒 → 毫秒） */
    private fun writeTextFx() {
        if (!::btnEnterFx.isInitialized || !::btnExitFx.isInitialized ||
            !::editEnterMs.isInitialized || !::editExitMs.isInitialized) return
        if (recordView.activeTextLine() == null) return
        val eIn = ((editEnterMs.text.toString().toFloatOrNull() ?: 0.3f) * 1000f).toLong().coerceAtLeast(0L)
        val eOut = ((editExitMs.text.toString().toFloatOrNull() ?: 0.3f) * 1000f).toLong().coerceAtLeast(0L)
        val inFx = when (btnEnterFx.text.toString()) {
            "打字" -> TextFx.TYPE
            "渐显" -> TextFx.FADE
            else -> TextFx.NONE
        }
        val outFx = when (btnExitFx.text.toString()) {
            "打字" -> TextFx.TYPE
            "渐隐" -> TextFx.FADE
            else -> TextFx.NONE
        }
        recordView.setActiveTextFx(inFx, outFx, eIn, eOut)
    }

    private fun refreshPlaceLinesButton() {
        btnPlaceLines.text = if (recordView.placeLinesAll) "全部" else "单个"
        btnPlaceLines.setTextColor(if (recordView.placeLinesAll) MENU_ON else MENU_OFF)
    }

    private fun refreshLineHiButton() {
        btnLineHi.text = if (recordView.highlightAllLines) "开" else "关"
        btnLineHi.setTextColor(if (recordView.highlightAllLines) MENU_ON else MENU_OFF)
    }

    private fun fmtNum(v: Float): String {
        val rounded = Math.round(v * 100f) / 100f
        return if (rounded == Math.floor(rounded.toDouble()).toFloat()) rounded.toInt().toString() else rounded.toString()
    }

    private fun refreshDoubleHintButton() {
        btnDoubleHint.text = if (recordView.editDoubleHint) "开" else "关"
        btnDoubleHint.setTextColor(if (recordView.editDoubleHint) MENU_ON else MENU_OFF)
    }

    private fun refreshSoundButton() {
        btnSound.text = if (recordView.soundEnabled) "开" else "关"
        btnSound.setTextColor(if (recordView.soundEnabled) MENU_ON else MENU_OFF)
    }

    private fun refreshSmoothButton() {
        btnSmooth.text = if (recordView.smoothMove) "开" else "关"
        btnSmooth.setTextColor(if (recordView.smoothMove) MENU_ON else MENU_OFF)
    }

    /** 双指缩放开关文字与颜色（「触摸」分类） */
    private fun refreshPinchZoomButton() {
        btnPinchZoom.text = if (recordView.pinchEnabled) "开" else "关"
        btnPinchZoom.setTextColor(if (recordView.pinchEnabled) MENU_ON else MENU_OFF)
    }

    /** 「快捷操作」三态：关闭 / 开启 / 快速（按钮上只放状态，不加操作反馈小字） */
    private fun refreshQuickOpButton() {
        btnQuickOp.text = when (quickOpMode) { 1 -> "开启"; 2 -> "快速"; else -> "关闭" }
        btnQuickOp.setTextColor(when (quickOpMode) {
            1 -> MENU_ON
            2 -> 0xFFFFEB3B.toInt()
            else -> MENU_OFF
        })
    }

    /** 自动校准：先算会影响多少个音符，确认后再改（整批一个撤销步） */
    private fun askAutoCalibrate() {
        if (recordView.notes.isEmpty()) { toast("还没有放任何音符"); return }
        val n = recordView.countCalibratable()
        if (n == 0) { toast("没有需要校准的音符"); return }
        KbAlertBuilder(this)
            .setTitle("自动校准")
            .setMessage("把 $n 个落在「离粗线一格」上的音符吸附到粗线？\n\n（长条只挪头部、长度不变；可以撤销）")
            .setPositiveButton("校准") { _, _ ->
                val changed = recordView.autoCalibrate()
                refreshInfo()
                toast("已校准 $changed 个音符（可撤销）")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshGhostPlaceButton() {
        btnGhostPlace.text = if (recordView.ghostPlace) "开" else "关"
        btnGhostPlace.setTextColor(if (recordView.ghostPlace) MENU_ON else MENU_OFF)
    }

    private fun refreshBarsButton() {
        if (!this::topBar.isInitialized || !this::bottomBar.isInitialized) return   // 兜底：还没 findViewById 就跳过
        // 侧边窗口开着时两条栏一律收起（窗口贴着屏幕边、上下顶到边缘，会盖住它们）
        val sideWindow = this::recordView.isInitialized &&
            (recordView.isLinePanelOpen || recordView.isClipPanelOpen)
        val hidden = recordView.hideBars || sideWindow
        findViewById<View>(R.id.btnShowBars).visibility = View.GONE
        topBar.visibility = if (hidden) View.GONE else View.VISIBLE
        bottomBar.visibility = if (hidden) View.GONE else View.VISIBLE
        // 顶部三颗按钮的触摸层跟着遮罩一起显隐，否则遮罩藏了还会误触
        aiHotZone?.visibility = if (hidden) View.GONE else View.VISIBLE
        // 按钮文字只反映「隐藏遮罩」这个设置本身（窗口临时收起两条栏不算）
        btnHideBars.text = if (recordView.hideBars) "开" else "关"
        btnHideBars.setTextColor(if (recordView.hideBars) MENU_ON else MENU_OFF)
        // 两条栏自己已经全透明：遮罩带子由 RecordView 在音符之前画（只降亮度、不遮挡）。
        // 高度是每帧现取的（见 maskBandsProvider），这里只需要重画一次。
        if (this::recordView.isInitialized) recordView.invalidate()
    }

    private fun refreshOverlapFrameButton() {
        btnOverlapFrame.text = if (recordView.overlapFrame) "开" else "关"
        btnOverlapFrame.setTextColor(if (recordView.overlapFrame) MENU_ON else MENU_OFF)
    }

    private fun refreshGhostShowButton() {
        val v = recordView.ghostShow
        btnGhostShow.text = if (v == 1) "淡化" else "显示"
        btnGhostShow.setTextColor(if (v == 0) MENU_OFF else MENU_ON)
    }

    private fun refreshGhostFrameButton() {
        btnGhostFrame.text = if (recordView.ghostFrame) "开" else "关"
        btnGhostFrame.setTextColor(if (recordView.ghostFrame) MENU_ON else MENU_OFF)
    }

    private fun refreshShowReverseButton() {
        btnShowReverse.text = if (recordView.markReverse) "开" else "关"
        btnShowReverse.setTextColor(if (recordView.markReverse) MENU_ON else MENU_OFF)
    }

    private fun refreshReverseButton() {
        btnReverse.text = if (recordView.reverseEnabled) "开" else "关"
        btnReverse.setTextColor(if (recordView.reverseEnabled) MENU_ON else MENU_OFF)
    }

    private fun refreshHRotateButton() {
        btnHRotate.text = if (recordView.hGridRotate) "开" else "关"
        btnHRotate.setTextColor(if (recordView.hGridRotate) MENU_ON else MENU_OFF)
    }

    private fun refreshQuickButton() {
        btnQuick.text = if (recordView.quickSwitch) "开" else "关"
        btnQuick.setTextColor(if (recordView.quickSwitch) MENU_ON else MENU_OFF)
    }

    private fun refreshGridButton() {
        btnGrid.text = when (recordView.gridMode) {
            1 -> "竖线"
            2 -> "全部"
            3 -> "隐藏"
            else -> "横线"
        }
        btnGrid.setTextColor(if (recordView.gridMode == 3) 0x99FFFFFF.toInt() else MENU_OFF)
    }

    /** 「其他」二级窗口开关（内容分类后变长，小屏上限制高度 + 可滚动） */
    private fun showOtherMenu(show: Boolean) {
        if (!show) focusOverlay?.clear()     // 菜单收起：撤掉键盘焦点虚线框
        menuScrim.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) {
            scaleEdit.clearFocus()
            speedEdit.clearFocus()
        }
        if (show) {
            // 「其他」菜单 = AI 助手抽屉的大小（右贴 458dp / 屏宽 86%）与样式
            // （半透明黑底、直角、从上方滑入），底部仍停在下遮罩（bottomBar）上沿。
            val dm = resources.displayMetrics
            val panelW = minOf(dp2(458), (dm.widthPixels * 0.86f).toInt())
            val targetH = if (this::bottomBar.isInitialized && bottomBar.visibility == View.VISIBLE) {
                bottomBar.top
            } else {
                (dm.heightPixels * 0.82f).toInt()
            }.coerceAtLeast(dp2(220))

            val lp = menuPanel.layoutParams as? FrameLayout.LayoutParams
            if (lp != null) {
                if (lp.width != panelW) lp.width = panelW
                if (lp.height != targetH) lp.height = targetH
                lp.gravity = Gravity.TOP or Gravity.END      // 和 AI 抽屉一样贴右侧
                menuPanel.layoutParams = lp
            }

            // 背景：与编辑界面遮罩同色同透明度（menu_panel_bg = #99000000），不使用模糊

            menuPanel.visibility = View.VISIBLE
            // 从顶部之上滑入
            menuPanel.translationY = -targetH.toFloat()
            menuPanel.animate()
                .translationY(0f)
                .setDuration(260L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        } else {
            if (menuPanel.visibility == View.VISIBLE) {
                val h = menuPanel.height.coerceAtLeast(1)
                menuPanel.animate()
                    .translationY(-h.toFloat())
                    .setDuration(200L)
                    .setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction {
                        menuPanel.visibility = View.GONE
                        menuPanel.translationY = 0f
                    }
                    .start()
            } else {
                menuPanel.visibility = View.GONE
                menuPanel.translationY = 0f
            }
        }
    }

    private fun refreshCompatButton() {        val mode = recordView.compatMode
        btnCompat.text = when (mode) {
            1 -> "框架"
            2 -> "全部"
            else -> "关闭"
        }
        btnCompat.setTextColor(if (mode == 0) MENU_OFF else MENU_ON)
    }

    private fun switchPass(p: Int) {
        if (recordView.playMode) exitPlay()
        recordView.pass = p
        refreshPassButtons()
        refreshInfo()
        recordView.invalidate()
    }

    /**
     * 底部四个按钮：普通模式是"第一次…第四次"（音符类型），
     * 噪域编辑模式变成"左上角/右上角/左下角/右下角"（选中的角高亮）。
     */
    private fun refreshPassButtons() {
        val active = resources.getDrawable(R.drawable.bg_btn_blue, null)
        val normal = resources.getDrawable(R.drawable.bg_btn_dark, null)
        if (recordView.noiseEditActive) {
            val labels = arrayOf("左上角", "右上角", "左下角", "右下角")
            val buttons = arrayOf(btnPass1, btnPass2, btnPass3, btnPass4)
            val sel = recordView.noiseCorner
            for (i in 0 until 4) {
                buttons[i].text = labels[i]
                buttons[i].background = if (i == sel) active else normal
            }
        } else {
            val labels = arrayOf("第一次", "第二次", "第三次", "第四次")
            val buttons = arrayOf(btnPass1, btnPass2, btnPass3, btnPass4)
            val p = recordView.pass
            for (i in 0 until 4) {
                buttons[i].text = labels[i]
                buttons[i].background = if (i + 1 == p) active else normal
            }
        }
    }

    /** 从当前位置开始 Autoplay（自动打歌） */
    private fun enterPlay() {
        if (::aiDrawer.isInitialized && aiDrawer.isOpen) aiDrawer.close()
        val mp = mediaPlayer
        if (mp == null) { toast("音频未就绪"); return }
        roundManualPlay = recordView.manualPlay   // 结算「重来」按原样重开
        recordView.editMode = false          // 退出编辑模式
        applyHudMeta()                       // HUD 左下角曲名 / 右下角难度
        // 噪域断触：掐断的瞬间「嗡」一声 + 音乐短暂减小（Phigros 的 SFX 效果）
        recordView.onNoiseJamSound = {
            playNoiseJamSfx()
            duckMusic()
        }

        var startAt = try { mp.currentPosition.toLong() } catch (_: Exception) { 0L }
        // 接近结尾（或已经播完）时按下 AUTOPLAY = 从头播放
        val atEnd = durationMs > 0 && startAt >= durationMs - 500
        if (atEnd) {
            // 歌已经播完：按下 AUTOPLAY = 从头播放（重新 prepare，位置立即归零，避免 seekTo 的异步旧位置）
            Log.i(TAG, "autoplay at end, restart playback from 0")
            startPlayback()
            startAt = 0L
        } else if (!mp.isPlaying) {
            try {
                mp.start()
                applySpeed(curSpeed)
            } catch (e: Exception) {
                Log.e(TAG, "autoplay start failed", e)
            }
        }

        // 音效开关只跟「其他」菜单里的音效按钮走：Autoplay 不再强制打开
        // Autoplay 自动收起上下遮罩（打歌时不需要编辑工具条），退出时还原
        if (barsBeforePlay == null) barsBeforePlay = recordView.hideBars
        if (!recordView.hideBars) {
            recordView.hideBars = true
            refreshBarsButton()
        }
        // Autoplay 自动隐藏制作线（退出时还原成上次的状态）
        if (gridBeforePlay == null) gridBeforePlay = recordView.gridMode
        if (recordView.gridMode != 3) {
            recordView.gridMode = 3
            refreshGridButton()
        }
        // 打歌用 Phira 游玩风格的缩放（不带编辑时的自定义缩放），退出时还原
        if (scaleBeforePlay == null) scaleBeforePlay = recordView.timelineScale
        setScale(playScale(), updateInput = false)
        recordView.startPlaySession(startAt)
        // ★ STOP 只落在「进这一局的那个按钮」上（用户点名）：
        //   自己打（PLAY）→ PLAY 变 STOP，AUTOPLAY 保持原样；Autoplay → AUTOPLAY 变 STOP。
        if (recordView.manualPlay) {
            btnPlayManual.text = "STOP"
            btnPlay.text = "AUTOPLAY"
        } else {
            btnPlay.text = "STOP"
            btnPlayManual.text = "PLAY"
        }
        btnPause.text = "暂停"
        refreshInfo()
        Log.i(TAG, "enterPlay at ${startAt}ms playing=${mediaPlayer?.isPlaying} speed=$curSpeed")
    }

    /**
     * 房里的「你写我打」开始（蓝方执行）：广播 start，自己留在编辑界面但画面照常流动。
     * 打方（绿，可以有很多个）收到 start 后各自进打歌界面手打。
     */
    private fun beginCoopRound() {
        if (coopGameOn) return
        coopGameOn = true
        coopAskWaiting = false
        Coop.pendingStartDelay = -1.0
        refreshCoopBar()
        recordView.manualPlay = false
        Coop.startGame(Coop.gameDelaySec)
        val mp = mediaPlayer
        if (mp != null && !mp.isPlaying) {
            try { mp.start(); applySpeed(curSpeed) } catch (_: Exception) {}
            btnPause.text = "暂停"
        }
        recordView.editMode = false
        recordView.invalidate()
        refreshInfo()
    }

    /**
     * 长按「播放」= 「自己打」模式（用户点名）：
     * 和你写我打的打方一样，四种键各自判定，只是**不需要联机**；
     * 联机中长按也只是本机自己打，不广播开始、和客户机无关。
     */
    private fun enterManualPlay() {
        recordView.manualPlay = true
        enterPlay()
        toast("自己打：蓝键点、黄键碰、长条按住、红键划")
    }

    /** 停止 Autoplay：按要求暂停音乐，而不是继续播放 */
    private fun exitPlay() {
        recordView.stopPlaySession()
        recordView.manualPlay = false
        try {
            mediaPlayer?.let { if (it.isPlaying) it.pause() }
        } catch (e: Exception) {
            Log.e(TAG, "autoplay pause failed", e)
        }
        // 暂停后直接进入编辑态：这样 STOP 之后马上就能上下拖动屏幕搓时间轴，
        // 不需要先点「继续」再点「暂停」。
        recordView.editMode = true
        coopGameOn = false            // 这一局结束（AUTOPLAY 停 / 双方退出都走这里）
        coopAskWaiting = false
        Coop.myAccepted = false       // 下一局重新问
        Coop.pendingStartDelay = -1.0
        refreshCoopBar()
        refreshSoundButton()
        gridBeforePlay?.let {                              // 退出 Autoplay：「制作线显示」还原
            recordView.gridMode = it
            refreshGridButton()
        }
        gridBeforePlay = null
        scaleBeforePlay?.let { setScale(it, updateInput = true) }   // 退出 Autoplay：还原自定义缩放
        scaleBeforePlay = null
        barsBeforePlay?.let {                               // 退出 Autoplay：上下遮罩还原
            recordView.hideBars = it
            refreshBarsButton()
        }
        barsBeforePlay = null
        btnPause.text = "继续"
        btnPlay.text = "AUTOPLAY"
        btnPlayManual.text = "PLAY"
        refreshInfo()
        Log.i(TAG, "exitPlay -> paused at ${mediaPlayer?.currentPosition}ms, editMode=true")
    }

    // ---------------------------------------------------------------- 结算界面（Phira 风格）

    /**
     * 选曲页进来的曲子：返回键 / 顶部「退出」= **直接回主界面**（用户点名）。
     * 这类曲子只是从列表点开打的，没有编辑行为，所以既不进编辑态也不问"是否保存"。
     */
    private fun backToMainPage() {
        try { if (recordView.playMode) recordView.stopPlaySession() } catch (_: Exception) { }
        try { mediaPlayer?.let { if (it.isPlaying) it.pause() } } catch (_: Exception) { }
        recordView.manualPlay = false
        finish()
    }

    /**
     * 这一局结束 → Phira 风格结算界面。
     *
     * ★ **三种打歌入口共用这一段**（用户点名：别修复不全面）：
     *   · 选曲页 START（`fromPlayPage`）
     *   · 编辑页「PLAY」自己打 / 「AUTOPLAY」
     *   · 联机「你写我打」的打方（绿框）
     * 触发点也只有三个，同样共用：曲子放完（`onCompletion`）、暂停菜单左边「<」、联机对面 stop。
     * 分数沿用 命中 / 总数 × 1,000,000（和右上角 HUD 一致）。
     */
    private fun finishManualAndShowEnding() {
        val rv = recordView
        val total = rv.playTotalNotes
        val hit = rv.playHitCount
        val maxCombo = rv.playMaxCombo
        val miss = rv.playMissCount
        if (rv.playMode) exitPlay()
        if (endingOverlay != null) return
        val score = if (total <= 0) 0
        else Math.round(1_000_000.0 * hit / total).toInt().coerceIn(0, 1_000_000)
        val prefs = getSharedPreferences("frphi_scores", 0)
        val key = meta.tabId.ifEmpty { "default" }
        val best = prefs.getInt(key, -1)
        val isNew = score > best
        if (isNew) prefs.edit().putInt(key, score).apply()
        val summary = EndingOverlay.Summary(
            title = meta.name.ifEmpty { "未命名" },
            level = meta.level,
            score = score, hit = hit, good = rv.playGoodCount, total = total,
            maxCombo = maxCombo, miss = miss,
            best = best.coerceAtLeast(0), isNewBest = isNew,
            // 右侧 9:16 长方形 = 主界面那块（同一张图；没选图就是灰底，任何入口都不回退成曲子背景）
            coverUri = playCoverUri(),
            // 背景 = 打歌界面那张高斯模糊图（同一张，用户点名）
            blurredBg = rv.blurredBgForEnding()
        )
        val host = findViewById<ViewGroup>(android.R.id.content)
        val retry: () -> Unit = {
            endingOverlay = null
            recordView.manualPlay = roundManualPlay   // 原来怎么打就怎么重开
            if (!recordView.playMode) enterPlay()
            restartPlay()
        }
        val proceed: () -> Unit = {
            // ★ 选曲页进来的曲子：**不摘覆盖层**，直接 finish，让它随 Activity 一起消失。
            //   先摘再 finish 会在退出动画那半秒里露出编辑界面（用户报的「点继续闪编辑界面」就是这个）。
            if (fromPlayPage) {
                endingOverlay = null
                finish()
            } else {
                // 编辑页 / 联机进来的：摘掉覆盖层就回到编辑界面（打歌态在出结算前已经 exitPlay 过了）
                closeEnding()
                if (recordView.playMode) exitPlay()
            }
        }
        endingRetry = retry
        endingProceed = proceed
        endingOverlay = EndingOverlay.show(
            this, host, summary, onRetry = retry, onProceed = proceed
        )
    }

    /** 收起结算界面（不销毁 Activity，编辑页 / 联机进来时要能回到编辑界面） */
    private fun closeEnding() {
        val v = endingOverlay ?: return
        endingOverlay = null
        try { (v.parent as? ViewGroup)?.removeView(v) } catch (_: Exception) { }
    }

    // ---------------------------------------------------------------- 打歌暂停菜单（Phira 风格）

    /**
     * 结算右侧长方形显示什么图。
     *
     * ★ 用户点名的**唯一**来源：主界面/选曲页那块 9:16 长方形用的图
     *   （`frphi_play/cover`，`restorePlayCover()` 就是它）。
     *   不管是「选曲页 START」还是「编辑页 AUTOPLAY / PLAY」进来的，
     *   结算都必须和主界面那块**同一张图**；没选过图就是那块灰底
     *   （`0xFFC8CDD6`），**绝不能**回退成曲子的背景图 `meta.imageUri`
     *   （上一轮只在选曲页入口拦住了，编辑页入口还在漏，用户点名）。
     */
    private fun playCoverUri(): String? {
        return try {
            getSharedPreferences("frphi_play", 0).getString("cover", null)?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private var playMenuOverlay: ViewGroup? = null

    /**
     * Phira 的打歌暂停菜单：**1 秒内点两下**左上角暂停键才弹出（防误触）。
     * 横排三个图标按钮：空心左箭头=退出 Autoplay，空心圆环箭头=重来，实心右箭头=继续。
     */
    private fun showPlayMenu() {
        if (playMenuOverlay != null) return      // 已经打开
        try { if (mediaPlayer?.isPlaying == true) mediaPlayer?.pause() } catch (_: Exception) {}

        fun iconBtn(iconType: Int, desc: String, action: () -> Unit): View {
            return MenuIconView(this, iconType).apply {
                contentDescription = desc
                val s = dp2(64)
                layoutParams = LinearLayout.LayoutParams(s, s).apply {
                    marginStart = dp2(12); marginEnd = dp2(12)
                }
                // 不要外框：只有图标本身
                setBackground(null)
                setOnClickListener { hidePlayMenu(); action() }
            }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconBtn(0, "结束这一局") {
                // ★ 三种入口一个操作逻辑（用户点名）：点「<」= 结束这一局 → Phira 结算界面
                // 联机这一局先通知双方一起结束（对面收到 stop 后 onStop 里也跟着收尾）
                if (coopGameOn && coopRole != 0) Coop.stopGame()
                finishManualAndShowEnding()
            })
            addView(iconBtn(1, "重新播放") { restartPlay() })
            addView(iconBtn(2, "继续播放") { resumePlay() })
        }
        // 打方（manualPlay）：中间按钮下方显示「已打 / 一共」
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(row)
            if (recordView.manualPlay) {
                addView(TextView(this@RecordActivity).apply {
                    text = recordView.playHitCount.toString() + "/" + recordView.playTotalNotes
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 18f
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    setPadding(0, dp2(12), 0, 0)
                })
            }
        }
        val scrim = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x99000000.toInt())
            isClickable = true        // 吃掉底层画布的触摸
            isFocusable = true
            addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        (findViewById<ViewGroup>(android.R.id.content)).addView(scrim)
        playMenuOverlay = scrim
        recordView.invalidate()       // 音乐暂停后补一帧（图标切到播放三角）
    }

    /**
     * 暂停菜单的三个圆环图标（与 Phira 一致）：
     * 0=空心左箭头（退出） 1=空心圆环箭头（重来） 2=实心右箭头（继续）
     */
    private class MenuIconView(context: Context, val iconType: Int) : View(context) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = 0xFFFFFFFF.toInt()
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFFFFFFFF.toInt()
        }

        override fun onDraw(c: Canvas) {
            val s = minOf(width, height).toFloat()
            val cx = width / 2f
            val cy = height / 2f
            val r = s * 0.28f
            stroke.strokeWidth = s * 0.075f
            when (iconType) {
                0 -> {
                    // 左箭头 "<"：与右边实心三角的包围盒完全一致（镜像，宽 1.75r），不会显得拉长
                    val p = Path()
                    p.moveTo(cx + r * 0.75f, cy - r)
                    p.lineTo(cx - r, cy)
                    p.lineTo(cx + r * 0.75f, cy + r)
                    c.drawPath(p, stroke)
                }
                1 -> {
                    // 空心圆环箭头：圆环缺一个口，箭头补在缺口处（整体旋转 90°）
                    c.save()
                    c.rotate(90f, cx, cy)
                    val arc = RectF(cx - r, cy - r, cx + r, cy + r)
                    c.drawArc(arc, -60f, 300f, false, stroke)
                    // 终点角 240°（左上），沿顺时针切线方向补箭头
                    val ang = Math.toRadians(240.0)
                    val ax = cx + (r * Math.cos(ang)).toFloat()
                    val ay = cy + (r * Math.sin(ang)).toFloat()
                    val tx = -Math.sin(ang).toFloat()      // 切线方向
                    val ty = Math.cos(ang).toFloat()
                    val nx = Math.cos(ang).toFloat()       // 法线方向
                    val ny = Math.sin(ang).toFloat()
                    val hl = s * 0.13f
                    val p = Path()
                    p.moveTo(ax + tx * hl, ay + ty * hl)
                    p.lineTo(ax + nx * hl * 0.6f, ay + ny * hl * 0.6f)
                    p.lineTo(ax - nx * hl * 0.6f, ay - ny * hl * 0.6f)
                    p.close()
                    c.drawPath(p, fill)
                    c.restore()
                }
                else -> {
                    // 实心右箭头：实心三角指向右
                    val p = Path()
                    p.moveTo(cx - r * 0.75f, cy - r)
                    p.lineTo(cx - r * 0.75f, cy + r)
                    p.lineTo(cx + r, cy)
                    p.close()
                    c.drawPath(p, fill)
                }
            }
        }
    }

    private fun hidePlayMenu() {
        playMenuOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        playMenuOverlay = null
    }

    // ---------------------------------------------------------------- 你写我打：写方（蓝）
    /**
     * 写方这一局正在跑（绿方在打、自己留在编辑界面、没进打歌界面）。
     * 写方的暂停/继续就用编辑界面右下角原来那个「继续 / 暂停」按钮（用户点名：不要浮动按钮）。
     */
    private fun coopWriterRunning(): Boolean =
        coopGameOn && coopRole != 0 && !recordView.manualPlay && !recordView.playMode

    /**
     * 暂停菜单：继续。
     *
     * **本项目自己的语义（与 Phira 不同，用户点名）**：
     *  - **不回退时间**：从暂停的那一刻继续，不往回倒；
     *  - 点下后先等 **3 秒**，音乐保持暂停、谱面静止；
     *  - 屏幕正中显示 **3 → 2 → 1** 的秒数倒计时；
     *  - 这 3 秒内不能打音符（[RecordView.inputLocked] + 覆盖层吃触摸）；
     *  - 覆盖层底色就是暂停菜单那层 `#99000000`，3 秒内**由暗渐变到全亮**。
     * 倒计时结束才真正 `start()`，并从暂停位置接着播；连击/分数/已判定音符全部保留。
     */
    private fun resumePlay() {
        if (resumeCountdown != null) return        // 已经在倒计时了
        val mp = mediaPlayer ?: return
        val pos = try { mp.currentPosition.toLong() } catch (_: Exception) { currentPlayhead() }
            .let { if (it > 0L) it else currentPlayhead() }
        showResumeCountdown(pos)
    }

    private var resumeCountdown: View? = null
    private var resumeTicker: Runnable? = null

    /** 每秒步进 + 每 50ms 刷一次背景亮度的倒计时遮罩 */
    private fun showResumeCountdown(startPos: Long) {
        val root = findViewById<ViewGroup>(android.R.id.content) ?: return
        val seconds = 3

        val num = TextView(this).apply {
            text = seconds.toString()
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 55f                      // 用户点名：比上一版小一半
            typeface = android.graphics.Typeface.DEFAULT   // 不加粗（粗细也减半）
            includeFontPadding = false
            gravity = Gravity.CENTER
        }
        val overlay = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            // 起始色 = 暂停菜单的遮罩色（#99000000）
            setBackgroundColor(0x99000000.toInt())
            isClickable = true          // 吃掉所有触摸：3 秒内点不到谱面、也打不了音符
            isFocusable = true
            addView(
                num,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
        root.addView(overlay)
        resumeCountdown = overlay
        recordView.inputLocked = true

        val startAt = SystemClock.elapsedRealtime()
        val totalMs = (seconds * 1000L).coerceAtLeast(1L)
        var lastShown = seconds
        val ticker = object : Runnable {
            override fun run() {
                if (resumeCountdown !== overlay) return
                val elapsed = SystemClock.elapsedRealtime() - startAt
                if (elapsed >= totalMs) {
                    finishResumeCountdown(overlay, startPos)
                    return
                }
                // 背景：#99000000 的 alpha 线性降到 0（0x99 → 0）—— 暗 → 亮
                val k = elapsed.toFloat() / totalMs
                val a = (0x99 * (1f - k)).toInt().coerceIn(0, 0x99)
                overlay.setBackgroundColor(a shl 24)
                // 剩余秒数：3 → 2 → 1
                val remain = (seconds - elapsed / 1000L).toInt().coerceIn(1, seconds)
                if (remain != lastShown) {
                    lastShown = remain
                    num.text = remain.toString()          // 直接显示，不做动画
                }
                overlay.postDelayed(this, 40L)      // 40ms 刷一帧，渐变看着是连续的
            }
        }
        resumeTicker = ticker
        overlay.post(ticker)
    }

    /** 倒计时结束：撤掉遮罩 → 解锁输入 → 从暂停位置开始播 */
    private fun finishResumeCountdown(overlay: View, startPos: Long) {
        resumeTicker?.let { overlay.removeCallbacks(it) }
        resumeTicker = null
        if (resumeCountdown === overlay) resumeCountdown = null
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        recordView.inputLocked = false
        try {
            mediaPlayer?.let { mp ->
                // 不回退：就停在暂停那一刻。seekTo 只是把位置钉死，避免期间被别的逻辑挪走
                mp.seekTo(startPos.toInt())
                if (!mp.isPlaying) { mp.start(); applySpeed(curSpeed) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "autoplay resume failed", e)
        }
        resetPlayheadAnchor()
        updateTimeDisplay(startPos.toInt())
        // 打方点继续 → 告诉写方也继续（倒计时结束才广播，两边节奏一致）
        if (coopGameOn && coopRole != 0 && recordView.manualPlay) Coop.resumeGame()
        recordView.invalidate()
    }

    /** 页面离开/退出时把倒计时收干净，别留一个 inputLocked 卡住界面 */
    private fun cancelResumeCountdown() {
        val overlay = resumeCountdown ?: return
        resumeTicker?.let { overlay.removeCallbacks(it) }
        resumeTicker = null
        resumeCountdown = null
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        recordView.inputLocked = false
    }

    /** 暂停菜单：重来（回到开头重打这一把，连击/分数清零） */
    private fun restartPlay() {
        try {
            mediaPlayer?.let { mp ->
                mp.seekTo(0)
                if (!mp.isPlaying) { mp.start(); applySpeed(curSpeed) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "autoplay restart failed", e)
        }
        try { seekBar.progress = 0 } catch (_: Exception) {}
        resetPlayheadAnchor()
        recordView.startPlaySession(0)
        btnPause.text = "暂停"
        updateTimeDisplay(0)
        recordView.invalidate()
        Log.i(TAG, "playMenu restart from 0")
    }

    /** 设置谱面缩放（[updateInput] = 是否同步右侧「缩放」输入框） */
    private fun setScale(v: Float, updateInput: Boolean) {
        val s = v.coerceIn(0.01f, 100f)
        recordView.timelineScale = s
        if (updateInput && this::scaleEdit.isInitialized) scaleEdit.setText(fmtNum(s))
        recordView.invalidate()
    }

    /**
     * Autoplay 的缩放：和 Phira 游玩时的落谱速度完全一致。
     *
     * Phira 做法（prpr/src/parse/rpe.rs + core/note.rs + core/resource.rs）：
     *   height(t) = ∫ speed × SPEED_RATIO dt，SPEED_RATIO = 10/45/HEIGHT_RATIO = 10/45/0.83175 ≈ 0.26718；
     *   note 的世界 y = height / aspect_ratio，相机 y 缩放 = aspect_ratio ⇒ 屏幕 NDC 的 y = height，
     *   而整屏高 = 2 个 height 单位 ⇒ 流速 1 时每秒下落 0.26718 / 2 = 13.36% **屏幕高**
     *   （谱面视口高度恰好等于屏幕高：aspect_ratio = min(16/9, 屏宽/屏高)，viewport() 只在超宽屏左右补边）。
     *
     * 本应用 distPx = ∫speed dt(ms) × 0.16 ×(h/720)×scale，令 speed=1、1 秒 = 13.36% 屏高：
     *   scale = 0.1336 × 720 / 160 = 0.6002 ≈ 0.6（与屏幕尺寸无关）
     */
    private fun playScale(): Float = 0.6f

    /** 保存谱面草稿到应用私有目录（按标签 id） */

    // ---------------------------------------------------------------- 联机
    private fun dp2(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 联机初始化：延迟输入框、房间状态、收发回调 */
    private fun setupCoop() {
        coopDelayEdit = findViewById(R.id.coopDelayEdit)
        // 延迟默认值：按谱面流速估一个"按键落下来得及"的时间，用户可以随便改
        if (Coop.gameDelaySec <= 0.0) {
            val sp = meta.speed.toFloat().coerceAtLeast(0.2f)
            Coop.gameDelaySec = (1.0 / sp).coerceIn(0.3, 3.0)
        }
        coopDelayEdit?.setText(trimNum(Coop.gameDelaySec))
        fun applyDelay() {
            val v = coopDelayEdit?.text.toString().toFloatOrNull() ?: return
            Coop.gameDelaySec = v.coerceAtLeast(0f).toDouble()
        }
        coopDelayEdit?.setOnEditorActionListener { v, _, _ -> applyDelay(); v.clearFocus(); true }
        coopDelayEdit?.setOnFocusChangeListener { _, has -> if (!has) applyDelay() }
        refreshCoopBar()
        if (coopRole == 0) return
        recordView.coopLocked = true          // 联机中不撤销（本地制谱不受影响）
        lastSynced = recordView.saveDraft()
        Coop.onChart = { text -> if (alive()) applyRemoteChart(text) }
        Coop.onPlayers = { if (alive()) { refreshCoopBar(); renderPlayerList() } }
        Coop.onStart = { d ->
            if (alive()) {
                // 只有「接受」了这一局的人才打（用户点名：不接受的不打）
                if (Coop.myAccepted) startPlaybackFromRoom(d) else Coop.pendingStartDelay = d
            }
        }
        // 有人发起「你写我打」→ 每个人（含房主）都要弹接受/不接受
        Coop.onAskStart = { d -> if (alive()) showCoopAskDialog(d) }
        // 对面点了「退出」→ 本机也一起结束这一局
        Coop.onStop = {
            if (alive() && (coopGameOn || recordView.playMode)) {
                coopGameOn = false
                // 已经出了结算（自己点「<」结束的）就别再拆结算底下的画面
                if (endingOverlay == null) exitPlay()   // 停音乐 + 回编辑态（双方都停）
            }
        }
        // 写方点了「暂停」→ 打方跟着暂停并弹出暂停界面（中间按钮下方 X/X）
        Coop.onPauseGame = {
            if (alive() && recordView.playMode) {
                try { if (mediaPlayer?.isPlaying == true) mediaPlayer?.pause() } catch (_: Exception) {}
                showPlayMenu()
            }
        }
        // 有人点「继续」→ 双方一起继续
        Coop.onResumeGame = {
            if (alive() && recordView.playMode) {
                hidePlayMenu()
                try {
                    mediaPlayer?.let { mp -> if (!mp.isPlaying) { mp.start(); applySpeed(curSpeed) } }
                } catch (_: Exception) {}
                recordView.invalidate()
            }
        }
        Coop.onClosed = { msg ->
            if (alive()) { toast(msg); finish() }
        }
        // 本地改动 → 防抖 150ms 推给对面（一次触摸/一次面板修改算一次）
        recordView.onChartChanged = {
            if (!suppressSync) {
                chartDirty = true                 // 有过改动：退出时才需要问"是否保存"
                syncPending = true
                recordView.removeCallbacks(syncRunnable)
                recordView.postDelayed(syncRunnable, 150)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun readMetaLegacy(): ChartMeta? = intent.getParcelableExtra(KEY_META)

    /** 读取设置页传来的谱面参数；任何异常都退化成默认参数，绝不让编辑页闪退 */
    private fun readMeta(): ChartMeta? = try {
        val m = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(KEY_META, ChartMeta::class.java)
        } else {
            readMetaLegacy()
        }
        if (m == null) DebugLog.add("boot", "meta missing → 用默认参数继续")
        m
    } catch (e: Throwable) {
        DebugLog.add("boot", "meta unparcel failed → 用默认参数继续: $e")
        null
    }

    private fun alive(): Boolean = !isFinishing && !isDestroyed

    private fun pushChart() {
        if (coopRole == 0 || !syncPending) return
        syncPending = false
        val seq = ++syncSeq
        // 主线程只抓引用快照（O(n) 浅拷贝，不生成字符串），贵的 encode 挪到后台。
        val encoder = try { recordView.draftWithClipSnapshot() } catch (e: Exception) { return }
        syncExec.execute {
            val text = try { encoder() } catch (e: Exception) { return@execute }
            if (seq != syncSeq) return@execute          // 已有更新的改动，这一版直接丢
            val hash = text.hashCode()                 // 后台算，String 自带缓存
            handler.post {
                if (!alive() || seq != syncSeq) return@post
                if (hash == lastSyncedHash && text.length == lastSyncedLen) return@post
                lastSyncedHash = hash
                lastSyncedLen = text.length
                lastSynced = text
                Coop.sendChart(text)
            }
        }
    }

    private var pendingRemote: String? = null
    private val remoteApply = Runnable {
        pendingRemote?.let { t -> pendingRemote = null; applyRemoteChart(t) }
    }

    private fun applyRemoteChart(text: String) {
        if (text.isEmpty() || text == lastSynced) return
        // 正在拖音符时先别套用（下标会错位），等松手
        if (recordView.isTouchingNow()) {
            pendingRemote = text
            handler.removeCallbacks(remoteApply)
            handler.postDelayed(remoteApply, 250)
            return
        }
        lastSynced = text
        suppressSync = true
        // ★ keepHistory = true：对面推来的谱面**不清本地撤销栈**（联机中撤销本身关掉，
        //   但退出房间后本机的历史还在，不至于整份作废）。
        try { recordView.loadDraft(text, keepHistory = true) } catch (_: Exception) {}
        suppressSync = false
        refreshInfo()
    }

    /** 绿方：收到"开始"后按设定延迟起播（蓝方按下继续的那一刻算起点） */
    private fun startPlaybackFromRoom(delaySec: Double) {
        if (coopRole == 0) return
        if (Coop.me()?.color == Coop.COLOR_BLUE) return     // 蓝方自己已经按继续开始了
        if (Coop.me()?.color != Coop.COLOR_GREEN) return    // 没被框成绿方 = 不参与
        coopGameOn = true
        handler.postDelayed({
            val mp = mediaPlayer ?: return@postDelayed
            if (mp.isPlaying) return@postDelayed
            coopAskWaiting = false
            refreshCoopBar()
            // ★「你写我打」打方：进和 Autoplay 完全一样的界面，但音符要自己打（manualPlay）
            recordView.manualPlay = true
            enterPlay()
            toast("你写我打：音符自己打，双击左上角暂停")
            refreshInfo()
        }, (delaySec.coerceAtLeast(0.0) * 1000).toLong())
    }

    private fun refreshCoopBar() {
        if (!this::btnPlayer.isInitialized) return      // onCreate 早期调用：还没 bind
        val me = Coop.me()
        btnPlayer.text = "玩家列表"          // 文字永远不变，只改颜色
        // 颜色取房间框的那一套：蓝框 #2D7FF9 / 绿框 #22C55E；没被框住 = 原来的深色底白字
        val frame = when (me?.color) {
            Coop.COLOR_BLUE -> FRAME_BLUE
            Coop.COLOR_GREEN -> FRAME_GREEN
            else -> 0
        }
        if (frame == 0) {
            btnPlayer.setTextColor(0xFFFFFFFF.toInt())
            btnPlayer.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF10141F.toInt())
                cornerRadius = dp2(12).toFloat()
                if (coopAskWaiting) {
                    // 等各位点「接受」的时候把这个按钮框住（用户点名）
                    setStroke(dp2(2), 0xFF00E5FF.toInt())
                }
            }
        } else {
            btnPlayer.setTextColor(frameTint(frame, 0.45f))
            val bg = frameBg(frame, dp2(12).toFloat())
            if (coopAskWaiting) bg.setStroke(dp2(2), 0xFF00E5FF.toInt())
            btnPlayer.background = bg
        }
    }

    // ---------------------------------------------------------------- 「更多」开关持久化
    /** 读回上次的开关状态（第一次进来用默认值：音效关、自动移动开、隐藏音符显示…） */
    private fun applyRecorderSettings() {
        try {
            val p = getSharedPreferences("frphi_rec", 0)
            recordView.compatMode = p.getInt("compatMode", 0)
            recordView.smoothMove = p.getBoolean("smoothMove", false)
            recordView.autoEdgeScroll = p.getBoolean("autoEdgeScroll", true)
            recordView.editDoubleHint = p.getBoolean("doubleHint", false)
            recordView.soundEnabled = p.getBoolean("sound", false)
            recordView.overlapFrame = p.getBoolean("overlapFrame", false)
            recordView.markReverse = p.getBoolean("markReverse", false)
            recordView.highlightAllLines = p.getBoolean("lineHi", false)
            recordView.placeLinesAll = p.getBoolean("placeLines", false)
            recordView.quickSwitch = p.getBoolean("quickSwitch", false)
            recordView.hGridRotate = p.getBoolean("hRotate", false)
            recordView.gridMode = p.getInt("gridMode", 0)
            recordView.ghostPlace = p.getBoolean("ghostPlace", false)
            val gs = p.getInt("ghostShow", 0)
            recordView.ghostShow = if (gs == 1) 1 else 0          // 旧版 2=框住 → 归到"显示"
            recordView.ghostFrame = p.getBoolean("ghostFrame", gs == 2)
            recordView.realProcess = p.getInt("realProcess", 0)
            recordView.fullMode = p.getBoolean("fullMode", false)
            recordView.noteInfoMode = p.getBoolean("noteInfo", false)
            recordView.pinchEnabled = p.getBoolean("pinchEnabled", false)   // ★ 双指缩放：全局设置，多项目通用
            recordView.reverseEnabled = p.getBoolean("reverse", false)      // ★ 反面：此前漏持久化
            recordView.hideBars = p.getBoolean("hideBars", false)           // ★ 隐藏遮罩：只由按钮点击落盘
            quickOpMode = p.getInt("quickOp", 0).coerceIn(0, 2)             // 快捷操作：首次安装默认 0=关闭
        } catch (_: Exception) {}
    }

    private fun saveRecorderSettings() {
        try {
            getSharedPreferences("frphi_rec", 0).edit()
                .putInt("compatMode", recordView.compatMode)
                .putBoolean("smoothMove", recordView.smoothMove)
                .putBoolean("autoEdgeScroll", recordView.autoEdgeScroll)
                .putBoolean("doubleHint", recordView.editDoubleHint)
                .putBoolean("sound", recordView.soundEnabled)
                .putBoolean("overlapFrame", recordView.overlapFrame)
                .putBoolean("markReverse", recordView.markReverse)
                .putBoolean("lineHi", recordView.highlightAllLines)
                .putBoolean("placeLines", recordView.placeLinesAll)
                .putBoolean("quickSwitch", recordView.quickSwitch)
                .putBoolean("hRotate", recordView.hGridRotate)
                .putInt("gridMode", gridBeforePlay ?: recordView.gridMode)
                .putBoolean("ghostPlace", recordView.ghostPlace)
                .putInt("ghostShow", recordView.ghostShow)
                .putBoolean("ghostFrame", recordView.ghostFrame)
                .putInt("realProcess", recordView.realProcess)
                .putBoolean("fullMode", recordView.fullMode)
                .putBoolean("noteInfo", recordView.noteInfoMode)
                .putBoolean("pinchEnabled", recordView.pinchEnabled)
                .putBoolean("reverse", recordView.reverseEnabled)
                .putInt("quickOp", quickOpMode)
                .apply()
        } catch (_: Exception) {}
    }

    private fun refreshRealProcessButton() {
        val v = recordView.realProcess
        btnRealProcess.text = when (v) {
            1 -> "淡化"
            2 -> "隐藏"
            else -> "显示"
        }
        btnRealProcess.setTextColor(if (v == 0) MENU_OFF else MENU_ON)
    }

    private fun refreshAutoScrollButton() {
        val on = recordView.autoEdgeScroll
        btnAutoScroll.text = if (on) "开" else "关"
        btnAutoScroll.setTextColor(if (on) MENU_ON else MENU_OFF)
    }

    /**
     * 点"玩家列表"：开窗。
     * 右滑玩家 = 框色（第一个蓝=写谱，其余全部绿=打谱，可以很多人）；
     * **关掉这个窗口 = 开始「你写我打」**（用户点名，不再右滑两个人就自动开始）。
     */
    private fun onPlayerButton() {
        if (coopRole == 0) { toast("未联机（回主界面右侧抽屉开房/加入）"); return }
        openPlayerList()
    }

    /** 玩家列表窗口（和判定线抽屉/剪贴板一样是浮在页面上的面板，不是系统弹窗） */
    private var playerWindow: android.view.View? = null

    private fun openPlayerList() {
        if (coopRole == 0) { toast("未联机"); return }
        closePlayerWindow(startIfReady = false, immediate = true)
        val root = findViewById<android.view.ViewGroup>(android.R.id.content) ?: return

        // 不加遮罩：用户点名「窗口调出时不需要调暗背景」；这一层全屏透明只负责
        // 接住窗口外的点击（点空白关窗），窗口本身靠 alpha + translationX 做淡入滑入动画
        val overlay = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { closePlayerWindow() }
            alpha = 0f
        }
        // 宽度按判定线窗口的宽度、左边贴屏幕边、上下顶到屏幕边缘（用户点名：三个窗口统一）
        val panelW = recordView.sidePanelWidth().toInt().coerceAtLeast(dp2(120))
        // 样式**一比一复刻左侧剪贴板窗口**：纯半透明黑直角底、无描边、标题 13sp 粗体白、条目直角色块。
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2(8), dp2(10), dp2(8), dp2(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x99000000.toInt())
            }
            setOnClickListener { }                            // 点面板本身不关
            layoutParams = android.widget.FrameLayout.LayoutParams(
                panelW, ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.Gravity.START or android.view.Gravity.TOP
            )
            translationX = -panelW.toFloat()                  // 从左边推进来
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp2(2), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp2(30)
            )
        }
        // 最左侧：退出（双方一起结束这一局"你写我打"）。只在真的在打的时候出现
        if (coopGameOn) {
            head.addView(TextView(this).apply {
                text = "退出"
                setTextColor(0xFFFF8A80.toInt()); textSize = 12f
                includeFontPadding = false
                gravity = android.view.Gravity.CENTER
                // 直角色块（同抽屉里的操作条），不用圆角按钮背景
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x1A00E5FF)
                }
                setPadding(dp2(10), 0, dp2(10), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp2(24)
                ).also { it.marginEnd = dp2(8) }
                setOnClickListener {
                    closePlayerWindow(startIfReady = false)
                    Coop.stopGame()                  // 对面收到 stop 也一起退出
                }
            })
        }
        head.addView(TextView(this).apply {
            text = "玩家列表"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        panel.addView(head)


        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        playerListBox = box
        // 人多时只上下滚动；左右滑（踢出 / 框色）交给行自己的手势
        panel.addView(android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(box, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        renderPlayerList()

        overlay.addView(panel)
        root.addView(overlay)
        playerWindow = overlay
        // 窗口动画：面板从左边推进来 + 遮罩淡入（和剪贴板/判定线同一个手感）
        panel.animate().translationX(0f).setDuration(170).start()
        overlay.animate().alpha(1f).setDuration(170).start()
    }

    /**
     * 关掉玩家列表窗口。[startIfReady] = true 时顺便发起「你写我打」开始请求
     * （用户点名：**关窗只是发起请求**，每个人要先在弹窗里点「接受」才开始，不接受的不打）。
     * [immediate] = true 时不做收窗动画，直接摘掉（马上要开新窗/被踢等场景）。
     */
    private fun closePlayerWindow(startIfReady: Boolean = true, immediate: Boolean = false) {
        val w = playerWindow
        playerWindow = null
        playerListBox = null
        if (w != null) {
            val parent = w.parent as? android.view.ViewGroup
            if (immediate || parent == null) {
                (w as? android.view.ViewGroup)?.getChildAt(0)?.animate()?.cancel()
                w.animate().cancel()
                parent?.removeView(w)
            } else {
                val panel = (w as? android.view.ViewGroup)?.getChildAt(0)
                w.isClickable = false
                panel?.animate()?.translationX(-(panel.width.takeIf { it > 0 } ?: w.width).toFloat())
                    ?.setDuration(140)?.start()
                w.animate().alpha(0f).setDuration(140).withEndAction {
                    (w.parent as? android.view.ViewGroup)?.removeView(w)
                }.start()
            }
        }
        if (startIfReady && !coopGameOn && coopRole != 0 &&
            Coop.bothFramed()
        ) askCoopStart()
    }

    /** 发起「你写我打」：只有被框了色的人能发起；发起后所有人（含自己）弹接受/不接受 */
    private fun askCoopStart() {
        if (coopGameOn) return
        coopAskWaiting = true
        refreshCoopBar()                       // 先把「玩家列表」按钮框起来，等各位同意
        Coop.askStart(Coop.gameDelaySec)
    }

    /**
     * 「接受 / 不接受」弹窗：接受 = 参与这一局（蓝方接受才会真正开始），
     * 不接受 = 这一局不打（蓝方不接受 → 谁都不开）。
     */
    private fun showCoopAskDialog(delaySec: Double) {
        if (coopGameOn || coopAskDialogOn) return
        // 没被框色的人不参与这一局（也不打扰他）
        val me = Coop.me()
        if (me == null || me.color == Coop.COLOR_NONE) return
        coopAskDialogOn = true
        val isBlue = me?.color == Coop.COLOR_BLUE
        KbAlertBuilder(this)
            .setTitle("你写我打")
            .setMessage(if (isBlue) "开始一局？你写谱，其他人打。" else "房主要开一局你写我打，接受吗？")
            .setPositiveButton("接受") { _, _ ->
                coopAskDialogOn = false
                Coop.answerStart(true)
                val pending = Coop.pendingStartDelay
                Coop.pendingStartDelay = -1.0
                coopAskWaiting = false
                if (isBlue) {
                    beginCoopRound()
                } else if (pending >= 0.0) {
                    // 弹窗还没关的时候蓝方就已经开始了 → 直接进这一局
                    startPlaybackFromRoom(0.0)
                }
                refreshCoopBar()
            }
            .setNegativeButton("不接受") { _, _ ->
                coopAskDialogOn = false
                Coop.answerStart(false)
                coopAskWaiting = false
                refreshCoopBar()
            }
            .setCancelable(false)
            .show()
    }

    private fun renderPlayerList() {
        val box = playerListBox ?: return
        box.removeAllViews()
        if (Coop.players.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "还没有玩家"; setTextColor(0x99FFFFFF.toInt()); textSize = 10f
                includeFontPadding = false
                setPadding(dp2(2), dp2(6), dp2(2), dp2(6))
            })
            return
        }
        val ordered = Coop.orderedPlayers()
        for ((idx, p) in ordered.withIndex()) {
            val tv = TextView(this).apply {
                text = buildString {
                    append(idx + 1).append(". ").append(p.name).append("  ").append(p.ip)
                    // 有伪房主之后，MC 端（id 0）只是服务器，不再标「房主」（用户点名）
                    if (p.id == 0 && Coop.pseudoHostId < 0) append("  [房主]")
                    if (p.id == Coop.pseudoHostId) append("  [伪房主]")
                    when (p.color) {
                        Coop.COLOR_BLUE -> append("  [蓝·写谱]")
                        Coop.COLOR_GREEN -> append("  [绿·打谱]")
                    }
                    if (p.confirmed) append("  ✓已确认")
                    if (p.id == Coop.myId) append("  (我)")
                }
                setTextColor(
                    when (p.color) {
                        Coop.COLOR_BLUE -> 0xFF7FB6FF.toInt()
                        Coop.COLOR_GREEN -> 0xFF7CFF9B.toInt()
                        else -> 0xFFFFFFFF.toInt()
                    }
                )
                textSize = 13f
                includeFontPadding = false
                gravity = android.view.Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp2(11), 0, dp2(11), 0)
                // 直角色块（剪贴板窗口的条目同款：0x22FFFFFF，无圆角、无描边）
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (p.color == Coop.COLOR_NONE) 0x22FFFFFF else 0x1A00E5FF)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp2(46)
                ).also { it.topMargin = dp2(6) }
            }
            var downX = 0f
            tv.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { downX = ev.rawX; true }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        v.translationX = ev.rawX - downX; true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        val dx = v.translationX
                        v.animate().translationX(0f).setDuration(120).start()
                        val need = v.width.toFloat().coerceAtLeast(1f) * 0.6f
                        if (dx <= -need) askKick(p) else if (dx >= need) frameNextColor(p)
                        true
                    }
                    else -> { v.animate().translationX(0f).setDuration(120).start(); true }
                }
            }
            box.addView(tv)
        }
    }

    private fun frameNextColor(p: CoopPlayer) {
        val c = when {
            p.color != Coop.COLOR_NONE -> Coop.COLOR_NONE        // 再右滑 = 取消
            Coop.blue() == null -> Coop.COLOR_BLUE               // 第一个 = 蓝（写谱，只有一个）
            else -> Coop.COLOR_GREEN                             // 第二次/第三次/…全是绿（打谱，可以很多人）
        }
        Coop.framePlayer(p.id, c)
        refreshCoopBar()
        renderPlayerList()
    }

    /**
     * 谁都踢得动：房主可以被人踢（踢房主 = 直接关房间）、也允许踢自己。
     */
    private fun askKick(p: CoopPlayer) {
        val isMe = p.id == Coop.myId
        val isHost = p.id == 0
        // 伪房主出现后 MC 端只是服务器：踢它 = 服务器关闭 = 全员下线（用户点名：允许）
        val isMc = isHost && Coop.role == Coop.ROLE_CLIENT
        val title = when {
            isMc -> "踢出 MC 端"
            isHost -> "关闭房间"
            isMe -> "退出房间"
            else -> "踢出房间"
        }
        val msg = when {
            isMc -> "踢出 MC 端会直接关掉服务器，所有人都会下线。"
            isHost && isMe -> "你是房主，关掉后所有人都会退出房间。"
            isHost -> "把房主「" + p.name + "」踢出房间？房间会直接关闭。"
            isMe -> "把自己踢出房间？"
            else -> "把「" + p.name + "」踢出房间？"
        }
        val ok = when {
            isMc -> "踢出"
            isHost -> "关闭"
            isMe -> "退出"
            else -> "踢出"
        }
        KbAlertBuilder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(ok) { _, _ ->
                if (playerWindow != null) closePlayerWindow(startIfReady = false)
                Coop.kick(p.id)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------------------------------------------------------- AI 抽屉（和主界面同一个 AI）
    /** 编辑页的 AI 抽屉：从屏幕上方往下拉出，宽度 = 主界面左抽屉 + 右抽屉 */
    private lateinit var aiDrawer: AiDrawer

    private fun setupAiDrawer() {
        val aiBtn = findViewById<TextView>(R.id.btnAi)
        // 触摸层先加：AI 抽屉（scrim + 面板）后加，才会盖在触摸层上面
        setupAiHotZone(aiBtn, btnOther, findViewById(R.id.btnExport))
        // 玩家聊天抽屉（联机）：顶部「退出 ~ 玩家列表」区域滑动拉出
        chatDrawer = ChatDrawer(this, findViewById(android.R.id.content)) { Coop.chat(it) }
        Coop.onChatMessage = { n, t -> chatDrawer?.append(n, t) }
        aiDrawer = AiDrawer(this, findViewById(android.R.id.content), object : AiDrawer.Hooks {
            override fun musicUri(): Uri = meta.musicUri
            override fun durationMs(): Long = durationMs.toLong()
            override fun bpm(): Double = meta.bpm
            override fun bpmOffset(): Double = meta.bpmOffsetMs
            override fun level(): String = meta.level
            override fun lines(): List<Line> = recordView.lines
            override fun notes(): List<RecordedNote> = recordView.notes
            override fun noises(): List<NoiseArea> = recordView.noiseAreas
            override fun declaredLayerValues(): Set<Int> = recordView.declaredLayers
            override fun declaredNoiseLayerValues(): Set<String> = recordView.noiseDeclaredLayerKeys()
            override fun videos(): List<VideoBg> = recordView.videos.map { it.copy() }

            override fun applyDraft(text: String) {
                try {
                    // keepHistory=true：AI 改谱不再清空编辑器的撤销历史（否则玩家之前手改的
                    // 若干步会被 AI 一句话抹掉）。先让 RecordView 用内存数据拍一张"改动前"
                    // 的快照（不 encode 成字符串），这一轮结束再由 finishUndo 落成撤销步。
                    recordView.beginPendingUndo()
                    recordView.loadDraft(text, keepHistory = true)
                    recordView.invalidate()
                    refreshInfo()
                } catch (_: Exception) {}
            }

            /** AI 一轮改动收尾：keep=true 落成撤销步，false 丢弃（没改谱面 / 被撤回打断） */
            override fun finishUndo(keep: Boolean) {
                try {
                    if (keep) recordView.commitPendingUndo() else recordView.discardPendingUndo()
                    refreshInfo()
                } catch (_: Exception) {}
            }

            /**
             * AI 把噪域/关键帧/音符写在了播放头看不到的时刻 → 把播放头带过去。
             * 不跳的话用户看到的就是「AI 说做好了，但是我什么都没看到」（点名）。
             */
            override fun revealChart(fromMs: Long, toMs: Long) {
                try {
                    // revealRange 内部已经 seek 过播放器，这里只把进度条/时间显示同步成目标位置
                    // （不能读回 currentPlayhead：MediaPlayer 的 seekTo 是异步的，读回来还是旧值）
                    val target = recordView.revealRange(fromMs, toMs)
                    if (target >= 0L) {
                        seekBar.progress = target.toInt()
                        updateTimeDisplay(target.toInt())
                    }
                } catch (_: Exception) {}
            }

            /**
             * 抽屉「撤回」= 编辑页那个撤销按钮（同一个撤销栈、同一套判断），长按 = 重做。
             * 打歌中 / 联机中跟编辑页按钮一样禁用（RecordView.undo 自己也会拦联机）。
             */
            override fun undoChart(): Boolean {
                if (recordView.playMode) { toast("打歌中不能撤销，先按 STOP"); return false }
                if (recordView.coopLocked) { toast("联机中不能撤销"); return false }
                val ok = recordView.undoLast()
                if (ok) refreshInfo()
                return ok
            }

            override fun redoChart(): Boolean {
                if (recordView.playMode) { toast("打歌中不能重做，先按 STOP"); return false }
                if (recordView.coopLocked) { toast("联机中不能重做"); return false }
                val ok = recordView.redo()
                if (ok) refreshInfo()
                return ok
            }

            /** 一键应用 BPM：写回当前项目（BPM + BPM 偏移），退出到主界面也是新值 */
            override fun applyBpm(bpm: Double, offsetMs: Double?) {
                // 应用的是单 BPM：丢掉多 BPM 分段（否则导出时两套 BPM 会打架）
                // 走 editMeta ⇒ View 立刻生效 + 记一步撤销（分段也会跟着进快照，撤销能找回）
                recordView.editMeta(bpm = bpm, bpmOffsetMs = offsetMs, segments = emptyList())
                meta = if (offsetMs != null)
                    meta.copy(bpm = bpm, bpmOffsetMs = round3(offsetMs), bpmSegments = emptyList())
                else meta.copy(bpm = bpm, bpmSegments = emptyList())
                saveMetaToTab()
                refreshInfo()
                toast("BPM 已应用：" + trimNum(meta.bpm))
            }

            override fun setLevel(level: String) {
                meta = meta.copy(level = level)
                saveMetaToTab()
                refreshInfo()
            }

            /** 项目信息 11 个字段：曲名/曲师/谱师/难度/BPM/BPM偏移/判定线位置/流速/延迟/横线/竖线 */
            override fun applyMeta(
                name: String?, composer: String?, charter: String?, level: String?,
                bpm: Double?, bpmOffset: Double?, speed: Double?, linePos: Int?,
                delay: Int?, hLines: Int?, vLines: Int?
            ) {
                var m = meta
                name?.let { m = m.copy(name = it) }
                composer?.let { m = m.copy(composer = it) }
                charter?.let { m = m.copy(charter = it) }
                level?.let { m = m.copy(level = it) }
                var newBpm: Double? = null
                var newOff: Double? = null
                var newH: Int? = null
                var newV: Int? = null
                bpm?.let { v ->
                    if (v > 20.0 && v < 1000.0) {
                        // 手动/AI 指定单一 BPM：多 BPM 分段作废
                        m = m.copy(bpm = v, bpmSegments = emptyList())
                        newBpm = v
                    }
                }
                bpmOffset?.let {
                    val x = round3(it.coerceIn(-60000.0, 60000.0))
                    m = m.copy(bpmOffsetMs = x)
                    newOff = x
                }
                speed?.let {
                    // 编辑页的「流速」就是当前判定线的流速（关键帧选中时改那一帧）
                    val sp = it.coerceIn(0.05, 100.0).toFloat()
                    m = m.copy(speed = sp.toDouble())
                    recordView.lineSpeed = sp
                }
                linePos?.let {
                    val p = it.coerceIn(0, 100)
                    m = m.copy(linePosPct = p)
                    recordView.applyLinePosPct(p)   // 走撤销事务（撤销覆盖审计补的遗漏）
                }
                delay?.let { m = m.copy(delayMs = it.coerceIn(-2000, 2000)) }
                hLines?.let { val n = it.coerceIn(1, 16); m = m.copy(hLines = n); newH = n }
                vLines?.let { val n = it.coerceIn(1, 32); m = m.copy(vLines = n); newV = n }
                // ★ BPM/偏移/横线/竖线必须**同步进 View**（以前只写了 meta ⇒ AI 改了横线数、BPM 偏移，
                //   编辑网格不动，看起来像没生效）；editMeta 同时记一步撤销。
                if (newBpm != null || newOff != null || newH != null || newV != null) {
                    recordView.editMeta(
                        bpm = newBpm, bpmOffsetMs = newOff, hLines = newH, vLines = newV,
                        segments = if (newBpm != null) emptyList() else null
                    )
                }
                meta = m
                txtTitle.text = meta.name.ifEmpty { "未命名" }
                saveMetaToTab()
                refreshLineInputs()
                refreshInfo()
                recordView.invalidate()
            }

            override fun status(msg: String) {
                if (msg.isNotEmpty()) toast(msg)
            }

            override fun canEdit(): Boolean = !recordView.playMode && coopRole == 0

            // AI 说「导出谱面」时直接落盘（不弹保存框），把结果（含路径）回给它
            override fun exportChart(): String = exportChartSilently()
        })
        // 「AI」按钮：点一下直接打开 AI 界面（抽屉开着时它被遮住，点不到）
        aiBtn.setOnClickListener { aiDrawer.show() }
    }

    /** 顶部「AI / 其他 / 导出谱面」那一整块的触摸层（含按钮之间的缝隙） */
    private var aiHotZone: View? = null

    /**
     * 把「AI」「其他」「导出谱面」三颗按钮 + 它们之间的缝隙做成一块连续触摸区。
     *
     * 之前是给每颗按钮单独挂 OnTouchListener —— 手势会把点击吃掉，表现为"按钮点不动了"。
     * 现在改成一整块透明层：
     *   - 点：按坐标找到落点上的按钮，调它的 performClick()（原来的功能一点不变）；
     *   - 任意方向滑动：拉出 AI 抽屉；
     *   - 落在缝隙里：只吞掉，不做事。
     */
    private fun setupAiHotZone(aiBtn: View, otherBtn: View, exportBtn: View) {
        val root = findViewById<ViewGroup>(android.R.id.content) ?: return
        val hot = View(this).apply {
            setBackgroundColor(0x00000000)
            isClickable = true
        }
        // 必须在 AI 抽屉（scrim/panel）之前加进来，否则会盖在抽屉上面
        root.addView(hot, android.widget.FrameLayout.LayoutParams(0, 0))
        aiHotZone = hot

        val buttons = arrayOf(aiBtn, otherBtn, exportBtn)
        // 判定"滑动"的阈值：比系统 touchSlop 再大一点，避免点按时手指轻微滚动被当成滑动
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
            .coerceAtLeast(dp2(16))
        var downX = 0f
        var downY = 0f
        var pulled = false

        hot.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; pulled = false
                    v.isPressed = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!pulled && maxOf(
                            Math.abs(ev.rawX - downX), Math.abs(ev.rawY - downY)
                        ) > touchSlop
                    ) {
                        pulled = true
                        v.isPressed = false
                        aiDrawer.show()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (!pulled) {
                        // 点击：转发给落点上的那颗按钮（缝隙里就什么都不做）
                        val loc = IntArray(2)
                        for (b in buttons) {
                            b.getLocationInWindow(loc)
                            if (ev.rawX >= loc[0] && ev.rawX <= loc[0] + b.width &&
                                ev.rawY >= loc[1] && ev.rawY <= loc[1] + b.height
                            ) {
                                b.performClick()
                                break
                            }
                        }
                    }
                    pulled = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    pulled = false
                    true
                }
                else -> true
            }
        }

        // 位置跟着按钮走（旋转/键盘/隐藏遮罩都会触发重新布局）
        fun place() {
            if (aiBtn.width <= 0 || exportBtn.width <= 0) return
            val a = IntArray(2); aiBtn.getLocationInWindow(a)
            val b = IntArray(2); exportBtn.getLocationInWindow(b)
            val r = IntArray(2); root.getLocationInWindow(r)
            val left = a[0] - r[0]
            val top = a[1] - r[1]
            val right = b[0] + exportBtn.width - r[0]
            val bottom = a[1] + aiBtn.height - r[1]
            val lp = hot.layoutParams as android.widget.FrameLayout.LayoutParams
            // 上下左右各外扩几 dp，把按钮之间的缝隙也包进来
            val l = left - dp2(3)
            val t = top - dp2(5)
            val w = (right - left) + dp2(6)
            val h = (bottom - top) + dp2(10)
            // 值没变就别重新赋值：否则 layout → place → layout 会来回抖
            if (lp.leftMargin != l || lp.topMargin != t || lp.width != w || lp.height != h) {
                lp.leftMargin = l
                lp.topMargin = t
                lp.width = w
                lp.height = h
                hot.layoutParams = lp
            }
            hot.visibility = if (topBar.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        }
        aiBtn.post { place() }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
        aiBtn.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
    }

    /** 把 BPM / 偏移 / 难度写回项目标签（主界面回来时同步显示） */
    private fun saveMetaToTab() {
        if (meta.tabId.isEmpty()) return
        try {
            val p = getSharedPreferences("frphi_tabs", 0)
            val raw = p.getString("tabs", null)
            if (raw == null) { saveMetaToPlayStore(); return }
            val arr = org.json.JSONArray(raw)
            var hit = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("id") == meta.tabId) {
                    o.put("bpmSegments", BpmTimeline.encode(meta.bpmSegments))
                    o.put("name", meta.name)
                    o.put("composer", meta.composer)
                    o.put("charter", meta.charter)
                    o.put("level", meta.level)
                    o.put("bpm", trimNum(meta.bpm))
                    o.put("bpmOffset", trimNum(meta.bpmOffsetMs))
                    o.put("speed", trimNum(meta.speed))
                    o.put("linePos", meta.linePosPct.toString())
                    o.put("delay", meta.delayMs.toString())
                    o.put("hLines", meta.hLines.toString())
                    o.put("vLines", meta.vLines.toString())
                    hit = true
                }
            }
            if (hit) p.edit().putString("tabs", arr.toString()).apply()
            else saveMetaToPlayStore()
        } catch (_: Exception) {}
    }

    /**
     * 选曲页自己的谱面库（`frphi_play/charts`）里也有同一套项目信息。
     * 这类曲子不在主界面标签栏里，所以 `saveMetaToTab` 找不到它 —— 这里补一份，
     * 保证从选曲页进去改过曲名/难度，回到选曲页列表也看得到。
     */
    private fun saveMetaToPlayStore() {
        try {
            val p = getSharedPreferences("frphi_play", 0)
            val raw = p.getString("charts", null) ?: return
            val arr = org.json.JSONArray(raw)
            var hit = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("id") != meta.tabId) continue
                o.put("bpmSegments", BpmTimeline.encode(meta.bpmSegments))
                o.put("name", meta.name)
                o.put("composer", meta.composer)
                o.put("charter", meta.charter)
                o.put("level", meta.level)
                o.put("bpm", trimNum(meta.bpm))
                o.put("bpmOffset", trimNum(meta.bpmOffsetMs))
                o.put("speed", trimNum(meta.speed))
                o.put("linePos", meta.linePosPct.toString())
                o.put("delay", meta.delayMs.toString())
                o.put("hLines", meta.hLines.toString())
                o.put("vLines", meta.vLines.toString())
                hit = true
            }
            if (hit) p.edit().putString("charts", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- AI（写谱 / 修谱 / 缤纷）
    /** 正在跑的 AI 任务（null = 空闲） */
    private var aiCancel: AiClient.Cancel? = null
    private var aiDialog: android.app.AlertDialog? = null
    private var aiBar: ProgressBar? = null
    private var aiMsg: TextView? = null
    /** AI 开始前的谱面快照：结束后可以点「撤回」还原 */
    private var aiBeforeDraft: String? = null
    /** 本轮 AI 是否已经把「改之前」压进了撤销栈（决定「撤回」用撤销还是整份载入） */
    private var aiUndoSeeded = false

    /** 「更多」菜单里追加 AI 三个按钮（写在菜单最下面，和现有开关同一套样式） */
    /** 设备屏幕刷新率（取整）；拿不到退回 60。帧率占位框的默认值用 */
    private fun deviceRefreshRate(): Int {
        return try {
            val wm = getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
            val rate = wm.defaultDisplay.refreshRate
            if (rate > 0f && rate.isFinite()) Math.round(rate) else 60
        } catch (_: Exception) { 60 }
    }

    private fun addAiMenuButtons() {
        val grid = findViewById<android.widget.GridLayout>(R.id.menuGrid) ?: return
        // 分组标题（横跨 4 列）
        grid.addView(TextView(this).apply {
            text = "AI"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setPadding(dp2(10), 0, dp2(10), 0)
            background = getDrawable(R.drawable.bg_menu_cell)
            layoutParams = android.widget.GridLayout.LayoutParams().apply {
                width = 0
                height = dp2(24)
                columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 4, 1f)
                setMargins(dp2(1), dp2(1), dp2(1), dp2(1))
            }
        })
        for ((label, mode) in listOf("自动写谱" to 0, "自动修谱" to 1, "自动缤纷" to 2)) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = getDrawable(R.drawable.bg_menu_cell)
                setPadding(dp2(8), dp2(8), dp2(8), dp2(8))
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1, 1f)
                    setMargins(0, 0, 0, 0)
                }
            }
            cell.addView(TextView(this).apply {
                text = label
                setTextColor(0xFFA8B4C4.toInt())
                textSize = 11f
                includeFontPadding = false
                maxLines = 1
            })
            cell.addView(TextView(this).apply {
                text = "执行"
                gravity = android.view.Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
                includeFontPadding = false
                background = getDrawable(R.drawable.bg_menu_btn)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp2(40)
                ).also { it.topMargin = dp2(3) }
                setOnClickListener { runAi(mode) }
            })
            grid.addView(cell)
        }
    }

    /** 「更多」里的「全量模式」开关（和「音效」同尺寸，紧挨在它下面；用户自己写谱用，和 AI 无关） */
    private fun addFullModeButton() {
        val btn = findViewById<TextView>(R.id.btnFullMode) ?: return
        fun render() {
            btn.text = if (recordView.fullMode) "开启" else "关闭"
            btn.setTextColor(if (recordView.fullMode) 0xFFFFEB3B.toInt() else 0xFFFFFFFF.toInt())
        }
        render()
        btn.setOnClickListener {
            recordView.fullMode = !recordView.fullMode
            render()
        }
    }

    // ---------------------------------------------------------------- 音符列表（音符信息面板）
    /**
     * 「更多」→「音符列表」（默认关）：开启后**点红框里的音符不再删除**，改为在音符右侧弹出这个面板。
     * 第一列 = 删除 / X镜像 / 关闭；第二列 = Phiedit 的单音符属性（类型、时间、X、速度、大小、
     * 透明度、纵向偏移、可见时间、判定范围、音符颜色、特效颜色、打击音效）。
     * 框住一群音符时改的是**全部**被框住的音符：数值可以选「设为」或「增减」，时间按差值整体平移。
     */
    private var noteInfoWindow: View? = null
    private var noteInfoDelta = false          // 数值字段：false = 设为，true = 增减
    private var noteInfoSound = ""             // 打击音效（循环按钮）

    private fun addNoteInfoButton() {
        val btn = findViewById<TextView>(R.id.btnNoteInfo) ?: return
        fun render() {
            btn.text = if (recordView.noteInfoMode) "开启" else "关闭"
            btn.setTextColor(if (recordView.noteInfoMode) MENU_ON else MENU_OFF)
        }
        render()
        btn.setOnClickListener {
            recordView.noteInfoMode = !recordView.noteInfoMode
            if (!recordView.noteInfoMode) closeNoteInfoPanel()
            render()
        }
        // 点红框里的音符 → 弹面板（坐标是 RecordView 内的坐标）
        recordView.onNoteInfo = { x, y -> showNoteInfoPanel(x, y) }
    }

    private fun closeNoteInfoPanel() {
        val v = noteInfoWindow ?: return
        noteInfoWindow = null
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(v.windowToken, 0)
        } catch (_: Exception) {}
        try {
            (v.parent as? ViewGroup)?.removeView(v)
        } catch (_: Exception) {}
    }

    /** 0xRRGGBB → "r,g,b"；-1（没有颜色）→ 空串 */
    private fun rgbText(c: Int): String =
        if (c < 0) "" else "${(c shr 16) and 0xFF},${(c shr 8) and 0xFF},${c and 0xFF}"

    /** "r,g,b" / "#rrggbb" / 空（空 = 恢复原色）→ 0xRRGGBB / -1；写错了返回 null（不动） */
    private fun parseRgb(s: String): Int? {
        val t = s.trim()
        if (t.isEmpty()) return -1
        if (t.startsWith("#")) {
            return try { t.removePrefix("#").toInt(16) and 0xFFFFFF } catch (_: Exception) { null }
        }
        val p = t.split(',', '，', ' ')
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (p.size < 3) return null
        val r = p[0].toIntOrNull() ?: return null
        val g = p[1].toIntOrNull() ?: return null
        val b = p[2].toIntOrNull() ?: return null
        return (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }

    private fun showNoteInfoPanel(ax: Float, ay: Float) {
        val root = findViewById<ViewGroup>(android.R.id.content) ?: return
        val sel = recordView.selectedNotes()
        if (sel.isEmpty()) return
        closeNoteInfoPanel()
        val first = sel.first()
        noteInfoDelta = false
        noteInfoSound = first.hitsound

        val edits = HashMap<String, EditText>()
        val panelW = dp2(272)

        fun fmtNum(v: Float): String {
            val r = Math.round(v * 1000f) / 1000f
            return if (r == Math.floor(r.toDouble()).toFloat()) r.toInt().toString() else r.toString()
        }
        /** 选中音符这个属性都一样 → 显示数值；不一样 → 空着（提示"混合"），不动它就不会改 */
        fun sameText(get: (RecordedNote) -> Float, fmt: (Float) -> String = ::fmtNum): String {
            val v = get(first)
            return if (sel.all { Math.abs(get(it) - v) <= 1e-4f }) fmt(v) else ""
        }

        // ---- 第一列：删除 / X镜像 / 关闭 ----
        val col1 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp2(58), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        fun sideBtn(label: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 12f
            includeFontPadding = false
            setTextColor(color)
            background = getDrawable(R.drawable.bg_btn_dark)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp2(44)
            ).also { it.topMargin = dp2(6) }
            setOnClickListener { onClick() }
            col1.addView(this)
        }
        sideBtn("删除", 0xFFFF5252.toInt()) {
            val n = recordView.deleteSelectionByPanel()
            closeNoteInfoPanel()
            if (n > 0) toast("删除 $n 个音符")
        }
        sideBtn("X镜像", 0xFFFFFFFF.toInt()) {
            val n = recordView.mirrorSelection()
            if (n > 0) toast("镜像 $n 个音符")
            else toast("位置没变")
        }
        sideBtn("关闭", 0xFFFFFFFF.toInt()) { closeNoteInfoPanel() }

        // ---- 第二列：音符属性（和 Phiedit 的 NoteEditPanel 一一对应）----
        val col2 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2(8), 0, 0, 0)
        }

        fun title(text: String, top: Int = 8) = TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp2(2), dp2(top), 0, dp2(1))
            col2.addView(this)
        }

        /** 一个输入框（label 在框上方，和「更多」菜单同一套样式）；不负责加进父容器 */
        fun cellBox(label: String, key: String, value: String, hint: String = "混合", numeric: Boolean = true): LinearLayout {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            box.addView(TextView(this).apply {
                text = label
                textSize = 9f
                maxLines = 1
                setTextColor(0x99FFFFFF.toInt())
            })
            val e = EditText(this).apply {
                setText(value)
                this.hint = hint
                textSize = 12f
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(0x99FFFFFF.toInt())
                gravity = Gravity.CENTER
                background = getDrawable(R.drawable.bg_btn_transparent)
                inputType = if (numeric)
                    android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                else
                    android.text.InputType.TYPE_CLASS_TEXT
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                setSelectAllOnFocus(true)
                maxLines = 1
                setPadding(dp2(2), 0, dp2(2), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp2(30)
                ).also { it.topMargin = dp2(2) }
            }
            edits[key] = e
            box.addView(e)
            return box
        }

        /** 一行放两个输入框（中间留 8dp） */
        fun pairRow(
            k1: Pair<String, String>, k2: Pair<String, String>,
            v1: String, v2: String
        ) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp2(5) }
            }
            row.addView(cellBox(k1.first, k1.second, v1))
            row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp2(8), 1) })
            row.addView(cellBox(k2.first, k2.second, v2))
            col2.addView(row)
        }

        fun chipRow(labels: List<String>, active: Int, onPick: (Int) -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp2(30)
                ).also { it.topMargin = dp2(5) }
            }
            for ((i, label) in labels.withIndex()) {
                val on = i == active
                val v = TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 12f
                    includeFontPadding = false
                    setTextColor(if (on) MENU_ON else MENU_OFF)
                    background = getDrawable(R.drawable.bg_btn_dark)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                        .also { if (i > 0) it.marginStart = dp2(4) }
                    setOnClickListener { onPick(i) }
                }
                row.addView(v)
            }
            col2.addView(row)
        }

        fun headerText(): String =
            if (recordView.selectedCount() > 1) "已选 ${recordView.selectedCount()} 个音符（整组改）" else "单个音符"

        val head = TextView(this).apply {
            text = headerText()
            textSize = 11f
            setTextColor(0xFF66D9FF.toInt())
            setPadding(dp2(2), dp2(2), 0, 0)
        }
        col2.addView(head)

        // 类型（蓝 / 长条 / 红 / 黄）
        title("类型")
        val sameType = if (sel.all { it.type == first.type }) first.type else 0
        chipRow(listOf("蓝键", "长条", "红键", "黄键"), sameType - 1) { i ->
            val t = i + 1
            val oneCell = (60000.0 / meta.bpm / meta.hLines.coerceAtLeast(1)).toLong().coerceAtLeast(1L)
            recordView.applyNoteInfo { n ->
                if (n.type == t) n
                else if (t == 2 && n.endMs <= n.startMs) n.copy(type = 2, endMs = n.startMs + oneCell * 2, pass = 4)
                else n.copy(
                    type = t, pass = passOfType(t),
                    endMs = if (t == 2) Math.max(n.endMs, n.startMs + oneCell) else n.startMs
                )
            }
            refreshNoteInfoPanel()
        }

        // 正反面 / 真假
        title("方向与真假")
        val sameAbove = if (sel.all { it.above == first.above }) (if (first.above) 0 else 1) else -1
        chipRow(listOf("正面", "反面"), sameAbove) { i ->
            val up = i == 0
            recordView.applyNoteInfo { if (it.above == up) it else it.copy(above = up) }
            refreshNoteInfoPanel()
        }
        val sameFake = if (sel.all { it.fake == first.fake }) (if (first.fake) 1 else 0) else -1
        chipRow(listOf("真音符", "假音符"), sameFake) { i ->
            val f = i == 1
            recordView.applyNoteInfo { if (it.fake == f) it else it.copy(fake = f) }
            refreshNoteInfoPanel()
        }

        // 时间（毫秒）+ X 坐标（RPE 像素，-675 ~ 675）
        title("时间（ms）与 X 坐标（-675~675）")
        pairRow(
            "起" to "start", "止" to "end",
            sameText({ it.startMs.toFloat() }, { it.toLong().toString() }),
            if (sel.all { it.endMs == first.endMs }) first.endMs.toString() else ""
        )
        pairRow(
            "X" to "x", "速度" to "speed",
            sameText({ it.posX * 675f }), sameText({ it.speed })
        )

        // 大小 / 透明度（0~255）
        title("大小 / 透明度（0~255）")
        pairRow(
            "大小" to "size", "透明度" to "alpha",
            sameText({ it.size }), sameText({ it.alpha * 255f })
        )

        // 纵向偏移 / 可见时间
        title("纵向偏移 / 可见时间（秒）")
        pairRow(
            "Y偏移" to "yoff", "可见时间" to "vis",
            sameText({ it.yOffset }), sameText({ it.visibleTime })
        )

        // 判定范围
        title("判定范围（1 = 正常）")
        val jaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp2(5) }
        }
        col2.addView(jaRow)
        val jaBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        jaBox.addView(TextView(this).apply {
            text = "判定范围"
            textSize = 9f
            maxLines = 1
            setTextColor(0x99FFFFFF.toInt())
        })
        jaBox.addView(EditText(this).apply {
            setText(sameText({ it.judgeArea }))
            hint = "混合"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0x99FFFFFF.toInt())
            gravity = Gravity.CENTER
            background = getDrawable(R.drawable.bg_btn_transparent)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setSelectAllOnFocus(true)
            maxLines = 1
            setPadding(dp2(2), 0, dp2(2), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp2(30)
            ).also { it.topMargin = dp2(2) }
            edits["ja"] = this
        })
        jaRow.addView(jaBox)

        // 颜色 / 特效颜色
        title("颜色 R,G,B（空 = 原色）")
        val cRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp2(5) }
        }
        run {
            fun colorCell(label: String, key: String, value: String) {
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .also { if (cRow.childCount > 0) it.marginStart = dp2(8) }
                }
                box.addView(TextView(this).apply {
                    text = label
                    textSize = 9f
                    maxLines = 1
                    setTextColor(0x99FFFFFF.toInt())
                })
                box.addView(EditText(this).apply {
                    setText(value)
                    hint = "R,G,B"
                    textSize = 12f
                    setTextColor(0xFFFFFFFF.toInt())
                    setHintTextColor(0x99FFFFFF.toInt())
                    gravity = Gravity.CENTER
                    background = getDrawable(R.drawable.bg_btn_transparent)
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                    setSelectAllOnFocus(true)
                    maxLines = 1
                    setPadding(dp2(2), 0, dp2(2), 0)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp2(30)
                    ).also { it.topMargin = dp2(2) }
                    edits[key] = this
                })
                cRow.addView(box)
            }
            colorCell("音符颜色", "tint", if (sel.all { it.tint == first.tint }) rgbText(first.tint) else "")
            colorCell("特效颜色", "tinthit", if (sel.all { it.tintHit == first.tintHit }) rgbText(first.tintHit) else "")
        }
        col2.addView(cRow)

        // 打击音效（循环按钮）
        title("打击音效")
        val soundBtn = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            includeFontPadding = false
            setTextColor(0xFFFFFFFF.toInt())
            background = getDrawable(R.drawable.bg_btn_dark)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp2(30)
            ).also { it.topMargin = dp2(5) }
        }
        soundBtn.text = if (noteInfoSound.isEmpty()) "默认音效" else noteInfoSound
        soundBtn.setOnClickListener {
            noteInfoSound = when (noteInfoSound) {
                "" -> "tap.mp3"
                "tap.mp3" -> "drag.mp3"
                "drag.mp3" -> "flick.mp3"
                else -> ""
            }
            soundBtn.text = if (noteInfoSound.isEmpty()) "默认音效" else noteInfoSound
            recordView.applyNoteInfo { n ->
                if (n.hitsound == noteInfoSound) n else n.copy(hitsound = noteInfoSound)
            }
        }
        col2.addView(soundBtn)

        // 数值模式：设为 / 增减
        title("多选时数值怎么改")
        chipRow(listOf("设为", "增减 ±"), if (noteInfoDelta) 1 else 0) { i ->
            noteInfoDelta = i == 1
            refreshNoteInfoPanel()
        }

        // 应用（钉在面板底部，不跟着滚动）
        val applyBtn = TextView(this).apply {
            text = "应用到 ${recordView.selectedCount()} 个音符"
            gravity = Gravity.CENTER
            textSize = 13f
            includeFontPadding = false
            setTextColor(0xFFFFFFFF.toInt())
            background = getDrawable(R.drawable.bg_btn_blue)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp2(36)
            ).also { it.topMargin = dp2(6) }
        }

        fun commit() {
            fun num(key: String): Float? =
                edits[key]?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.toFloatOrNull()

            val t0 = num("start"); val t1 = num("end")
            val xv = num("x"); val spd = num("speed"); val sz = num("size"); val al = num("alpha")
            val yoff = num("yoff"); val vis = num("vis"); val ja = num("ja")
            val tintTxt = edits["tint"]?.text?.toString()
            val tintHitTxt = edits["tinthit"]?.text?.toString()
            val baseStart = recordView.selectedNotes().firstOrNull()?.startMs
            val delta = noteInfoDelta
            var changed = 0
            recordView.applyNoteInfo { n ->
                var m = n
                // ---- 起始时间：设为 = 整组平移到这个时间；增减 = 整组平移这么多 ----
                if (t0 != null && baseStart != null) {
                    val d = if (delta) t0.toLong() else t0.toLong() - baseStart
                    if (d != 0L) {
                        val ns = (m.startMs + d).coerceAtLeast(0L)
                        m = m.copy(startMs = ns, endMs = (m.endMs + (ns - m.startMs)).coerceAtLeast(ns))
                    }
                }
                // ---- 结束时间：只对长条有意义（设为 = 绝对；增减 = 加多少）；其它类型 end 恒等于 start ----
                if (t1 != null && m.type == 2) {
                    val target = if (delta) m.endMs + t1.toLong() else t1.toLong()
                    if (target != m.endMs) m = m.copy(endMs = Math.max(target, m.startMs))
                }
                if (xv != null) {
                    val target = (if (delta) m.posX + xv / 675f else xv / 675f)
                    if (Math.abs(target - m.posX) > 3e-4f) m = m.copy(posX = target.coerceIn(-1f, 1f))
                }
                if (spd != null) {
                    val target = if (delta) m.speed + spd else spd
                    if (Math.abs(target - m.speed) > 1e-4f) m = m.copy(speed = target.coerceAtLeast(0f))
                }
                if (sz != null) {
                    val target = if (delta) m.size + sz else sz
                    if (Math.abs(target - m.size) > 1e-4f) m = m.copy(size = target.coerceAtLeast(0f))
                }
                if (al != null) {
                    val target = (if (delta) m.alpha * 255f + al else al) / 255f
                    if (Math.abs(target - m.alpha) > 2e-3f) m = m.copy(alpha = target.coerceIn(0f, 1f))
                }
                if (yoff != null) {
                    val target = if (delta) m.yOffset + yoff else yoff
                    if (Math.abs(target - m.yOffset) > 1e-3f) m = m.copy(yOffset = target)
                }
                if (vis != null) {
                    val target = if (delta) m.visibleTime + vis else vis
                    if (Math.abs(target - m.visibleTime) > 1e-3f) m = m.copy(visibleTime = target.coerceAtLeast(0f))
                }
                if (ja != null) {
                    val target = if (delta) m.judgeArea + ja else ja
                    if (Math.abs(target - m.judgeArea) > 1e-4f) m = m.copy(judgeArea = target.coerceAtLeast(0f))
                }
                if (tintTxt != null) {
                    val c = parseRgb(tintTxt)
                    if (c != null && c != m.tint) m = m.copy(tint = c)
                }
                if (tintHitTxt != null) {
                    val c = parseRgb(tintHitTxt)
                    if (c != null && c != m.tintHit) m = m.copy(tintHit = c)
                }
                if (m !== n) changed++
                m
            }
            toast(if (changed > 0) "已改 $changed 个音符" else "没有变化")
        }

        applyBtn.setOnClickListener { commit() }
        // 键盘上按「完成」= 应用（不用先去点按钮）
        for (e in edits.values) {
            e.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    commit(); true
                } else false
            }
        }

        // ---- 组装面板 ----
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(col2)
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(scroll)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp2(8), dp2(8), dp2(8), dp2(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xF014161E.toInt())
                setStroke(dp2(1), 0x66FFFFFF)
                cornerRadius = dp2(10).toFloat()
            }
            addView(col1)
            val col2Box = LinearLayout(this@RecordActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                addView(fields)
                addView(applyBtn)                       // 固定在底部（表单太长时滚表单，不滚按钮）
            }
            addView(col2Box)
            setOnClickListener { }                         // 点面板自己不关
        }
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { closeNoteInfoPanel() }
            addView(panel)
        }
        root.addView(overlay)
        noteInfoWindow = overlay

        // 面板放在音符**右侧**；右边放不下就翻到左侧，再夹进屏幕里。
        // 弹键盘时（adjustResize）根布局会变矮，这里跟着缩小高度，保证面板不会被键盘顶出去。
        val maxH = (root.height * 0.9f).toInt().coerceAtLeast(dp2(160))
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)
        )
        val wantH = panel.measuredHeight.coerceIn(dp2(120), maxH)
        // 量完自然高度之后，让表单区域吃掉剩余空间（按钮钉在底部，表单内部滚动）
        fields.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )
        val rvLoc = IntArray(2)
        val rootLoc = IntArray(2)
        recordView.getLocationOnScreen(rvLoc)
        root.getLocationOnScreen(rootLoc)
        val cx = (rvLoc[0] - rootLoc[0]) + ax
        val cy = (rvLoc[1] - rootLoc[1]) + ay
        val gap = dp2(30)

        fun place() {
            val avail = root.height
            val inW = root.width
            if (avail <= 0 || inW <= 0) return
            val h = wantH.coerceAtMost((avail - dp2(8)).coerceAtLeast(dp2(120)))
            var left = (cx + gap).toInt()
            if (left + panelW > inW - dp2(6)) left = (cx - gap).toInt() - panelW
            left = left.coerceIn(dp2(4), (inW - panelW - dp2(4)).coerceAtLeast(dp2(4)))
            var top = (cy - h / 2f).toInt()
            top = top.coerceIn(dp2(4), (avail - h - dp2(4)).coerceAtLeast(dp2(4)))
            val lp = panel.layoutParams as? FrameLayout.LayoutParams
            if (lp != null && lp.width == panelW && lp.height == h &&
                lp.leftMargin == left && lp.topMargin == top
            ) return
            panel.layoutParams = FrameLayout.LayoutParams(panelW, h, Gravity.TOP or Gravity.START).also {
                it.leftMargin = left
                it.topMargin = top
            }
        }
        place()
        overlay.addOnLayoutChangeListener { _, _, _, _, _, oldL, oldT, oldR, oldB ->
            // 只关心"尺寸变了"（键盘弹出/收起），位置变化不重排，避免自己触发自己
            if (oldR - oldL != root.width || oldB - oldT != root.height) place()
        }
    }

    // ---------------------------------------------------------------- 图片线 / 文字线
    /**
     * 判定线抽屉页脚的左右滑：
     *  - 左滑「＋ 新建判定线」= 选一张图片当判定线（RPE 的 Texture）
     *  - 右滑「＋ 新建判定线」= 新建文字线（RPE 的 extended.textEvents）
     * 两条线都还是判定线：名字仍按 lineN 自动编号，类型只体现在 Texture / textEvents 上，
     * 和 Phiedit / Phira 的叫法一致（绝不把"图片/文字"写进 Name）。
     */
    private fun setupExtraLines() {
        recordView.onRequestImageLine = { pickLineImage() }
        recordView.onRequestTextLine = { editLineText(null) }
        // 判定线窗口右上角「图层」框：数字输入框改当前线的 zOrder（图层，大 = 上层）
        recordView.onPlaceBlocked = { kind ->
            toast(if (kind == LineKind.Image) "图片线是素材，不能放音符（换回普通判定线再放）"
            else "文字线是素材，不能放音符（换回普通判定线再放）")
        }
        btnLineText = findViewById(R.id.btnLineText)
        btnLineText.setOnClickListener {
            val l = recordView.activeTextLine()
            if (l == null) toast("当前不是文字线") else editLineText(l)
        }
    }

    /**
     * 文字动画窗口的事件绑定。
     * ★ 必须在 `textAnimPanel` 等字段 findViewById **之后**调用：以前这段写在
     *   setupExtraLines() 里，而 setupExtraLines 被 onCreate 第 440 行调用、
     *   这些控件 ~470 行才 findViewById ⇒ lateinit 未初始化直接崩
     *   （真机日志：UninitializedPropertyAccessException: lateinit property btnEnterFx）。
     */
    private fun bindTextAnimPanel() {
        // 长按「文字」按钮：手动收起 / 再下滑划出文字动画窗口
        btnLineText.setOnLongClickListener {
            if (textAnimShown) slideTextAnimPanel(false)
            else { refreshTextAnimInputs(); slideTextAnimPanel(true) }
            true
        }
        // 入场 / 出场特效切换（无 → 渐显/渐隐 → 打字 → 无）+ 时间输入 + 应用全部
        btnEnterFx.setOnClickListener {
            btnEnterFx.text = when (btnEnterFx.text.toString()) {
                "无" -> "渐显"
                "渐显" -> "打字"
                else -> "无"
            }
            writeTextFx()
        }
        btnExitFx.setOnClickListener {
            btnExitFx.text = when (btnExitFx.text.toString()) {
                "无" -> "渐隐"
                "渐隐" -> "打字"
                else -> "无"
            }
            writeTextFx()
        }
        editEnterMs.setOnEditorActionListener { v, _, _ -> v.clearFocus(); writeTextFx(); true }
        editExitMs.setOnEditorActionListener { v, _, _ -> v.clearFocus(); writeTextFx(); true }
        editEnterMs.setOnFocusChangeListener { _, has -> if (!has) writeTextFx() }
        editExitMs.setOnFocusChangeListener { _, has -> if (!has) writeTextFx() }
        btnApplyAllText.setOnClickListener {
            writeTextFx()
            val l = recordView.activeTextLine()
            if (l != null) {
                recordView.applyTextFxToAll(l.textEnterFx, l.textExitFx, l.textEnterMs, l.textExitMs)
            }
        }
    }

    // ---------------------------------------------------------------- 面板滑动（dispatch 层监听，不吞触摸）
    private var panelSwipeDownX = 0f
    private var panelSwipeDownY = 0f
    private var panelSwipeTracking = false
    private var panelSwipePulled = false
    private val panelSwipeSlop by lazy {
        android.view.ViewConfiguration.get(this).scaledTouchSlop.coerceAtLeast(dp2(16))
    }

    /**
     * 文字编辑双层窗口的「滑动开合」（任意方向）。
     * ★ 不消费任何事件：触摸原样交给面板里的 EditText / 按钮，输入框的光标、全选、哈点
     *   全是系统原生行为——以前热区 requestFocus 模拟点击导致输入框收不到真实触摸、
     *   光标永远停在最前面没法改（用户报的 bug）。滑动检测放在 dispatch 层，手里拿的
     *   是原始 MotionEvent，位移超过 touchSlop 才触发开合。
     */
    private fun maybeTrackPanelSwipe(ev: android.view.MotionEvent) {
        if (!::linePanelGroup.isInitialized || linePanelGroup.visibility != View.VISIBLE) {
            panelSwipeTracking = false
            return
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val inPanel = linePanelHit(ev.rawX, ev.rawY)
                panelSwipeTracking = inPanel
                // 输入框正在编辑（键盘开着）时，输入框上的拖动让给文本选择/光标定位
                if (inPanel && keyboardAcceptingText()) {
                    val hit = hitChildAt(linePanelGroup, ev.rawX, ev.rawY)
                    if (hit is EditText) panelSwipeTracking = false
                }
                if (panelSwipeTracking) {
                    panelSwipeDownX = ev.rawX
                    panelSwipeDownY = ev.rawY
                    panelSwipePulled = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!panelSwipeTracking || panelSwipePulled) return
                if (maxOf(
                        Math.abs(ev.rawX - panelSwipeDownX), Math.abs(ev.rawY - panelSwipeDownY)
                    ) > panelSwipeSlop) {
                    panelSwipePulled = true
                    panelSwipeTracking = false
                    editEnterMs.clearFocus()
                    editExitMs.clearFocus()
                    (getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager)
                        ?.hideSoftInputFromWindow(linePanelGroup.windowToken, 0)
                    // 任意方向滑动 = 切换第二层（开着收、关着开）
                    if (textAnimShown) slideTextAnimPanel(false)
                    else { refreshTextAnimInputs(); slideTextAnimPanel(true) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                panelSwipeTracking = false
            else -> {}
        }
    }

    /** 输入法当前是否在接收文本（有输入框获得焦点 / 键盘开着） */
    private fun keyboardAcceptingText(): Boolean = try {
        (getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager)?.isAcceptingText ?: false
    } catch (e: Exception) {
        false
    }

    // ---------------------------------------------------------------- 联机聊天抽屉滑动
    /**
     * dispatch 层监听（不消费事件，按钮照常）：
     * 手指在顶部「退出 ~ 玩家列表」三个按钮组成的区域上**任意方向**滑动（超出 slop）
     * ⇒ 拉出玩家聊天抽屉；仅联机时有效。点击仍按原按钮功能走。
     */
    private fun maybeTrackChatSwipe(ev: android.view.MotionEvent) {
        if (!::btnPlayer.isInitialized) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                chatSwipeX = ev.rawX
                chatSwipeY = ev.rawY
                chatSwipePulled = false
                chatSwipeTracking = inChatZone(ev.rawX, ev.rawY)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!chatSwipeTracking || chatSwipePulled) return
                if (maxOf(Math.abs(ev.rawX - chatSwipeX), Math.abs(ev.rawY - chatSwipeY)) >
                    android.view.ViewConfiguration.get(this).scaledTouchSlop.coerceAtLeast(dp2(16))) {
                    chatSwipePulled = true
                    chatSwipeTracking = false
                    if (coopRole != 0) chatDrawer?.show()
                }
            }
            else -> chatSwipeTracking = false
        }
    }

    private fun inChatZone(x: Float, y: Float): Boolean {
        // 「退出」和「保存」没有字段，每次查找一次（DOWN 才触发，开销可忽略）
        val views = arrayOf<View>(
            findViewById(R.id.btnBack), findViewById(R.id.btnSave), btnPlayer
        )
        for (v in views) {
            if (v.visibility != View.VISIBLE) continue
            v.getLocationInWindow(chatLoc)
            if (x >= chatLoc[0] && x <= chatLoc[0] + v.width &&
                y >= chatLoc[1] && y <= chatLoc[1] + v.height) return true
        }
        return false
    }

    /** 面板滑动只关心这一层窗口可见、落点在窗口内、且没有其它弹层叠在上面 */
    private fun linePanelHit(x: Float, y: Float): Boolean {
        if (!::linePanelGroup.isInitialized ||
            linePanelGroup.visibility != View.VISIBLE) return false
        // 「其他」菜单开着时它在右上角，可能与面板区域重叠——让菜单先赢
        if (::menuPanel.isInitialized && menuPanel.visibility == View.VISIBLE) return false
        val loc = IntArray(2)
        linePanelGroup.getLocationInWindow(loc)
        return x >= loc[0] && x <= loc[0] + linePanelGroup.width &&
            y >= loc[1] && y <= loc[1] + linePanelGroup.height
    }

    /** 找 (x,y)（窗口坐标）落点的最深可见子 View（递归） */
    private fun hitChildAt(v: View, x: Float, y: Float): View? {
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) {
                hitChildAt(v.getChildAt(i), x, y)?.let { return it }
            }
        }
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        if (v.visibility == View.VISIBLE &&
            x >= loc[0] && x <= loc[0] + v.width &&
            y >= loc[1] && y <= loc[1] + v.height) return v
        return null
    }

    private fun pickLineImage() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(i, REQ_LINE_IMAGE)
    }

    /**
     * 图片线：把图复制进**项目自己的导入目录**（和铺面 zip 解包的素材同目录）。
     * 谱面里只记文件名（导出时按这个名字打包）。
     * 不用原始文件名，避免重名互相覆盖、也避开中文/空格在 zip 里的坑。
     *
     * ★ 图片线的图**跟着项目走**：新建的图和导入的图都在项目导入目录里，
     * 载入草稿也只从这里找——绝不允许跨项目共享图片（用户点名：铺面图片只和铺面/项目文件有关，
     * 全局目录里的同名图绝不能兜底，否则显示错图、导出也打错图）。
     */
    private fun onLineImagePicked(uri: Uri) {
        try {
            // 项目导入目录；tabId 异常时才退回旧的共享目录（不该发生，纯兜底）
            val dir = if (meta.tabId.isNotEmpty()) ProjectStorage.importedDir(meta.tabId)
                      else File(filesDir, LINE_IMG_DIR)
            if (!dir.exists()) dir.mkdirs()
            val name = "line_" + System.currentTimeMillis() + ".png"
            val dst = File(dir, name)
            contentResolver.openInputStream(uri)?.use { ins ->
                dst.outputStream().use { out -> ins.copyTo(out, 64 * 1024) }
            } ?: run { toast("这张图片读不出来"); return }
            if (dst.length() <= 0L) { toast("这张图片读不出来"); return }
            val opt = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(dst.absolutePath, opt)
            val l = recordView.addImageLine(name, dst.absolutePath, opt.outWidth, opt.outHeight)
            if (l == null) {
                try { dst.delete() } catch (_: Exception) {}
                return
            }
            toast("已新建图片线（宽 ${opt.outWidth}px，上面不能放音符）")
        } catch (e: Exception) {
            toast("导入图片失败：" + (e.message ?: ""))
        }
    }

    // ---------------------------------------------------------------- 视频背景

    private fun pickVideo() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        startActivityForResult(i, REQ_VIDEO)
    }

    /** 视频背景：文件复制进项目导入目录（和图片线同目录），谱面里只记文件名 */
    private fun onVideoPicked(uri: Uri) {
        try {
            val dir = if (meta.tabId.isNotEmpty()) ProjectStorage.importedDir(meta.tabId)
                      else File(filesDir, LINE_IMG_DIR)
            if (!dir.exists()) dir.mkdirs()
            val name = "video_" + System.currentTimeMillis() + "." + videoExt(uri)
            val dst = File(dir, name)
            contentResolver.openInputStream(uri)?.use { ins ->
                dst.outputStream().use { out -> ins.copyTo(out, 64 * 1024) }
            } ?: run { toast("这个视频读不出来"); return }
            if (dst.length() <= 0L) { toast("这个视频读不出来"); return }
            recordView.addVideo(name)
            toast("已新建视频")
        } catch (e: Exception) {
            toast("导入视频失败：" + (e.message ?: ""))
        }
    }

    private fun videoExt(uri: Uri): String {
        val name = runCatching {
            contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        val fromName = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (fromName in setOf("mp4", "webm", "mkv", "mov", "m4v", "3gp", "avi", "ts")) return fromName
        val fromMime = (contentResolver.getType(uri) ?: "").substringAfter('/').lowercase()
        return when (fromMime) {
            "mp4" -> "mp4"
            "webm" -> "webm"
            "matroska", "x-matroska" -> "mkv"
            "quicktime" -> "mov"
            "3gpp" -> "3gp"
            else -> "mp4"
        }
    }

    /** 视频属性：开始时间 / 缩放方式 / 透明度 / 昏暗度（和 Phira extra.json 一一对应） */
    private fun editVideoProperties(index: Int) {
        val v = recordView.videos.getOrNull(index) ?: return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2(20), dp2(8), dp2(20), 0)
        }
        fun label(t: String): TextView = TextView(this).apply {
            text = t
            textSize = 12f
            setPadding(0, dp2(8), 0, 0)
        }
        fun field(value: String): EditText = EditText(this).apply {
            setText(value)
            textSize = 14f
            setSelectAllOnFocus(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val start = field(fmtNum(v.startMs / 1000f))
        val alpha = field(fmtNum(v.alpha))
        val dim = field(fmtNum(v.dim))
        var mode = v.scaleMode
        val modeBtn = TextView(this).apply {
            text = VideoBg.scaleName(mode)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp2(8), 0, dp2(8))
            setOnClickListener {
                mode = (mode + 1) % 3
                text = VideoBg.scaleName(mode)
            }
        }
        box.addView(label("开始（秒）"))
        box.addView(start)
        box.addView(label("缩放方式"))
        box.addView(modeBtn)
        box.addView(label("透明度"))
        box.addView(alpha)
        box.addView(label("昏暗度"))
        box.addView(dim)
        val dlg = KbAlertBuilder(this)
            .setTitle("视频")
            .setView(box)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val s = ((start.text.toString().toFloatOrNull() ?: (v.startMs / 1000f)) * 1000f).toLong()
                val a = alpha.text.toString().toFloatOrNull() ?: v.alpha
                val d = dim.text.toString().toFloatOrNull() ?: v.dim
                recordView.updateVideo(index, s, mode, a, d)
                toast("视频属性已更新")
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    /** 新建 / 修改文字线的内容（existing = null 表示新建） */
    private fun editLineText(existing: Line?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2(20), dp2(8), dp2(20), 0)
        }
        val input = EditText(this).apply {
            setText(existing?.lineText ?: "")
            hint = "输入文字"
            textSize = 14f
            maxLines = 2
            setSelectAllOnFocus(true)
        }
        box.addView(input)
        val dlg = KbAlertBuilder(this)
            .setTitle(if (existing == null) "输入文字" else "修改文字")
            .setView(box)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val t = input.text.toString().trim()
                if (t.isEmpty()) { toast("文字不能为空"); return@setOnClickListener }
                val ok = if (existing != null) recordView.setLineText(existing.id, t) else recordView.addTextLine(t) != null
                if (ok) { toast(if (existing == null) "已新建文字线" else "文字已更新"); dlg.dismiss() }
            }
        }
        dlg.show()
    }

    /** 面板里点了类型/正反/真假/模式：数值跟着重新读一遍（重开面板最省事，也不会残留旧文字） */
    private fun refreshNoteInfoPanel() {
        val sel = recordView.selectedNotes()
        if (sel.isEmpty()) { closeNoteInfoPanel(); return }
        val anchor = recordView.selectionAnchor() ?: return
        showNoteInfoPanel(anchor[0], anchor[1])
    }

    /** 类型 → 录制阶段的 pass（1=第一阶段 2=第二阶段 3=第三阶段 4=长条） */
    private fun passOfType(t: Int): Int = when (t) {
        1 -> 2
        4 -> 1
        2 -> 4
        else -> 3
    }

    private fun runAi(mode: Int) {
        if (aiCancel != null) { toast("AI 正在制作中，先等它结束"); return }
        // 打歌中也能用：先停掉 Autoplay，回到编辑态再开始
        if (recordView.playMode) exitPlay()
        if (coopRole != 0) { toast("联机中不支持 AI 操作（谱面要实时同步给房间里的人）"); return }
        val ep = AiStore.active(this)
        if (ep == null || !ep.usable) {
            toast("请先在主界面长按「AI」按钮配置接口、API Key 和模型")
            return
        }
        showOtherMenu(false)
        val title = when (mode) {
            0 -> "自动写谱"
            1 -> "自动修谱"
            else -> "自动缤纷"
        }
        // 做谱前先检查设备延迟：延迟太大写出来的谱根本没法打（写谱/修谱才需要）
        if (mode != 2 && meta.delayMs > 150) {
            KbAlertBuilder(this)
                .setTitle("延迟偏大")
                .setMessage("当前设备延迟是 ${meta.delayMs} ms，偏大，写出来的谱可能对不上音乐。\n建议先去「校准」再回来。仍然继续吗？")
                .setPositiveButton("继续") { _, _ -> launchAi(mode, ep, title) }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        launchAi(mode, ep, title)
    }

    private fun launchAi(mode: Int, ep: AiEndpoint, title: String) {
        // 记录改之前的谱面，AI 做错了可以「撤回」
        aiBeforeDraft = try { recordView.saveDraft() } catch (_: Exception) { null }
        aiUndoSeeded = false
        showAiDialog(title)
        startAiJob(mode, ep)
    }

    /** 原生弹窗：标题 + 「正在制作中」+ 进度条 + 底部「取消 / 关闭」 */
    private fun showAiDialog(title: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2(18), dp2(6), dp2(18), dp2(2))
        }
        val msg = TextView(this).apply {
            text = "正在制作中…"
            setTextColor(0xFF333333.toInt())
            textSize = 13f
            setPadding(0, 0, 0, dp2(8))
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = true
        }
        box.addView(msg)
        box.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp2(18)
        ))
        aiMsg = msg
        aiBar = bar
        val d = KbAlertBuilder(this)
            .setTitle(title)
            .setView(box)
            .setNegativeButton("取消", null)
            .setNeutralButton("撤回", null)
            .setPositiveButton("关闭", null)
            .setCancelable(false)
            .create()
        d.setOnShowListener {
            d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
            d.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).isEnabled = false
            d.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                // 取消 = 停掉请求，已经生成的音符/特效保留
                aiCancel?.cancelled = true
                d.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                aiMsg?.text = "正在停止，保留已生成的内容…"
            }
        }
        d.show()
        aiDialog = d
    }

    private fun startAiJob(mode: Int, ep: AiEndpoint) {
        val cancel = AiClient.Cancel()
        aiCancel = cancel
        val dur = durationMs.toLong().coerceAtLeast(1L)
        val bpm = meta.bpm
        val level = meta.level
        Thread {
            var holder: AiChart.Holder? = null
            var parsed = 0
            var expected = 1
            var scanFrom = 0
            var lastApply = 0L
            var usedLocal = false
            fun ui(block: () -> Unit) = runOnUiThread { if (!isFinishing && !isDestroyed) block() }

            fun applyNow(enhance: Boolean = false) {
                val h = holder ?: return
                lastApply = SystemClock.elapsedRealtime()
                ui { applyAiHolder(h, enhance) }
            }

            /** 从收到的整段文本里扫描「已经闭合」的 JSON 对象逐个应用。
             *  不依赖换行 —— AI 若把数组/多个对象挤在一行也能解析；半截的对象留给下一段。 */
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
                        val c = full[j]
                        if (inStr) {
                            if (esc) esc = false
                            else if (c == '\\') esc = true
                            else if (c == '"') inStr = false
                        } else when (c) {
                            '"' -> inStr = true
                            '{' -> depth++
                            '}' -> {
                                depth--
                                if (depth == 0) { end = j; break }
                            }
                        }
                        j++
                    }
                    if (end < 0) { scanFrom = i; return }      // 还没闭合，等下一段
                    val obj = full.substring(i, end + 1)
                    i = end + 1
                    scanFrom = i
                    val h = holder ?: continue
                    if (AiChart.applyJsonLine(obj, h, dur, bpm, meta.hLines)) {
                        parsed++
                        val p = (parsed * 92.0 / expected).toInt().coerceIn(0, 92) + 6
                        ui {
                            aiBar?.isIndeterminate = false
                            if ((aiBar?.progress ?: 0) < p) aiBar?.progress = p
                        }
                        if (SystemClock.elapsedRealtime() - lastApply > 800) applyNow(false)
                    }
                }
                scanFrom = i
            }

            try {
                // 1) 音频分析（写谱/修谱要用；缤纷只看谱面）
                var beats: List<AudioBeats.Beat> = emptyList()
                if (mode != 2) {
                    ui { aiMsg?.text = "正在分析音频…"; aiBar?.isIndeterminate = true }
                    val r = AudioBeats.analyze(this, meta.musicUri, isCancelled = { cancel.cancelled })
                    if (r != null) beats = r.beats
                }
                if (cancel.cancelled) throw InterruptedException("cancelled")

                // 2) 准备提示词和初始谱面
                val baseLines = AiChart.copyLines(recordView.lines)
                val baseNotes = if (mode == 0) ArrayList<RecordedNote>() else ArrayList(recordView.notes)
                holder = AiChart.Holder(
                    baseLines, baseNotes,
                    (baseLines.maxOfOrNull { it.id } ?: 0) + 1,
                    // 噪域 / 空图层声明必须带进 Holder：以前缺这三项，走一次「自动写谱」
                    // 就会把整张谱的噪域和空图层清光（写回草稿时是空的）
                    noises = recordView.noiseAreas.toMutableList(),
                    declaredLayers = recordView.declaredLayers,
                    declaredNoiseLayers = recordView.noiseDeclaredLayerKeys(),
                    videos = recordView.videos.map { it.copy() },
                    writeMode = mode == 0,
                    diversify = mode == 0,
                    difficulty = difficultyOf(level)
                )
                val system: String
                val user: String
                val difficulty = difficultyOf(level)
                when (mode) {
                    0 -> {
                        val durSec = dur / 1000.0
                        // 难度 0~20 → 每秒音符数 0.7~3.3（不再堆成一片）
                        expected = Math.round((0.7 + difficulty * 0.13) * durSec)
                            .toInt().coerceIn(30, 900)
                        system = AiPrompts.systemWrite()
                        user = AiPrompts.writeUser(
                            bpm, meta.bpmOffsetMs, dur, level, difficulty, baseLines, beats, expected
                        )
                    }
                    1 -> {
                        expected = (baseNotes.size / 3).coerceIn(8, 60)
                        system = AiPrompts.systemFix()
                        user = AiPrompts.fixUser(
                            bpm, dur, beats, AiChart.chartDigest(baseLines, baseNotes)
                        )
                    }
                    else -> {
                        expected = 24
                        system = AiPrompts.systemDeco()
                        user = AiPrompts.decoUser(
                            bpm, dur, AiChart.chartDigest(baseLines, baseNotes), expected
                        )
                    }
                }
                ui {
                    aiMsg?.text = "AI 正在制作中…"
                    aiBar?.isIndeterminate = false
                    aiBar?.progress = 6
                }

                // 3) 流式请求，边收边套用
                val aiMessages = mutableListOf("system" to system, "user" to user)
                val full = AiClient.chat(
                    ep,
                    aiMessages,
                    cancel,
                    maxTokens = if (mode == 0) 8192 else 4096,
                    temperature = if (mode == 0) 0.8 else 0.5,
                    onDelta = { text -> consume(text) }
                )
                // 收尾：把最后一段也扫一遍
                consume(full)
                // 写谱时按“时间覆盖”续写：只输出到一半、或大量重复导致去重后变少，都要继续补到曲尾
                if (mode == 0 && !cancel.cancelled && parsed > 0 && dur > 0) {
                    aiMessages.add("assistant" to full)
                    var round = 0
                    while (round < 3 && !cancel.cancelled) {
                        val h = holder ?: break
                        val lastMs = h.notes.maxOfOrNull { it.startMs } ?: 0L
                        if (h.notes.isEmpty()) break
                        val tailGap = dur - lastMs
                        if (tailGap <= maxOf(2000L, dur / 20)) break
                        scanFrom = 0
                        ui { aiMsg?.text = "AI 正在续写后半段…（已覆盖 ${lastMs / 1000}s / ${dur / 1000}s）" }
                        val contPrompt = "继续写谱。已有音符最后到 ${lastMs}ms，曲长 ${dur}ms。" +
                            "请只输出 ${lastMs}ms 之后的新音符，按时间递增，最后一个音符要接近曲长；" +
                            "不要重复 ${lastMs}ms 及之前的音符，不要解释，一行一个 JSON 对象。"
                        val cont = AiClient.chat(
                            ep,
                            aiMessages + ("user" to contPrompt),
                            cancel,
                            maxTokens = 8192,
                            temperature = 0.8,
                            onDelta = { text -> consume(text) }
                        )
                        consume(cont)
                        aiMessages.add("assistant" to cont)
                        val newLast = holder?.notes?.maxOfOrNull { it.startMs } ?: lastMs
                        if (newLast <= lastMs) break
                        round++
                    }
                }
                // AI 完全没给出可识别音符时，用本地节拍引擎兜底（时间仍然只取重音点）
                if (mode == 0 && !cancel.cancelled && parsed == 0) {
                    holder?.let { h ->
                        val local = AutoChart.build(
                            beats, difficulty, h.lines.firstOrNull()?.id ?: 0, dur, bpm
                        )
                        if (local.isNotEmpty()) {
                            h.notes.clear()
                            h.notes.addAll(local)
                            parsed = local.size
                            usedLocal = true
                        }
                    }
                }
            } catch (e: Exception) {
                val msg = when {
                    cancel.cancelled -> "已取消"
                    e.message.isNullOrBlank() -> "请求失败"
                    else -> "请求失败：" + e.message
                }
                Log.w(TAG, "AI job failed", e)
                ui {
                    holder?.let { applyAiHolder(it, true) }
                    aiBeforeDraft?.let { aiUndoSeeded = recordView.seedUndoForReplace(it) }
                    finishAiUi(if (cancel.cancelled) "已停止，保留已生成的内容" else msg, false)
                }
                aiCancel = null
                return@Thread
            }

            ui {
                holder?.let { applyAiHolder(it, true) }
                aiBeforeDraft?.let { aiUndoSeeded = recordView.seedUndoForReplace(it) }
                // 注意：这里**不落盘** —— 用户退出时选「不保存」才不保存，选「保存」才写草稿
                val h = holder
                val n = h?.notes?.size ?: 0
                val what = when {
                    usedLocal -> "AI 未返回结果，已按节拍本地生成 " + n + " 个音符"
                    parsed == 0 -> "AI 没有返回可识别的节奏数据，请重试或换个模型"
                    mode == 0 -> "已写入 " + n + " 个音符"
                    mode == 1 -> "已修正 " + parsed + " 处"
                    else -> "已添加 " + parsed + " 条特效"
                }
                finishAiUi(if (cancel.cancelled) "已停止，保留已生成的内容" else "完成：" + what, true)
            }
            aiCancel = null
        }.start()
    }

    /** 结束态：进度 100、允许点「关闭」；[ok]=true 才提示成功 */
    private fun finishAiUi(text: String, ok: Boolean) {
        aiMsg?.text = text
        aiBar?.isIndeterminate = false
        aiBar?.progress = 100
        aiDialog?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
        aiDialog?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            aiDialog?.dismiss()
        }
        aiDialog?.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
        // 「撤回」：还原 AI 开始前的谱面
        val before = aiBeforeDraft
        aiDialog?.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.apply {
            isEnabled = before != null
            setOnClickListener {
                before?.let {
                    // seedUndoForReplace 已经把「AI 改之前」压成一步撤销；这里必须走 undoLast()
                    // 把这一步真正消费掉（还能用「重做」再加回来）。以前直接 loadDraft 会把
                    // 这一步留在栈上，之后玩家再点撤销会莫名少掉更早的一步。
                    if (!aiUndoSeeded || !recordView.undoLast()) {
                        // 兜底（快照解析失败 / 联机锁定等）：整份载入，保留之前手改的历史
                        try { recordView.loadDraft(it, keepHistory = true) } catch (_: Exception) {}
                    }
                }
                aiBeforeDraft = null
                aiUndoSeeded = false
                isEnabled = false
                aiDialog?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                toast("已撤回 AI 的改动")
                refreshInfo()
                aiDialog?.dismiss()
            }
        }
        if (ok) toast(text)
        refreshInfo()
    }

    /** 把 AI 当前结果整体套进 RecordView（替换谱面，走草稿编解码，保证正反面/假音符都带上） */
    private fun applyAiHolder(h: AiChart.Holder, enhance: Boolean) {
        try {
            // 续写可能产生重复音符：按 类型+时间+位置+线 去重
            val seen = HashSet<String>()
            val notes = h.notes.filter {
                seen.add(it.type.toString() + "|" + it.startMs + "|" + it.endMs + "|" +
                    it.lineId + "|" + Math.round(it.posX * 1000f))
            }.sortedBy { it.startMs }
            // 只有最终落盘时才做模板/抽稀后处理：流式预览阶段少算一遍，省 CPU
            val finalNotes = if (enhance && h.diversify)
                AutoChart.enhance(notes, h.difficulty, durationMs.toLong(), meta.bpm)
            else notes
            val text = DraftCodec.encode(
                h.lines, finalNotes,
                noise = h.noises,
                declaredLayers = h.declaredLayers,
                declaredNoiseLayers = h.declaredNoiseLayers,
                videos = h.videos
            )
            // keepHistory=true：流式预览会反复套用，别每次都把撤销历史清空；
            // 这一轮结束由 seedUndoForReplace 补一条「AI 改之前」的撤销步
            recordView.loadDraft(text, keepHistory = true)
            recordView.invalidate()
        } catch (_: Exception) {}
    }

    private fun saveDraft() {
        val f = draftFile ?: return
        val parent = f.parentFile ?: return
        try {
            if (!parent.exists()) parent.mkdirs()
            f.writeText(recordView.saveDraft())
            chartDirty = false               // 已经写盘：之后没改动就退出不再弹窗
        } catch (_: Exception) {}
    }

    /** 毫秒 → HH:MM:SS（时:分:秒） */
    private fun fmtHms(ms: Long): String {
        val totalSec = ms.coerceAtLeast(0L) / 1000L
        val h = totalSec / 3600L
        val m = totalSec % 3600L / 60L
        val s = totalSec % 60L
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    /** 进度条右侧「当前-总长」：拖动时传预览位置，其余按播放器 / 滑块读取 */
    private fun updateTimeDisplay(previewMs: Int? = null) {
        if (!::txtTime.isInitialized) return
        val total = durationMs.toLong().coerceAtLeast(0L)
        val cur = when {
            previewMs != null -> previewMs.toLong()
            seeking -> seekBar.progress.toLong()
            else -> {
                val pos = try { mediaPlayer?.currentPosition?.toLong() ?: 0L } catch (_: Exception) { 0L }
                // 播完后 currentPosition 可能回绕成 0，用滑块位置兜底，别显示 00:00:00
                if (pos <= 0L && seekBar.progress > 0) seekBar.progress.toLong() else pos
            }
        }.coerceIn(0L, total)
        txtTime.text = fmtHms(cur) + "-" + fmtHms(total)
    }

    private fun refreshInfo() {
        // 打歌 HUD 的曲名/难度跟着项目信息走（改曲名、改难度立刻反映到左下角/右下角）
        applyHudMeta()
        // 打歌（Autoplay）时左上角不留任何小字
        if (recordView.playMode) {
            txtInfo.visibility = android.view.View.GONE
            return
        }
        txtInfo.visibility = android.view.View.VISIBLE
        val segTxt = if (meta.bpmSegments.size > 1) "（" + meta.bpmSegments.size + " 段）" else ""
        if (recordView.editMode) {
            val ln = if (recordView.lineCount > 1) " · 线" + (recordView.activeLineIndex + 1) + "/" + recordView.lineCount else ""
            txtInfo.text = "编辑模式" + ln + (if (segTxt.isEmpty()) "" else " · BPM" + segTxt)
            return
        }
        val st = recordView.noteStats()
        txtInfo.text = "BPM " + trimNum(meta.bpm) + segTxt + " · 横线 " + meta.hLines + " · 竖线 " + meta.vLines + " · " +
            "总 " + st[0] + "（1:" + st[1] + " 2:" + st[2] + " 3:" + st[3] +
            (if (st[4] > 0) " 假:" + st[4] else "") + "）"
    }

    /**
     * 打歌 HUD 左下角曲名 / 右下角难度（Phira 同位置）。
     * **三种打歌入口共用**：选曲页 START、编辑页 PLAY/AUTOPLAY、联机「你写我打」打方。
     */
    private fun applyHudMeta() {
        if (!::recordView.isInitialized) return
        recordView.hudTitle = meta.name
        recordView.hudLevel = meta.level
    }

    /** 保留 3 位小数（BPM / BPM 偏移都支持 3 位小数） */
    private fun round3(v: Double): Double = if (!v.isFinite()) 0.0 else Math.round(v * 1000.0) / 1000.0

    private fun trimNum(d: Double): String {
        val r = round3(d)
        return if (r == Math.floor(r) && !r.isInfinite()) r.toLong().toString() else r.toString()
    }

    /** 从「难度」文本里取 0~20 的数值（"12+" "Lv.15" "9.5" 都能认；没有数字默认 8） */
    private fun difficultyOf(level: String): Double =
        Regex("\\d+(?:\\.\\d+)?").find(level)?.value?.toDoubleOrNull()?.coerceIn(0.0, 20.0) ?: 8.0

    /** 读取输入框并应用变速（0.5~2.0） */
    private fun applySpeedFromInput() {
        val raw = speedEdit.text.toString().trim()
        val sp = raw.toFloatOrNull()
        if (sp == null || sp < 0.01f || sp > 100f) {
            speedEdit.setText(curSpeed.toString())
            return
        }
        curSpeed = sp
        speedEdit.setText(sp.toString())
        applySpeed(sp)
        if (mediaPlayer?.isPlaying != true) {
            // 暂停时输入倍速：保存，播放时生效（不弹 toast）
        }
    }

    private fun applySpeed(sp: Float) {
        try {
            val mp = mediaPlayer ?: return
            if (!mp.isPlaying) return          // setPlaybackParams 仅播放中可用，暂停时跳过
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                val p = mp.playbackParams
                mp.playbackParams = p.setSpeed(sp)
                resetPlayheadAnchor()
            }
        } catch (_: Exception) {}
    }

    private fun startPlayback() {
        // 未选音频：直接进入编辑模式（不报错，AUTOPLAY/暂停自动禁用）
        if (meta.musicUri == Uri.EMPTY) {
            btnPause.text = "暂停"
            refreshInfo()
            return
        }
        try {
            mediaPlayer?.release()
            mediaPlayer = null
            val mp = MediaPlayer()
            val afd = contentResolver.openAssetFileDescriptor(meta.musicUri, "r")
            if (afd == null) { toast("无法打开音频"); return }
            afd.use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            mp.prepare()
            durationMs = mp.duration
            seekBar.max = durationMs.coerceAtLeast(1)
            recordView.trackLengthMs = durationMs.toLong()
            mediaPlayer = mp
            mp.setOnCompletionListener {
                // 播完把进度条钉在末尾：此时 currentPosition 可能已回绕成 0
                if (durationMs > 0) {
                    try { seekBar.progress = durationMs } catch (_: Exception) {}
                    updateTimeDisplay(durationMs)
                }
                // ★ 三种打歌入口统一：曲子放完 = 这一局结束 → Phira 结算界面
                //（选曲页 START / 编辑页 PLAY·AUTOPLAY / 联机「你写我打」打方 都是这一条）
                if (recordView.playMode) finishManualAndShowEnding()
            }
            mp.seekTo(0)
            try { seekBar.progress = 0 } catch (_: Exception) {}
            updateTimeDisplay(0)
            // 进入录制默认暂停：点右下角 AUTOPLAY 才播放
            btnPause.text = "继续"
            applySpeed(curSpeed)
            Log.i(TAG, "playback ready(not started) dur=${durationMs}ms speed=$curSpeed outLatency=${audioOutputLatencyMs}ms")
            DebugLog.add("latency", "输出延迟≈${audioOutputLatencyMs}ms（AudioManager 估算，仅诊断）；输入按 MotionEvent.eventTime 补偿排队抖动")
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "playback failed", e)
            toast("音频播放失败：${e.message}")
        }
    }

    private fun exportPez() {
        val list = recordView.notes.toList()
        if (list.isEmpty()) { toast("还没有放任何音符"); return }
        if (exporting) { toast("正在导出…"); return }
        showExportDialog(list)
    }

    /** 导出进度弹窗：后台打包，进度条 + 百分比，完成后点「确认」再选保存位置 */
    private fun showExportDialog(list: List<RecordedNote>) {
        exporting = true
        exportTmp = null
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2(22), dp2(18), dp2(22), dp2(4))
        }
        exportBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp2(14)
            ).also { it.topMargin = dp2(16) }
        }
        box.addView(exportBar)
        exportInfo = TextView(this).apply {
            text = "0%"
            setTextColor(0xFF777777.toInt())
            textSize = 12f
            gravity = Gravity.END
            setPadding(0, dp2(6), 0, 0)
        }
        box.addView(exportInfo)

        val dlg = KbAlertBuilder(this)
            .setTitle("导出谱面")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("确认", null)
            .setNegativeButton("取消", null)
            .create()
        exportDlg = dlg
        dlg.setOnShowListener {
            exportOk = dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).apply {
                isEnabled = false
                setOnClickListener {
                    val tmp = exportTmp
                    dlg.dismiss()
                    exportDlg = null
                    if (tmp != null) launchSavePicker()
                }
            }
            dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                .setOnClickListener {
                    exportTicker?.let { handler.removeCallbacks(it) }
                    exportTicker = null
                    exportDlg = null
                    dlg.dismiss()
                    exportTmp = null
                    // exporting 保持 true：后台打包还在写同一个 export.zip，等它收尾再允许再次导出
                }
        }
        dlg.show()
        startExportTicker()

        Thread {
            val tmp = try {
                packPezForExport(list) { p ->
                    runOnUiThread {
                        if (exportDlg === dlg) {
                            val cur = exportBar?.progress ?: 0
                            if (p > cur) {
                                exportBar?.progress = p
                                exportInfo?.text = "$p%"
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "export pack failed", e)
                null
            }
            runOnUiThread {
                exporting = false
                if (exportDlg !== dlg) {
                    tmp?.delete()
                    return@runOnUiThread
                }
                exportTicker?.let { handler.removeCallbacks(it) }
                exportTicker = null
                if (tmp != null) {
                    exportTmp = tmp
                    exportBar?.progress = 100
                    exportInfo?.text = "100%"
                } else {
                    exportInfo?.text = getString(R.string.msg_export_fail)
                }
                exportOk?.isEnabled = true
            }
        }.start()
    }

    private fun startExportTicker() {
        exportTicker?.let { handler.removeCallbacks(it) }
        exportTicker = object : Runnable {
            override fun run() {
                val bar = exportBar ?: return
                // 真实进度由打包回调推进；这里只兜底"音频没有大小、进度不动"的情况
                if (bar.progress < 90) {
                    bar.progress += 1
                    exportInfo?.text = bar.progress.toString() + "%"
                    handler.postDelayed(this, 70)
                }
            }
        }
        handler.postDelayed(exportTicker!!, 70)
    }

    private fun launchSavePicker() {
        try {
            val safeName = meta.name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, "$safeName.zip")
            }
            startActivityForResult(intent, REQ_SAVE)
        } catch (e: Exception) {
            e.printStackTrace()
            toast(getString(R.string.msg_export_fail) + "：" + e.message)
        }
    }

    /** 把当前谱面打成 .pez 的 zip（放 cacheDir），失败返回 null */
    private fun packPezForExport(
        list: List<RecordedNote>,
        onProgress: ((Int) -> Unit)? = null
    ): File? {
        val dir = File(cacheDir, "pez")
        if (!dir.exists()) dir.mkdirs()
        val tmp = File(dir, "export.zip")
        // 判定线姿态全在 recordView.lines 里，meta 只提供曲目信息
        val ok = PezExporter.export(
            meta, recordView.lines, list, contentResolver, tmp,
            recordView.noiseAreas, recordView.videos, onProgress
        )
        return if (ok) tmp else null
    }

    /**
     * **不弹选择器**的导出（给 AI 用：用户说「导出谱面」时直接落盘，不用再点一次保存框）。
     *
     * 落点：`下载/FRPHI/<曲名>.zip`（Android 10+ 走 MediaStore，不需要任何权限）；
     * Android 9 及以下没有权限时退到应用自己的外部目录（`Android/data/.../files/export`）。
     * 返回一句给用户看的结果（含落盘位置）。
     */
    fun exportChartSilently(): String {
        val list = recordView.notes.toList()
        if (list.isEmpty()) return "还没有放任何音符，导出不了。"
        var src: File? = null
        try {
            src = packPezForExport(list)
        } catch (e: Exception) {
            Log.e(TAG, "export pack failed", e)
        }
        if (src == null) return "导出失败：打包不出 .pez（" + getString(R.string.msg_export_fail) + "）"
        val safeName = meta.name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").ifBlank { "chart" }
        val fileName = "$safeName.zip"
        // Android 10+：MediaStore 写「下载」不需要权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FRPHI")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { it.copyTo(out) }
                    }
                    return "已导出：" + fileName + "（在手机的「下载/FRPHI」里，共 " + list.size + " 个音符）"
                }
            } catch (e: Exception) {
                Log.e(TAG, "export to Downloads failed", e)
            }
        }
        // 兜底：应用自己的外部目录（卸载才会删），不需要权限
        return try {
            val dir = File(getExternalFilesDir(null) ?: filesDir, "export")
            if (!dir.exists()) dir.mkdirs()
            val dst = File(dir, fileName)
            src.copyTo(dst, true)
            "已导出：" + dst.absolutePath + "（共 " + list.size + " 个音符）"
        } catch (e: Exception) {
            Log.e(TAG, "export fallback failed", e)
            "导出失败：" + (e.message ?: e.javaClass.simpleName)
        }
    }

    /** 剪贴板面板 → 导入 .frclip */
    private fun pickClipImport() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, REQ_CLIP_IMPORT)
        } catch (e: Exception) {
            toast("打不开文件选择器：" + e.message)
        }
    }

    /** 剪贴板面板 → 导出某一条为 .frclip */
    private fun exportClipEntry(entryIndex: Int) {
        val text = recordView.exportClipEntry(entryIndex)
        if (text == null) { toast("这条剪贴板是空的"); return }
        try {
            val f = File(cacheDir, "clip.frclip")
            f.writeText(text)
            clipTmp = f
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "frclip_$stamp.frclip")
            }
            startActivityForResult(intent, REQ_CLIP_EXPORT)
        } catch (e: Exception) {
            toast("导出失败：" + e.message)
        }
    }

    private var exitAfterExport = false

    /**
     * 判定线抽屉开着时，点顶部/底部栏的任何按钮都只用来"关抽屉"，不再触发那个按钮。
     * （抽屉画在 RecordView 里，而顶/底栏是它的兄弟 View，所以要在 Activity 这一层拦。）
     *
     * ★★ 现在三个侧边窗口都**贴边、上下顶到屏幕边缘**，窗口打开时 refreshBarsButton() 会把
     * 两条栏收起（GONE）。GONE 之后 topBar.height / bottomBar.top 仍是上次布局的旧值
     * （bottomBar.top 甚至可能是 0），照旧按 y <= topH || y >= botTop 拦，会把**点判定线
     * 窗口里的标题 / 页签 / 列表行 / 页脚**全部误判成"点顶底栏" ⇒ 一按就收窗口
     * （用户报的「点里面的内容立即收起，其他窗口都正常」）。⇒ **只在栏确实可见时才拦**；
     * 栏收起时所有点击交给 RecordView 自己的 handleLineSwitcherTouch 处理，窗口外才关窗。
     */
    /**
     * 快捷操作（外接键盘）。「其他 → 操作 → 快捷操作」三态：
     *   0 关闭：键盘**完全不接管**（空格暂停/开始也只在开启后才有）；
     *   1 开启：全键位，窗口键按一下开、再按一下关；
     *   2 快速：窗口键**按住打开、松开就关**（只关本次按键自己打开的那个窗口）。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val kc = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        val up = event.action == KeyEvent.ACTION_UP
        val repeat = event.repeatCount
        val mode = try {
            getSharedPreferences("frphi_rec", 0).getInt("quickOp", 0).coerceIn(0, 2)
        } catch (_: Exception) { 0 }
        if (mode == 0) return super.dispatchKeyEvent(event)
        val typing = currentFocus is EditText

        // ---- 结算界面：ESC = 重来，Enter = 继续；其它键一律不参与 ----
        if (endingOverlay != null) {
            if (down && repeat == 0) {
                if (kc == KeyEvent.KEYCODE_ESCAPE) {
                    // ★ 原「重来」按钮是在自己的点击监听里 removeView 的；
                    //   键盘直接调回调会绕过那一步，结算层还盖着 → 看着像没重来。
                    closeEnding()
                    endingRetry?.invoke()
                } else if (KbNavUtil.isEnter(kc)) endingProceed?.invoke()
            }
            return true
        }

        // ---- 选曲页「自己打」打歌中：ESC = 结束这一局 → Phira 结算界面
        //      （和暂停菜单最左边「<」图标完全同一个入口；其它打歌入口 ESC 保持原行为）
        if (kc == KeyEvent.KEYCODE_ESCAPE && down && repeat == 0 &&
            fromPlayPage && recordView.playMode) {
            focusOverlay?.clear()
            if (coopGameOn && coopRole != 0) Coop.stopGame()
            finishManualAndShowEnding()
            return true
        }

        // ---- ESC = 返回：**一次只关最上面一层**，逐层往下，最后才走系统返回 ----
        if (kc == KeyEvent.KEYCODE_ESCAPE) {
            if (down && repeat == 0) {
                focusOverlay?.clear()
                if (kbLineLeftPending) {
                    kbLineLeftPending = false; kbLineLeftDone = true; kbLineLeftCircle = 1
                    recordView.keyboardPanelResetSwipe()
                }
                when {
                    menuPanel.visibility == View.VISIBLE -> showOtherMenu(false)
                    recordView.isLinePanelOpen -> recordView.closeLinePanel(keepSelection = true)
                    recordView.isClipPanelOpen -> recordView.closeClipPanel()
                    playerWindow != null -> closePlayerWindow(startIfReady = false)
                    playMenuOverlay != null -> hidePlayMenu()
                    chatDrawer?.isOpen == true -> chatDrawer?.close()
                    ::aiDrawer.isInitialized && aiDrawer.isOpen -> aiDrawer.close()
                    else -> onBackPressed()
                }
            }
            return true
        }

        // ---- 空格：暂停 / 开始 ----
        if (kc == KeyEvent.KEYCODE_SPACE) {
            if (down && repeat == 0) btnPause.performClick()
            return true
        }

        // ---- 统一键盘预选择框（白色直角虚线框）----
        // 作用域优先级：判定线 / 剪贴板面板 > 「其他」菜单 > 玩家列表 > AI 抽屉
        val scope = navScope()
        if (scope != navScopeKey) {
            navScopeKey = scope; kbNavIndex = -1; focusOverlay?.clear()
            kbLineLeftPending = false; kbLineLeftDone = false; kbLineLeftCircle = 1
            recordView.keyboardPanelResetSwipe()
        }
        if (scope.isNotEmpty()) {
            // 按住 "<" 时做了别的操作（方向键 / 回车 / 其它）→ 圆圈状态到此为止，不执行复制
            if (kbLineLeftPending && down && repeat == 0 &&
                kc != KeyEvent.KEYCODE_COMMA && kc != KeyEvent.KEYCODE_SLASH) {
                kbLineLeftPending = false
                kbLineLeftDone = true
                kbLineLeftCircle = 1
                recordView.keyboardPanelResetSwipe()
            }
            val dir = KbNavUtil.dirOf(kc)
            val enter = KbNavUtil.isEnter(kc)
            val swipe = KbNavUtil.swipeOf(kc)
            val slash = kc == KeyEvent.KEYCODE_SLASH
            if (dir != 0) {
                if (down) kbNavMove(dir)
                return true
            }
            if (slash) {
                if (down && repeat == 0) {
                    if (kbLineLeftPending) {
                        // 按住 "<" 时按 "/"：只移动到第 2 个圆圈（带音符），什么都不做
                        kbSlashUsed = true
                        kbLineLeftCircle = 2
                        recordView.keyboardPanelSetCircle(true)
                    } else { kbSlashHeld = true; kbSlashUsed = false }
                } else if (up) {
                    if (kbLineLeftPending) {
                        // "<" 还按着、松开 "/" ⇒ 滑回第 1 个圆圈，仍然什么都不做
                        kbLineLeftCircle = 1
                        recordView.keyboardPanelSetCircle(false)
                    } else if (!kbSlashUsed) kbNavAct(kbNavIndex, KbAct.LONG)
                    kbSlashHeld = false
                }
                return true
            }
            if (swipe >= 0) {
                val isLineLeft = scope == "line" && swipe == KbAct.SWIPE_LEFT
                if (down && repeat == 0) {
                    if (isLineLeft) {
                        // 判定线左滑：按下只停在圆圈上，直到松开 "<" 才执行
                        if (kbNavIndex < 0) kbNavMove(1)
                        kbLineLeftPending = true
                        kbLineLeftIndex = kbNavIndex
                        kbLineLeftDone = false
                        kbLineLeftCircle = if (kbSlashHeld) 2 else 1
                        recordView.keyboardPanelSwipeAnim(kbNavIndex, KbAct.SWIPE_LEFT, hold = true)
                        if (kbSlashHeld) {
                            kbSlashUsed = true
                            recordView.keyboardPanelSetCircle(true)
                        }
                    } else {
                        val act = if (kbSlashHeld) {
                            kbSlashUsed = true
                            if (swipe == KbAct.SWIPE_LEFT) KbAct.SWIPE_LEFT2 else KbAct.SWIPE_RIGHT2
                        } else swipe
                        kbNavAct(kbNavIndex, act)
                    }
                } else if (up && isLineLeft && kbLineLeftPending && !kbLineLeftDone) {
                    // 松开 "<" 才真正执行：按当前停在第几个圆圈决定带不带音符
                    val act = if (kbLineLeftCircle == 2) KbAct.SWIPE_LEFT2 else KbAct.SWIPE_LEFT
                    kbLineLeftAct(kbLineLeftIndex, act)
                }
                return true
            }
            if (enter) {
                if (down && repeat == 0) {
                    val ef = currentFocus
                    if (ef is EditText) ef.clearFocus()   // 数值框：回车 = 确认输入
                    else kbNavAct(kbNavIndex, KbAct.CLICK)
                }
                return true
            }
        }

        // ---- 窗口键：Tab 玩家列表 / 左Shift 剪切板 / 左Alt 判定线面版 / 右Alt 其他列表 ----
        when (kc) {
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> {
                if (down && repeat == 0) {
                    if (mode == 2) openQuickWindow(kc) else toggleQuickWindow(kc)
                } else if (up && mode == 2) {
                    closeQuickWindow(kc)
                }
                return true
            }
        }

        // 打字时字母/数字键交还系统；顺手把长按 Q/A 残留的预览偏移落定
        if (typing) {
            if (qSeekPending) { recordView.flushEditSeek(); qSeekPending = false }
            return super.dispatchKeyEvent(event)
        }

        // ---- 右Shift = AUTOPLAY ----
        if (kc == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            if (down && repeat == 0) btnPlay.performClick()
            return true
        }

        // ---- 1/2/3/4 = 第几次 ----
        val passBtn = when (kc) {
            KeyEvent.KEYCODE_1 -> btnPass1
            KeyEvent.KEYCODE_2 -> btnPass2
            KeyEvent.KEYCODE_3 -> btnPass3
            KeyEvent.KEYCODE_4 -> btnPass4
            else -> null
        }
        if (passBtn != null) {
            if (down && repeat == 0) passBtn.performClick()
            return true
        }

        // ---- Enter = PLAY（菜单外） ----
        if (kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            kc == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (down && repeat == 0) btnPlayManual.performClick()
            return true
        }

        // ---- 选中音符 Ctrl+C / V / X，Ctrl+Z 撤销 ----
        if (down && repeat == 0 && event.isCtrlPressed) {
            when (kc) {
                KeyEvent.KEYCODE_C -> { recordView.copySelection(); refreshInfo() }
                KeyEvent.KEYCODE_V -> { recordView.pasteClipboard(); refreshInfo() }
                KeyEvent.KEYCODE_X -> { recordView.cutSelection(); refreshInfo() }
                KeyEvent.KEYCODE_Z -> { recordView.undo(); refreshInfo() }
                else -> return super.dispatchKeyEvent(event)
            }
            qCtrlCombo = true
            return true
        }
        if (up && qCtrlCombo && (kc == KeyEvent.KEYCODE_C || kc == KeyEvent.KEYCODE_V ||
                kc == KeyEvent.KEYCODE_X || kc == KeyEvent.KEYCODE_Z)) {
            qCtrlCombo = false
            return true
        }

        // ---- W/S 缩放：单击 ±0.1，长按每 repeat ±0.02 平滑移动 ----
        if (kc == KeyEvent.KEYCODE_W || kc == KeyEvent.KEYCODE_S) {
            if (down) {
                val step = if (repeat == 0) 0.1f else 0.02f
                val d = if (kc == KeyEvent.KEYCODE_W) step else -step
                setScale(recordView.timelineScale + d, true)
            }
            return true
        }

        // ---- Q/A 时间轴：单击一个横细线，长按每 repeat 1/4 格平滑，松手一次落定 ----
        // 键位 Q/E → Q/A，方向整体反转：Q = 时间轴前进，A = 后退
        if (kc == KeyEvent.KEYCODE_Q || kc == KeyEvent.KEYCODE_A) {
            if (down) {
                val g = recordView.gridStepMs()
                val step = if (repeat == 0) g else (g / 4L).coerceAtLeast(1L)
                recordView.addEditSeek((if (kc == KeyEvent.KEYCODE_Q) step else -step).toInt())
                qSeekPending = true
            } else if (up && qSeekPending) {
                recordView.flushEditSeek()
                qSeekPending = false
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** 当前键盘预选择的作用域（变了就重置下标，框不会串到别的窗口） */
    private fun navScope(): String = when {
        recordView.keyboardPanel == 1 -> "line"
        recordView.keyboardPanel == 2 -> "clip"
        menuPanel.visibility == View.VISIBLE -> "menu"
        playerWindow != null -> "player"
        ::aiDrawer.isInitialized && aiDrawer.isOpen -> "ai"
        else -> ""
    }

    /** 该作用域里可点的 View（手绘面板没有 View，走 navRects 的矩形） */
    private fun navViews(): List<View> = when (navScope()) {
        "menu" -> findViewById<android.widget.GridLayout>(R.id.menuGrid)
            ?.let { FocusNav.itemsOf(it) } ?: emptyList()
        "player" -> KbNavUtil.collect(playerWindow)
        "ai" -> if (::aiDrawer.isInitialized) KbNavUtil.collect(aiDrawer.navRoot) else emptyList()
        else -> emptyList()
    }

    private fun navRects(): List<android.graphics.RectF> {
        val sc = navScope()
        return if (sc == "line" || sc == "clip") recordView.keyboardPanelRects()
        else KbNavUtil.rects(navViews())
    }

    /** 方向键移动虚线框；面板贴边时把行滚进可视区 */
    private fun kbNavMove(dir: Int) {
        val rects = navRects()
        if (rects.isEmpty()) return
        val next = KbNavUtil.nearest(rects, kbNavIndex, dir)
        kbNavIndex = next
        val sc = navScope()
        if (sc == "line" || sc == "clip") {
            recordView.keyboardPanelEnsureVisible(next)
            focusBox().focusRect(recordView.keyboardPanelRects().getOrNull(next))
        } else {
            val v = navViews().getOrNull(next)
            focusBox().focus(v)
            if (v != null) FocusNav.ensureVisible(
                v, findViewById<android.widget.GridLayout>(R.id.menuGrid)?.parent as? ScrollView)
        }
    }

    /** Enter / "<" / ">" / "/" 落到当前框住的项：面板走模拟触摸，View 走 KbGesture */
    private fun kbNavAct(index: Int, act: Int) {
        if (index < 0) { kbNavMove(1); return }   // 还没选过：先框住第一项
        val sc = navScope()
        if (sc == "line" || sc == "clip") {
            recordView.keyboardPanelAct(index, act)      // 操作立即触发，不等动画
            if (act == KbAct.SWIPE_LEFT || act == KbAct.SWIPE_LEFT2 ||
                act == KbAct.SWIPE_RIGHT || act == KbAct.SWIPE_RIGHT2) {
                recordView.keyboardPanelSwipeAnim(index, act)   // 并行 0.2s 视觉动画
            }
        } else navViews().getOrNull(index)?.let { KbGesture.send(it, act) }
    }

    /** 判定线左滑选完圆圈：执行对应复制并立刻收掉动画状态 */
    private fun kbLineLeftAct(index: Int, act: Int) {
        if (index >= 0) recordView.keyboardPanelAct(index, act)
        recordView.keyboardPanelResetSwipe()
        kbLineLeftPending = false
        kbLineLeftDone = true
        kbLineLeftCircle = 1
    }

    /** 焦点框懒加载：盖在 content 最上层，不拦截触摸 */
    private fun focusBox(): KeyboardFocusOverlay {
        val old = focusOverlay
        if (old != null) return old
        val box = KeyboardFocusOverlay(this)
        findViewById<ViewGroup>(android.R.id.content)?.addView(
            box, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        focusOverlay = box
        return box
    }

    /** 快捷操作「开启」态：窗口键按一下开、再按一下关 */
    private fun toggleQuickWindow(kc: Int) {
        when (kc) {
            KeyEvent.KEYCODE_TAB -> togglePlayerWindow()
            KeyEvent.KEYCODE_SHIFT_LEFT ->
                recordView.toggleClipPanelFromKeyboard(!recordView.isClipPanelOpen)
            KeyEvent.KEYCODE_ALT_LEFT ->
                recordView.toggleLinePanelFromKeyboard(!recordView.isLinePanelOpen)
            KeyEvent.KEYCODE_ALT_RIGHT ->
                showOtherMenu(menuPanel.visibility != View.VISIBLE)
        }
    }

    /** 快捷操作「快速」态：按下打开（记下是自己开的，松手才关） */
    private fun openQuickWindow(kc: Int) {
        when (kc) {
            KeyEvent.KEYCODE_TAB -> {
                if (coopRole == 0) return          // 没开房间就没有用：静默，不弹提示
                if (playerWindow == null) { openPlayerList(); qHeldPlayer = true }
            }
            KeyEvent.KEYCODE_SHIFT_LEFT ->
                if (!recordView.isClipPanelOpen) {
                    recordView.toggleClipPanelFromKeyboard(true); qHeldClip = true
                }
            KeyEvent.KEYCODE_ALT_LEFT ->
                if (!recordView.isLinePanelOpen) {
                    recordView.toggleLinePanelFromKeyboard(true); qHeldLine = true
                }
            KeyEvent.KEYCODE_ALT_RIGHT ->
                if (menuPanel.visibility != View.VISIBLE) { showOtherMenu(true); qHeldOther = true }
        }
    }

    /** 快捷操作「快速」态：松开关掉本次按键自己打开的窗口 */
    private fun closeQuickWindow(kc: Int) {
        when (kc) {
            KeyEvent.KEYCODE_TAB -> if (qHeldPlayer) {
                closePlayerWindow(startIfReady = false); qHeldPlayer = false
            }
            KeyEvent.KEYCODE_SHIFT_LEFT -> if (qHeldClip) {
                recordView.toggleClipPanelFromKeyboard(false); qHeldClip = false
            }
            KeyEvent.KEYCODE_ALT_LEFT -> if (qHeldLine) {
                recordView.toggleLinePanelFromKeyboard(false); qHeldLine = false
            }
            KeyEvent.KEYCODE_ALT_RIGHT -> if (qHeldOther) {
                showOtherMenu(false); qHeldOther = false
            }
        }
    }

    /** 玩家列表：没开房间就没有用（静默）；已开则开/关切换 */
    private fun togglePlayerWindow() {
        if (playerWindow != null) { closePlayerWindow(startIfReady = false); return }
        if (coopRole == 0) return
        openPlayerList()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 文字编辑双层窗口的滑动开合：只监听、不消费，输入框/按钮照常拿真实触摸
        maybeTrackPanelSwipe(ev)
        // 联机聊天抽屉：顶部「退出 ~ 玩家列表」区域滑动拉出（同样不消费、不挡按钮）
        maybeTrackChatSwipe(ev)
        if (::recordView.isInitialized && recordView.isLinePanelOpen &&
            ev.actionMasked == MotionEvent.ACTION_DOWN
        ) {
            val y = ev.y
            val topVisible = ::topBar.isInitialized && topBar.visibility == View.VISIBLE
            val botVisible = ::bottomBar.isInitialized && bottomBar.visibility == View.VISIBLE
            val topH = if (topVisible) topBar.height.toFloat() else 0f
            val botTop = if (botVisible) bottomBar.top.toFloat() else Float.MAX_VALUE
            if ((topVisible && y <= topH) || (botVisible && y >= botTop)) {
                recordView.closeLinePanel()
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 返回键 / 顶部「退出」：问"是否保存这次改动"（导出走右上角「导出谱面」按钮） */
    override fun onBackPressed() {
        // 继续倒计时中按返回：只取消倒计时（回到"暂停"状态），不要顺手把这一局退掉
        if (resumeCountdown != null) {
            cancelResumeCountdown()
            btnPause.text = "继续"
            return
        }
        // 结算界面显示时，返回键 = 「继续」：选曲页进来的回主界面，其余回编辑界面
        if (endingOverlay != null) {
            // 选曲页进来的：覆盖层留着一起 finish（先摘会闪一下编辑界面）
            if (fromPlayPage) { endingOverlay = null; finish(); return }
            closeEnding()
            if (recordView.playMode) exitPlay()
            return
        }
        // ★ 打歌中按返回（三种入口同一套）：退出这一局 —— 选曲页进来的回主界面，其余回编辑界面。
        //   不能再往下走「恢复遮罩 / 问是否保存」，打歌时那是编辑态的逻辑。
        if (fromPlayPage) { backToMainPage(); return }
        if (recordView.playMode) { exitPlay(); return }
        // 抽屉开着：返回键先收起抽屉（玩家聊天和 AI 抽屉同等待遇）
        if (chatDrawer?.isOpen == true) { chatDrawer?.close(); return }
        // AI 抽屉开着：返回键先收起抽屉
        if (::aiDrawer.isInitialized && aiDrawer.isOpen) { aiDrawer.close(); return }
        // 遮罩被隐藏时，返回键先把它恢复出来（再按一次才问保存/退出）
        if (recordView.hideBars) {
            recordView.hideBars = false
            refreshBarsButton()
            return
        }
        if (exitAfterExport) { saveDraft(); super.onBackPressed(); return }
        // 不论之前是否按过保存，退出都要问"是否保存"（用户要求）
        askSaveAndExit()
    }

    private fun askSaveAndExit() {
        if (isFinishing || isDestroyed) return
        KbAlertBuilder(this)
            .setTitle("保存这次改动？")
            .setPositiveButton("保存") { _, _ ->
                saveDraft()
                toast(getString(R.string.msg_draft_saved))
                finish()
            }
            .setNegativeButton("不保存") { _, _ -> finish() }
            .setNeutralButton("取消", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LINE_IMAGE && resultCode == RESULT_OK && data?.data != null) {
            onLineImagePicked(data.data!!)
            return
        }
        if (requestCode == REQ_VIDEO && resultCode == RESULT_OK && data?.data != null) {
            onVideoPicked(data.data!!)
            return
        }
        if (requestCode == REQ_CLIP_EXPORT && resultCode == RESULT_OK && data?.data != null) {
            val src = clipTmp ?: return
            try {
                contentResolver.openOutputStream(data.data!!)?.use { out ->
                    src.inputStream().use { ins -> ins.copyTo(out, 65536) }
                }
            } catch (e: Exception) {
                toast("导出失败：" + e.message)
            }
            return
        }
        if (requestCode == REQ_CLIP_IMPORT && resultCode == RESULT_OK && data?.data != null) {
            try {
                val text = contentResolver.openInputStream(data.data!!)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                val n = recordView.importClipText(text)
                if (n > 0) toast("已导入 $n 个音符到剪贴板") else toast("文件里没有可识别的音符")
            } catch (e: Exception) {
                toast("导入失败：" + e.message)
            }
            return
        }
        if (requestCode == REQ_SAVE && resultCode == RESULT_OK && data?.data != null) {
            val src = exportTmp ?: return
            try {
                contentResolver.openOutputStream(data.data!!)?.use { out ->
                    src.inputStream().use { ins ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val r = ins.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r)
                        }
                    }
                }
                toast(getString(R.string.msg_exported))
                if (exitAfterExport) { saveDraft(); finish() }
            } catch (e: Exception) {
                toast(getString(R.string.msg_export_fail) + "：" + e.message)
                if (exitAfterExport) { saveDraft(); finish() }
            }
        }
    }

    override fun onPause() {
        cancelResumeCountdown()          // 倒计时期间切走：把遮罩和输入锁一起收掉
        saveRecorderSettings()                  // 「更多」里的开关落盘（下次进编辑页沿用）
        if (coopRole == 1) saveDraft()          // 服务端副本随时落盘（客户端不存任何东西）
        handler.removeCallbacksAndMessages(null)
        try { if (mediaPlayer?.isPlaying == true) { mediaPlayer?.pause(); btnPause.text = "继续" } } catch (_: Exception) {}
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(ticker, 120)
    }

    override fun onDestroy() {
        // 注意：这里**不能**把 Coop 回调置空 —— onDestroy 可能晚于 MainActivity.onResume，
        // 会把主界面刚绑好的回调清掉。改成回调里判断 isFinishing / isDestroyed。
        if (coopRole == 1) saveDraft()
        // AI 任务：页面被销毁就停掉请求（线程里会自己看 isFinishing/isDestroyed）
        aiCancel?.cancelled = true
        if (::aiDrawer.isInitialized) aiDrawer.close()
        try { aiDialog?.dismiss() } catch (_: Exception) {}
        aiDialog = null
        aiBar = null
        aiMsg = null
        exportTicker?.let { handler.removeCallbacks(it) }
        exportTicker = null
        try { exportDlg?.dismiss() } catch (_: Exception) {}
        exportDlg = null
        handler.removeCallbacksAndMessages(null)
        try { syncExec.shutdownNow() } catch (_: Exception) {}
        recordView.releaseSounds()
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }

    /** 用户要求：不要底部 toast 弹窗。提示写到顶部信息行 + 联机日志里 */
    // ---------------------------------------------------------------- 噪域断触效果
    /** 断触音效池（懒加载；缺失时不响，不报错） */
    private var jamSnd: android.media.SoundPool? = null
    private var jamSndId = 0
    private var jamStream = 0
    private var duckRunnable: Runnable? = null

    /** 断触瞬间的「嗡」：低沉的短促电流声（现场合成 WAV，不依赖资源包） */
    private fun playNoiseJamSfx() {
        try {
            if (jamSnd == null) {
                val sp = android.media.SoundPool.Builder().setMaxStreams(2).build()
                val f = File(cacheDir, "noise_jam.wav")
                if (!f.exists() || f.length() == 0L) {
                    java.io.FileOutputStream(f).use { it.write(buildNoiseJamWav()) }
                }
                jamSndId = sp.load(f.absolutePath, 1)
                jamSnd = sp
            }
            val sp = jamSnd ?: return
            if (jamSndId == 0) return
            // 上一声还没停就先掐掉，避免叠成噪音
            if (jamStream != 0) { try { sp.stop(jamStream) } catch (_: Exception) {} }
            jamStream = sp.play(jamSndId, 0.9f, 0.9f, 2, 0, 1f)
        } catch (_: Exception) {}
    }

    /**
     * 合成断触音：0.16 秒的带通噪声（模拟电流/干扰），
     * 加一点 70Hz 的低频体感，末尾做指数衰减，听感是「嗡—噗」。
     */
    private fun buildNoiseJamWav(): ByteArray {
        val rate = 22050
        val n = (rate * 0.16).toInt()
        val pcm = ByteArrayOutputStream(n * 2)
        var low = 0f
        var prev = 0f
        val rnd = java.util.Random(0x6E6F6973L)
        for (i in 0 until n) {
            val t = i.toFloat() / rate
            // 一阶低通 + 一阶高通 = 带通，做出「沙沙的电流」而不是白噪声
            val white = rnd.nextFloat() * 2f - 1f
            low += 0.25f * (white - low)
            val hp = low - prev
            prev = low
            val band = hp * 3.2f
            // 低频体感
            val body = Math.sin(2.0 * Math.PI * 70.0 * t).toFloat() * 0.35f
            val env = Math.pow(1.0 - i.toDouble() / n, 2.4).toFloat()      // 指数衰减
            val attack = Math.min(1f, i / (rate * 0.006f))                  // 6ms 起音，避免爆音
            val v = ((band * 0.8f + body) * env * attack * 0.85f).coerceIn(-1f, 1f)
            val s = (v * 32767f).toInt()
            pcm.write(s and 0xFF)
            pcm.write((s shr 8) and 0xFF)
        }
        val data = pcm.toByteArray()
        val hdr = ByteArrayOutputStream()
        fun le32(v: Int) { hdr.write(v and 0xFF); hdr.write((v shr 8) and 0xFF); hdr.write((v shr 16) and 0xFF); hdr.write((v shr 24) and 0xFF) }
        fun le16(v: Int) { hdr.write(v and 0xFF); hdr.write((v shr 8) and 0xFF) }
        hdr.write("RIFF".toByteArray()); le32(36 + data.size); hdr.write("WAVE".toByteArray())
        hdr.write("fmt ".toByteArray()); le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
        hdr.write("data".toByteArray()); le32(data.size)
        hdr.write(data)
        return hdr.toByteArray()
    }

    /**
     * 断触时把音乐压下去（0.35 倍约 0.42 秒，然后平滑回到 1）。
     * 用 setVolume 而不是暂停：音乐不断档，才有 Phigros 那种「声音被干扰」的感觉。
     */
    private fun duckMusic() {
        val mp = mediaPlayer ?: return
        val dur = 420L
        duckRunnable?.let { handler.removeCallbacks(it) }
        try { mp.setVolume(0.35f, 0.35f) } catch (_: Exception) { return }
        val r = object : Runnable {
            private var t0 = 0L
            override fun run() {
                if (t0 == 0L) t0 = SystemClock.elapsedRealtime()
                val p = ((SystemClock.elapsedRealtime() - t0).toFloat() / dur).coerceIn(0f, 1f)
                try { mp.setVolume(0.35f + 0.65f * p, 0.35f + 0.65f * p) } catch (_: Exception) {}
                if (p < 1f) handler.postDelayed(this, 30) else { duckRunnable = null }
            }
        }
        duckRunnable = r
        handler.postDelayed(r, 30)
    }

    private fun toast(msg: String) {
        Coop.log("提示：" + msg)
        // 打歌时这行是隐藏的：真出错（导出失败之类）就把它顶出来，不然用户什么都看不到
        try {
            txtInfo.visibility = android.view.View.VISIBLE
            txtInfo.text = msg
        } catch (_: Exception) {}
    }
}