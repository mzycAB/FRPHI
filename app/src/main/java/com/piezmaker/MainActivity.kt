package com.piezmaker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import android.widget.Toast

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
    var hLines = "4"
    var vLines = "8"
    var music: String? = null
    var image: String? = null

    fun toJson(): org.json.JSONObject {
        val o = org.json.JSONObject()
        o.put("id", id); o.put("name", name); o.put("composer", composer); o.put("charter", charter)
        o.put("level", level); o.put("bpm", bpm); o.put("speed", speed); o.put("linePos", linePos)
        o.put("delay", delay); o.put("hLines", hLines); o.put("vLines", vLines)
        music?.let { o.put("music", it) }; image?.let { o.put("image", it) }
        return o
    }

    companion object {
        fun fromJson(o: org.json.JSONObject): TabData {
            val t = TabData()
            t.id = o.optString("id", t.id)
            t.name = o.optString("name", ""); t.composer = o.optString("composer", ""); t.charter = o.optString("charter", "")
            t.level = o.optString("level", ""); t.bpm = o.optString("bpm", "120"); t.speed = o.optString("speed", "1.0")
            t.linePos = o.optString("linePos", "60"); t.delay = o.optString("delay", "0")
            t.hLines = o.optString("hLines", "4"); t.vLines = o.optString("vLines", "8")
            if (o.has("music")) t.music = o.optString("music")
            if (o.has("image")) t.image = o.optString("image")
            return t
        }
    }
}

/** 设置页：标签栏多项目 + 选素材填信息（可全部留空直接进入编辑） */
class MainActivity : Activity() {

    private companion object {
        const val REQ_MUSIC = 1001
        const val REQ_IMAGE = 1002
        const val REQ_CALIB = 1003
        const val REQ_IMPORT = 1004
    }

    private lateinit var tabBar: LinearLayout
    private lateinit var editName: EditText
    private lateinit var editComposer: EditText
    private lateinit var editCharter: EditText
    private lateinit var editLevel: EditText
    private lateinit var editBpm: EditText
    private lateinit var editSpeed: EditText
    private lateinit var editLinePos: EditText
    private lateinit var editDelay: EditText
    private lateinit var editHLines: EditText
    private lateinit var editVLines: EditText
    private lateinit var txtMusic: TextView
    private lateinit var txtImage: TextView

    private val tabs = mutableListOf<TabData>()
    private var current = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        tabBar = findViewById(R.id.tabBar)
        editName = findViewById(R.id.editName)
        editComposer = findViewById(R.id.editComposer)
        editCharter = findViewById(R.id.editCharter)
        editLevel = findViewById(R.id.editLevel)
        editBpm = findViewById(R.id.editBpm)
        editSpeed = findViewById(R.id.editSpeed)
        editLinePos = findViewById(R.id.editLinePos)
        editDelay = findViewById(R.id.editDelay)
        editHLines = findViewById(R.id.editHLines)
        editVLines = findViewById(R.id.editVLines)
        txtMusic = findViewById(R.id.txtMusic)
        txtImage = findViewById(R.id.txtImage)

        findViewById<View>(R.id.btnPickMusic).setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
            }
            startActivityForResult(i, REQ_MUSIC)
        }
        findViewById<View>(R.id.btnPickImage).setOnClickListener {
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

        loadTabs()
        if (tabs.isEmpty()) tabs.add(TabData())
        restore(tabs[current])
        renderTabs()
    }

    /** 从 SharedPreferences 恢复标签列表（退出重进保留项目与草稿 id） */
    private fun loadTabs() {
        try {
            val raw = getSharedPreferences("frphi_tabs", 0).getString("tabs", null) ?: return
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) tabs.add(TabData.fromJson(arr.getJSONObject(i)))
        } catch (_: Exception) {}
    }

    /** 保存标签列表 */
    private fun saveTabs() {
        try {
            val arr = org.json.JSONArray()
            for (t in tabs) arr.put(t.toJson())
            getSharedPreferences("frphi_tabs", 0).edit().putString("tabs", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    // ---------------- 标签栏 ----------------
    /** 标签栏（浏览器式）：显示项目标签 + ＋ 新建；点标签切换、长按标签删除 */
    private fun renderTabs() {
        tabBar.removeAllViews()
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
            chip.setOnClickListener { switchTab(i) }
            chip.setOnLongClickListener {
                android.app.AlertDialog.Builder(this)
                    .setTitle("删除标签")
                    .setMessage("删除「$label」？该项目的配置将一并清除。")
                    .setPositiveButton("删除") { _, _ ->
                        if (tabs.size <= 1) { toast("至少保留一个标签"); return@setPositiveButton }
                        tabs.removeAt(i)
                        if (current > i) current--
                        if (current >= tabs.size) current = tabs.size - 1
                        saveCurrent()
                        restore(tabs[current])
                        saveTabs()
                        renderTabs()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
            if (i == current) chip.setBackgroundResource(R.drawable.bg_btn_outline)
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
    }

    private fun switchTab(i: Int) {
        if (i == current) return
        saveCurrent()
        saveTabs()
        current = i
        restore(tabs[i])
        renderTabs()
    }

    private fun saveCurrent() {
        val t = tabs[current]
        t.name = editName.text.toString().trim()
        t.composer = editComposer.text.toString().trim()
        t.charter = editCharter.text.toString().trim()
        t.level = editLevel.text.toString().trim()
        t.bpm = editBpm.text.toString().trim()
        t.speed = editSpeed.text.toString().trim()
        t.linePos = editLinePos.text.toString().trim()
        t.delay = editDelay.text.toString().trim()
        t.hLines = editHLines.text.toString().trim()
        t.vLines = editVLines.text.toString().trim()
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
        editHLines.setText(t.hLines.ifEmpty { "4" })
        editVLines.setText(t.vLines.ifEmpty { "8" })
        musicUri = t.music?.let { runCatching { Uri.parse(it) }.getOrNull() }
        imageUri = t.image?.let { runCatching { Uri.parse(it) }.getOrNull() }
        txtMusic.text = "音频：${musicUri?.lastPathSegment ?: "未选择（可不填）"}"
        txtImage.text = "背景：${imageUri?.lastPathSegment ?: "未选择（可不填）"}"
    }

    private var musicUri: Uri? = null
    private var imageUri: Uri? = null

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == REQ_CALIB) {
            val v = data.getIntExtra("delay", -1)
            if (v >= 0) editDelay.setText(v.toString())
            return
        }
        if (requestCode == REQ_IMPORT) {
            importPez(data.data ?: return)
            return
        }
        val uri = data.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        when (requestCode) {
            REQ_MUSIC -> {
                musicUri = uri
                txtMusic.text = "音频：${lastSegment(uri)}"
                tabs[current].music = uri.toString()
            }
            REQ_IMAGE -> {
                imageUri = uri
                txtImage.text = "背景：${lastSegment(uri)}"
                tabs[current].image = uri.toString()
            }
        }
    }

    private fun lastSegment(uri: Uri): String = uri.lastPathSegment ?: "已选择"

    /** 导入 pez/phi 谱面：解析 RPE JSON → 新建标签 + 写入草稿（音符/曲名/BPM/横线数） */
    private fun importPez(uri: Uri) {
        try {
            val zis = java.util.zip.ZipInputStream(contentResolver.openInputStream(uri))
            var jsonStr: String? = null
            while (true) {
                val ze = zis.nextEntry ?: break
                if (ze.name.endsWith(".json") && !ze.isDirectory) {
                    jsonStr = zis.readBytes().toString(Charsets.UTF_8)
                    break
                }
            }
            zis.close()
            val doc = org.json.JSONObject(jsonStr ?: run { toast("未找到谱面 JSON"); return })
            val t = TabData()
            val meta = doc.optJSONObject("META")
            t.name = meta?.optString("title", "导入谱面") ?: "导入谱面"
            t.composer = meta?.optString("artist", "") ?: ""
            t.charter = meta?.optString("charter", "") ?: ""
            t.level = meta?.optString("level", "12") ?: "12"
            val bpm = doc.optJSONArray("BPMList")?.optJSONObject(0)?.optDouble("bpm", 120.0) ?: 120.0
            t.bpm = if (bpm == Math.floor(bpm)) bpm.toLong().toString() else bpm.toString()

            fun msOf(a: org.json.JSONArray): Long {
                val d = a.optInt(2, 1).coerceAtLeast(1)
                val beat = a.optInt(0) + a.optInt(1).toDouble() / d
                return (beat * 60000.0 / bpm).toLong()
            }
            val notes = org.json.JSONArray()
            var hLines = 4
            val lines = doc.optJSONArray("judgeLineList")
            if (lines != null) for (li in 0 until lines.length()) {
                val line = lines.getJSONObject(li)
                val arr = line.optJSONArray("notes") ?: continue
                for (ni in 0 until arr.length()) {
                    val o = arr.getJSONObject(ni)
                    val st = o.optJSONArray("startTime") ?: continue
                    val et = o.optJSONArray("endTime") ?: st
                    val d0 = st.optInt(2, 4)
                    if (d0 in 1..64 && hLines == 4) hLines = d0
                    val posX = o.optDouble("positionX", 0.0)
                    val n = org.json.JSONObject()
                    n.put("t", o.optInt("type", 1))
                    n.put("s", msOf(st))
                    n.put("e", msOf(et))
                    n.put("x", (posX / 675.0).coerceIn(-1.0, 1.0))
                    n.put("p", 1)
                    notes.put(n)
                }
            }
            t.hLines = hLines.toString()

            // 写草稿（RecordView.loadDraft 格式）
            val draft = org.json.JSONObject()
            draft.put("notes", notes).put("kf", org.json.JSONArray())
            draft.put("mx", 0).put("my", 0).put("rot", 0).put("spd", 1.0)
            val f = File(filesDir, "drafts/${t.id}.json")
            f.parentFile?.mkdirs()
            f.writeText(draft.toString())

            // 单项目模式：导入直接替换当前项目
            tabs.clear()
            tabs.add(t)
            current = 0
            restore(t)
            saveTabs()
            toast("导入成功：${t.name}（${notes.length()} 个音符）")
        } catch (e: Exception) {
            toast("导入失败：${e.message}")
        }
    }

    private fun startRecord() {
        saveCurrent()
        val t = tabs[current]
        val name = t.name.ifEmpty { "未命名" }
        val composer = t.composer
        val charter = t.charter
        val level = t.level          // 未填写难度就不填
        val bpm = t.bpm.toDoubleOrNull()?.takeIf { it > 0 } ?: 120.0
        val speed = t.speed.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
        val linePos = t.linePos.toIntOrNull()?.coerceIn(0, 100) ?: 60
        val delay = t.delay.toIntOrNull() ?: 0
        val h = t.hLines.toIntOrNull()?.coerceAtLeast(1) ?: 4
        val v = t.vLines.toIntOrNull()?.coerceAtLeast(1) ?: 8
        // 素材可空：音频空 → 进入但无播放；背景空 → 纯色底
        val mu = t.music?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: Uri.EMPTY
        val iu = t.image?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: Uri.EMPTY

        val meta = ChartMeta(name, composer, charter, level, bpm, speed, linePos, delay, h, v, mu, iu, 0f, 0f, 0f, t.id)
        saveTabs()
        startActivity(Intent(this, RecordActivity::class.java).putExtra("meta", meta))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}