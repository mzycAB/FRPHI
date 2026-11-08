package com.piezmaker

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
    private lateinit var btnSound: TextView
    private lateinit var btnSmooth: TextView
    private lateinit var btnCompat: TextView
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
    /** 进 Autoplay 之前的音效开关（Autoplay 强制开，退出时还原成编辑时的状态） */
    private var soundBeforePlay: Boolean? = null
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
    /** 「你写我打」这一局是否正在跑（蓝方按了继续 / 绿方收到开始）。退出按钮看它，不看 playMode：
     *  这个模式其实不点亮 playMode（两边都只是跟着音乐放），所以以前用 playMode 判断永远进不去列表。 */
    private var coopGameOn = false
    private var suppressSync = false      // 正在应用远端草稿 → 不要再推回去
    private var lastSynced = ""           // 最近一次收发过的草稿文本（去重用）
    private var syncPending = false
    private val syncRunnable = Runnable { pushChart() }
    private var coopDelayEdit: EditText? = null
    private var txtCoopRoom: TextView? = null
    private var playerListBox: LinearLayout? = null
    private var mediaPlayer: MediaPlayer? = null
    private var exportTmp: File? = null
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
    /** 拖进度条松手后要等播放器 seek 到的目标位置（到位后撤掉预览偏移） */
    private var cursorSeekTarget: Int? = null

    private fun resetPlayheadAnchor() {
        phLastSync = 0L
    }

    private fun currentPlayhead(): Long {
        val mp = mediaPlayer ?: return 0L
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

        meta = intent.getParcelableExtra(KEY_META) ?: run {
            toast("参数缺失"); finish(); return
        }

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
        btnSound = findViewById(R.id.btnSound)
        btnSmooth = findViewById(R.id.btnSmooth)
        btnCompat = findViewById(R.id.btnCompat)
        btnDoubleHint = findViewById(R.id.btnDoubleHint)
        btnPlaceLines = findViewById(R.id.btnPlaceLines)
        txtSpeedLabel = findViewById(R.id.txtSpeedLabel)
        applyRecorderSettings()          // 「更多」里的开关：沿用上次的设置，不要每次进编辑页重置
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
        lineEditPanel = findViewById(R.id.lineEditPanel)
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

        recordView.onLineEditChanged = { show ->
            lineEditPanel.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                noiseEditPanel.visibility = View.GONE
                refreshLineInputs()
            }
        }
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
        recordView.onPauseTap = { showPlayMenu() }
        recordView.onLinePoseChanged = { refreshLineInputs() }
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
        recordView.hLines = meta.hLines
        recordView.vLines = meta.vLines
        recordView.linePosPct = meta.linePosPct
        recordView.pass = 1
        recordView.playheadProvider = { currentPlayhead() }
        recordView.playingProvider = { mediaPlayer?.isPlaying == true }
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
            } else {
                // 框蓝/绿只是"你写我打"游戏才需要；普通联机编辑时直接播
                if (coopRole != 0 && Coop.bothFramed()) {
                    val me = Coop.me()
                    when {
                        !Coop.bothConfirmed() -> {
                            toast("对方还没确认"); return@setOnClickListener
                        }
                        me?.color == Coop.COLOR_GREEN -> {
                            toast("绿方等蓝方按「继续」开始"); return@setOnClickListener
                        }
                        else -> {
                            coopGameOn = true                        // 这一局开始了
                            Coop.startGame(Coop.gameDelaySec)         // 蓝方：广播开始（带延迟）
                        }
                    }
                }
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
            mediaPlayer?.let { mp ->
                // 同进度条：别 seek 到精确末尾，否则播放完成会让位置回绕成 0
                val c = t.coerceIn(0, (durationMs - 1).coerceAtLeast(0))
                try {
                    mp.seekTo(c)
                    seekBar.progress = c
                    resetPlayheadAnchor()
                    recordView.invalidate()
                    updateTimeDisplay(c)
                } catch (_: Exception) {}
            }
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
                try { mediaPlayer?.seekTo(target) } catch (_: Exception) {}
                // 播放器 seek 是异步的：等它真的到位（见 currentPlayhead）再撤掉预览偏移，画面不会跳回去
                cursorSeekTarget = target
                resetPlayheadAnchor()
                recordView.invalidate()
                updateTimeDisplay(target)
            }
        })

        findViewById<View>(R.id.btnBack).setOnClickListener {
            if (recordView.hideBars) { recordView.hideBars = false; refreshBarsButton() }
            askSaveAndExit()
        }
        // 「播放」：从当前位置进入 Phira 打歌模式（再点一次退出）
        btnPlay.setOnClickListener {
            if (recordView.playMode) exitPlay() else enterPlay()
        }
        // 保存：把当前改动写进草稿（没有改动就不重复写盘）
        findViewById<View>(R.id.btnSave).setOnClickListener {
            if (chartDirty) saveDraft()
        }
        // 撤销：一次回退一步（放置 / 拖动 / 删除 / 判定线 / 关键帧），可连续撤销
        findViewById<View>(R.id.btnUndo).setOnClickListener {
            if (recordView.playMode) { toast("打歌中不能撤销，先按 STOP"); return@setOnClickListener }
            if (coopRole != 0) { toast("联机中不能撤销（改动实时同步给房间里的人）"); return@setOnClickListener }
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
            if (coopRole != 0) { toast("联机中不能重做"); return@setOnLongClickListener true }
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
            if (coopRole != 0) { toast("联机中不能重做"); return@setOnClickListener }
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
        startPlayback()
        switchPass(1)
        // 默认暂停并进入编辑模式（可立即放置/搓时间/编辑判定线）
        recordView.editMode = true
        btnPause.text = "继续"
        refreshInfo()
        // 噪域提示：让谱师知道这谱的噪域已经读到了（但没有噪域就一个字都不出）
        val noiseN = recordView.noiseAreas.size
        if (noiseN > 0) toast("已读取 $noiseN 块噪域（右侧「噪域」页签可编辑）")
        handler.postDelayed(ticker, 200)
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

    /** 自动校准：先算会影响多少个音符，确认后再改（整批一个撤销步） */
    private fun askAutoCalibrate() {
        if (recordView.notes.isEmpty()) { toast("还没有放任何音符"); return }
        val n = recordView.countCalibratable()
        if (n == 0) { toast("没有需要校准的音符"); return }
        android.app.AlertDialog.Builder(this)
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
        val hidden = recordView.hideBars
        findViewById<View>(R.id.btnShowBars).visibility = View.GONE
        topBar.visibility = if (hidden) View.GONE else View.VISIBLE
        bottomBar.visibility = if (hidden) View.GONE else View.VISIBLE
        // 顶部三颗按钮的触摸层跟着遮罩一起显隐，否则遮罩藏了还会误触
        aiHotZone?.visibility = if (hidden) View.GONE else View.VISIBLE
        btnHideBars.text = if (hidden) "开" else "关"
        btnHideBars.setTextColor(if (hidden) MENU_ON else MENU_OFF)
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
        menuScrim.visibility = if (show) View.VISIBLE else View.GONE
        menuPanel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            menuPanel.post {
                val maxH = (resources.displayMetrics.heightPixels * 0.72f).toInt()
                if (menuPanel.height > maxH) {
                    menuPanel.layoutParams = menuPanel.layoutParams.also { it.height = maxH }
                }
            }
        } else {
            scaleEdit.clearFocus()
            speedEdit.clearFocus()
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
        recordView.editMode = false          // 退出编辑模式
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

        soundBeforePlay = recordView.soundEnabled
        recordView.soundEnabled = true          // Autoplay 默认开音效
        refreshSoundButton()
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
        btnPlay.text = "STOP"
        btnPause.text = "暂停"
        refreshInfo()
        Log.i(TAG, "enterPlay at ${startAt}ms playing=${mediaPlayer?.isPlaying} speed=$curSpeed")
    }

    /** 停止 Autoplay：按要求暂停音乐，而不是继续播放 */
    private fun exitPlay() {
        recordView.stopPlaySession()
        try {
            mediaPlayer?.let { if (it.isPlaying) it.pause() }
        } catch (e: Exception) {
            Log.e(TAG, "autoplay pause failed", e)
        }
        // 暂停后直接进入编辑态：这样 STOP 之后马上就能上下拖动屏幕搓时间轴，
        // 不需要先点「继续」再点「暂停」。
        recordView.editMode = true
        coopGameOn = false            // 这一局结束（AUTOPLAY 停 / 双方退出都走这里）
        soundBeforePlay?.let { recordView.soundEnabled = it }   // 退出 Autoplay：音效还原成编辑时的状态
        soundBeforePlay = null
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
        refreshInfo()
        Log.i(TAG, "exitPlay -> paused at ${mediaPlayer?.currentPosition}ms, editMode=true")
    }

    // ---------------------------------------------------------------- 打歌暂停菜单（Phira 风格）

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
            addView(iconBtn(0, "退出 Autoplay") { exitPlay() })
            addView(iconBtn(1, "重新播放") { restartPlay() })
            addView(iconBtn(2, "继续播放") { resumePlay() })
        }
        val scrim = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x99000000.toInt())
            isClickable = true        // 吃掉底层画布的触摸
            isFocusable = true
            addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
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

    /** 暂停菜单：继续（原地接着打） */
    private fun resumePlay() {
        try {
            mediaPlayer?.let { mp -> if (!mp.isPlaying) { mp.start(); applySpeed(curSpeed) } }
        } catch (e: Exception) {
            Log.e(TAG, "autoplay resume failed", e)
        }
        recordView.invalidate()
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
        txtCoopRoom = findViewById(R.id.txtCoopRoom)
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
        Coop.onStart = { d -> if (alive()) startPlaybackFromRoom(d) }
        // 对面点了「退出」→ 本机也一起结束这一局
        Coop.onStop = {
            if (alive() && (coopGameOn || recordView.playMode)) {
                coopGameOn = false
                exitPlay()                       // 停音乐 + 回编辑态（双方都停）
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

    private fun alive(): Boolean = !isFinishing && !isDestroyed

    private fun pushChart() {
        if (coopRole == 0 || !syncPending) return
        syncPending = false
        val text = recordView.draftWithClip()      // 谱面 + 剪贴板一起同步
        if (text == lastSynced) return
        lastSynced = text
        Coop.sendChart(text)
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
        try { recordView.loadDraft(text) } catch (_: Exception) {}
        suppressSync = false
        refreshInfo()
    }

    /** 绿方：收到"开始"后按设定延迟起播（蓝方按下继续的那一刻算起点） */
    private fun startPlaybackFromRoom(delaySec: Double) {
        if (coopRole == 0) return
        if (Coop.me()?.color == Coop.COLOR_BLUE) return     // 蓝方自己已经按继续开始了
        coopGameOn = true
        handler.postDelayed({
            val mp = mediaPlayer ?: return@postDelayed
            if (mp.isPlaying) return@postDelayed
            mp.start()
            btnPause.text = "暂停"
            recordView.editMode = false
            applySpeed(curSpeed)
            recordView.invalidate()
            refreshInfo()
        }, (delaySec.coerceAtLeast(0.0) * 1000).toLong())
    }

    private fun refreshCoopBar() {
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
            }
        } else {
            btnPlayer.setTextColor(frameTint(frame, 0.45f))
            btnPlayer.background = frameBg(frame, dp2(12).toFloat())
        }
        txtCoopRoom?.text = when {
            coopRole == 0 -> "未联机"
            coopRole == 1 -> "服务端 · ${Coop.players.size} 人"
            else -> "客户端 · ${Coop.players.size} 人"
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
        } catch (_: Exception) {}
    }

    private fun saveRecorderSettings() {
        try {
            getSharedPreferences("frphi_rec", 0).edit()
                .putInt("compatMode", recordView.compatMode)
                .putBoolean("smoothMove", recordView.smoothMove)
                .putBoolean("autoEdgeScroll", recordView.autoEdgeScroll)
                .putBoolean("doubleHint", recordView.editDoubleHint)
                .putBoolean("sound", soundBeforePlay ?: recordView.soundEnabled)
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

    /** 点"玩家列表"：这一局在跑就直接开窗（最左边有「退出」）；还没开始、两人又都框好了就先弹确认 */
    private fun onPlayerButton() {
        if (coopRole == 0) { toast("未联机（回主界面右侧抽屉开房/加入）"); return }
        if (!coopGameOn && Coop.bothFramed() && Coop.me()?.color != Coop.COLOR_NONE) {
            askStartCoop(); return
        }
        openPlayerList()
    }

    /** 玩家列表窗口（和判定线抽屉/剪贴板一样是浮在页面上的面板，不是系统弹窗） */
    private var playerWindow: android.view.View? = null

    private fun openPlayerList() {
        if (coopRole == 0) { toast("未联机"); return }
        closePlayerWindow()
        val root = findViewById<android.view.ViewGroup>(android.R.id.content) ?: return

        // 不加遮罩：弹窗出来时其它地方亮度不变（点空白处仍然能关掉）
        val overlay = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { closePlayerWindow() }
        }
        // 顶边贴到左上角「玩家列表」按钮下面，底边与顶边 y 轴对称（高度 = 屏高 - 2×顶距）
        val btnLoc = IntArray(2)
        val rootLoc = IntArray(2)
        btnPlayer.getLocationOnScreen(btnLoc)
        root.getLocationOnScreen(rootLoc)
        val topPx = (btnLoc[1] - rootLoc[1]) + btnPlayer.height + dp2(6)
        val panelH = (root.height - topPx * 2).coerceAtLeast(dp2(180))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2(12), dp2(10), dp2(12), dp2(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xF014161E.toInt())
                setStroke(dp2(1), 0x66FFFFFF)
                cornerRadius = dp2(10).toFloat()
            }
            setOnClickListener { }                            // 点面板本身不关
            layoutParams = android.widget.FrameLayout.LayoutParams(
                dp2(250), panelH,
                android.view.Gravity.START or android.view.Gravity.TOP
            ).also { it.setMargins(dp2(14), topPx, 0, 0) }
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        // 最左侧：退出（双方一起结束这一局"你写我打"）。只在真的在打的时候出现
        if (coopGameOn) {
            head.addView(TextView(this).apply {
                text = "退出"
                setTextColor(0xFFFF8A80.toInt()); textSize = 12f
                includeFontPadding = false
                gravity = android.view.Gravity.CENTER
                background = resources.getDrawable(R.drawable.bg_btn_dark, null)
                setPadding(dp2(10), 0, dp2(10), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp2(26)
                ).also { it.marginEnd = dp2(8) }
                setOnClickListener {
                    closePlayerWindow()
                    Coop.stopGame()                  // 对面收到 stop 也一起退出
                }
            })
        }
        head.addView(TextView(this).apply {
            text = "玩家列表"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 14f
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        panel.addView(head)


        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        playerListBox = box
        panel.addView(box)
        renderPlayerList()

        overlay.addView(panel)
        root.addView(overlay)
        playerWindow = overlay
    }

    private fun closePlayerWindow() {
        (playerWindow?.parent as? android.view.ViewGroup)?.removeView(playerWindow)
        playerWindow = null
        playerListBox = null
    }

    private fun renderPlayerList() {
        val box = playerListBox ?: return
        box.removeAllViews()
        if (Coop.players.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "还没有玩家"; setTextColor(0x99FFFFFF.toInt()); textSize = 12f
                setPadding(dp2(8), dp2(8), dp2(8), dp2(8))
            })
            return
        }
        val ordered = Coop.orderedPlayers()
        for ((idx, p) in ordered.withIndex()) {
            val tv = TextView(this).apply {
                text = buildString {
                    append(idx + 1).append(". ").append(p.name).append("  ").append(p.ip)
                    if (p.id == 0) append("  [房主]")
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
                setPadding(dp2(10), dp2(12), dp2(10), dp2(12))
                setBackgroundColor(0x1AFFFFFF)
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
            Coop.blue() == null -> Coop.COLOR_BLUE               // 第一个 = 蓝（写谱）
            Coop.green() == null -> Coop.COLOR_GREEN             // 第二个 = 绿（打谱）
            else -> Coop.COLOR_NONE
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
        val title = when {
            isHost -> "关闭房间"
            isMe -> "退出房间"
            else -> "踢出房间"
        }
        val msg = when {
            isHost && isMe -> "你是房主，关掉后所有人都会退出房间。"
            isHost -> "把房主「" + p.name + "」踢出房间？房间会直接关闭。"
            isMe -> "把自己踢出房间？"
            else -> "把「" + p.name + "」踢出房间？"
        }
        val ok = when {
            isHost -> "关闭"
            isMe -> "退出"
            else -> "踢出"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(ok) { _, _ ->
                if (playerWindow != null) closePlayerWindow()
                Coop.kick(p.id)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 双方各自确认，蓝方再按「继续」开始 */
    private fun askStartCoop() {
        val blue = Coop.blue()
        val green = Coop.green()
        val me = Coop.me()
        val other = if (me?.color == Coop.COLOR_BLUE) green else blue
        val msg = "蓝（写谱）：" + (blue?.name ?: "-") + "\n" +
            "绿（打谱）：" + (green?.name ?: "-") + "\n\n" +
            "对方确认：" + (if (other?.confirmed == true) "已确认" else "未确认") + "\n" +
            "游戏延迟：${Coop.gameDelaySec} 秒"
        android.app.AlertDialog.Builder(this)
            .setTitle("开始联机打谱")
            .setMessage(msg)
            .setPositiveButton("确认") { _, _ ->
                me?.let { Coop.confirmPlayer(it.id) }
                refreshCoopBar()
                toast(if (Coop.bothConfirmed()) "双方都确认了，蓝方按「继续」开始" else "已确认，等对方确认")
            }
            .setNegativeButton("取消", null)
            // 最左边：退出这一局（双方一起停）
            .setNeutralButton("退出") { _, _ -> Coop.stopGame() }
            .show()
    }

    // ---------------------------------------------------------------- AI 抽屉（和主界面同一个 AI）
    /** 编辑页的 AI 抽屉：从屏幕上方往下拉出，宽度 = 主界面左抽屉 + 右抽屉 */
    private lateinit var aiDrawer: AiDrawer

    private fun setupAiDrawer() {
        val aiBtn = findViewById<TextView>(R.id.btnAi)
        // 触摸层先加：AI 抽屉（scrim + 面板）后加，才会盖在触摸层上面
        setupAiHotZone(aiBtn, btnOther, findViewById(R.id.btnExport))
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
                meta = if (offsetMs != null)
                    meta.copy(bpm = bpm, bpmOffsetMs = round3(offsetMs), bpmSegments = emptyList())
                else meta.copy(bpm = bpm, bpmSegments = emptyList())
                recordView.bpm = bpm
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
                bpm?.let { v ->
                    if (v > 20.0 && v < 1000.0) {
                        // 手动/AI 指定单一 BPM：多 BPM 分段作废
                        m = m.copy(bpm = v, bpmSegments = emptyList())
                        recordView.bpm = v
                    }
                }
                bpmOffset?.let { m = m.copy(bpmOffsetMs = round3(it.coerceIn(-60000.0, 60000.0))) }
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
                hLines?.let { m = m.copy(hLines = it.coerceIn(1, 16)) }
                vLines?.let { m = m.copy(vLines = it.coerceIn(1, 32)) }
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
            val raw = p.getString("tabs", null) ?: return
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
    private fun addAiMenuButtons() {
        val panel = menuPanel as? LinearLayout ?: return
        val scroll = panel.getChildAt(0) as? ScrollView ?: return
        val col = scroll.getChildAt(0) as? LinearLayout ?: return
        col.addView(TextView(this).apply {
            text = "AI"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp2(2), dp2(9), dp2(2), dp2(1))
        })
        for ((label, mode) in listOf("自动写谱" to 0, "自动修谱" to 1, "自动缤纷" to 2)) {
            col.addView(TextView(this).apply {
                text = label
                gravity = android.view.Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 12f
                includeFontPadding = false
                background = getDrawable(R.drawable.bg_btn_dark)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp2(30)
                ).also { it.topMargin = dp2(5) }
                setOnClickListener { runAi(mode) }
            })
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
        val dlg = android.app.AlertDialog.Builder(this)
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
            android.app.AlertDialog.Builder(this)
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
        val d = android.app.AlertDialog.Builder(this)
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
                declaredNoiseLayers = h.declaredNoiseLayers
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
            }
            mp.seekTo(0)
            try { seekBar.progress = 0 } catch (_: Exception) {}
            updateTimeDisplay(0)
            // 进入录制默认暂停：点右下角 AUTOPLAY 才播放
            btnPause.text = "继续"
            applySpeed(curSpeed)
            Log.i(TAG, "playback ready(not started) dur=${durationMs}ms speed=$curSpeed")
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "playback failed", e)
            toast("音频播放失败：${e.message}")
        }
    }

    private fun exportPez() {
        val list = recordView.notes.toList()
        if (list.isEmpty()) { toast("还没有放任何音符"); return }
        try {
            val tmp = packPezForExport(list) ?: run {
                toast(getString(R.string.msg_export_fail)); return
            }
            exportTmp = tmp

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
    private fun packPezForExport(list: List<RecordedNote>): File? {
        val dir = File(cacheDir, "pez")
        if (!dir.exists()) dir.mkdirs()
        val tmp = File(dir, "export.zip")
        // 判定线姿态全在 recordView.lines 里，meta 只提供曲目信息
        val ok = PezExporter.export(meta, recordView.lines, list, contentResolver, tmp, recordView.noiseAreas)
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
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::recordView.isInitialized && recordView.isLinePanelOpen &&
            ev.actionMasked == MotionEvent.ACTION_DOWN
        ) {
            val y = ev.y
            val topH = if (::topBar.isInitialized) topBar.height.toFloat() else 0f
            val botTop = if (::bottomBar.isInitialized) bottomBar.top.toFloat() else Float.MAX_VALUE
            if (y <= topH || y >= botTop) {
                recordView.closeLinePanel()
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 返回键 / 顶部「退出」：问"是否保存这次改动"（导出走右上角「导出谱面」按钮） */
    override fun onBackPressed() {
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
        android.app.AlertDialog.Builder(this)
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
        handler.removeCallbacksAndMessages(null)
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