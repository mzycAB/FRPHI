package com.piezmaker

/**
 * 噪域的**时间索引**：`[appearMs, disappearMs)` 区间查询，给每帧绘制用。
 *
 * ## 为什么需要它
 * 官谱一张能有 **一万四千多块**噪域（ハテ AT = 14355 块，谱长 170 秒）。
 * 没有索引时，`onDraw` 每帧都要把全部区域走一遍判 `aliveAt`，命中的再每块
 * 新建 `DashPathEffect`、拼标题字符串 —— 进编辑页直接卡死 / OOM。
 * 加上它之后，同一时刻真正需要画的只有个位数到几百块（实测 21 秒时 20 块）。
 *
 * ## 实现
 * 按 `appearMs` 升序排一遍，同时算出「消失时间的前缀最大值」`maxEnd`：
 * 因为 `maxEnd` 单调不减，可以用一次二分把所有「已经结束」的块整段跳过，
 * 剩下从二分位置往后扫到第一个 `appearMs > t` 为止。
 *
 * ## 不变量
 * **不假设区域互不相交**。官谱里同一时刻经常几十上百块重叠，
 * 这里只做「粗筛 + 逐个判 aliveAt」，绝不把重叠当异常。
 *
 * 纯 JVM，不碰 Android API，可以直接单测。
 */
class NoiseTimeIndex {

    /** 按 appearMs 升序的区域 */
    private var sorted: Array<NoiseArea> = emptyArray()

    /** [sorted] 每项在原列表里的下标（画「噪域 N」用） */
    private var idxOf: IntArray = IntArray(0)

    /** 按 appearMs 升序的「消失时间前缀最大值」：单调不减，用来二分收左边界 */
    private var maxEnd: DoubleArray = DoubleArray(0)

    // 缓存判断：列表内容没变就不重排
    private var stampSize = -1
    private var stampFirst: NoiseArea? = null
    private var stampLast: NoiseArea? = null

    /** 上一次 [build] 是否真的重排过（自测 / 调试用） */
    var lastBuildRebuilt = false
        private set

    /** 当前索引里的区域数 */
    val size: Int get() = sorted.size

    /**
     * 强制下次 [build] 重建。
     * 列表被**原地** `clear()` + `addAll()` 改过时必须调 —— 那种情况下
     * 「长度 + 首末元素身份」可能碰巧没变，会误判成「内容没改」。
     */
    fun invalidate() {
        stampSize = -1
        stampFirst = null
        stampLast = null
    }

    /** 建立索引。同一份列表重复调用不会重排。 */
    fun build(list: List<NoiseArea>) {
        lastBuildRebuilt = false
        if (list.isEmpty()) {
            if (sorted.isNotEmpty()) lastBuildRebuilt = true
            sorted = EMPTY
            idxOf = IntArray(0)
            maxEnd = DoubleArray(0)
            stampSize = 0
            stampFirst = null
            stampLast = null
            return
        }
        val n = list.size
        // 内容没变就不重建：编辑页每帧都会调 draw，重排一万四千条太贵。
        // 用「长度 + 首末元素身份」判同一份列表：草稿载入 / 撤销都会换掉列表实例。
        if (n == stampSize && list[0] === stampFirst && list[n - 1] === stampLast) return

        val order = ArrayList<Int>(n)
        for (i in 0 until n) order.add(i)
        order.sortBy { list[it].appearMs }

        val s = arrayOfNulls<NoiseArea>(n)
        val ids = IntArray(n)
        val me = DoubleArray(n)
        var run = Double.NEGATIVE_INFINITY
        for (k in 0 until n) {
            val i = order[k]
            val a = list[i]
            s[k] = a
            ids[k] = i
            if (a.disappearMs > run) run = a.disappearMs
            me[k] = run
        }
        @Suppress("UNCHECKED_CAST")
        sorted = s as Array<NoiseArea>
        idxOf = ids
        maxEnd = me
        stampSize = n
        stampFirst = list[0]
        stampLast = list[n - 1]
        lastBuildRebuilt = true
    }

    /**
     * 把 [t] 时刻可见（`appearMs <= t < disappearMs`）的区域填进 [out]，
     * 并把它们在原列表里的下标填进 [outIdx]。两个输出都会被 `clear()`。
     */
    fun query(t: Double, out: MutableList<NoiseArea>, outIdx: IntList) {
        out.clear()
        outIdx.clear()
        val n = sorted.size
        if (n == 0) return
        // 二分：第一个「前缀最大消失时间 > t」的位置，它之前的一定全结束了
        var lo = 0
        var hi = n
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (maxEnd[mid] <= t) lo = mid + 1 else hi = mid
        }
        for (k in lo until n) {
            val a = sorted[k]
            if (a.appearMs > t) break     // 后面出现的更晚，全都还没到
            if (t < a.disappearMs) {
                out.add(a)
                outIdx.add(idxOf[k])
            }
        }
    }

    /** 只要区域、不管下标时的便捷重载 */
    fun query(t: Double, out: MutableList<NoiseArea>) = query(t, out, NO_IDX)

    /** 极简 int 列表：避免给每帧绘制再造 `ArrayList<Int>` 装箱对象 */
    class IntList(cap: Int = 8) {
        var arr = IntArray(if (cap < 8) 8 else cap)
            private set
        var size = 0
            private set
        fun clear() { size = 0 }
        fun add(v: Int) {
            if (size == arr.size) arr = arr.copyOf(arr.size * 2)
            arr[size++] = v
        }
        operator fun get(i: Int): Int = arr[i]
    }

    private companion object {
        val EMPTY: Array<NoiseArea> = arrayOf()
        val NO_IDX = IntList(4)
    }
}
