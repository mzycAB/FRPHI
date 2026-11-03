package com.piezmaker

import android.content.Context
import java.io.File

/**
 * Phira 资源包（respack）。
 *
 * 目录结构（与 Phira 一致）：
 *   info.yml
 *   click.png / hold.png / flick.png / drag.png
 *   click_mh.png / hold_mh.png / flick_mh.png / drag_mh.png
 *   hit_fx.png（精灵图，帧数由 info.yml 的 hitFx: [列, 行] 决定）
 *   click / drag / flick 音效（.ogg 或 .wav / .mp3）
 *
 * 只依赖 java.io.File 与 Bitmap，桌面端也能编同一份代码。
 */
class ResPack(val dir: File) {

    /** info.yml 的顶层 key: value（不处理嵌套结构，够用就行） */
    private val meta: Map<String, String> = run {
        val f = File(dir, "info.yml")
        if (!f.isFile) emptyMap() else runCatching {
            f.readText(Charsets.UTF_8).lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("#") && it.first() != ' ' && it.first() != '\t' }
                .mapNotNull { line ->
                    val i = line.indexOf(':')
                    if (i <= 0) null
                    else line.substring(0, i).trim() to line.substring(i + 1).trim().trim('"', '\'')
                }.toMap()
        }.getOrDefault(emptyMap())
    }

    val name: String = meta["name"]?.takeIf { it.isNotEmpty() } ?: dir.name
    val author: String = meta["author"].orEmpty()

    private val fxPair: List<Int> = meta["hitFx"]?.let { intsOf(it) } ?: emptyList()
    val hitFxCols: Int = fxPair.getOrNull(0) ?: 8
    val hitFxRows: Int = fxPair.getOrNull(1) ?: 7
    /** 秒 → 毫秒；Phira 默认 0.5s */
    val hitFxDurationMs: Long = ((meta["hitFxDuration"]?.toFloatOrNull() ?: 0.5f) * 1000f).toLong()
    val hitFxScale: Float = meta["hitFxScale"]?.toFloatOrNull() ?: 1f

    /** 音效文件：优先 .ogg，其次 .wav / .mp3 */
    fun sfx(n: String): File? =
        listOf(".ogg", ".wav", ".mp3").map { File(dir, n + it) }.firstOrNull { it.isFile }

    companion object {
        val NOTE_FILES = listOf(
            "click.png", "hold.png", "flick.png", "drag.png",
            "click_mh.png", "hold_mh.png", "flick_mh.png", "drag_mh.png"
        )

        private fun intsOf(s: String): List<Int> =
            Regex("-?\\d+").findAll(s).map { it.value.toInt() }.toList()

        fun isValid(dir: File): Boolean =
            File(dir, "info.yml").isFile &&
                File(dir, "hit_fx.png").isFile &&
                NOTE_FILES.all { File(dir, it).isFile }

        /** 在 dir 及其下一层子目录里找一个合法资源包（zip 里常带一层同名文件夹） */
        fun find(dir: File, depth: Int = 1): ResPack? {
            if (isValid(dir)) return ResPack(dir)
            if (depth <= 0) return null
            dir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                find(sub, depth - 1)?.let { return it }
            }
            return null
        }
    }
}

/** 已导入资源包的位置与"当前使用哪一个"（目录名存在 SharedPreferences） */
object ResPackStore {
    private const val PREFS = "frphi_respack"
    private const val KEY = "dir"
    private const val KEY_ORDER = "order"
    private const val SEP = "\u0001"

    /** 自带（内置 assets）资源包在排序里的固定 key */
    const val BUILTIN_KEY = "__builtin__"

    fun root(filesDir: File): File = File(filesDir, "respacks")

    /**
     * 目录里所有资源包。除了完整包，也包含"空白资源包项目"
     * （只有 info.yml，暂时没有贴图/音效，等以后进编辑页再填）。
     */
    fun all(filesDir: File): List<ResPack> =
        root(filesDir).listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                ResPack.find(dir) ?: if (File(dir, "info.yml").isFile) ResPack(dir) else null
            }
            ?: emptyList()

    /** 旧的按导入先后排序（内部/兼容用） */
    fun list(filesDir: File): List<ResPack> =
        all(filesDir).sortedBy { it.dir.lastModified() }

    /**
     * 资源包窗口里的显示顺序：自带包 + 所有已导入/空白包。
     * 返回 key → ResPack（自带包的 ResPack 为 null）。
     * 顺序存在 SharedPreferences；新导入的包没有历史位置时放在最后。
     */
    fun ordered(context: Context, filesDir: File): List<Pair<String, ResPack?>> {
        val packs = all(filesDir)
        val byKey = packs.associateBy { it.dir.name }
        val prefs = context.getSharedPreferences(PREFS, 0)
        val saved = prefs.getString(KEY_ORDER, null)
            ?.split(SEP)?.filter { it.isNotEmpty() } ?: emptyList()
        val keys = ArrayList<String>()
        for (k in saved) {
            if (k == BUILTIN_KEY || byKey.containsKey(k)) {
                if (k !in keys) keys.add(k)
            }
        }
        // 第一次用 / 老版本没存过顺序：自带包放最前面
        if (BUILTIN_KEY !in keys) keys.add(0, BUILTIN_KEY)
        // 新导入但还没进顺序表的：按导入时间接在最后
        for (p in packs.sortedBy { it.dir.lastModified() }) {
            if (p.dir.name !in keys) keys.add(p.dir.name)
        }
        return keys.map { it to if (it == BUILTIN_KEY) null else byKey[it] }
    }

    fun saveOrder(context: Context, keys: List<String>) {
        context.getSharedPreferences(PREFS, 0).edit()
            .putString(KEY_ORDER, keys.joinToString(SEP))
            .apply()
    }

    /** 把一个 key 放到所有项目最后（复制/新建空白包都用它） */
    fun appendToEnd(context: Context, filesDir: File, key: String) {
        val keys = ordered(context, filesDir).map { it.first }.toMutableList()
        keys.remove(key)
        keys.add(key)
        saveOrder(context, keys)
    }

    fun active(context: Context): ResPack? {
        val dirName = context.getSharedPreferences(PREFS, 0).getString(KEY, null) ?: return null
        val dir = File(root(context.filesDir), dirName)
        // 空白资源包项目只有 info.yml；也允许选中（渲染时缺的贴图/音效会自动回退到内置 assets）
        return ResPack.find(dir) ?: if (dir.isDirectory && File(dir, "info.yml").isFile) ResPack(dir) else null
    }

    fun setActive(context: Context, pack: ResPack?) {
        context.getSharedPreferences(PREFS, 0).edit()
            .putString(KEY, pack?.let { it.dir.name })
            .apply()
    }

    /** 删除已导入的资源包；若删的正是当前包，自动切回默认 */
    fun remove(context: Context, pack: ResPack) {
        if (active(context)?.dir?.name == pack.dir.name) setActive(context, null)
        pack.dir.deleteRecursively()
        // 顺序表里也删掉，避免以后同名目录复用时继承旧位置
        val keys = ordered(context, context.filesDir).map { it.first }.toMutableList()
        keys.remove(pack.dir.name)
        saveOrder(context, keys)
    }

    private fun uniqueDir(root: File, base: String): File {
        var name = base.ifEmpty { "pack" }
        var i = 2
        while (File(root, name).exists()) {
            name = base + "_" + i
            i++
        }
        return File(root, name)
    }

    /** 新建一个空白资源包项目（只有 info.yml），放在列表最后 */
    fun createBlank(context: Context, filesDir: File): ResPack {
        val root = root(filesDir)
        root.mkdirs()
        val dir = uniqueDir(root, "blank_" + System.currentTimeMillis())
        dir.mkdirs()
        var n = 1
        while (all(filesDir).any { it.name == "资源包$n" }) n++
        File(dir, "info.yml").writeText(
            "name: 资源包$n\nauthor: FRPHI\n", Charsets.UTF_8
        )
        val pack = ResPack(dir)
        appendToEnd(context, filesDir, dir.name)
        return pack
    }

    /** 复制一个已导入/空白资源包（目录整个复制），放在列表最后 */
    fun copyPack(context: Context, filesDir: File, src: ResPack): ResPack? {
        val root = root(filesDir)
        root.mkdirs()
        val target = uniqueDir(root, src.dir.name)
        return try {
            src.dir.copyRecursively(target, overwrite = false)
            val pack = ResPack(target)
            appendToEnd(context, filesDir, target.name)
            pack
        } catch (_: Exception) {
            target.deleteRecursively()
            null
        }
    }

    /**
     * 把内置 assets 资源包落盘成一个真正的资源包目录。
     * 复制自带包 / 导出自带包时用。
     */
    fun materializeBuiltin(context: Context, target: File, name: String = "默认"): Boolean {
        return try {
            target.deleteRecursively()
            target.mkdirs()
            val assets = context.assets
            for (n in ResPack.NOTE_FILES) {
                assets.open("notes/$n").use { ins ->
                    File(target, n).outputStream().use { out -> ins.copyTo(out, 16384) }
                }
            }
            // 打击特效精灵图（以前漏复制，复制出来的包里 hit_fx.png 是空的）
            try {
                assets.open("notes/hit_fx.png").use { ins ->
                    File(target, "hit_fx.png").outputStream().use { out -> ins.copyTo(out, 16384) }
                }
            } catch (_: Exception) {}
            // 音效：蓝键 = click；黄键与蓝键同一音效（复制一份）；红键 = flick；长条 = drag 音效
            val sfxCopy = listOf(
                "sounds/click.ogg" to "click.ogg",
                "sounds/click.ogg" to "drag.ogg",
                "sounds/flick.ogg" to "flick.ogg",
                "sounds/drag.ogg" to "hold.ogg"
            )
            for ((src, dst) in sfxCopy) {
                try {
                    assets.open(src).use { ins ->
                        File(target, dst).outputStream().use { out -> ins.copyTo(out, 16384) }
                    }
                } catch (_: Exception) {}
            }
            File(target, "info.yml").writeText(
                "name: $name\nauthor: FRPHI\nhitFx: [8, 7]\nhitFxDuration: 0.46\nhitFxScale: 0.9\n",
                Charsets.UTF_8
            )
            true
        } catch (_: Exception) {
            target.deleteRecursively()
            false
        }
    }

    /** 复制自带资源包（先落盘，再当作普通包放到最后） */
    fun copyBuiltin(context: Context, filesDir: File): ResPack? {
        val root = root(filesDir)
        root.mkdirs()
        var n = 1
        while (all(filesDir).any { it.name == "默认 副本$n" }) n++
        val target = uniqueDir(root, "default_copy_" + System.currentTimeMillis())
        if (!materializeBuiltin(context, target, "默认 副本$n")) return null
        val pack = ResPack(target)
        appendToEnd(context, filesDir, target.name)
        return pack
    }
}
