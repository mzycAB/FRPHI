package com.piezmaker

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs

/**
 * 资源包编辑页（画图）。
 *
 * 中间画布；上方标签页（切换各贴图）+「打击音效」；左侧绘图工具；
 * 右侧颜色面板（色环/色卡、当前色、最近颜色 24 格、RGB）或音效面板；
 * 左上角 退出 / 保存 / 撤销 / 重做 + 可改名；右上角 导入图片。
 */
class ResPackActivity : Activity() {

    private companion object {
        val ITEMS = listOf(
            "click.png" to "蓝键",
            "hold.png" to "长条",
            "flick.png" to "红键",
            "drag.png" to "黄键",
            "click_mh.png" to "蓝键双押",
            "hold_mh.png" to "长条双押",
            "flick_mh.png" to "红键双押",
            "drag_mh.png" to "黄键双押",
            "hit_fx.png" to "打击特效"
        )
        /** 音效类型（蓝→黄→红→长） */
        val SFX = listOf(
            "click" to "蓝键音效",
            "drag" to "黄键音效",
            "flick" to "红键音效",
            "hold" to "长条音效"
        )
        val SFX_EXTS = listOf("ogg", "wav", "mp3")
        const val UNDO_LIMIT = 5
        const val RECENT_MAX = 24
        const val REQ_IMAGE = 2101
        const val REQ_AUDIO = 2102
        const val REQ_EXPORT = 2103
        const val PREFS = "frphi_respack_editor"
    }

    /** 待写入的音效（保存时才真正落盘；[uri] 为 null 表示删除） */
    private class PendingSfx(val uri: Uri?, val ext: String)

    /** 一个图层：和同一张图里其它层同尺寸；[visible] = 眼睛开关 */
    internal class Layer(var bmp: Bitmap, var name: String, var visible: Boolean = true)

    /**
     * 撤销快照：只记「某一层的像素」。
     * 图层的新建/删除/上下移/显隐属于结构改动，直接清空撤销栈（不占内存）。
     */
    private class LayerSnap(val layer: Int, val bmp: Bitmap)

    private class Doc(
        val file: String,
        val label: String,
        var bmp: Bitmap,
        val undo: ArrayList<LayerSnap> = ArrayList(),
        val redo: ArrayList<LayerSnap> = ArrayList(),
        var dirty: Boolean = false
    ) {
        /** 图层（PS 式）：底 → 顶；[active] = 当前正在画的那一层 */
        val layers = ArrayList<Layer>()
        var active = 0
        var pendingBefore: LayerSnap? = null

        init {
            layers.add(Layer(bmp, "图层 1"))
        }
    }

    private lateinit var dir: File
    private var packName = ""
    private var dirty = false
    private var nameChanged = false

    private val docs = ArrayList<Doc>()
    private var cur = 0

    private lateinit var canvasView: PixelCanvasView
    private lateinit var nameView: TextView
    private lateinit var tabsRow: LinearLayout
    private lateinit var tabViews: List<TextView>
    private lateinit var sfxTab: TextView
    private lateinit var currentSwatch: View
    private lateinit var recentRow: LinearLayout
    private lateinit var rgbEdit: EditText
    private lateinit var wheel: ColorWheelView
    private lateinit var wheelBox: View
    private lateinit var colorSquare: ColorSquareView
    private lateinit var colorScroll: View
    private lateinit var sfxScroll: View
    private lateinit var btnUndo: TextView
    private lateinit var btnRedo: TextView
    private lateinit var widthEdit: EditText
    private lateinit var heightEdit: EditText
    private val toolButtons = ArrayList<TextView>()
    private val shapeButtons = ArrayList<TextView>()

    private var colorMode = 0                  // 0=色环 1=色卡
    private var sfxMode = false

    // ---- 左右拉条 + 抽屉窗口（左=剪贴板，右=图层）----
    private var clipDrawer: View? = null
    private var layerDrawer: View? = null
    private var clipInfo: TextView? = null
    private var layerBox: LinearLayout? = null
    // 抽屉全高贴边后会盖住这批 chrome：窗口打开时收起来，关掉再恢复（同编辑界面）
    private var chromeTopBar: LinearLayout? = null
    private var chromeTabs: HorizontalScrollView? = null
    private var chromeLeft: ScrollView? = null
    private var chromeRight: FrameLayout? = null
    /** 剪贴板里那一块（复制出来的位图） */
    private var clipBitmap: android.graphics.Bitmap? = null
    private var curSfxType = "click"
    private val recentColors = ArrayList<Int>()
    private val pendingSfx = HashMap<String, PendingSfx>()
    private var exportTmp: File? = null

    private var currentColor = 0xFFFFFFFF.toInt()
    private var suppressRgb = false
    private var suppressSize = false
    private var suppressAlpha = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 和游戏一致：任何界面都不自动息屏
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dirName = intent.getStringExtra("dirName")
            ?: intent.getStringExtra("name") ?: ""
        dir = File(File(filesDir, "respacks"), dirName)
        if (!dir.isDirectory) {
            finish()
            return
        }
        packName = ResPack(dir).name
        hideSystemUi()
        loadRecent()
        loadDocs()
        buildUi()
        loadDoc(0)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    // ------------------------------------------------------------------ 数据

    /**
     * 先用 1×1 占位图把界面立刻建出来，9 张贴图在后台解码完成后再替换 ——
     * 避免大图（尤其打击特效）在主线程解码卡住首屏。
     */
    private fun loadDocs() {
        for ((file, label) in ITEMS) {
            docs.add(Doc(file, label, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)))
        }
        Thread {
            val loaded = ArrayList<Bitmap>(ITEMS.size)
            val loadedLayers = ArrayList<ArrayList<Layer>?>(ITEMS.size)
            val loadedActive = ArrayList<Int>(ITEMS.size)
            for ((file, _) in ITEMS) {
                val flat = runCatching {
                    val f = File(dir, file)
                    if (f.isFile) {
                        BitmapFactory.decodeFile(f.absolutePath)?.copy(Bitmap.Config.ARGB_8888, true)
                            ?: Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                    } else blankBitmap(file)
                }.getOrElse { Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888) }
                loaded.add(flat)
                // 图层单独存在 app 私有目录（不往资源包里塞额外文件，导出包才干净）
                val set = loadLayerSet(file)
                if (set != null && set.first.isNotEmpty() &&
                    set.first.all { it.bmp.width == flat.width && it.bmp.height == flat.height }
                ) {
                    loadedLayers.add(set.first)
                    loadedActive.add(set.second)
                } else {
                    loadedLayers.add(null)
                    loadedActive.add(0)
                }
            }
            runOnUiThread {
                for ((i, b) in loaded.withIndex()) {
                    docs[i].bmp = b
                    val ls = loadedLayers[i]
                    if (ls != null) {
                        docs[i].layers.clear()
                        docs[i].layers.addAll(ls)
                        docs[i].active = loadedActive[i].coerceIn(0, ls.lastIndex)
                    } else {
                        docs[i].layers[0].bmp = b
                        docs[i].active = 0
                    }
                }
                canvasView.isEnabled = true
                loadDoc(cur)
            }
        }.start()
    }

    // ---------------------------------------------------------------- 图层持久化
    /** 某张贴图的图层存放目录（app 私有；资源包目录里只放 Phira 认识的贴图/音效） */
    private fun layerDir(file: String): File =
        File(File(File(filesDir, "respack_layers"), dir.name), file.substringBeforeLast('.'))

    /** 读回图层；没有/读不全都返回 null（退回单图层） */
    private fun loadLayerSet(file: String): Pair<ArrayList<Layer>, Int>? {
        return try {
            val ld = layerDir(file)
            val meta = File(ld, "meta.txt")
            if (!meta.isFile) return null
            val out = ArrayList<Layer>()
            for ((i, ln) in meta.readLines().withIndex()) {
                if (ln.isEmpty()) continue
                val parts = ln.split('|')
                val name = parts.getOrElse(0) { "图层 " + (i + 1) }.ifEmpty { "图层 " + (i + 1) }
                val vis = (parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 1) != 0
                val f = File(ld, "$i.png")
                if (!f.isFile) return null
                val b = BitmapFactory.decodeFile(f.absolutePath)?.copy(Bitmap.Config.ARGB_8888, true)
                    ?: return null
                out.add(Layer(b, name, vis))
            }
            if (out.isEmpty()) return null
            val active = File(ld, "active.txt").takeIf { it.isFile }
                ?.readText()?.trim()?.toIntOrNull() ?: 0
            out to active.coerceIn(0, out.lastIndex)
        } catch (_: Exception) {
            null
        }
    }

    /** 保存图层（每层一张 PNG + meta.txt），保存时调用 */
    private fun saveLayerSet(d: Doc) {
        try {
            val ld = layerDir(d.file)
            if (!ld.isDirectory && !ld.mkdirs()) return
            // 层数变少时清掉多余的旧文件
            for (f in ld.listFiles() ?: emptyArray()) {
                if (!f.name.endsWith(".png")) continue
                val idx = f.nameWithoutExtension.toIntOrNull()
                if (idx == null || idx >= d.layers.size) f.delete()
            }
            val sb = StringBuilder()
            d.layers.forEachIndexed { i, l ->
                FileOutputStream(File(ld, "$i.png")).use { out ->
                    l.bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                sb.append(l.name.replace('|', '/')).append('|').append(if (l.visible) 1 else 0).append('\n')
            }
            File(ld, "meta.txt").writeText(sb.toString())
            File(ld, "active.txt").writeText(d.active.toString())
        } catch (_: Exception) {}
    }

    /** 空资源包：先建一张空画布（尺寸取内置素材尺寸；打击特效按 8×7 帧的常见尺寸） */
    private fun blankBitmap(file: String): Bitmap {
        val size = builtinSize(file)
        return Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888)
    }

    private fun builtinSize(file: String): Pair<Int, Int> {
        if (file == "hit_fx.png") return 1024 to 896
        return try {
            assets.open("notes/$file").use { ins ->
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(ins, null, o)
                if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else 512 to 512
            }
        } catch (_: Exception) {
            512 to 512
        }
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF0E1016.toInt()) }

        canvasView = PixelCanvasView(this).apply {
            // 贴图还在后台解码时不接受绘画
            isEnabled = false
            // 左右面板 + 顶部栏让出来的中间区域
            setPadding(dp(134), dp(86), dp(174), dp(6))
            onStrokeBegin = {
                // 只拍当前层的快照（别的层这一笔不会动，省内存）
                val d = docs[cur]
                d.active = d.active.coerceIn(0, d.layers.lastIndex)
                d.pendingBefore = LayerSnap(d.active, d.layers[d.active].bmp.copy(Bitmap.Config.ARGB_8888, true))
            }
            onStrokeEnd = {
                val d = docs[cur]
                val before = d.pendingBefore
                d.pendingBefore = null
                if (before != null) {
                    // 真正画完才把颜色记进「最近颜色」（橡皮不算，取消的笔画也不算）
                    if (tool != PixelCanvasView.TOOL_ERASER) pushRecent(brushColor)
                    d.undo.add(before)
                    while (d.undo.size > UNDO_LIMIT) d.undo.removeAt(0)
                    d.redo.clear()
                    d.dirty = true
                    dirty = true
                    composite(d)                 // 保存/导出看的是拍平后的整图
                    refreshUndoButtons()
                }
            }
            // 双指缩放开始：把单指误画的那一笔整段回滚，不留笔迹、也不记撤销
            onStrokeCancel = {
                val d = docs[cur]
                val before = d.pendingBefore
                d.pendingBefore = null
                if (before != null) {
                    val li = before.layer.coerceIn(0, d.layers.lastIndex)
                    val live = d.layers[li].bmp
                    val c = Canvas(live)
                    c.drawColor(0, PorterDuff.Mode.CLEAR)
                    c.drawBitmap(before.bmp, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
                    d.layers[li].bmp = live
                    composite(d)
                    canvasView.invalidate()
                }
            }
            onColorPicked = { c -> applyColor(c) }
            onSelectionChanged = { updateClipInfo() }
            // 双指捏合 = 缩放框住的那块（用户点名：不是按钮，是双指缩放框）
            onPinchScaleSelection = { f -> scaleSel(f) }
            tool = PixelCanvasView.TOOL_BRUSH
            brushColor = currentColor
            brushSize = 8f
        }
        root.addView(canvasView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // ---- 顶部栏 ----
        chromeTopBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xCC12151D.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val topBar = chromeTopBar!!
        topBar.addView(smallBtn("退出") { requestExit() })
        topBar.addView(smallBtn("保存") { saveAll() }.also {
            (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(6)
        })
        btnUndo = smallBtn("撤销") { doUndo() }
        btnUndo.let { (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(6) }
        topBar.addView(btnUndo)
        btnRedo = smallBtn("重做") { doRedo() }
        btnRedo.let { (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(6) }
        topBar.addView(btnRedo)
        // 导入图片：放在「重做」右边、资源包名字左边
        topBar.addView(smallBtn("导入图片") { pickImage() }.also {
            (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(6)
        })

        nameView = TextView(this).apply {
            text = packName
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setPadding(dp(12), 0, dp(12), 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { renameDialog() }
        }
        topBar.addView(nameView, LinearLayout.LayoutParams(0, dp(30), 1f))
        // 导出：放在最右（原来「导入图片」的位置）
        topBar.addView(smallBtn("导出资源包") { exportPack() })
        root.addView(topBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46), Gravity.TOP
        ))

        // ---- 标签页（贴图 + 打击音效） ----
        tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        tabViews = docs.mapIndexed { i, d ->
            TextView(this).apply {
                text = d.label
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 12f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener { loadDoc(i) }
            }
        }
        tabViews.forEach { tabsRow.addView(it) }
        sfxTab = TextView(this).apply {
            text = "打击音效"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                sfxMode = true
                refreshTabs()
                refreshRightPanel()
            }
        }
        tabsRow.addView(View(this), LinearLayout.LayoutParams(dp(1), dp(18)).also { it.topMargin = dp(4) })
        tabsRow.addView(sfxTab)
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0xAA12151D.toInt())
            addView(tabsRow)
        }
        chromeTabs = tabScroll
        root.addView(tabScroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(40), Gravity.TOP
        ).also { it.topMargin = dp(46) })

        // ---- 左侧工具栏（可滚动，防止小屏被裁） ----
        val leftBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        fun toolRow(l1: String, t1: Int, l2: String, t2: Int) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val a = toolBtn(l1) { canvasView.tool = t1; refreshTools() }
            val b = toolBtn(l2) { canvasView.tool = t2; refreshTools() }
            toolButtons.add(a)
            toolButtons.add(b)
            row.addView(a, LinearLayout.LayoutParams(0, dp(30), 1f))
            row.addView(b, LinearLayout.LayoutParams(0, dp(30), 1f).also { it.marginStart = dp(4) })
            leftBody.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30)
            ).also { it.topMargin = dp(4) })
        }
        toolRow("画笔", PixelCanvasView.TOOL_BRUSH, "橡皮", PixelCanvasView.TOOL_ERASER)
        toolRow("油漆桶", PixelCanvasView.TOOL_BUCKET, "吸色", PixelCanvasView.TOOL_PICKER)

        // 笔刷形状：拖拽出一笔图形（自由 = 原来的连续画笔）
        leftBody.addView(sectionLabel("笔刷形状"))
        fun shapeRow(l1: String, s1: Int, l2: String, s2: Int) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val a = toolBtn(l1, 10.5f) { canvasView.shape = s1; refreshShapes() }
            val b = toolBtn(l2, 10.5f) { canvasView.shape = s2; refreshShapes() }
            shapeButtons.add(a)
            shapeButtons.add(b)
            row.addView(a, LinearLayout.LayoutParams(0, dp(28), 1f))
            row.addView(b, LinearLayout.LayoutParams(0, dp(28), 1f).also { it.marginStart = dp(4) })
            leftBody.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28)
            ).also { it.topMargin = dp(4) })
        }
        shapeRow("自由", PixelCanvasView.SHAPE_FREE, "直线", PixelCanvasView.SHAPE_LINE)
        shapeRow("正方形", PixelCanvasView.SHAPE_SQUARE, "长方形", PixelCanvasView.SHAPE_RECT)
        shapeRow("圆形", PixelCanvasView.SHAPE_CIRCLE, "椭圆形", PixelCanvasView.SHAPE_ELLIPSE)
        shapeRow("正三角形", PixelCanvasView.SHAPE_TRI_EQ, "三角形", PixelCanvasView.SHAPE_TRI)

        // 复制 / 黏贴：和形状按钮放在同一栏（用户点名）
        val clipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val copyBtn = toolBtn("复制", 10.5f) { doCopySelection() }
        val pasteBtn = toolBtn("黏贴", 10.5f) { doPaste() }
        clipRow.addView(copyBtn, LinearLayout.LayoutParams(0, dp(28), 1f))
        clipRow.addView(pasteBtn, LinearLayout.LayoutParams(0, dp(28), 1f).also { it.marginStart = dp(4) })
        leftBody.addView(clipRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(28)
        ).also { it.topMargin = dp(4) })

        // 粗细：上面输入框 + 下面拖动条
        leftBody.addView(sectionLabel("粗细"))
        var sizeEditRef: EditText? = null
        var sizeSeekRef: SeekBar? = null
        val sizeEdit = numEdit("8") { v ->
            if (!suppressSize) {
                val n = v.coerceIn(1, 128)
                canvasView.brushSize = n.toFloat()
                suppressSize = true
                sizeSeekRef?.progress = n - 1
                suppressSize = false
            }
        }
        sizeEditRef = sizeEdit
        leftBody.addView(sizeEdit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(28)
        ))
        val sizeSeek = SeekBar(this).apply {
            max = 127
            progress = 7
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    canvasView.brushSize = (p + 1).toFloat()
                    suppressSize = true
                    sizeEditRef?.setText((p + 1).toString())
                    suppressSize = false
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        sizeSeekRef = sizeSeek
        leftBody.addView(sizeSeek, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(22)
        ))

        // 深浅（透明度）：上面输入框 + 下面拖动条
        leftBody.addView(sectionLabel("深浅（透明度）"))
        var alphaEditRef: EditText? = null
        var alphaSeekRef: SeekBar? = null
        val alphaEdit = numEdit("100") { v ->
            if (!suppressAlpha) {
                val n = v.coerceIn(0, 100)
                canvasView.alphaPercent = n
                suppressAlpha = true
                alphaSeekRef?.progress = n
                suppressAlpha = false
            }
        }
        alphaEditRef = alphaEdit
        leftBody.addView(alphaEdit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(28)
        ))
        val alphaSeek = SeekBar(this).apply {
            max = 100
            progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    canvasView.alphaPercent = p
                    suppressAlpha = true
                    alphaEditRef?.setText(p.toString())
                    suppressAlpha = false
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        alphaSeekRef = alphaSeek
        leftBody.addView(alphaSeek, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(22)
        ))

        // 分辨率：输入框直接改当前贴图尺寸
        leftBody.addView(sectionLabel("分辨率"))
        val resRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        widthEdit = numEdit("") { }
        heightEdit = numEdit("") { }
        resRow.addView(widthEdit, LinearLayout.LayoutParams(0, dp(28), 1f))
        resRow.addView(TextView(this).apply {
            text = "×"
            setTextColor(0x99FFFFFF.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            includeFontPadding = false
        }, LinearLayout.LayoutParams(dp(14), dp(28)))
        resRow.addView(heightEdit, LinearLayout.LayoutParams(0, dp(28), 1f))
        leftBody.addView(resRow)
        leftBody.addView(smallBtn("应用尺寸") { applyResolution() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(28)
        ).also { it.topMargin = dp(4) })

        val leftScroll = ScrollView(this).apply {
            setBackgroundColor(0xCC12151D.toInt())
            isVerticalScrollBarEnabled = false
            addView(leftBody, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        chromeLeft = leftScroll
        root.addView(leftScroll, FrameLayout.LayoutParams(dp(128), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.START)
            .also { it.topMargin = dp(92); it.bottomMargin = dp(6); it.marginStart = dp(6) })

        // ---- 右侧面板：颜色 / 音效 ----
        colorScroll = buildColorPanel()
        sfxScroll = buildSfxPanel()
        val rightBox = FrameLayout(this).apply {
            setBackgroundColor(0xCC12151D.toInt())
            addView(colorScroll, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            addView(sfxScroll, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        chromeRight = rightBox
        root.addView(rightBox, FrameLayout.LayoutParams(dp(168), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.END)
            .also { it.topMargin = dp(92); it.bottomMargin = dp(6); it.marginEnd = dp(6) })

        // ---- 左右灰色拉条 + 抽屉窗口（用户点名：左剪贴板、右图层，样式和编辑界面一致）----
        val barLp = FrameLayout.LayoutParams(dp(10), dp(264), Gravity.CENTER_VERTICAL or Gravity.START)
            .also { it.marginStart = dp(2) }
        root.addView(grayBar { toggleClipDrawer() }, barLp)
        val barRp = FrameLayout.LayoutParams(dp(10), dp(264), Gravity.CENTER_VERTICAL or Gravity.END)
            .also { it.marginEnd = dp(2) }
        root.addView(grayBar { toggleLayerDrawer() }, barRp)

        // 抽屉**贴边、上下顶到屏幕边缘**（用户点名：和编辑界面一致）：
        // 左边贴屏幕左沿、右边贴屏幕右沿，从 y=0 一直顶到底，不做上下留白
        clipDrawer = buildClipDrawer().also {
            root.addView(it, FrameLayout.LayoutParams(dp(214), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.START))
        }
        layerDrawer = buildLayerDrawer().also {
            root.addView(it, FrameLayout.LayoutParams(dp(214), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.END))
        }

        setContentView(root)
        refreshTools()
        refreshShapes()
        refreshTabs()
        refreshUndoButtons()
        refreshRightPanel()
        applyColor(currentColor)
    }

    // ---------------------------------------------------------------- 左右灰色拉条 + 抽屉窗口
    /**
     * 屏幕左右那条灰色拉条：和编辑界面（RecordView）同款 ——
     * 10dp 宽、264dp 长、纵向居中、圆角 2.5dp、颜色 #C8CDD6 七成不透明。
     */
    private fun grayBar(onTap: () -> Unit): View = View(this).apply {
        background = GradientDrawable().apply {
            setColor(0xB4C8CDD6.toInt())
            cornerRadius = dp(1) * 2.5f
        }
        isClickable = true
        setOnClickListener { onTap() }
    }

    private fun toggleClipDrawer() {
        val d = clipDrawer ?: return
        val show = d.visibility != View.VISIBLE
        if (show) {
            layerDrawer?.visibility = View.GONE
            updateClipInfo()
            showDrawer(d, fromLeft = true)
        } else {
            hideDrawer(d, fromLeft = true)
        }
    }

    private fun toggleLayerDrawer() {
        val d = layerDrawer ?: return
        val show = d.visibility != View.VISIBLE
        if (show) {
            clipDrawer?.visibility = View.GONE
            rebuildLayerList()
            showDrawer(d, fromLeft = false)
        } else {
            hideDrawer(d, fromLeft = false)
        }
    }

    /** 抽屉滑入（和编辑界面右侧窗口同一个手感：从边上推出来，不是硬闪） */
    private fun showDrawer(d: View, fromLeft: Boolean) {
        setChromeVisible(false)               // 全高窗口盖住了顶部栏/左右面板，先收起来
        val w = (d.layoutParams?.width ?: dp(214)).toFloat()
        d.animate().cancel()                  // 上一次滑出还没收完就再点：先掐掉，别把新窗口收成 GONE
        d.visibility = View.VISIBLE
        d.translationX = if (fromLeft) -w else w
        d.animate().translationX(0f).setDuration(160).start()
    }

    private fun hideDrawer(d: View, fromLeft: Boolean) {
        setChromeVisible(true)                // 关窗就把顶部栏/左右面板请回来（动画中恢复也顺）
        val w = (d.layoutParams?.width ?: dp(214)).toFloat()
        d.animate().cancel()
        d.animate().translationX(if (fromLeft) -w else w).setDuration(140)
            .withEndAction {
                // 只有"确实还滑在外面"才收起来（中途被 showDrawer 接管的情况）
                val ww = (d.layoutParams?.width ?: dp(214)).toFloat()
                if (Math.abs(d.translationX) >= ww - 1f) d.visibility = View.GONE
            }.start()
    }

    /** 抽屉全高贴边会盖住顶部栏/页签/左右面板：打开收起、关闭恢复（同编辑界面） */
    private fun setChromeVisible(show: Boolean) {
        val v = if (show) View.VISIBLE else View.GONE
        chromeTopBar?.visibility = v
        chromeTabs?.visibility = v
        chromeLeft?.visibility = v
        chromeRight?.visibility = v
    }

    // ★ 点窗口外 = 关窗口（用户点名修复）：窗口开着时，一旦按下落在抽屉矩形外，
    // 直接收窗并吃掉这次按下（否则会点到底下被盖住的按钮/画布上去）。
    /** 资源包界面：白色虚线框选条目，方向键移动，Enter 单击，"< >" 左右滑，"/" 长按 */
    private var kbBox: KeyboardFocusOverlay? = null
    private var kbIndex = -1
    private var kbSlashHeld = false
    private var kbSlashUsed = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val mode = try {
            getSharedPreferences("frphi_rec", 0).getInt("quickOp", 0)
        } catch (_: Exception) { 0 }
        if (mode <= 0) return super.dispatchKeyEvent(event)
        val kc = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        val up = event.action == KeyEvent.ACTION_UP
        val dir = KbNavUtil.dirOf(kc)
        val enter = KbNavUtil.isEnter(kc)
        val swipe = KbNavUtil.swipeOf(kc)
        val slash = kc == KeyEvent.KEYCODE_SLASH
        if (dir == 0 && !enter && swipe < 0 && !slash) return super.dispatchKeyEvent(event)
        if (currentFocus is EditText) return super.dispatchKeyEvent(event)

        val root = findViewById<ViewGroup>(android.R.id.content)
        val views = KbNavUtil.collect(root)
        if (views.isEmpty()) return true
        val box = kbBox ?: KeyboardFocusOverlay(this).also {
            root?.addView(it, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            kbBox = it
        }
        fun act(a: Int) {
            if (kbIndex !in views.indices) { kbIndex = 0; box.focus(views[0]); return }
            KbGesture.send(views[kbIndex], a)
        }
        when {
            dir != 0 && down -> {
                kbIndex = KbNavUtil.nearest(KbNavUtil.rects(views), kbIndex, dir)
                box.focus(views.getOrNull(kbIndex))
            }
            enter && down -> act(KbAct.CLICK)
            slash && down -> { kbSlashHeld = true; kbSlashUsed = false }
            slash && up -> { if (!kbSlashUsed) act(KbAct.LONG); kbSlashHeld = false }
            swipe >= 0 && down -> {
                val a = if (kbSlashHeld) {
                    kbSlashUsed = true
                    if (swipe == KbAct.SWIPE_LEFT) KbAct.SWIPE_LEFT2 else KbAct.SWIPE_RIGHT2
                } else swipe
                act(a)
            }
        }
        return true
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            val clip = clipDrawer
            val layer = layerDrawer
            if (clip != null && clip.visibility == View.VISIBLE && !drawerHit(clip, ev)) {
                hideDrawer(clip, fromLeft = true)
                return true
            }
            if (layer != null && layer.visibility == View.VISIBLE && !drawerHit(layer, ev)) {
                hideDrawer(layer, fromLeft = false)
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 按下点是否落在抽屉矩形里（用屏幕坐标比，避免状态栏偏移误判） */
    private fun drawerHit(d: View, ev: android.view.MotionEvent): Boolean {
        val loc = IntArray(2)
        d.getLocationOnScreen(loc)
        return ev.rawX >= loc[0] && ev.rawX <= loc[0] + d.width &&
            ev.rawY >= loc[1] && ev.rawY <= loc[1] + d.height
    }

    /**
     * 抽屉窗口的底板：**一比一复刻主界面左侧抽屉**（用户点名）——
     * 纯半透明黑 #99000000、直角、没有描边；内边距 左8 上10 右8 下8。
     * （以前是浮窗样式 #F014161E + 14dp 圆角 + 1.5dp 白描边，已废弃。）
     */
    private fun drawerBody(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(10), dp(8), dp(8))
        background = GradientDrawable().apply {
            setColor(0x99000000.toInt())
        }
        visibility = View.GONE
        isClickable = true
        setOnClickListener { }                 // 点抽屉本身不关
    }

    /** 左侧抽屉：剪贴板（复制 / 黏贴 / 放大缩小选中） */
    private fun buildClipDrawer(): View {
        val body = drawerBody()
        body.addView(drawerTitle("剪贴板"))
        clipInfo = TextView(this).apply {
            text = "长按画面 0.5 秒拖出方框"
            setTextColor(0x99FFFFFF.toInt())
            textSize = 10f
            includeFontPadding = false
            setPadding(dp(2), dp(2), 0, dp(6))
        }
        body.addView(clipInfo)
        body.addView(drawerBtn("取消框选", 0x1A00E5FF) { canvasView.clearSelection(); updateClipInfo() }, fullRow())
        return body
    }

    /** 抽屉标题：13sp 粗体白色，左边距对齐主界面左抽屉标题（内边距 8 + 2 = 10dp） */
    private fun drawerTitle(t: String): TextView = TextView(this).apply {
        text = t
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        includeFontPadding = false
        setPadding(dp(2), 0, 0, dp(4))
    }

    /** 抽屉里的按钮：主界面左抽屉那种直角色块（不是浮窗的圆角描边按钮） */
    private fun drawerBtn(t: String, tint: Int = 0x22FFFFFF, action: () -> Unit): TextView =
        TextView(this).apply {
            text = t
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(10), 0, dp(10), 0)
            background = GradientDrawable().apply { setColor(tint) }
            setOnClickListener { action() }
        }

    /**
     * 右侧抽屉：图层 —— **当前这张贴图自己的图层**（PS 式，不是资源包的贴图列表）：
     * 上面是图层列表（顶层在上）、每行有眼睛开关，点一行 = 切到那层画；
     * 下面一排按钮：新建 / 删除 / 上移 / 下移（长按行 = 改名）。
     */
    private fun buildLayerDrawer(): View {
        val body = drawerBody()
        body.addView(drawerTitle("图层"))
        layerBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(layerBox, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        body.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val ops = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ops.addView(drawerBtn("＋") { addLayer() }, LinearLayout.LayoutParams(0, dp(30), 1f))
        ops.addView(drawerBtn("删") { deleteLayer() }, LinearLayout.LayoutParams(0, dp(30), 1f))
        ops.addView(drawerBtn("↑") { moveLayer(1) }, LinearLayout.LayoutParams(0, dp(30), 1f))
        ops.addView(drawerBtn("↓") { moveLayer(-1) }, LinearLayout.LayoutParams(0, dp(30), 1f))
        body.addView(ops, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(30)
        ).also { it.topMargin = dp(6) })
        rebuildLayerList()
        return body
    }

    private fun fullRow(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)).also { it.topMargin = dp(4) }

    /** 重画图层列表：PS 习惯 —— 顶层排在最上面 */
    private fun rebuildLayerList() {
        val box = layerBox ?: return
        box.removeAllViews()
        val d = docs.getOrNull(cur) ?: return
        for (i in d.layers.indices.reversed()) {
            val l = d.layers[i]
            val on = i == d.active
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(6), 0)
                // 主界面左抽屉的行：直角色块，选中=淡青、其余=淡白（没有圆角、没有描边）
                background = GradientDrawable().apply {
                    setColor(if (on) 0x2600E5FF else 0x22FFFFFF)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(30)
                ).also { it.topMargin = dp(6) }
            }
            // 眼睛：点一下显示/隐藏（隐藏的层不参与合成，也不出现在画面上）
            val eye = TextView(this).apply {
                text = if (l.visible) "◉" else "○"
                setTextColor(if (l.visible) 0xFF00E5FF.toInt() else 0x66FFFFFF)
                textSize = 13f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener { toggleLayerVisible(i) }
            }
            row.addView(eye, LinearLayout.LayoutParams(dp(24), dp(30)))
            val name = TextView(this).apply {
                text = l.name
                setTextColor(if (on) 0xFF00E5FF.toInt() else 0xFFFFFFFF.toInt())
                textSize = 13f
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { selectLayer(i) }
                setOnLongClickListener { renameLayer(i); true }
            }
            row.addView(name, LinearLayout.LayoutParams(0, dp(30), 1f))
            box.addView(row)
        }
    }

    // ------------------------------------------------------------------ 图层操作

    /** 把某张贴图的图层栈推给画布（显示用） */
    private fun pushLayers(d: Doc) {
        d.active = d.active.coerceIn(0, d.layers.lastIndex)
        canvasView.setLayerStack(
            d.layers.map { it.bmp },
            BooleanArray(d.layers.size) { d.layers[it].visible },
            d.active,
            d.layers[d.active].bmp
        )
    }

    /** 拍平成整图（保存 / 导出看的就是它） */
    private fun composite(d: Doc) {
        val first = d.layers.firstOrNull()?.bmp ?: return
        val w = first.width
        val h = first.height
        // ★ 合成用的画布绝不能是某一层本身（会把那层内容覆盖成整图 → 越画越花）
        val reuse = d.bmp.width == w && d.bmp.height == h && d.layers.none { it.bmp === d.bmp }
        val out = if (reuse) d.bmp else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (l in d.layers) if (l.visible) c.drawBitmap(l.bmp, 0f, 0f, p)
        d.bmp = out
    }

    private fun clearUndo(d: Doc) {
        d.undo.clear()
        d.redo.clear()
        d.pendingBefore = null
    }

    private fun selectLayer(i: Int) {
        val d = docs.getOrNull(cur) ?: return
        if (i !in d.layers.indices) return
        d.active = i
        canvasView.replaceBitmap(d.layers[i].bmp)
        pushLayers(d)
        refreshUndoButtons()
        rebuildLayerList()
    }

    private fun toggleLayerVisible(i: Int) {
        val d = docs.getOrNull(cur) ?: return
        val l = d.layers.getOrNull(i) ?: return
        l.visible = !l.visible
        d.dirty = true
        dirty = true
        composite(d)
        pushLayers(d)
        rebuildLayerList()
    }

    /** 在当前层上方新建一个空图层，并切到它 */
    private fun addLayer() {
        val d = docs.getOrNull(cur) ?: return
        val base = d.layers[d.active.coerceIn(0, d.layers.lastIndex)].bmp
        val nb = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        d.layers.add(d.active + 1, Layer(nb, "图层 " + (d.layers.size + 1)))
        d.active = d.active + 1
        clearUndo(d)
        d.dirty = true
        dirty = true
        composite(d)
        canvasView.replaceBitmap(nb)
        pushLayers(d)
        refreshUndoButtons()
        rebuildLayerList()
    }

    private fun deleteLayer() {
        val d = docs.getOrNull(cur) ?: return
        if (d.layers.size <= 1) return        // 至少要留一层（没提示条：这页不弹操作反馈）
        d.layers.removeAt(d.active.coerceIn(0, d.layers.lastIndex))
        d.active = d.active.coerceIn(0, d.layers.lastIndex)
        clearUndo(d)
        d.dirty = true
        dirty = true
        composite(d)
        canvasView.replaceBitmap(d.layers[d.active].bmp)
        pushLayers(d)
        refreshUndoButtons()
        rebuildLayerList()
    }

    /** [dir] = 1 往上（更靠前）、-1 往下 */
    private fun moveLayer(dir: Int) {
        val d = docs.getOrNull(cur) ?: return
        val i = d.active.coerceIn(0, d.layers.lastIndex)
        val j = i + dir
        if (j !in d.layers.indices) return
        val l = d.layers.removeAt(i)
        d.layers.add(j, l)
        d.active = j
        clearUndo(d)
        d.dirty = true
        dirty = true
        composite(d)
        pushLayers(d)
        rebuildLayerList()
    }

    private fun renameLayer(i: Int) {
        val d = docs.getOrNull(cur) ?: return
        val l = d.layers.getOrNull(i) ?: return
        val edit = EditText(this).apply {
            setText(l.name)
            setSelection(l.name.length)
            setTextColor(0xFF000000.toInt())
            setBackgroundColor(0xFFF2F2F2.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        KbAlertBuilder(this)
            .setTitle("图层名字")
            .setView(edit)
            .setPositiveButton("确定") { _, _ ->
                val n = edit.text.toString().trim()
                if (n.isNotEmpty()) { l.name = n; rebuildLayerList() }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doCopySelection() {
        val b = canvasView.copySelection()
        if (b == null) { updateClipInfo(); return }
        clipBitmap = b
        updateClipInfo()
    }

    private fun doPaste() {
        val b = clipBitmap ?: return
        if (canvasView.pasteBitmap(b)) {
            dirty = true
            composite(docs[cur])          // 黏贴只落在当前层，整图要重拍一次
            refreshUndoButtons()
        }
        updateClipInfo()
    }

    private fun scaleSel(f: Float) {
        if (canvasView.scaleSelection(f)) {
            dirty = true
            composite(docs[cur])
            refreshUndoButtons()
        }
        updateClipInfo()
    }

    private fun updateClipInfo() {
        val r = canvasView.selectionRect()
        val sz = clipBitmap?.let { it.width.toString() + "×" + it.height } ?: "空"
        clipInfo?.text = if (r == null || r.isEmpty) {
            "未框选 · 剪贴板 " + sz
        } else {
            "框 " + r.width() + "×" + r.height() + " · 剪贴板 " + sz
        }
    }

    private fun buildColorPanel(): View {        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }

        // 色环 / 色卡 切换（放在色环上方）
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnWheel = modeBtn("色环") { colorMode = 0; refreshRightPanel() }
        val btnSwatch = modeBtn("色卡") { colorMode = 1; refreshRightPanel() }
        modeRow.addView(btnWheel, LinearLayout.LayoutParams(0, dp(26), 1f))
        modeRow.addView(btnSwatch, LinearLayout.LayoutParams(0, dp(26), 1f).also { it.marginStart = dp(4) })
        body.addView(modeRow)

        wheel = ColorWheelView(this).apply {
            color = currentColor
            onColorChanged = { c -> applyColor(c) }
        }
        wheelBox = wheel
        body.addView(wheel, LinearLayout.LayoutParams(dp(140), dp(140)).also { it.topMargin = dp(4) })

        // 色卡：正方形平滑选色（横轴饱和度、纵轴明度，色相跟随色环）
        colorSquare = ColorSquareView(this).apply {
            hue = 0f
            onColorChanged = { c -> applyColor(c, keepHue = true) }
            visibility = View.GONE
        }
        body.addView(colorSquare, LinearLayout.LayoutParams(dp(140), dp(140)).also { it.topMargin = dp(4) })

        body.addView(sectionLabel("当前颜色"))
        currentSwatch = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(currentColor)
                setStroke(dp(1), 0x55FFFFFF)
            }
        }
        body.addView(currentSwatch, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(24)
        ).also { it.topMargin = dp(3) })

        // 最近颜色：4 列 × 6 行（24 个，跨会话保存）
        body.addView(sectionLabel("最近颜色"))
        recentRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(recentRow)
        rebuildRecent()

        body.addView(sectionLabel("RGB"))
        rgbEdit = EditText(this).apply {
            setText(String.format(Locale.US, "%06X", currentColor and 0xFFFFFF))
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            includeFontPadding = false
            setSingleLine(true)
            hint = "如 0000FF"
            setHintTextColor(0x66FFFFFF.toInt())
            setBackgroundColor(0x22FFFFFF)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (suppressRgb) return
                    val t = s?.toString()?.trim() ?: return
                    if (t.length == 6) {
                        val v = t.toLongOrNull(16) ?: return
                        applyColor(0xFF000000.toInt() or v.toInt())
                    }
                }
            })
        }
        body.addView(rgbEdit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(32)
        ).also { it.topMargin = dp(3) })

        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun buildSfxPanel(): View {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        body.addView(TextView(this).apply {
            text = "打击音效"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            includeFontPadding = false
            setPadding(0, 0, 0, dp(4))
        })
        for ((type, label) in SFX) {
            body.addView(sfxRow(type, label), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
            ).also { it.topMargin = dp(4) })
        }
        body.addView(smallBtn("导入音效") { pickAudio() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(30)
        ).also { it.topMargin = dp(10) })
        body.addView(smallBtn("返回颜色") {
            sfxMode = false; refreshTabs(); refreshRightPanel()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(30)
        ).also { it.topMargin = dp(6) })

        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** 一行音效：点击 = 选为导入目标；右滑 = 删除该音效文件 */
    private fun sfxRow(type: String, label: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(0x1AFFFFFF)
                setStroke(dp(1), 0x33FFFFFF)
            }
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            includeFontPadding = false
        })
        row.addView(TextView(this).apply {
            text = displaySfxName(type) ?: "未选择音频"
            setTextColor(0x99FFFFFF.toInt())
            textSize = 10f
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        var downX = 0f
        var moved = false
        row.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    if (abs(dx) > dp(6)) moved = true
                    v.translationX = maxOf(0f, dx)      // 右滑
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = e.rawX - downX
                    v.animate().translationX(0f).setDuration(120).start()
                    if (dx > dp(50)) {
                        deleteSfx(type)                   // 右滑 = 删除
                    } else if (!moved) {
                        curSfxType = type
                        refreshRightPanel()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().translationX(0f).setDuration(120).start()
                    true
                }
                else -> true
            }
        }
        return row
    }

    private fun smallBtn(text: String, action: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(10), 0, dp(10), 0)
            minWidth = dp(52)
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(0x33FFFFFF)
                setStroke(dp(1), 0x44FFFFFF)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)
            )
            setOnClickListener { action() }
        }

    private fun modeBtn(text: String, action: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = GradientDrawable().apply {
                cornerRadius = dp(5).toFloat()
                setColor(0x22FFFFFF)
                setStroke(dp(1), 0x33FFFFFF)
            }
            setOnClickListener { action() }
        }

    private fun toolBtn(text: String, textSize: Float = 13f, action: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            this.textSize = textSize
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(0x22FFFFFF)
                setStroke(dp(1), 0x33FFFFFF)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30)
            )
            setOnClickListener { action() }
        }

    private fun sectionLabel(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(0x99FFFFFF.toInt())
            textSize = 10f
            includeFontPadding = false
            setPadding(0, dp(6), 0, dp(1))
        }

    /** 数字输入框（上面输入框 + 下面拖动条的一部分） */
    private fun numEdit(initial: String, onChange: (Int) -> Unit): EditText =
        EditText(this).apply {
            setText(initial)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            includeFontPadding = false
            setSingleLine(true)
            setBackgroundColor(0x22FFFFFF)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val v = s?.toString()?.trim()?.toIntOrNull() ?: return
                    onChange(v)
                }
            })
        }

    /** 改变当前图片分辨率（缩放，保留内容） */
    private fun applyResolution() {
        val w = widthEdit.text.toString().trim().toIntOrNull() ?: return
        val h = heightEdit.text.toString().trim().toIntOrNull() ?: return
        if (w !in 1..4096 || h !in 1..4096) return
        val d = docs.getOrNull(cur) ?: return
        if (d.bmp.width == w && d.bmp.height == h) return
        clearUndo(d)                     // 尺寸变了，旧快照对不上，撤销栈清空
        // 所有图层一起缩放，不然坐标就错位了
        for (l in d.layers) {
            l.bmp = Bitmap.createScaledBitmap(l.bmp, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
        }
        d.bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.dirty = true
        dirty = true
        composite(d)
        canvasView.setBitmap(d.layers[d.active].bmp)
        pushLayers(d)
        refreshUndoButtons()
        rebuildLayerList()
    }

    // ------------------------------------------------------------------ 状态刷新

    private fun refreshShapes() {
        val s = canvasView.shape
        for ((i, v) in shapeButtons.withIndex()) {
            val on = i == s
            v.setTextColor(if (on) 0xFF00E5FF.toInt() else 0xFFFFFFFF.toInt())
            (v.background as? GradientDrawable)?.setColor(if (on) 0x3300E5FF else 0x22FFFFFF)
        }
    }

    private fun refreshTools() {
        val t = canvasView.tool
        for ((i, v) in toolButtons.withIndex()) {
            val on = i == t
            v.setTextColor(if (on) 0xFF00E5FF.toInt() else 0xFFFFFFFF.toInt())
            (v.background as? GradientDrawable)?.setColor(if (on) 0x3300E5FF else 0x22FFFFFF)
        }
    }

    private fun refreshTabs() {
        for ((i, v) in tabViews.withIndex()) {
            val on = i == cur && !sfxMode
            v.setTextColor(if (on) 0xFF00E5FF.toInt() else 0xB3FFFFFF.toInt())
            v.background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(if (on) 0x2200E5FF else 0x00000000)
                if (on) setStroke(dp(1), 0x6600E5FF)
            }
        }
        sfxTab.setTextColor(if (sfxMode) 0xFF00E5FF.toInt() else 0xB3FFFFFF.toInt())
        sfxTab.background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(if (sfxMode) 0x2200E5FF else 0x00000000)
            if (sfxMode) setStroke(dp(1), 0x6600E5FF)
        }
    }

    private fun refreshUndoButtons() {
        val d = docs.getOrNull(cur)
        btnUndo.alpha = if (d != null && d.undo.isNotEmpty()) 1f else 0.4f
        btnRedo.alpha = if (d != null && d.redo.isNotEmpty()) 1f else 0.4f
    }

    /** 右侧面板：颜色 / 音效 + 色环 / 色卡 */
    private fun refreshRightPanel() {
        colorScroll.visibility = if (sfxMode) View.GONE else View.VISIBLE
        sfxScroll.visibility = if (sfxMode) View.VISIBLE else View.GONE
        wheelBox.visibility = if (colorMode == 0) View.VISIBLE else View.GONE
        colorSquare.visibility = if (colorMode == 1) View.VISIBLE else View.GONE
        rebuildSfxRows()
    }

    private fun rebuildSfxRows() {
        // 音效面板内容简单重建（行文字要跟着文件变化更新）
        val sv = sfxScroll as? ScrollView ?: return
        if (sv.childCount > 0) {
            val body = (sv.getChildAt(0) as? LinearLayout) ?: return
            // 前 2 个是标题/说明，后面是 3 行 + 按钮；直接刷新每行的第二行文字
            for ((i, item) in SFX.withIndex()) {
                val row = body.getChildAt(2 + i) as? LinearLayout ?: continue
                val nameTv = row.getChildAt(1) as? TextView ?: continue
                nameTv.text = displaySfxName(item.first) ?: "未选择音频"
                val on = item.first == curSfxType
                (row.background as? GradientDrawable)?.setColor(if (on) 0x3300E5FF else 0x1AFFFFFF)
                (row.background as? GradientDrawable)?.setStroke(dp(1), if (on) 0x6600E5FF else 0x33FFFFFF)
            }
        }
    }

    private val recentCells = ArrayList<View>()

    private fun rebuildRecent() {
        // 24 个格子只建一次，之后每次落笔只刷新背景色（不再整块销毁重建，避免布局重排）
        if (recentCells.isEmpty()) {
            var row: LinearLayout? = null
            for (i in 0 until RECENT_MAX) {
                if (i % 4 == 0) {
                    row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    recentRow.addView(row, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(20)
                    ).also { it.topMargin = if (i == 0) 0 else dp(2) })
                }
                val v = View(this)
                row!!.addView(v, LinearLayout.LayoutParams(0, dp(20), 1f).also {
                    if (i % 4 != 0) it.marginStart = dp(2)
                })
                recentCells.add(v)
            }
        }
        for ((i, v) in recentCells.withIndex()) {
            val c = recentColors.getOrNull(i)
            v.background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                if (c != null) {
                    setColor(c)
                    setStroke(dp(1), 0x44FFFFFF)
                } else {
                    setColor(0x00000000)              // 没用过的槽位 = 空白，不填色
                    setStroke(dp(1), 0x22FFFFFF)
                }
            }
            v.setOnClickListener(if (c != null) View.OnClickListener { applyColor(c) } else null)
        }
    }

    // ------------------------------------------------------------------ 颜色

    private fun applyColor(c: Int, keepHue: Boolean = false) {
        currentColor = c
        canvasView.brushColor = c
        (currentSwatch.background as? GradientDrawable)?.setColor(c)
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        if (!keepHue) {
            wheel.setHsv(hsv[0], hsv[1], hsv[2])
            colorSquare.hue = hsv[0]
        }
        // ★ 不动 colorSquare.sat/value：色卡上的选择圆圈保持用户点的位置，
        //   只有自己点色卡（ColorSquareView.onTouch 内部）才更新，退出绘制界面才复位。
        suppressRgb = true
        rgbEdit.setText(String.format(Locale.US, "%06X", c and 0xFFFFFF))
        suppressRgb = false
    }

    private fun pushRecent(c: Int) {
        recentColors.remove(c)
        recentColors.add(0, c)
        while (recentColors.size > RECENT_MAX) recentColors.removeAt(recentColors.size - 1)
        rebuildRecent()
        saveRecent()
    }

    private fun loadRecent() {
        val s = getSharedPreferences(PREFS, 0).getString("recent", "") ?: ""
        for (t in s.split(',')) {
            val v = t.toIntOrNull() ?: continue
            if (v !in recentColors) recentColors.add(v)
            if (recentColors.size >= RECENT_MAX) break
        }
    }

    private fun saveRecent() {
        getSharedPreferences(PREFS, 0).edit()
            .putString("recent", recentColors.joinToString(","))
            .apply()
    }

    // ------------------------------------------------------------------ 项目切换 / 撤销重做

    private fun loadDoc(i: Int) {
        if (i !in docs.indices) return
        cur = i
        sfxMode = false
        val d = docs[i]
        d.active = d.active.coerceIn(0, d.layers.lastIndex)
        canvasView.setBitmap(d.layers[d.active].bmp)
        pushLayers(d)
        // 打击特效是精灵图：按 info.yml 的 hitFx [列,行] 在每帧之间画分隔线
        if (docs[i].file == "hit_fx.png") {
            val pack = ResPack(dir)
            canvasView.splitCols = pack.hitFxCols
            canvasView.splitRows = pack.hitFxRows
        } else {
            canvasView.splitCols = 0
            canvasView.splitRows = 0
        }
        widthEdit.setText(docs[i].bmp.width.toString())
        heightEdit.setText(docs[i].bmp.height.toString())
        refreshTabs()
        refreshUndoButtons()
        refreshRightPanel()
        if (layerBox != null) rebuildLayerList()      // 右抽屉的「图层」高亮跟着切
    }

    private fun doUndo() {
        val d = docs.getOrNull(cur) ?: return
        val prev = d.undo.removeLastOrNull() ?: return
        val li = prev.layer.coerceIn(0, d.layers.lastIndex)
        d.redo.add(LayerSnap(li, d.layers[li].bmp.copy(Bitmap.Config.ARGB_8888, true)))
        d.layers[li].bmp = prev.bmp
        d.dirty = true
        dirty = true
        composite(d)
        if (li == d.active) canvasView.replaceBitmap(d.layers[li].bmp)
        pushLayers(d)
        syncSizeInputs()
        refreshUndoButtons()
        rebuildLayerList()
    }

    private fun doRedo() {
        val d = docs.getOrNull(cur) ?: return
        val next = d.redo.removeLastOrNull() ?: return
        val li = next.layer.coerceIn(0, d.layers.lastIndex)
        d.undo.add(LayerSnap(li, d.layers[li].bmp.copy(Bitmap.Config.ARGB_8888, true)))
        d.layers[li].bmp = next.bmp
        d.dirty = true
        dirty = true
        composite(d)
        if (li == d.active) canvasView.replaceBitmap(d.layers[li].bmp)
        pushLayers(d)
        syncSizeInputs()
        refreshUndoButtons()
        rebuildLayerList()
    }

    /** 撤销/重做可能改回一个不同尺寸的图，输入框要跟着更新 */
    private fun syncSizeInputs() {
        val d = docs.getOrNull(cur) ?: return
        widthEdit.setText(d.bmp.width.toString())
        heightEdit.setText(d.bmp.height.toString())
    }

    // ------------------------------------------------------------------ 导入图片 / 音效

    private fun pickImage() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(i, REQ_IMAGE)
    }

    private fun pickAudio() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(i, REQ_AUDIO)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        when (requestCode) {
            REQ_IMAGE -> importImage(data.data!!)
            REQ_AUDIO -> importAudio(data.data!!)
            REQ_EXPORT -> {
                val src = exportTmp ?: return
                try {
                    contentResolver.openOutputStream(data.data!!)?.use { out ->
                        src.inputStream().use { it.copyTo(out, 65536) }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun importImage(uri: Uri) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return
            var sample = 1
            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return
            val d = docs[cur]
            d.pendingBefore = null
            clearUndo(d)
            // 导入图片 = 整张贴图换成这张（图层回到只剩「图层 1」）
            val nb = bmp.copy(Bitmap.Config.ARGB_8888, true)
            d.layers.clear()
            d.layers.add(Layer(nb, "图层 1"))
            d.active = 0
            d.bmp = nb
            d.dirty = true
            dirty = true
            canvasView.setBitmap(nb)
            pushLayers(d)
            refreshUndoButtons()
            rebuildLayerList()
        } catch (_: Exception) {}
    }

    /**
     * 导入音效：**先记到 pending**，点「保存」才真正写入。
     * （这样"删音效后退出选不保存"时文件不会被改动，重新进入还和以前一样）
     */
    private fun importAudio(uri: Uri) {
        pendingSfx[curSfxType] = PendingSfx(uri, audioExt(uri))
        dirty = true
        refreshRightPanel()
    }

    private fun audioExt(uri: Uri): String {
        val mime = contentResolver.getType(uri) ?: ""
        val fromName = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase() ?: ""
        if (fromName in SFX_EXTS) return fromName
        return when {
            mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
            mime.contains("wav") -> "wav"
            else -> "ogg"
        }
    }

    /** 列表里显示的名字：优先显示 pending 的选择，其次显示磁盘上的文件 */
    private fun displaySfxName(type: String): String? {
        if (pendingSfx.containsKey(type)) {
            val p = pendingSfx[type] ?: return null
            if (p.uri == null) return null                  // 待删除
            return p.uri.lastPathSegment?.substringAfterLast('/') ?: "已选择音频"
        }
        return sfxFileName(type)
    }

    private fun sfxFileName(type: String): String? {
        for (e in SFX_EXTS) {
            val f = File(dir, "$type.$e")
            if (f.isFile) return f.name
        }
        return null
    }

    /** 删除音效：同样先记 pending，保存时才删 */
    private fun deleteSfx(type: String) {
        pendingSfx[type] = PendingSfx(null, "")
        dirty = true
        refreshRightPanel()
    }

    /** 保存时统一落盘：删除旧文件 + 复制新音效 */
    private fun applyPendingSfx(): Boolean {
        if (pendingSfx.isEmpty()) return true
        var ok = true
        for ((type, p) in pendingSfx) {
            for (e in SFX_EXTS) File(dir, "$type.$e").takeIf { it.isFile }?.delete()
            val uri = p.uri ?: continue
            try {
                contentResolver.openInputStream(uri)?.use { ins ->
                    File(dir, "$type.${p.ext}").outputStream().use { out -> ins.copyTo(out, 16384) }
                } ?: run { ok = false }
            } catch (_: Exception) {
                ok = false
            }
        }
        if (ok) pendingSfx.clear()
        return ok
    }

    // ------------------------------------------------------------------ 保存 / 退出 / 改名

    private fun saveAll(): Boolean {
        // 没有改动（图片/名字/音效都没动）就不必重复写盘；但仍要补齐 info.yml 的必填字段
        // （老版本导出的包缺 holdAtlas，Phira 会直接报 invalid info.yml / missing field `holdAtlas`）
        if (!dirty && !nameChanged && pendingSfx.isEmpty()) {
            ResPackStore.ensureInfo(dir, packName)
            return true
        }
        var ok = true
        for (d in docs) {
            if (!d.dirty) continue
            try {
                composite(d)                 // 多图层 → 先拍平成整图再存 PNG
                FileOutputStream(File(dir, d.file)).use { out ->
                    d.bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                saveLayerSet(d)              // 图层另存一份（下次进来还能分开编辑）
                d.dirty = false
            } catch (_: Exception) {
                ok = false
            }
        }
        if (!applyPendingSfx()) ok = false
        // 图片写盘之后再补：holdAtlas 要按**新图片尺寸**换算（用户可能刚改过分辨率）
        ResPackStore.ensureInfo(dir, packName)
        if (ok) {
            nameChanged = false
            dirty = false
            refreshUndoButtons()
        }
        return ok
    }

    private fun requestExit() {
        // 不论之前是否按过保存，退出都要弹窗（用户要求）
        KbAlertBuilder(this)
            .setTitle("保存这次改动？")
            .setPositiveButton("保存") { _, _ ->
                saveAll()
                finish()
            }
            .setNegativeButton("不保存") { _, _ -> finish() }
            .setNeutralButton("取消", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        requestExit()
    }

    private fun renameDialog() {
        // 对话框是白底：输入框文字必须是黑色，否则看不见
        val edit = EditText(this).apply {
            setText(packName)
            setSelection(packName.length)
            setTextColor(0xFF000000.toInt())
            setBackgroundColor(0xFFF2F2F2.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        KbAlertBuilder(this)
            .setTitle("资源包名字")
            .setView(edit)
            .setPositiveButton("确定") { _, _ ->
                val n = edit.text.toString().trim()
                if (n.isEmpty()) return@setPositiveButton
                packName = n
                nameView.text = n
                nameChanged = true
                dirty = true
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 导出资源包（先把改动落盘，再打包成 zip 交给系统保存对话框） */
    private fun exportPack() {
        saveAll()
        val safe = packName.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").ifEmpty { "respack" }
        Thread {
            try {
                val zip = File(cacheDir, "export_respack.zip")
                java.util.zip.ZipOutputStream(zip.outputStream()).use { zos ->
                    dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { f ->
                        zos.putNextEntry(java.util.zip.ZipEntry(f.name))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                runOnUiThread {
                    exportTmp = zip
                    val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/octet-stream"
                        putExtra(Intent.EXTRA_TITLE, "$safe.zip")
                    }
                    startActivityForResult(i, REQ_EXPORT)
                }
            } catch (_: Exception) {
            }
        }.start()
    }
}
