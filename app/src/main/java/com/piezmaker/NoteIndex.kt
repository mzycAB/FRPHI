package com.piezmaker

/**
 * 音符按判定线分组的下标（每组内按 startMs 升序），纯 JVM 可单测。
 *
 * ★ 以前在 RecordView.rebuildNoteIndex() 里用 `while (ids[k] != lineId) k++`
 *   线性找线：741 线 / 44 万音符约 3.26 亿次比较（两遍各 1.63 亿）。
 *   现在全程 HashMap 定位 slot，O(notes)：
 *   741 线 / 44 万音符 ≈ 88 万次查找，约 370 倍。
 *
 * 末尾的 slot 表原先只给 indexesForLine() 查询用，构建时却没用 —— 这里补上。
 * 线少（≤8）时仍返回 null，让调用方走更便宜的线性扫，与原策略一致。
 */
object NoteIndex {

    class Result(
        val ids: IntArray,
        val arrays: Array<IntArray>,
        /** 线 id → 数组下标；线数 ≤ 8 时为 null */
        val slot: HashMap<Int, Int>?
    )

    fun build(notes: List<RecordedNote>, sort: (IntArray) -> Unit): Result {
        val n = notes.size
        val slot = HashMap<Int, Int>(if (n < 8) 8 else 16)
        val ids = ArrayList<Int>(16)
        val counts = ArrayList<Int>(16)

        // 第一遍：counting sort 的计数（HashMap，不再逐线线性扫）
        for (i in 0 until n) {
            val id = notes[i].lineId
            val k = slot[id]
            if (k == null) {
                slot[id] = ids.size
                ids.add(id)
                counts.add(1)
            } else {
                counts[k] = counts[k] + 1
            }
        }

        // 第二遍：原生 IntArray 填下标，零装箱
        val arrays = Array(ids.size) { IntArray(counts[it]) }
        val fill = IntArray(ids.size)
        for (i in 0 until n) {
            val k = slot[notes[i].lineId] ?: continue
            arrays[k][fill[k]++] = i
        }
        for (a in arrays) if (a.size > 1) sort(a)

        return Result(ids.toIntArray(), arrays, if (ids.size > 8) slot else null)
    }
}
