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

    fun root(filesDir: File): File = File(filesDir, "respacks")

    fun list(filesDir: File): List<ResPack> =
        root(filesDir).listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { ResPack.find(it) }
            ?.sortedBy { it.dir.lastModified() }   // 按导入先后，新的在后面
            ?: emptyList()

    fun active(context: Context): ResPack? {
        val dirName = context.getSharedPreferences(PREFS, 0).getString(KEY, null) ?: return null
        return ResPack.find(File(root(context.filesDir), dirName))
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
    }
}
