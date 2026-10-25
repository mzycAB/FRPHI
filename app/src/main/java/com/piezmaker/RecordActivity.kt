package com.piezmaker

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import java.io.File

/** 录制页：三阶段制谱（drag/hold → click → flick）+ 播放控制 + 导出 PEZ */
class RecordActivity : Activity() {

    private companion object {
        const val TAG = "FRPHI"
        const val REQ_SAVE = 2001
        const val KEY_META = "meta"
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
    private lateinit var speedEdit: EditText
    private lateinit var lineEditPanel: View
    private lateinit var editAngle: EditText
    private lateinit var editLX: EditText
    private lateinit var editLY: EditText
    private lateinit var editLineSpeed: EditText
    private lateinit var scaleEdit: EditText
    private var curSpeed = 1.0f
    private var draftFile: File? = null

    private lateinit var meta: ChartMeta
    private var mediaPlayer: MediaPlayer? = null
    private var exportTmp: File? = null
    private var durationMs: Int = 0
    private var seeking = false

    private val handler = Handler(Looper.getMainLooper())
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
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        setContentView(R.layout.activity_record)

        meta = intent.getParcelableExtra(KEY_META) ?: run {
            toast("参数缺失"); finish(); return
        }

        recordView = findViewById(R.id.recordView)
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
        // 放置兼容：按一下切换 兼容关闭 → 红框兼容 → 全部兼容（改善手机端误触）
        btnCompat.setOnClickListener {
            recordView.compatMode = (recordView.compatMode + 1) % 3
            refreshCompatButton()
            // 兼容模式切换不再弹 toast（用户要求）
        }
        // 平滑移动开关：开启后放置/移动不吸附横竖线交叉点
        btnSmooth.setOnClickListener {
            recordView.smoothMove = !recordView.smoothMove
            btnSmooth.text = if (recordView.smoothMove) "平滑:开" else "平滑:关"
            btnSmooth.setTextColor(if (recordView.smoothMove) 0xFFFFEB3B.toInt() else 0xFFFFFFFF.toInt())
        }
        speedEdit = findViewById(R.id.speedEdit)
        lineEditPanel = findViewById(R.id.lineEditPanel)
        editAngle = findViewById(R.id.editAngle)
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
        bindLineInput(editLX) { it?.let { f -> recordView.lineMoveX = f.coerceIn(-1f, 1f); recordView.invalidate() } }
        bindLineInput(editLY) { it?.let { f -> recordView.lineMoveY = f.coerceIn(-1f, 1f); recordView.invalidate() } }
        bindLineInput(editLineSpeed) { it?.let { f ->
            if (f in 0.05f..100f) { recordView.lineSpeed = f; refreshInfo() }
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
        recordView.hLines = meta.hLines
        recordView.vLines = meta.vLines
        recordView.linePosPct = meta.linePosPct
        recordView.pass = 1
        recordView.playheadProvider = { mediaPlayer?.currentPosition?.toLong() ?: 0L }
        recordView.playingProvider = { mediaPlayer?.isPlaying == true }
        recordView.setBackgroundImage(meta.imageUri)

        btnPass1.setOnClickListener { switchPass(1) }
        btnPass2.setOnClickListener { switchPass(2) }
        btnPass3.setOnClickListener { switchPass(3) }
        btnPass4.setOnClickListener { switchPass(4) }

        // 打击音效开关（右上角，导出旁边）
        btnSound.setOnClickListener {
            recordView.soundEnabled = !recordView.soundEnabled
            btnSound.text = if (recordView.soundEnabled) "音效:开" else "音效:关"
            btnSound.setBackgroundResource(R.drawable.bg_btn_transparent)
            btnSound.setTextColor(if (recordView.soundEnabled) 0xFFFFFFFF.toInt() else 0x66FFFFFF.toInt())
        }
        btnSound.text = if (recordView.soundEnabled) "音效:开" else "音效:关"
        // 长按音效按钮：显示内部状态，方便在手机上排查“没有声音 / 判定异常”
        btnSound.setOnLongClickListener {
            toast(recordView.debugInfo())
            true
        }
        refreshCompatButton()

        // 暂停 = 进入编辑模式（自由放置/拖动/删除/搓时间轴）；继续 = 退出编辑
        btnPause.setOnClickListener {
            val mp = mediaPlayer ?: return@setOnClickListener
            if (mp.isPlaying) {
                mp.pause()
                btnPause.text = "继续"
                recordView.editMode = true
            } else {
                mp.start()
                btnPause.text = "暂停"
                recordView.editMode = false
                applySpeed(curSpeed)          // 从暂停恢复时补设速度
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
            }
        })

        findViewById<View>(R.id.btnBack).setOnClickListener {
            saveDraft()
            mediaPlayer?.stop(); mediaPlayer?.release(); mediaPlayer = null
            finish()
        }
        // 「播放」：从当前位置进入 Phira 打歌模式（再点一次退出）
        btnPlay.setOnClickListener {
            if (recordView.playMode) exitPlay() else enterPlay()
        }
        findViewById<View>(R.id.btnUndo).setOnClickListener {
            recordView.undoLast(); refreshInfo()
        }
        findViewById<View>(R.id.btnExport).setOnClickListener { exportPez() }

        // 谱面草稿：退出后重进保留改动（按标签 id 持久化）
        if (meta.tabId.isNotEmpty()) {
            draftFile = File(filesDir, "drafts/${meta.tabId}.json")
            draftFile?.takeIf { it.exists() }?.let { f ->
                try { recordView.loadDraft(f.readText()) } catch (_: Exception) {}
            }
        }

        startPlayback()
        switchPass(1)
        // 默认暂停并进入编辑模式（可立即放置/搓时间/编辑判定线）
        recordView.editMode = true
        btnPause.text = "继续"
        refreshInfo()
        handler.postDelayed(ticker, 200)
    }

    /** 刷新“放置兼容”按钮文字/高亮 */
    /** 刷新判定线输入面板数值（跟随拖动/旋转/打帧） */
    private fun refreshLineInputs() {
        val rv = recordView
        editAngle.setText(fmtNum(rv.lineRotate))
        editLX.setText(fmtNum(rv.lineMoveX))
        editLY.setText(fmtNum(rv.lineMoveY))
        editLineSpeed.setText(fmtNum(rv.lineSpeed))
    }

    private fun fmtNum(v: Float): String {
        val rounded = Math.round(v * 100f) / 100f
        return if (rounded == Math.floor(rounded.toDouble()).toFloat()) rounded.toInt().toString() else rounded.toString()
    }

    private fun refreshCompatButton() {
        val mode = recordView.compatMode
        btnCompat.text = when (mode) {
            1 -> "红框兼容"
            2 -> "全部兼容"
            else -> "兼容关闭"
        }
        btnCompat.setTextColor(if (mode == 0) 0xFFFFFFFF.toInt() else 0xFFFFEB3B.toInt())
    }

    private fun switchPass(p: Int) {        if (recordView.playMode) exitPlay()
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
        btnPause.text = "继续"
        btnPlay.text = "AUTOPLAY"
        refreshInfo()
        Log.i(TAG, "exitPlay -> paused at ${mediaPlayer?.currentPosition}ms, editMode=true")
    }

    fun currentSpeed(): Float = curSpeed

    /** 保存谱面草稿到应用私有目录（按标签 id） */
    private fun saveDraft() {
        val f = draftFile ?: return
        try {
            if (!f.parentFile.exists()) f.parentFile.mkdirs()
            f.writeText(recordView.saveDraft())
        } catch (_: Exception) {}
    }

    private fun refreshInfo() {
        if (recordView.editMode) {
            txtInfo.text = "编辑模式 · 点=放音 拖音符=改位 双击=删 上下滑=时间轴"
            return
        }
        if (recordView.playMode) {
            txtInfo.text = recordView.playStats()
            return
        }
        txtInfo.text = "BPM ${trimNum(meta.bpm)} · 横线 ${meta.hLines} · 竖线 ${meta.vLines} · " +
            "总 ${recordView.notes.size}（1:${recordView.countOf(1)} 2:${recordView.countOf(2)} 3:${recordView.countOf(3)}）"
    }

    private fun trimNum(d: Double): String =
        if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()

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

    private fun trimNum2(d: Float): String {
        val v = d.toDouble()
        return if (Math.floor(v) == v && !d.isInfinite()) d.toInt().toString() else d.toString()
    }

    private fun applySpeed(sp: Float) {
        try {
            val mp = mediaPlayer ?: return
            if (!mp.isPlaying) return          // setPlaybackParams 仅播放中可用，暂停时跳过
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                val p = mp.playbackParams
                mp.playbackParams = p.setSpeed(sp)
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
            val ok = PezExporter.export(meta.copy(speed = recordView.lineSpeed.toDouble()), list, contentResolver, tmp, recordView.lineKeyframes)
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

    private var exitAfterExport = false

    /** 返回键：弹窗确认是否导出保存铺面 */
    override fun onBackPressed() {
        if (exitAfterExport) { saveDraft(); super.onBackPressed(); return }
        android.app.AlertDialog.Builder(this)
            .setTitle("保存铺面内容？")
            .setMessage("导出为 .pez 文件并退出？谱面草稿仍会自动保留。")
            .setPositiveButton("是") { _, _ ->
                exitAfterExport = true
                exportPez()
            }
            .setNegativeButton("否") { _, _ ->
                saveDraft()
                finish()
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
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
        handler.removeCallbacksAndMessages(null)
        try { if (mediaPlayer?.isPlaying == true) { mediaPlayer?.pause(); btnPause.text = "继续" } } catch (_: Exception) {}
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(ticker, 120)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recordView.releaseSounds()
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}