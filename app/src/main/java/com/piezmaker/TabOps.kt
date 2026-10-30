package com.piezmaker

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
