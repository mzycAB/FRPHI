package com.piezmaker

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.util.Log
import java.io.File

/** 录制页：三阶段制谱（drag/hold → click → flick）+ 播放控制 + 导出 PEZ */
class RecordActivity : Activity() {

    private companion object {
        const val TAG = "FRPHI"
        const val REQ_SAVE = 2001
        const val REQ_CLIP_IMPORT = 2004
        const val REQ_CLIP_EXPORT = 2003
        const val KEY_META = "meta"
        /** 「其他」窗口（顶部遮罩同色）里的文字色：关=白，开=黄 */
        const val MENU_OFF = 0xFFFFFFFF.toInt()
        const val MENU_ON = 0xFFFFEB3B.toInt()
    }

    private lateinit var recordView: RecordView
    private lateinit var txtTitle: TextView
    private lateinit var txtInfo: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPause: TextView
    private lateinit var btnPass1: TextView
    private lateinit var btnPass2: TextView
    private lateinit var btnPass3: TextView
    private lateinit var btnPass4: TextView
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
    private lateinit var txtLineInfo: TextView
    private lateinit var txtSpeedLabel: TextView
    private lateinit var speedEdit: EditText
    private lateinit var lineEditPanel: View
    private lateinit var editAngle: EditText
    private lateinit var editAlpha: EditText
    private lateinit var editLX: EditText
    private lateinit var editLY: EditText
    private lateinit var editLineSpeed: EditText
    private lateinit var scaleEdit: EditText
    private var curSpeed = 1.0f
    /** 进 Autoplay 之前的音效开关（Autoplay 强制开，退出时还原成编辑时的状态） */
    private var soundBeforePlay: Boolean? = null
    /** 进 Autoplay 之前的「制作线显示」模式（Autoplay 强制隐藏，退出时还原） */
    private var gridBeforePlay: Int? = null
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

    private val handler = Handler(Looper.getMainLooper())
    // 播放头：不再每帧调 MediaPlayer.currentPosition（Binder 调用 + 抖动），
    // 改成"锚点 + 墙钟"推算，每 0.5 秒校正一次；seek/变速时立刻重置锚点。
    private var phBaseMs = 0L
    private var phBaseAt = 0L
    private var phLastSync = 0L

    private fun resetPlayheadAnchor() {
        phLastSync = 0L
    }

    private fun currentPlayhead(): Long {
        val mp = mediaPlayer ?: return 0L
        val now = android.os.SystemClock.elapsedRealtime()
        val playing = try { mp.isPlaying } catch (_: Exception) { false }
        if (!playing || now - phLastSync > 500L) {
            phBaseMs = try { mp.currentPosition.toLong() } catch (_: Exception) { 0L }
            phBaseAt = now
            phLastSync = now
            return phBaseMs
        }
        return phBaseMs + Math.round((now - phBaseAt) * curSpeed.toDouble())
    }

    private val ticker = object : Runnable {
        override fun run() {
            val mp = mediaPlayer
            if (mp != null && !seeking) {
                try {
                    if (durationMs > 0) seekBar.progress = mp.currentPosition
                } catch (_: Exception) {}
            }
            handler.postDelayed(this, 120)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        txtLineInfo = findViewById(R.id.txtLineInfo)
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
        menuScrim.setOnClickListener { showOtherMenu(false) }
        addAiMenuButtons()
        addFullModeButton()
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
            if (show) refreshLineInputs()
        }
        // 长按判定线进编辑（画布上的判定线 / 抽屉里的判定线框）：正在播就先暂停，按钮文字同步
        recordView.onRequestPauseForEdit = {
            try { if (mediaPlayer?.isPlaying == true) mediaPlayer?.pause() } catch (_: Exception) {}
            btnPause.text = "继续"
        }
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

        btnPass1.setOnClickListener { switchPass(1) }
        btnPass2.setOnClickListener { switchPass(2) }
        btnPass3.setOnClickListener { switchPass(3) }
        btnPass4.setOnClickListener { switchPass(4) }

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
                val c = t.coerceIn(0, durationMs)
                try {
                    mp.seekTo(c)
                    seekBar.progress = c
                    resetPlayheadAnchor()
                    recordView.invalidate()
                } catch (_: Exception) {}
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) recordView.invalidate()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                seeking = true
                mediaPlayer?.let { if (it.isPlaying) { it.pause(); btnPause.text = "继续" } }
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                sb?.let { mediaPlayer?.seekTo(it.progress) }
                seeking = false
                resetPlayheadAnchor()
                recordView.invalidate()      // 暂停时拖进度条：画面静止，换位置要重画
            }
        })

        findViewById<View>(R.id.btnBack).setOnClickListener { askSaveAndExit() }
        // 「播放」：从当前位置进入 Phira 打歌模式（再点一次退出）
        btnPlay.setOnClickListener {
            if (recordView.playMode) exitPlay() else enterPlay()
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
        findViewById<View>(R.id.btnExport).setOnClickListener { exportPez() }

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
            draftFile = File(filesDir, "drafts/${meta.tabId}.json")
            try { recordView.loadDraft(Coop.currentChart) } catch (_: Exception) {}
        } else if (meta.tabId.isNotEmpty()) {
            draftFile = File(filesDir, "drafts/${meta.tabId}.json")
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
        val head = "线${rv.activeLineIndex + 1}/${rv.lineCount}"
        txtLineInfo.text = if (kf >= 0) "$head\n帧" + String.format("%.2f", kf / 1000f) + "s" else head
        txtSpeedLabel.text = if (kf >= 0) "流速·帧" else "流速"
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
        btnHideBars.text = if (hidden) "开" else "关"
        btnHideBars.setTextColor(if (hidden) MENU_ON else MENU_OFF)
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
        val active = resources.getDrawable(R.drawable.bg_btn_blue, null)
        val normal = resources.getDrawable(R.drawable.bg_btn_dark, null)
        btnPass1.background = if (p == 1) active else normal
        btnPass2.background = if (p == 2) active else normal
        btnPass3.background = if (p == 3) active else normal
        btnPass4.background = if (p == 4) active else normal
        refreshInfo()
        recordView.invalidate()
    }

    /** 从当前位置开始 Autoplay（自动打歌） */
    private fun enterPlay() {
        val mp = mediaPlayer
        if (mp == null) { toast("音频未就绪"); return }
        recordView.editMode = false          // 退出编辑模式

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
        // Autoplay 自动收起上下遮罩（打歌时不需要编辑工具条）
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
        btnPause.text = "继续"
        btnPlay.text = "AUTOPLAY"
        refreshInfo()
        Log.i(TAG, "exitPlay -> paused at ${mediaPlayer?.currentPosition}ms, editMode=true")
    }

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

    // ---------------------------------------------------------------- AI（写谱 / 修谱 / 缤纷）
    /** 正在跑的 AI 任务（null = 空闲） */
    private var aiCancel: AiClient.Cancel? = null
    private var aiDialog: android.app.AlertDialog? = null
    private var aiBar: ProgressBar? = null
    private var aiMsg: TextView? = null
    /** AI 开始前的谱面快照：结束后可以点「撤回」还原 */
    private var aiBeforeDraft: String? = null

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
            toast(
                if (recordView.fullMode)
                    "全量模式：单击蓝键 / 长按长条 / 长按左右拖黄键 / 短滑红键"
                else "已关闭全量模式"
            )
        }
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
                    val r = AudioBeats.analyze(this, meta.musicUri)
                    if (r != null) beats = r.beats
                }
                if (cancel.cancelled) throw InterruptedException("cancelled")

                // 2) 准备提示词和初始谱面
                val baseLines = AiChart.copyLines(recordView.lines)
                val baseNotes = if (mode == 0) ArrayList<RecordedNote>() else ArrayList(recordView.notes)
                holder = AiChart.Holder(
                    baseLines, baseNotes,
                    (baseLines.maxOfOrNull { it.id } ?: 0) + 1,
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
                val full = AiClient.chat(
                    ep,
                    listOf("system" to system, "user" to user),
                    cancel,
                    maxTokens = if (mode == 0) 8192 else 4096,
                    temperature = if (mode == 0) 0.8 else 0.5,
                    onDelta = { text -> consume(text) }
                )
                // 收尾：把最后一段也扫一遍
                consume(full)
                // 写谱时如果 AI 没给够（常见于输出被截断），自动续写一轮
                if (mode == 0 && !cancel.cancelled && parsed < expected / 2) {
                    scanFrom = 0
                    val cont = AiClient.chat(
                        ep,
                        listOf(
                            "system" to system,
                            "user" to user,
                            "assistant" to full,
                            "user" to "继续。你刚才只输出了一部分，请从最后一个音符之后接着输出剩下的音符，" +
                                "仍然一行一个 JSON 对象，不要重复已经输出过的音符，不要解释。"
                        ),
                        cancel,
                        maxTokens = 8192,
                        temperature = 0.8,
                        onDelta = { text -> consume(text) }
                    )
                    consume(cont)
                }
                // AI 完全没给出可识别音符时，用本地节拍引擎兜底（时间仍然只取重音点）
                if (mode == 0 && !cancel.cancelled && parsed == 0) {
                    val h = holder
                    if (h != null) {
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
                    aiBeforeDraft?.let { recordView.seedUndoForReplace(it) }
                    finishAiUi(if (cancel.cancelled) "已停止，保留已生成的内容" else msg, false)
                }
                aiCancel = null
                return@Thread
            }

            ui {
                holder?.let { applyAiHolder(it, true) }
                aiBeforeDraft?.let { recordView.seedUndoForReplace(it) }
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
                    try { recordView.loadDraft(it) } catch (_: Exception) {}
                }
                aiBeforeDraft = null
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
            val text = DraftCodec.encode(h.lines, finalNotes)
            recordView.loadDraft(text)
            recordView.invalidate()
        } catch (_: Exception) {}
    }

    private fun saveDraft() {
        val f = draftFile ?: return
        try {
            if (!f.parentFile.exists()) f.parentFile.mkdirs()
            f.writeText(recordView.saveDraft())
        } catch (_: Exception) {}
    }

    private fun refreshInfo() {
        // 打歌（Autoplay）时左上角不留任何小字
        if (recordView.playMode) {
            txtInfo.visibility = android.view.View.GONE
            return
        }
        txtInfo.visibility = android.view.View.VISIBLE
        if (recordView.editMode) {
            val ln = if (recordView.lineCount > 1) " · 线${recordView.activeLineIndex + 1}/${recordView.lineCount}" else ""
            txtInfo.text = "编辑模式$ln"
            return
        }
        val st = recordView.noteStats()
        txtInfo.text = "BPM ${trimNum(meta.bpm)} · 横线 ${meta.hLines} · 竖线 ${meta.vLines} · " +
            "总 ${st[0]}（1:${st[1]} 2:${st[2]} 3:${st[3]}" +
            (if (st[4] > 0) " 假:${st[4]}" else "") + "）"
    }

    private fun trimNum(d: Double): String =
        if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()

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
            mp.seekTo(0)
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
            val dir = File(cacheDir, "pez")
            if (!dir.exists()) dir.mkdirs()
            val tmp = File(dir, "export.pez")
            // 判定线姿态全在 recordView.lines 里，meta 只提供曲目信息
            val ok = PezExporter.export(meta, recordView.lines, list, contentResolver, tmp)
            if (!ok) { toast(getString(R.string.msg_export_fail)); return }
            exportTmp = tmp

            val safeName = meta.name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, "$safeName.pez")
            }
            startActivityForResult(intent, REQ_SAVE)
        } catch (e: Exception) {
            e.printStackTrace()
            toast(getString(R.string.msg_export_fail) + "：" + e.message)
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

    /** 返回键 / 顶部「退出」：问"是否保存这次改动"（导出走右上角「导出 .pez」按钮） */
    override fun onBackPressed() {
        // 遮罩被隐藏时，返回键先把它恢复出来（再按一次才问保存/退出）
        if (recordView.hideBars) {
            recordView.hideBars = false
            refreshBarsButton()
            return
        }
        if (exitAfterExport) { saveDraft(); super.onBackPressed(); return }
        askSaveAndExit()
    }

    private fun askSaveAndExit() {
        if (isFinishing || isDestroyed) return
        android.app.AlertDialog.Builder(this)
            .setTitle("保存这次改动？")
            .setMessage("保存后下次进入这个项目可以继续编辑；选「不保存」会丢弃本次的改动。")
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
    private fun toast(msg: String) {
        Coop.log("提示：" + msg)
        // 打歌时这行是隐藏的：真出错（导出失败之类）就把它顶出来，不然用户什么都看不到
        try {
            txtInfo.visibility = android.view.View.VISIBLE
            txtInfo.text = msg
        } catch (_: Exception) {}
    }
}