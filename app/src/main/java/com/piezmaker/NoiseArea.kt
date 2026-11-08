package com.piezmaker

import org.json.JSONArray
import org.json.JSONObject

/**
 * 噪域（RPE `blockAreaList`）—— 数据与编辑支持。
 *
 * 噪域是 RPE 1.5.0 起加入的一组「屏幕区域块」，Phira Pro / Re:PhiEdit Next 会在块内叠加
 * 噪点 / 干扰特效。本项目不实现那层着色器，但要做到：
 *  1. 把 `blockAreaList` 完整读进来（不报错、不丢字段）；
 *  2. 在编辑器里画出区域框 + 激活区间 + 事件概览，并支持拖四角、矩形关键帧、
 *     显示/深浅、新建/复制/删除、图层（layerId）；
 *  3. 导出时**原样写回**，不认识的字段也保留（[raw] 保留原始 JSON，[toJson] 只做字段补全）。
 *
 * 编辑结果会转成矩形关键帧（[rectKeys]），导出时换算成官方 move/scale 事件；
 * 私有扩展（显示/深浅/毫秒关键帧）写进 `_frphi`，其他播放器会忽略。
 *
 * 字段来源（节拍一律是 RPE 的 `[i, n, d]` = i + n/d）：
 *  - 区域：`Name` `bottomLeft{x,y}` `topRight{x,y}` `appearTime` `disappearTime` `isInvert` `layerId`
 *  - 激活：`activeIntervals[] { startTime, endTime, readyFlash }`
 *  - 事件轨道：`moveXEvents` `moveYEvents` `rotateEvents` `scaleXEvents` `scaleYEvents`，
 *    每条事件 `{ startTime, endTime, start, end, easingType, easingLeft, easingRight, bezier, bezierPoints, anchor{x,y} }`
 */
class NoiseArea(
    val name: String = "",
    /** 区域左下角（RPE 世界坐标：x ∈ ±675，y ∈ ±450，屏幕向上为正） */
    val left: Float = -250f,
    val bottom: Float = -150f,
    val right: Float = 250f,
    val top: Float = 150f,
    /** 生命周期的毫秒区间（已按 BPM 换算；-1 = 谱面开始前就存在，Long.MAX_VALUE = 一直存在） */
    val appearMs: Double = 0.0,
    val disappearMs: Double = Double.MAX_VALUE,
    /** 叠加混合是否取反（RPE `isInvert`，即官谱的 isSubtract） */
    val invert: Boolean = false,
    val intervals: List<Interval> = emptyList(),
    val tracks: Map<String, List<NoiseEvent>> = emptyMap(),
    /** 皮肤层 id（RPE 2.3.0 的 layerId；空 = 默认层） */
    val layerId: String = "",
    /**
     * 编辑过的**矩形关键帧**（毫秒 → 世界坐标四边升序 l/b/r/t）。
     * 空 = 用静态区域 + 原始 move/scale/rotate 事件（导入的谱面都是这个状态）。
     */
    val rectKeys: List<RectKey> = emptyList(),
    /** 是否显示（私有的 FRPHI 扩展，导出时写进 `_frphi`，不认识的播放器会忽略） */
    val visible: Boolean = true,
    /**
     * 警示区（true）= 只闪不掐手；真噪域（false）= 激活时掐真实手指。
     * 只影响真实手指的断触，Autoplay 的自动判定本来就不受噪域影响。
     */
    val warning: Boolean = false,
    /** 被编辑过：导出/存草稿时要按当前字段重建 JSON（raw 里的旧数据已经过期） */
    val dirty: Boolean = false,
    /** **原始 JSON**：导出时优先原样写回，保证 FRPHI 不认识的字段也不会被吃掉 */
    var raw: JSONObject? = null
) {
    /** 一个矩形关键帧（世界坐标，y 向上为正） */
    class RectKey(val timeMs: Long, val left: Float, val bottom: Float, val right: Float, val top: Float)

    /**
     * [nowMs] 时刻的矩形（l/b/r/t，世界坐标）。没有关键帧时就是静态区域。
     * 关键帧之间**线性插值**；首帧之前取首帧、末帧之后取末帧（和 Phira 的 hold 语义一致）。
     */
    fun rectAt(nowMs: Double): FloatArray {
        if (rectKeys.isEmpty()) return floatArrayOf(left, bottom, right, top)
        val k0 = rectKeys[0]
        if (nowMs <= k0.timeMs) return floatArrayOf(k0.left, k0.bottom, k0.right, k0.top)
        val last = rectKeys[rectKeys.size - 1]
        if (nowMs >= last.timeMs) return floatArrayOf(last.left, last.bottom, last.right, last.top)
        var lo = 0
        var hi = rectKeys.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (rectKeys[mid].timeMs <= nowMs) lo = mid else hi = mid
        }
        val a = rectKeys[lo]
        val b = rectKeys[hi]
        val span = (b.timeMs - a.timeMs).toDouble()
        if (span <= 1e-9) return floatArrayOf(b.left, b.bottom, b.right, b.top)
        val p = ((nowMs - a.timeMs) / span).coerceIn(0.0, 1.0).toFloat()
        return floatArrayOf(
            a.left + (b.left - a.left) * p,
            a.bottom + (b.bottom - a.bottom) * p,
            a.right + (b.right - a.right) * p,
            a.top + (b.top - a.top) * p
        )
    }

    /** 复制一份（改关键帧/显示/深浅用；调用方负责把 [raw] 更新成重建后的 JSON） */
    fun edited(
        rectKeys: List<RectKey> = this.rectKeys,
        visible: Boolean = this.visible,
        warning: Boolean = this.warning,
        tracks: Map<String, List<NoiseEvent>> = this.tracks
    ): NoiseArea = NoiseArea(
        name = name, left = left, bottom = bottom, right = right, top = top,
        appearMs = appearMs, disappearMs = disappearMs, invert = invert,
        intervals = intervals, tracks = tracks, layerId = layerId,
        rectKeys = rectKeys, visible = visible, warning = warning,
        dirty = true, raw = null
    )

    /** 一个激活区间（毫秒） */
    class Interval(val startMs: Double, val endMs: Double, val readyFlash: Double = 0.5)

    /** 一条事件（值从 start 缓动到 end，毫秒；anchor 只对 rotate/scale 有意义） */
    class NoiseEvent(
        val startMs: Double,
        val endMs: Double,
        val start: Float,
        val end: Float,
        val easing: Int = 1,
        val anchorX: Float = Float.NaN,
        val anchorY: Float = Float.NaN
    )

    /** 区域中心（RPE 世界坐标） */
    val cx: Float get() = (left + right) / 2f
    val cy: Float get() = (bottom + top) / 2f

    /** 这条事件在 [nowMs] 时刻的值（没有事件 → fallback） */
    fun trackValue(track: String, nowMs: Double, fallback: Float): Float {
        val list = tracks[track] ?: return fallback
        if (list.isEmpty()) return fallback
        // 事件按时间升序；用二分找「最后一条已开始的」，避免长轨道上的线性扫。
        // 官谱一条 moveEvents 能有几十条，且每帧每块要查 5 条轨道，线性扫是主要热点之一。
        var lo = 0
        var hi = list.size - 1
        var hit = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].startMs <= nowMs) { hit = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (hit < 0) return fallback
        val e = list[hit]
        if (e.endMs <= e.startMs) return e.end
        val p = ((nowMs - e.startMs) / (e.endMs - e.startMs)).toFloat().coerceIn(0f, 1f)
        return e.start + (e.end - e.start) * p
    }

    /** [nowMs] 时刻是否处于「激活」状态（落在某个激活区间内） */
    fun activeAt(nowMs: Double): Boolean {
        if (nowMs < appearMs || nowMs >= disappearMs) return false
        for (iv in intervals) {
            if (nowMs >= iv.startMs && nowMs < iv.endMs) return true
            // 零长度区间：Phira 视为一次瞬时激活，这里也给一个 1ms 的可见窗口
            if (iv.startMs == iv.endMs && nowMs >= iv.startMs && nowMs < iv.startMs + 1.0) return true
        }
        return false
    }

    /** [nowMs] 时刻是否在生命周期内（可能要画） */
    fun aliveAt(nowMs: Double): Boolean = nowMs >= appearMs && nowMs < disappearMs

    // ------------------------------------------------------------------ JSON

    companion object {
        /** 事件轨道名（顺序固定，便于概览按这个顺序画） */
        val TRACKS = arrayOf("moveXEvents", "moveYEvents", "rotateEvents", "scaleXEvents", "scaleYEvents")

        /**
         * RPE 噪域 transform 事件的默认锚点。
         *
         * phira pro / prpr 的 `noise_anchor_default()` 就是 `[0.5, 0.5]`（归一化屏幕中心），
         * 真实官谱里每条 `rotateEvents` / `scaleEvents` 也都带这个字段。
         * FRPHI 自己派生的 move/scale 事件不带锚点 ⇒ 读器报
         * `blockAreaList[0] / scaleXEvents / missing transform anchor`，整张谱导入失败。
         * 所以**导出边界**上每条 transform 事件都必须有合法的 `anchor`。
         */
        private const val DEFAULT_ANCHOR_X = 0.5
        private const val DEFAULT_ANCHOR_Y = 0.5

        /** 需要保证 `anchor` 的轨道：RPE 五条 + 官谱的 `scaleEvents`（官谱 `rotateEvents` 已在五条里） */
        private val ANCHOR_TRACKS = arrayOf(
            "moveXEvents", "moveYEvents", "rotateEvents", "scaleXEvents", "scaleYEvents", "scaleEvents"
        )

        /**
         * 解析 `blockAreaList`。任何一条区域解析失败只跳过它（并把原因塞进 [warnings]），
         * 绝不让整个谱面导入失败 —— 噪域只是附属信息。
         *
         * @param bpmItems RPE 的 BPMList 拍缓存：`(起始拍, bpm)`，按拍升序。噪域的时间字段同样是
         *   RPE 节拍三元组，必须用同一张表换算成毫秒，否则多 BPM 谱面会整体错位。
         */
        fun parseAll(
            arr: JSONArray?,
            bpmItems: List<Pair<Double, Double>>,
            warnings: MutableList<String>? = null
        ): List<NoiseArea> {
            if (arr == null || arr.length() == 0) return emptyList()
            val beats = Beats(bpmItems)
            val out = ArrayList<NoiseArea>(arr.length())
            var bad = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val a = runCatching { parseOne(o, beats) }.getOrNull()
                if (a != null) out.add(a) else bad++
            }
            if (bad > 0) warnings?.add("噪域有 $bad 块字段不完整，已跳过（其余照常读取）")
            return out
        }

        /**
         * 解析**官谱**（Phigros Official JSON）的 `blockAreaList`。
         *
         * 官谱的噪域和 RPE 完全是两套结构，**绝不能混用 [parseAll]**：
         *  - 区域：`bottomLeftPercentage` / `topRightPercentage`，x/y 都是 **0~1 的屏幕百分比**（不是 ±675 世界坐标）；
         *    但注意它可以是 0~1 之外（官谱里见过 -1~2，用于溢出屏幕），所以不能直接 clamp。
         *  - 时间：`appearTime` / `disappearTime` / `enableTime` / `disableTime` 单位是**秒**（浮点），
         *    不是 RPE 的 `[i,n,d]` 节拍三元组。
         *  - 激活区间：**没有 `activeIntervals`**，而是 `[enableTime, disableTime)` 这一段；
         *    `appearTime`/`disappearTime` 是它自己的生命周期（一般等于 enable/disable）。
         *  - 事件：`moveEvents[{time, endPosition{x,y}, easeTypeX, easeTypeY}]`、
         *    `rotateEvents[{time, rotation, easeType, anchor}]`、`scaleEvents[{time, scale{x,y}}]`。
         *    这类事件是**只给终点+时刻**的（起点是上一条的终点），不是 RPE 的 start/end 区间。
         *  - `isSubtract` ↔ RPE 的 `isInvert`。
         *
         * 官方谱面一张能做到 **一万多块**噪域（ハテ AT 就是 14355 块），所以这里用预设容量、
         * 不做任何字符串拼接，并且解析失败只跳过单块。
         */
        fun parseAllPgr(arr: JSONArray?, warnings: MutableList<String>? = null): List<NoiseArea> {
            if (arr == null || arr.length() == 0) return emptyList()
            val out = ArrayList<NoiseArea>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // parseOnePgr 全程 optDouble + isFinite 兜底，不会抛；这里不套 runCatching
                // （它连 OutOfMemoryError 都吞，而且一万多次 try 本身有开销）
                out.add(parseOnePgr(o))
            }
            return out
        }

        /**
         * **按块自动判格式**地解析一串噪域原始 JSON（草稿 [DraftCodec] 走这条）。
         *
         * 草稿里存的是「原始 JSON 一行一条」，一张草稿可能同时来自 RPE 和官谱
         * （例如官谱导入后又手动加过 RPE 噪域），所以必须逐块判：
         *  - 有 `bottomLeftPercentage` 或 `topRightPercentage` ⇒ 官谱格式；
         *  - 否则按 RPE（`bottomLeft` / `topRight`）解析。
         *
         * ★ 这里**绝不能**统一用 [parseAll]：官谱 JSON 喂给 RPE 解析器时字段全读不到，
         * 每一块都退回默认矩形 + `disappearMs = Long.MAX_VALUE`，结果一万多块噪域
         * 「永远存在、全叠在屏幕正中」，一进编辑页就每帧画一万多个矩形 ⇒ 卡死/OOM。
         *
         * @param bpmItems RPE 分支要用的 BPM 表；官谱分支不用它（时间本来就是秒）。
         */
        fun parseAllAuto(
            arr: JSONArray?,
            bpmItems: List<Pair<Double, Double>> = emptyList(),
            warnings: MutableList<String>? = null
        ): List<NoiseArea> {
            if (arr == null || arr.length() == 0) return emptyList()
            val beats = if (bpmItems.isEmpty()) null else Beats(bpmItems)
            // 草稿里没有 BPMList：那就按「官谱那种绝对时间」换算不了 RPE 的节拍三元组，
            // 只能用默认 120BPM 兜底（官谱块走 parseOnePgr，根本不看它）。
            // 注意别用 `by lazy` 委托：每块都要过一次委托，一万多块时白花几十毫秒。
            var fallbackBeats: Beats? = null
            val out = ArrayList<NoiseArea>(arr.length())
            var bad = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // 官谱分支不会抛（全 optDouble 兜底）；RPE 分支保留单块兜底，
                // 免得一块脏数据把整张草稿的噪域全丢掉
                if (o.has("bottomLeftPercentage") || o.has("topRightPercentage")) {
                    out.add(parseOnePgr(o))
                } else {
                    val b = beats ?: (fallbackBeats ?: Beats(emptyList()).also { fallbackBeats = it })
                    val a = runCatching { parseOne(o, b) }.getOrNull()
                    if (a != null) out.add(a) else bad++
                }
            }
            if (bad > 0) warnings?.add("噪域有 $bad 块字段不完整，已跳过（其余照常读取）")
            return out
        }

        /**
         * 官谱百分比坐标 → 本项目的世界坐标（x ∈ ±675、y ∈ ±450，y 向上为正）。
         * 官谱的百分比是「屏幕 0~1，原点左上、y 向下」⇒ x 线性映射到 ±675，y 取反并映射到 ±450。
         */
        private fun pctToWorldX(p: Double): Float = ((p - 0.5) * 2.0 * 675.0).toFloat()
        private fun pctToWorldY(p: Double): Float = ((0.5 - p) * 2.0 * 450.0).toFloat()

        /** FRPHI 私有扩展 `_frphi` 的解析结果（其他播放器会忽略这个字段） */
        private class Frphi(
            val keys: List<RectKey>,
            val visible: Boolean,
            val warning: Boolean,
            val appearMs: Double?,
            val disappearMs: Double?,
            val intervals: List<Interval>?
        )

        /** 读 `_frphi`（显示/深浅、毫秒制时间、矩形关键帧）。没有就返回 null，走原始字段。 */
        private fun parseFrphi(fr: JSONObject?): Frphi? {
            if (fr == null) return null
            val keys = ArrayList<RectKey>()
            fr.optJSONArray("rectKeys")?.let { a ->
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val t = o.optDouble("t", Double.NaN)
                    val l = o.optDouble("l", 0.0)
                    val b = o.optDouble("b", 0.0)
                    val r = o.optDouble("r", 0.0)
                    val top = o.optDouble("top", 0.0)
                    if (!t.isFinite() || !l.isFinite() || !b.isFinite() || !r.isFinite() || !top.isFinite()) continue
                    keys.add(RectKey(t.toLong(), l.toFloat(), b.toFloat(), r.toFloat(), top.toFloat()))
                }
            }
            keys.sortBy { it.timeMs }
            val ivs = fr.optJSONArray("intervals")?.let { ia ->
                val out = ArrayList<Interval>()
                for (k in 0 until ia.length()) {
                    val e = ia.optJSONObject(k) ?: continue
                    val s = e.optDouble("s", Double.NaN)
                    val d = e.optDouble("e", Double.NaN)
                    if (!s.isFinite() || !d.isFinite()) continue
                    out.add(Interval(minOf(s, d), maxOf(s, d), e.optDouble("rf", 0.5)))
                }
                out
            }
            return Frphi(
                keys = keys,
                visible = fr.optBoolean("visible", true),
                warning = fr.optBoolean("warning", false),
                appearMs = fr.optDouble("appearMs", Double.NaN).takeIf { it.isFinite() },
                disappearMs = fr.optDouble("disappearMs", Double.NaN).takeIf { it.isFinite() },
                intervals = ivs
            )
        }

        fun parseOnePgr(o: JSONObject): NoiseArea {
            fun num(k: String, key: String): Double {
                val v = o.optJSONObject(k)?.optDouble(key, 0.0) ?: 0.0
                return if (v.isFinite()) v else 0.0
            }
            var l = pctToWorldX(num("bottomLeftPercentage", "x"))
            var b = pctToWorldY(num("bottomLeftPercentage", "y"))
            var r = pctToWorldX(num("topRightPercentage", "x"))
            var t = pctToWorldY(num("topRightPercentage", "y"))
            if (!l.isFinite()) l = -250f
            if (!b.isFinite()) b = -150f
            if (!r.isFinite()) r = 250f
            if (!t.isFinite()) t = 150f
            // 百分比是「左上→右下」，换到 y 向上后右下角的 y 反而更小，这里统一成 left<right、bottom<top
            if (r < l) { val x = l; l = r; r = x }
            if (t < b) { val y = b; b = t; t = y }

            fun sec(key: String): Double {
                val v = o.optDouble(key, Double.NaN)
                return if (v.isFinite()) v * 1000.0 else Double.NaN
            }

            val appearS = sec("appearTime")
            val disappearS = sec("disappearTime")
            val enableS = sec("enableTime")
            val disableS = sec("disableTime")
            // 生命周期：有 appear/disappear 就用它，没有就退回 enable/disable
            val appear = if (appearS.isFinite()) appearS else if (enableS.isFinite()) enableS else 0.0
            var disappear = if (disappearS.isFinite()) disappearS
                else if (disableS.isFinite()) disableS else Double.MAX_VALUE
            if (disappear < appear) disappear = appear

            // 官谱没有 activeIntervals：「激活」就是 enable → disable 这一段
            val ivs = ArrayList<Interval>(1)
            val a0 = if (enableS.isFinite()) enableS else appear
            val a1 = if (disableS.isFinite()) disableS else disappear
            if (a1 > a0 || a1 == a0) ivs.add(Interval(minOf(a0, a1), maxOf(a0, a1), 0.0))

            // 事件：官谱只有「终点 + 时刻」，起点是上一条的终点 ⇒ 逐条配对成区间。
            // moveEvents / scaleEvents 各只遍历一次，同时产出 x、y 两条轨道
            // （以前 x/y 各扫一遍数组、各读一次 endPosition/scale，一万四千块时白花一倍解析时间）。
            val tracks = LinkedHashMap<String, List<NoiseEvent>>(4)
            pgrMoveBoth(o.optJSONArray("moveEvents"))?.let {
                tracks["moveXEvents"] = it[0]!!
                tracks["moveYEvents"] = it[1]!!
            }
            pgrRot(o.optJSONArray("rotateEvents"))?.let { tracks["rotateEvents"] = it }
            pgrScaleBoth(o.optJSONArray("scaleEvents"))?.let {
                tracks["scaleXEvents"] = it[0]!!
                tracks["scaleYEvents"] = it[1]!!
            }

            val fr = parseFrphi(o.optJSONObject("_frphi"))
            return NoiseArea(
                name = o.optString("Name", ""),
                left = l, bottom = b, right = r, top = t,
                appearMs = fr?.appearMs ?: appear,
                disappearMs = fr?.disappearMs ?: disappear,
                // 官谱 isSubtract = 反相混合（RPE 叫 isInvert）
                invert = o.optBoolean("isSubtract", false),
                intervals = fr?.intervals ?: ivs,
                tracks = tracks,
                layerId = "",
                rectKeys = fr?.keys ?: emptyList(),
                visible = fr?.visible ?: true,
                warning = fr?.warning ?: false,
                dirty = fr != null,
                raw = o
            )
        }

        /**
         * 官谱 moveEvents → 同时产出 x、y 两条轨道（`endPosition.x/y` 是百分比，这里换成世界坐标）。
         * 只遍历一次数组：一万四千块噪域时，x/y 各扫一遍会白花一倍解析时间。
         * 返回 `[x轨道, y轨道]`，两条都为空时返回 null。
         */
        private fun pgrMoveBoth(arr: JSONArray?): Array<List<NoiseEvent>?>? {
            if (arr == null || arr.length() == 0) return null
            val xs = ArrayList<NoiseEvent>(arr.length())
            val ys = ArrayList<NoiseEvent>(arr.length())
            var prevT = 0.0
            var prevX = 0f
            var prevY = 0f
            for (k in 0 until arr.length()) {
                val e = arr.optJSONObject(k) ?: continue
                val tt = e.optDouble("time", Double.NaN)
                if (!tt.isFinite()) continue
                val tms = tt * 1000.0
                val ep = e.optJSONObject("endPosition")
                val vx = pctToWorldX(ep?.optDouble("x", 0.0) ?: 0.0)
                val vy = pctToWorldY(ep?.optDouble("y", 0.0) ?: 0.0)
                if (!vx.isFinite() || !vy.isFinite()) continue
                val end = maxOf(prevT, tms)
                xs.add(NoiseEvent(prevT, end, prevX, vx, e.optInt("easeTypeX", 1)))
                ys.add(NoiseEvent(prevT, end, prevY, vy, e.optInt("easeTypeY", 1)))
                prevT = end; prevX = vx; prevY = vy
            }
            if (xs.isEmpty()) return null
            return arrayOf<List<NoiseEvent>?>(xs, ys)
        }

        /** 官谱 rotateEvents：`rotation` 一个值 */
        private fun pgrRot(arr: JSONArray?): List<NoiseEvent>? {
            if (arr == null || arr.length() == 0) return null
            val evs = ArrayList<NoiseEvent>(arr.length())
            var prevT = 0.0
            var prevV = 0f
            for (k in 0 until arr.length()) {
                val e = arr.optJSONObject(k) ?: continue
                val tt = e.optDouble("time", Double.NaN)
                if (!tt.isFinite()) continue
                val tms = tt * 1000.0
                val v = e.optDouble("rotation", 0.0).toFloat()
                if (!v.isFinite()) continue
                val an = e.optJSONObject("anchor")
                evs.add(
                    NoiseEvent(
                        prevT, maxOf(prevT, tms), prevV, v, e.optInt("easeType", 1),
                        an?.optDouble("x", 0.0)?.toFloat() ?: Float.NaN,
                        an?.optDouble("y", 0.0)?.toFloat() ?: Float.NaN
                    )
                )
                prevT = maxOf(prevT, tms); prevV = v
            }
            return if (evs.isEmpty()) null else evs
        }

        /**
         * 官谱 scaleEvents → 同时产出 x、y 两条轨道（`scale.x/y`，缺省 1）。
         * 同样只遍历一次数组。
         */
        private fun pgrScaleBoth(arr: JSONArray?): Array<List<NoiseEvent>?>? {
            if (arr == null || arr.length() == 0) return null
            val xs = ArrayList<NoiseEvent>(arr.length())
            val ys = ArrayList<NoiseEvent>(arr.length())
            var prevT = 0.0
            var prevX = 1f
            var prevY = 1f
            for (k in 0 until arr.length()) {
                val e = arr.optJSONObject(k) ?: continue
                val tt = e.optDouble("time", Double.NaN)
                if (!tt.isFinite()) continue
                val tms = tt * 1000.0
                val sc = e.optJSONObject("scale")
                val vx = sc?.optDouble("x", 1.0)?.toFloat() ?: 1f
                val vy = sc?.optDouble("y", 1.0)?.toFloat() ?: 1f
                if (!vx.isFinite() || !vy.isFinite()) continue
                val an = e.optJSONObject("anchor")
                val ax = an?.optDouble("x", 0.0)?.toFloat() ?: Float.NaN
                val ay = an?.optDouble("y", 0.0)?.toFloat() ?: Float.NaN
                val end = maxOf(prevT, tms)
                xs.add(NoiseEvent(prevT, end, prevX, vx, e.optInt("easeTypeX", 1), ax, ay))
                ys.add(NoiseEvent(prevT, end, prevY, vy, e.optInt("easeTypeY", 1), ax, ay))
                prevT = end; prevX = vx; prevY = vy
            }
            if (xs.isEmpty()) return null
            return arrayOf<List<NoiseEvent>?>(xs, ys)
        }

        fun parseOne(o: JSONObject, beats: Beats): NoiseArea {
            fun beat(key: String): Double {
                val t = o.optJSONArray(key) ?: return Double.NaN
                return beats.ms(tripleBeatsOf(t))
            }

            val bl = o.optJSONObject("bottomLeft")
            val tr = o.optJSONObject("topRight")
            var l = if (bl != null) bl.optDouble("x", -250.0).toFloat() else -250f
            var b = if (bl != null) bl.optDouble("y", -150.0).toFloat() else -150f
            var r = if (tr != null) tr.optDouble("x", 250.0).toFloat() else 250f
            var t = if (tr != null) tr.optDouble("y", 150.0).toFloat() else 150f
            if (!l.isFinite()) l = -250f
            if (!b.isFinite()) b = -150f
            if (!r.isFinite()) r = 250f
            if (!t.isFinite()) t = 150f
            // 允许右上角写成比左下角还小：交换，别让区域变成负宽
            if (r < l) { val x = l; l = r; r = x }
            if (t < b) { val y = b; b = t; t = y }

            val appearRaw = beat("appearTime")
            val disRaw = beat("disappearTime")
            val appear = if (appearRaw.isFinite()) appearRaw else 0.0
            val disappear = if (disRaw.isFinite()) disRaw else Double.MAX_VALUE

            val ivs = ArrayList<Interval>()
            o.optJSONArray("activeIntervals")?.let { ia ->
                for (k in 0 until ia.length()) {
                    val e = ia.optJSONObject(k) ?: continue
                    val s = msOf(e, "startTime", beats)
                    val d = msOf(e, "endTime", beats)
                    if (s.isNaN() || d.isNaN()) continue
                    // readyFlash：毫秒前的「预备闪烁」；RPE 里 true = 0.5 秒
                    val rf = when (val v = e.opt("readyFlash")) {
                        is Boolean -> if (v) 0.5 else 0.0
                        is Number -> {
                            val d2 = v.toDouble()
                            // 老谱面可能直接写秒数（Re:PhiEdit Next 就是这么读的）
                            if (d2.isFinite() && d2 >= 0) d2 else 0.5
                        }
                        else -> 0.5
                    }
                    ivs.add(Interval(minOf(s, d), maxOf(s, d), rf))
                }
            }
            ivs.sortBy { it.startMs }

            val tracks = LinkedHashMap<String, List<NoiseEvent>>()
            for (tk in TRACKS) {
                val evs = parseTrack(o.optJSONArray(tk), beats) ?: continue
                if (evs.isNotEmpty()) tracks[tk] = evs
            }

            val fr = parseFrphi(o.optJSONObject("_frphi"))
            return NoiseArea(
                name = o.optString("Name", ""),
                left = l, bottom = b, right = r, top = t,
                appearMs = fr?.appearMs ?: appear,
                disappearMs = fr?.disappearMs ?: maxOf(disappear, appear),
                invert = o.optBoolean("isInvert", false),
                intervals = fr?.intervals ?: ivs,
                tracks = tracks,
                layerId = o.optString("layerId", ""),
                rectKeys = fr?.keys ?: emptyList(),
                visible = fr?.visible ?: true,
                warning = fr?.warning ?: false,
                dirty = fr != null,
                raw = o
            )
        }

        private fun parseTrack(arr: JSONArray?, beats: Beats): List<NoiseArea.NoiseEvent>? {
            if (arr == null || arr.length() == 0) return null
            val out = ArrayList<NoiseArea.NoiseEvent>(arr.length())
            for (k in 0 until arr.length()) {
                val e = arr.optJSONObject(k) ?: continue
                val s = msOf(e, "startTime", beats)
                val d = msOf(e, "endTime", beats)
                if (s.isNaN() || d.isNaN()) continue
                val v0 = e.optDouble("start", 0.0).toFloat()
                val v1 = e.optDouble("end", 0.0).toFloat()
                if (!v0.isFinite() || !v1.isFinite()) continue
                val an = e.optJSONObject("anchor")
                out.add(
                    NoiseArea.NoiseEvent(
                        startMs = minOf(s, d), endMs = maxOf(s, d),
                        start = v0, end = v1,
                        easing = e.optInt("easingType", 1),
                        anchorX = an?.optDouble("x", 0.0)?.toFloat() ?: Float.NaN,
                        anchorY = an?.optDouble("y", 0.0)?.toFloat() ?: Float.NaN
                    )
                )
            }
            out.sortBy { it.startMs }
            return out
        }

        private fun msOf(o: JSONObject, key: String, beats: Beats): Double {
            val t = o.optJSONArray(key) ?: return Double.NaN
            return beats.ms(tripleBeatsOf(t))
        }

        /**
         * 拍 → 毫秒（RPE 多 BPM 分段积分）。
         * 和 PezImporter 的 BpmMap 同一算法，但这里自己算：噪域是纯数据、要能被单元测试单独驱动，
         * 不该依赖导入器的私有类。
         */
        class Beats(items: List<Pair<Double, Double>>) {
            private val startBeats: DoubleArray
            private val startSec: DoubleArray
            private val bpms: DoubleArray

            init {
                val sorted = items.filter { it.second.isFinite() && it.second > 0.0 }.sortedBy { it.first }
                val use = if (sorted.isEmpty()) listOf(0.0 to 120.0) else sorted
                startBeats = DoubleArray(use.size)
                startSec = DoubleArray(use.size)
                bpms = DoubleArray(use.size)
                var time = 0.0
                var lastBeats = 0.0
                var lastBpm = 0.0
                var hasLast = false
                for ((i, it) in use.withIndex()) {
                    val (b, v) = it
                    if (hasLast) time += (b - lastBeats) * (60.0 / lastBpm)
                    startBeats[i] = b
                    startSec[i] = time
                    bpms[i] = v
                    lastBeats = b
                    lastBpm = v
                    hasLast = true
                }
            }

            fun ms(beats: Double): Double {
                if (!beats.isFinite()) return Double.NaN
                var i = startBeats.size - 1
                while (i > 0 && startBeats[i] > beats) i--
                return (startSec[i] + (beats - startBeats[i]) * (60.0 / bpms[i])) * 1000.0
            }
        }

        /** RPE 节拍三元组 `[i, n, d]` = i + n/d */
        fun tripleBeatsOf(a: JSONArray): Double {
            if (a.length() < 3) return 0.0
            val i = a.optDouble(0, 0.0)
            val n = a.optDouble(1, 0.0)
            val d = a.optDouble(2, 1.0)
            return if (d == 0.0 || !d.isFinite()) i else i + n / d
        }

        /**
         * 写回 `blockAreaList`。
         * 原始对象在且**没被编辑过** → 原样返回（字段顺序、精度、FRPHI 不认识的字段全都保留）；
         * 编辑过/手搓的才按当前字段重建，并把 FRPHI 私有数据写进 `_frphi`。
         * [beatsOfMs] = 毫秒 → 拍（导出时用真实 BPM 分段；null 时退回 1 拍 = 500ms 的粗略换算）。
         */
        fun toJson(list: List<NoiseArea>, beatsOfMs: ((Double) -> Double)? = null): JSONArray {
            val arr = JSONArray()
            for (a in list) {
                val o: JSONObject = a.raw?.takeIf { !a.dirty } ?: build(a, beatsOfMs)
                arr.put(ensureAnchors(o))
            }
            return arr
        }

        /** 让 RecordView 在编辑后能立刻拿到可存进草稿的 JSON（带 `_frphi`） */
        fun toJsonObject(a: NoiseArea, beatsOfMs: ((Double) -> Double)? = null): JSONObject {
            val o = a.raw?.takeIf { !a.dirty } ?: build(a, beatsOfMs)
            return ensureAnchors(o)
        }

        /**
         * 给每条 transform 事件补 `anchor`（缺省或非法时写成 `{x:0.5,y:0.5}`）。
         * 只做**加法**：已有合法 anchor 不动，FRPHI 不认识的字段一个都不删。
         */
        private fun ensureAnchors(o: JSONObject): JSONObject {
            for (tk in ANCHOR_TRACKS) {
                val arr = o.optJSONArray(tk) ?: continue
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val an = e.optJSONObject("anchor")
                    val ax = an?.optDouble("x", Double.NaN) ?: Double.NaN
                    val ay = an?.optDouble("y", Double.NaN) ?: Double.NaN
                    if (!ax.isFinite() || !ay.isFinite()) {
                        e.put("anchor", JSONObject().put("x", DEFAULT_ANCHOR_X).put("y", DEFAULT_ANCHOR_Y))
                    }
                }
            }
            return o
        }

        /** Float 版的有限值兜底（anchorX/Y 可能是 NaN） */
        private fun anchorOf(v: Float): Double =
            if (!v.isNaN() && !v.isInfinite()) v.toDouble() else DEFAULT_ANCHOR_X

        private fun build(a: NoiseArea, beatsOfMs: ((Double) -> Double)?): JSONObject = JSONObject().apply {
            // 编辑过：静态矩形取首个关键帧（官方格式的 bottomLeft/topRight 只描述基础矩形）
            val k0 = a.rectKeys.firstOrNull()
            val l0 = k0?.left ?: a.left
            val b0 = k0?.bottom ?: a.bottom
            val r0 = k0?.right ?: a.right
            val t0 = k0?.top ?: a.top
            put("Name", a.name)
            put("bottomLeft", JSONObject().put("x", l0.toDouble()).put("y", b0.toDouble()))
            put("topRight", JSONObject().put("x", r0.toDouble()).put("y", t0.toDouble()))
            put("isInvert", a.invert)
            if (a.layerId.isNotEmpty()) put("layerId", a.layerId)
            put("appearTime", beatsTriple(a.appearMs, beatsOfMs))
            put("disappearTime", beatsTriple(a.disappearMs, beatsOfMs))
            put("activeIntervals", JSONArray().also { ia ->
                for (iv in a.intervals) ia.put(
                    JSONObject().put("startTime", beatsTriple(iv.startMs, beatsOfMs))
                        .put("endTime", beatsTriple(iv.endMs, beatsOfMs))
                        .put("readyFlash", iv.readyFlash)
                )
            })
            // 有矩形关键帧时按关键帧生成标准的 move/scale 事件（其他播放器也能看到动画）；
            // 否则沿用原始事件轨道（未编辑的噪域走 raw 分支，正常不会走到这里）。
            val derived = if (a.rectKeys.size >= 2) rectTracks(a) else null
            val tracks = derived ?: a.tracks
            for (tk in TRACKS) {
                val evs = tracks[tk] ?: continue
                put(tk, JSONArray().also { ja ->
                    for (e in evs) ja.put(JSONObject().apply {
                        put("startTime", beatsTriple(e.startMs, beatsOfMs))
                        put("endTime", beatsTriple(e.endMs, beatsOfMs))
                        put("start", e.start.toDouble())
                        put("end", e.end.toDouble())
                        put("easingType", e.easing)
                        // phira pro 的噪域解析器要求每条 transform 事件都带 anchor；
                        // FRPHI 派生的 move/scale 事件本来没有锚点，NaN 一律补默认中心点。
                        put("anchor", JSONObject()
                            .put("x", anchorOf(e.anchorX))
                            .put("y", if (!e.anchorY.isNaN() && !e.anchorY.isInfinite())
                                e.anchorY.toDouble() else DEFAULT_ANCHOR_Y))
                    })
                })
            }
            // FRPHI 私有扩展：毫秒制时间 + 矩形关键帧 + 显示/深浅。
            // 其他播放器不认识这个字段会直接忽略；FRPHI 自己读回来时以它为准。
            put("_frphi", JSONObject().apply {
                put("visible", a.visible)
                put("warning", a.warning)
                put("appearMs", a.appearMs)
                put("disappearMs", a.disappearMs)
                put("intervals", JSONArray().also { ia ->
                    for (iv in a.intervals) ia.put(JSONObject()
                        .put("s", iv.startMs).put("e", iv.endMs).put("rf", iv.readyFlash))
                })
                if (a.rectKeys.isNotEmpty()) put("rectKeys", JSONArray().also { ka ->
                    for (k in a.rectKeys) ka.put(JSONObject()
                        .put("t", k.timeMs).put("l", k.left.toDouble()).put("b", k.bottom.toDouble())
                        .put("r", k.right.toDouble()).put("top", k.top.toDouble()))
                })
            })
        }

        /**
         * 矩形关键帧 → 标准事件：moveX/Y 存绝对中心坐标，scaleX/Y 存相对首帧的宽高倍率。
         * 与 [NoiseRuntime.sample] 的变换顺序一致（先以中心缩放，再平移）。
         */
        private fun rectTracks(a: NoiseArea): Map<String, List<NoiseEvent>> {
            val keys = a.rectKeys
            if (keys.size < 2) return emptyMap()
            val w0 = (keys[0].right - keys[0].left).coerceAtLeast(1e-3f)
            val h0 = (keys[0].top - keys[0].bottom).coerceAtLeast(1e-3f)
            val mx = ArrayList<NoiseEvent>(keys.size - 1)
            val my = ArrayList<NoiseEvent>(keys.size - 1)
            val sx = ArrayList<NoiseEvent>(keys.size - 1)
            val sy = ArrayList<NoiseEvent>(keys.size - 1)
            for (i in 0 until keys.size - 1) {
                val k = keys[i]
                val n = keys[i + 1]
                val cx0 = (k.left + k.right) / 2f
                val cy0 = (k.bottom + k.top) / 2f
                val cx1 = (n.left + n.right) / 2f
                val cy1 = (n.bottom + n.top) / 2f
                val s0x = (k.right - k.left) / w0
                val s1x = (n.right - n.left) / w0
                val s0y = (k.top - k.bottom) / h0
                val s1y = (n.top - n.bottom) / h0
                val t0 = k.timeMs.toDouble()
                val t1 = n.timeMs.toDouble()
                mx.add(NoiseEvent(t0, t1, cx0, cx1, 1))
                my.add(NoiseEvent(t0, t1, cy0, cy1, 1))
                sx.add(NoiseEvent(t0, t1, s0x, s1x, 1))
                sy.add(NoiseEvent(t0, t1, s0y, s1y, 1))
            }
            return mapOf(
                "moveXEvents" to mx, "moveYEvents" to my,
                "scaleXEvents" to sx, "scaleYEvents" to sy
            )
        }

        /** 毫秒 → RPE 节拍三元组（[beatsOfMs] 给了就用真实 BPM，否则 1 拍 = 500ms 粗略换算） */
        private fun beatsTriple(ms: Double, beatsOfMs: ((Double) -> Double)? = null): JSONArray {
            if (ms >= Double.MAX_VALUE / 2) return JSONArray().put(1 shl 20).put(0).put(1)
            if (!ms.isFinite() || ms <= 0.0) return JSONArray().put(0).put(0).put(1)
            val beats = beatsOfMs?.invoke(ms) ?: (ms / 500.0)
            if (!beats.isFinite()) return JSONArray().put(0).put(0).put(1)
            val i = Math.floor(beats)
            val frac = beats - i
            // 找一个小分母让分数精确到 1e-6（1/192 拍以内）
            for (d in intArrayOf(1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 192)) {
                val n = Math.round(frac * d)
                if (Math.abs(n.toDouble() / d - frac) < 1e-6) {
                    return JSONArray().put(i.toLong()).put(n.toLong()).put(d.toLong())
                }
            }
            return JSONArray().put(i.toLong()).put(Math.round(frac * 192.0)).put(192L)
        }
    }
}
