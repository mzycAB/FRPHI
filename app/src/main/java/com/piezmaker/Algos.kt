package com.piezmaker

/**
 * 渲染热路径用到的纯算法（不依赖 Android，可单元测试 / 可跑基准）。
 *
 * 这两块以前都写在 View 里、每帧被调用上千次：
 *  - 判定线姿态插值：原来是 `lineKeyframes.sortedBy{}` + 线性扫描，每个音符都要来一遍
 *  - 双押检测：原来是未判定音符两两比较 O(n²)
 */
object LinePose {

    /** 在"按时间升序"的关键帧里插值，结果写进 out[0..2]（不分配对象） */
    fun interpolate(kfs: Array<LineKeyframe>, now: Long, out: FloatArray) {
        if (kfs.isEmpty()) {
            out[0] = 0f; out[1] = 0f; out[2] = 0f
            return
        }
        // 二分：最后一个 timeMs <= now
        var lo = 0
        var hi = kfs.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (kfs[mid].timeMs <= now) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (idx < 0) {
            val f = kfs[0]
            out[0] = f.moveX; out[1] = f.moveY; out[2] = f.rotate
            return
        }
        val a = kfs[idx]
        if (idx + 1 >= kfs.size) {
            out[0] = a.moveX; out[1] = a.moveY; out[2] = a.rotate
            return
        }
        val b = kfs[idx + 1]
        val span = (b.timeMs - a.timeMs).toFloat().coerceAtLeast(1f)
        val t = ((now - a.timeMs).toFloat() / span).coerceIn(0f, 1f)
        out[0] = a.moveX + (b.moveX - a.moveX) * t
        out[1] = a.moveY + (b.moveY - a.moveY) * t
        out[2] = a.rotate + (b.rotate - a.rotate) * t
    }

    /** 参考实现（优化前的写法：线性扫描），只用于对拍测试 */
    fun interpolateNaive(kfs: List<LineKeyframe>, now: Long, out: FloatArray) {
        if (kfs.isEmpty()) {
            out[0] = 0f; out[1] = 0f; out[2] = 0f
            return
        }
        val i = kfs.indexOfLast { it.timeMs <= now }
        if (i < 0) {
            val f = kfs.first(); out[0] = f.moveX; out[1] = f.moveY; out[2] = f.rotate
            return
        }
        val a = kfs[i]
        if (i + 1 >= kfs.size) { out[0] = a.moveX; out[1] = a.moveY; out[2] = a.rotate; return }
        val b = kfs[i + 1]
        val span = (b.timeMs - a.timeMs).toFloat().coerceAtLeast(1f)
        val t = ((now - a.timeMs).toFloat() / span).coerceIn(0f, 1f)
        out[0] = a.moveX + (b.moveX - a.moveX) * t
        out[1] = a.moveY + (b.moveY - a.moveY) * t
        out[2] = a.rotate + (b.rotate - a.rotate) * t
    }
}

object NoteAlgos {

    /**
     * 双押集合：起音时间差 ≤ [win] 的成对音符都算双押（Phira multiple_hint）。
     *
     * @param sortedIdx 按起音时间升序的音符下标
     * @param count 音符总数
     * @param fromMs / toMs 只处理这个时间范围内的音符（远处音符不会画，标了也没用）
     * @param timeOf 取第 i 个音符的起音时间
     * @param isJudged 是否已判定（已判定的不参与）
     */
    fun doubleHints(
        sortedIdx: IntArray,
        count: Int,
        fromMs: Long,
        toMs: Long,
        win: Long,
        timeOf: (Int) -> Long,
        isJudged: (Int) -> Boolean
    ): Set<Int> {
        if (count < 2 || sortedIdx.isEmpty()) return emptySet()

        // 二分第一个 >= fromMs 的位置
        var lo = 0
        var hi = sortedIdx.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (timeOf(sortedIdx[mid]) < fromMs) lo = mid + 1 else hi = mid
        }
        if (lo >= sortedIdx.size || timeOf(sortedIdx[lo]) > toMs) return emptySet()

        // 先取出时间窗口内的**未判定**音符（保持时间升序）。
        // 注意不能边扫边跳已判定的：如果窗口左端那个音符已判定，滑窗指针会越过还没比较的配对，
        // 漏掉"两个未判定音符相邻"的情况（这正是对拍测试抓到的问题）。
        val live = ArrayList<Int>(64)
        var p = lo
        while (p < sortedIdx.size) {
            val idx = sortedIdx[p]
            if (timeOf(idx) > toMs) break
            if (!isJudged(idx)) live.add(idx)
            p++
        }
        if (live.size < 2) return emptySet()

        val set = HashSet<Int>()
        var j = 1
        for (a in 0 until live.size) {
            if (j <= a) j = a + 1
            val ta = timeOf(live[a])
            while (j < live.size && timeOf(live[j]) - ta <= win) {
                set.add(live[a])
                set.add(live[j])
                j++
            }
        }
        return set
    }

    /**
     * 和 [doubleHints] 同算法，但**不为每帧分配任何对象**（渲染热路径用）：
     * 结果直接写进调用方复用的布尔数组，不建 HashSet、不装箱下标。
     *
     * @param mark 长度 = 音符总数，进来时应当全是 false；命中的位置返回 true
     * @param liveBuf 长度 >= 音符总数 的临时 int 缓冲（调用方复用）
     * @return 是否命中任何双押（没有就整块都没被改过）
     */
    fun markDoubleHints(
        sortedIdx: IntArray,
        fromMs: Long,
        toMs: Long,
        win: Long,
        timeOf: (Int) -> Long,
        isJudged: (Int) -> Boolean,
        mark: BooleanArray,
        liveBuf: IntArray
    ): Boolean {
        if (sortedIdx.isEmpty() || mark.isEmpty()) return false

        var lo = 0
        var hi = sortedIdx.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (timeOf(sortedIdx[mid]) < fromMs) lo = mid + 1 else hi = mid
        }
        if (lo >= sortedIdx.size || timeOf(sortedIdx[lo]) > toMs) return false

        var live = 0
        var p = lo
        while (p < sortedIdx.size) {
            val idx = sortedIdx[p]
            if (timeOf(idx) > toMs) break
            if (!isJudged(idx) && live < liveBuf.size) liveBuf[live++] = idx
            p++
        }
        if (live < 2) return false

        var hit = false
        var j = 1
        for (a in 0 until live) {
            if (j <= a) j = a + 1
            val ta = timeOf(liveBuf[a])
            while (j < live && timeOf(liveBuf[j]) - ta <= win) {
                mark[liveBuf[a]] = true
                mark[liveBuf[j]] = true
                hit = true
                j++
            }
        }
        return hit
    }

    /** 参考实现（优化前的 O(n²) 两两比较），只用于对拍测试 */
    fun doubleHintsNaive(
        indices: List<Int>,
        win: Long,
        timeOf: (Int) -> Long,
        isJudged: (Int) -> Boolean
    ): Set<Int> {
        val set = HashSet<Int>()
        val unjudged = indices.filter { !isJudged(it) }
        for (x in unjudged) {
            for (y in unjudged) {
                if (x == y) continue
                if (Math.abs(timeOf(x) - timeOf(y)) <= win) { set.add(x); set.add(y) }
            }
        }
        return set
    }
}

/**
 * 自动校准（纯逻辑，可单元测试）。
 *
 * 实时放置时手容易抖，音符常落在"离粗线一格"的细线上（粗线 = 每拍那条主线，
 * 细线 = 1/hLines 拍的格子）。这里把这类音符吸到最近的粗线上。
 */
object Calibrate {

    /**
     * @param startMs 音符时间
     * @param cellMs 一格多少毫秒（= 60000/bpm/hLines）
     * @param hLines 每拍几格（>=2 才有"细线"）
     * @return 应该吸附到的目标毫秒；-1 表示不用动
     */
    fun target(startMs: Long, cellMs: Double, hLines: Int): Long {
        if (hLines < 2 || !cellMs.isFinite() || cellMs <= 0.0) return -1L
        val idx: Long = Math.round(startMs / cellMs)
        val rem = (((idx % hLines) + hLines) % hLines).toInt()
        val targetIdx = when (rem) {
            1 -> idx - 1                 // 粗线后 1 格 → 回到这条粗线
            hLines - 1 -> idx + 1        // 粗线前 1 格 → 推到下条粗线
            else -> return -1L
        }
        val t = Math.round(targetIdx * cellMs)
        return if (t < 0 || t == startMs) -1L else t
    }
}
