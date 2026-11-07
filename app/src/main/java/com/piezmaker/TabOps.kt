package com.piezmaker

import java.io.File

/**
 * 标签列表的纯逻辑（不依赖 Android，可直接单元测试）。
 *
 * 这里只放"算下标"这种容易写错的部分——之前删除标签的 bug 就是顺序 + 下标算错：
 * 先 removeAt 再 saveCurrent，会把界面上（被删标签的）内容写进"顶上来"的那个标签。
 */
object TabOps {

    /**
     * 删除第 [index] 个标签之后，应该选中哪一个。
     *
     *  - 删的是当前标签 → 顶上来的那个（删的是最后一个就往前挪一个）
     *  - 删的在当前标签之前 → 当前标签整体前移一位
     *  - 删的在当前标签之后 → 当前标签不动
     *
     * @param size 删除**前**的标签数量（>= 1）
     * @param index 被删除的下标
     * @param current 删除前的选中下标
     * @return 删除后应该选中的下标（调用方需保证删除后仍有标签，即 size > 1）
     */
    fun afterDelete(size: Int, index: Int, current: Int): Int {
        require(size >= 1) { "size 必须 >= 1" }
        if (size == 1) return 0
        require(index in 0 until size) { "index 越界：$index / $size" }
        require(current in 0 until size) { "current 越界：$current / $size" }
        return when {
            index == current -> index.coerceAtMost(size - 2)
            index < current -> current - 1
            else -> current
        }
    }

    /**
     * 标签 id 会直接拼进文件路径（`drafts/<id>.json`、`imported/<id>/`），必须干净：
     * 空 id → 变成 `imported/` 目录本身，删一个标签会把所有导入素材清空；
     * 带 `/` 或 `..` → 删到目录外去。这里只放行 UUID 那种字符。
     */
    fun isSafeId(id: String): Boolean = id.matches(Regex("[A-Za-z0-9_-]{8,64}"))
}

/**
 * 项目文件存放位置（草稿 / 导入素材 / 项目音频图片）。
 *
 * 优先放在 **应用专属外部目录** `Android/data/com.piezmaker/files/...`：
 * 普通文件管理器 / 电脑 USB 能看到，卸载应用时系统会一起删除，也不需要额外存储权限。
 * 外部存储不可用时退回内部 `filesDir`。
 */
object ProjectStorage {
    private lateinit var rootDir: File
    private lateinit var legacyDir: File

    /** @param root 外部应用目录（`getExternalFilesDir(null)`），null/不可用时传 filesDir
     *  @param legacy 旧版内部目录（filesDir），用于启动时把旧项目搬过来 */
    fun init(root: File, legacy: File) {
        rootDir = root
        legacyDir = legacy
    }

    val root: File get() = rootDir

    fun draftsDir(): File = File(rootDir, "drafts")
    fun draftFile(id: String): File = File(draftsDir(), "$id.json")
    fun importedRoot(): File = File(rootDir, "imported")
    fun importedDir(id: String): File = File(importedRoot(), id)
    fun mediaRoot(): File = File(rootDir, "project_media")
    fun mediaDir(id: String): File = File(mediaRoot(), id)
    fun mediaTrashRoot(): File = File(rootDir, "project_media_trash")
    fun mediaTrashDir(id: String): File = File(mediaTrashRoot(), id)

    /** 旧版内部路径 → 新的外部路径（只处理 file:// 前缀，content:// 原样返回） */
    fun remapLegacyUri(uri: String?): String? {
        if (uri == null) return null
        val from = "file://" + legacyDir.absolutePath + "/"
        if (!uri.startsWith(from)) return uri
        val rest = uri.removePrefix(from)
        // 只有 project_media / imported 搬到了外部目录；
        // 旧的 music/ 目录仍留在内部，不能改路径，否则会指向不存在的文件。
        if (!rest.startsWith("project_media/") && !rest.startsWith("imported/")) return uri
        // 迁移失败时外部文件可能不存在：继续用旧路径，别让项目直接失效
        if (!File(rootDir, rest).isFile) return uri
        return "file://" + rootDir.absolutePath + "/" + rest
    }

    /**
     * 第一次升级时把内部目录里的旧项目搬到外部目录。
     * 搬成功后删掉内部旧目录；失败则保留旧文件，下次启动再试。
     */
    fun migrateLegacy() {
        if (rootDir.absolutePath == legacyDir.absolutePath) return
        for (name in listOf("drafts", "imported", "project_media", "project_media_trash")) {
            val src = File(legacyDir, name)
            if (!src.exists()) continue
            val dst = File(rootDir, name)
            if (copyDir(src, dst)) {
                try { src.deleteRecursively() } catch (_: Exception) {}
            }
        }
    }

    /** 只补缺失文件，不覆盖外部目录里已有的（避免把新内容盖回旧内容） */
    private fun copyDir(src: File, dst: File): Boolean {
        val list = src.listFiles() ?: return true
        if (!dst.exists() && !dst.mkdirs()) return false
        for (f in list) {
            val to = File(dst, f.name)
            if (f.isDirectory) {
                if (!copyDir(f, to)) return false
            } else if (!to.exists()) {
                try {
                    f.copyTo(to, overwrite = false)
                } catch (_: Exception) {
                    return false
                }
            }
        }
        return true
    }
}

