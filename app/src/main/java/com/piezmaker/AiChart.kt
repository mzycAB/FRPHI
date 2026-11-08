package com.piezmaker

import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 输出的 JSON Lines 协议 + 提示词。
 *
 * 一行 = 一个 JSON 对象（不包数组、不带代码块），这样流式返回时可以逐行解析、
 * 中途取消也能保住已经生成的部分。
 *
 * 认得的键：
 *   {"note":1,"t":1234,"x":0.25,"end":1800,"line":0,"fake":0,"above":1}   音符（1 tap / 2 hold / 3 flick / 4 drag）
 *   {"nl":1,"pos":45,"speed":1.2,"name":"line2"}                          新建判定线
 *   {"line":0,"pos":55,"speed":1,"rot":0,"x":0,"y":0}                     改现有判定线
 *   {"kf":0,"t":1000,"x":0.2,"y":-0.1,"r":5,"sp":1.4,"a":0.8}             判定线关键帧
 *   {"fix":3,"t":1200,"x":0.1,"end":1600}                                  修正第 3 个音符
 *   {"del":4} / {"del":[4,5]}                                              删除音符
 *   {"fake":[1,2]} / {"rev":[1,2]}                                         标记假音符 / 反转音符
 */
object AiChart {

    /** 解析状态：可反复 apply，边收边生效 */
    class Holder(
        val lines: MutableList<Line>,
        val notes: MutableList<RecordedNote>,
        var newLineId: Int,
        /** 噪域快照：AI 的 na / area 命令只替换元素或追加，不原地改对象 */
        val noises: MutableList<NoiseArea> = mutableListOf(),
        /** 空图层声明（AI 写回草稿时带上，否则快照会丢空图层） */
        val declaredLayers: Set<Int> = emptySet(),
        val declaredNoiseLayers: Set<String> = emptySet(),
        /** 自动写谱：强制正面音符（above=1）、非假音符 */
        var writeMode: Boolean = false,
        /** 自动写谱：如果 AI 全给了 Tap，套用时按比例补成 Drag/Flick */
        var diversify: Boolean = false,
        /** 套用时用的难度（0~20），用于控制键型/长条比例 */
        var difficulty: Double = 8.0
    ) {
        /**
         * 本轮新内容的可见时间范围（毫秒）。
         *
         * 为什么需要它：编辑器是「按播放头时间」渲染的 —— 谱面写在第 40 秒、噪域 40~45 秒、
         * 关键帧在 40 秒之后，而播放头还停在 0 秒，屏幕上当然什么都没有。
         * 用户看到的就是「AI 说做好了，但是我什么都没看到」（点名）。
         * 这里记下新内容的时间区间，一轮结束时编辑器会跳过去。
         */
        var revealFrom: Long = Long.MAX_VALUE
            private set
        var revealTo: Long = Long.MIN_VALUE
            private set

        /** 报告一段「新内容」的时间区间（自动取并集） */
        fun reveal(t0: Long, t1: Long = t0) {
            if (t0 == Long.MAX_VALUE || t1 == Long.MIN_VALUE) return
            val a = (if (t0 <= t1) t0 else t1).coerceIn(0L, REVEAL_MAX_MS)
            // 上限 24 小时：噪域的 disappear 常常是 MAX_VALUE（一直存在），
            // 直接拿来算区间会溢出，跳转判断也就永远"已经在范围内"。
            val b = (if (t0 <= t1) t1 else t0).coerceIn(0L, REVEAL_MAX_MS)
            if (a < revealFrom) revealFrom = a
            if (b > revealTo) revealTo = b
        }

        val hasReveal: Boolean get() = revealFrom <= revealTo

        /** 开局时的噪域块数 / 关键帧总数（用来在气泡里说清「到底加了什么」） */
        val baseNoiseCount: Int = noises.size
        val baseKeyframeCount: Int = lines.sumOf { it.keyframes.size }

        /** 这一轮新增的噪域块数（AI 说"画了噪域"时，用户可以对着这个数字验收） */
        val addedNoises: Int get() = (noises.size - baseNoiseCount).coerceAtLeast(0)

        /** 这一轮新增的判定线关键帧数 */
        val addedKeyframes: Int
            get() = (lines.sumOf { it.keyframes.size } - baseKeyframeCount).coerceAtLeast(0)

        private companion object {
            const val REVEAL_MAX_MS = 86_400_000L
        }
    }

    fun copyLines(src: List<Line>): MutableList<Line> = src.map { s ->
        Line(s.id).apply {
            posPct = s.posPct
            moveX = s.moveX; moveY = s.moveY; rotate = s.rotate
            speed = s.speed; alpha = s.alpha; name = s.name
            keyframes.addAll(s.keyframes)
            // 图片线/文字线的素材属性必须一起复制，否则 AI 一碰谱面，写回草稿时
            // 图片线丢贴图、文字线丢文字、缩放全丢（退化成普通判定线）
            texture = s.texture; texturePath = s.texturePath
            lineText = s.lineText
            scaleX = s.scaleX; scaleY = s.scaleY
            textEvents.addAll(s.textEvents)
            invalidate()
        }
    }.toMutableList()

    private fun lineOf(st: Holder, id: Int?): Line =
        st.lines.firstOrNull { it.id == id } ?: st.lines.firstOrNull() ?: Line(0).also { st.lines.add(it) }

    /** 应用一行 JSON；返回 true = 这一行被识别（用于进度统计） */
    fun applyJsonLine(raw: String, st: Holder, durationMs: Long, bpm: Double, hLines: Int): Boolean {
        val s = raw.trim()
        if (s.isEmpty() || s == "{" || s == "}" || s == "[" || s == "]") return false
        val o = try { JSONObject(s) } catch (_: Exception) { return false }

        // 容错：AI 有时会把整批塞进 {"notes":[...]} 里
        o.optJSONArray("notes")?.let { arr ->
            var any = false
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                if (applyObject(e, st, durationMs, bpm, hLines)) any = true
            }
            return any
        }
        return applyObject(o, st, durationMs, bpm, hLines)
    }

    private fun applyObject(o: JSONObject, st: Holder, durationMs: Long, bpm: Double, hLines: Int): Boolean {
        // 容错：把 "type" 当 "note" 用（有些模型不按提示词写）
        if (!o.has("note") && o.has("type") && o.has("t")) {
            o.put("note", o.optInt("type", 1))
        }

        if (o.has("fix")) {
            // 改单个音符：位置/时间/结束时间/键型/所属判定线/正反面/真假
            val i = o.optInt("fix", -1)
            if (i !in st.notes.indices) return false
            val n = st.notes[i]
            val t = if (o.has("t")) ms(o.optDouble("t", n.startMs.toDouble()), durationMs) else n.startMs
            // 键型：note 和 type 都认（模型两种写法都有）
            val type = when {
                o.has("note") -> o.optInt("note", n.type).coerceIn(1, 4)
                o.has("type") && !o.has("note") -> o.optInt("type", n.type).coerceIn(1, 4)
                else -> n.type
            }
            val rawEnd = if (o.has("end")) ms(o.optDouble("end", n.endMs.toDouble()), durationMs)
                         else n.endMs
            // 长条必须有结束时间；改成 Tap/Flick 就把结束时间收回到起点
            val end = if (type == 2 || type == 4) {
                val e = maxOf(t + 1, rawEnd)
                if (e <= t + 1) (t + 400L).coerceAtMost(durationMs.coerceAtLeast(t + 1)) else e
            } else t
            val x = if (o.has("x")) o.optDouble("x", n.posX.toDouble()).coerceIn(-1.0, 1.0).toFloat() else n.posX
            val lineId = if (o.has("line")) lineOf(st, o.optInt("line", n.lineId)).id else n.lineId
            val above = if (o.has("above")) o.optInt("above", if (n.above) 1 else 0) != 0 else n.above
            val fake = if (o.has("fake")) o.optInt("fake", if (n.fake) 1 else 0) != 0 else n.fake
            // 音符级扩展属性（speed/yOffset/alpha/size/visibleTime）：只改给了的字段
            val speed = if (o.has("speed")) clampNoteSpeed(o.optDouble("speed", n.speed.toDouble())) else n.speed
            val yOff = if (o.has("yOffset")) o.optDouble("yOffset", n.yOffset.toDouble()).coerceIn(-5000.0, 5000.0).toFloat() else n.yOffset
            val alpha = if (o.has("alpha")) o.optDouble("alpha", n.alpha.toDouble()).coerceIn(0.0, 1.0).toFloat() else n.alpha
            val size = if (o.has("size")) o.optDouble("size", n.size.toDouble()).coerceIn(0.05, 20.0).toFloat() else n.size
            val visT = if (o.has("visibleTime")) o.optDouble("visibleTime", n.visibleTime.toDouble()).coerceIn(0.0, 999999.0).toFloat() else n.visibleTime
            st.notes[i] = n.copy(
                type = type, startMs = t, endMs = end, posX = x,
                pass = passOfType(type), lineId = lineId, above = above, fake = fake,
                speed = speed, yOffset = yOff, alpha = alpha, size = size, visibleTime = visT
            )
            st.reveal(t, end)
            return true
        }

        if (o.has("note")) {
            val type = o.optInt("note", 1).coerceIn(1, 4)
            val t = ms(o.optDouble("t", 0.0), durationMs)
            val endRaw = if (o.has("end")) ms(o.optDouble("end", t.toDouble()), durationMs) else t
            val end = maxOf(t, endRaw)
            val x = o.optDouble("x", 0.0).coerceIn(-1.0, 1.0).toFloat()
            val line = lineOf(st, if (o.has("line")) o.optInt("line", 0) else null)
            var fake = o.optInt("fake", 0) != 0
            var above = if (o.has("above")) o.optInt("above", 1) != 0 else true
            // 自动写谱一律正面、非假音符（模型经常照抄示例里的 above=0）
            if (st.writeMode) { above = true; fake = false }
            st.notes.add(RecordedNote(
                type, t, end, x, passOfType(type), line.id, above, fake,
                // 音符级扩展属性（RPE speed/yOffset/alpha/size/visibleTime）：不写就用默认
                speed = if (o.has("speed")) clampNoteSpeed(o.optDouble("speed", 1.0)) else 1f,
                yOffset = if (o.has("yOffset")) o.optDouble("yOffset", 0.0).coerceIn(-5000.0, 5000.0).toFloat() else 0f,
                alpha = if (o.has("alpha")) o.optDouble("alpha", 1.0).coerceIn(0.0, 1.0).toFloat() else 1f,
                size = if (o.has("size")) o.optDouble("size", 1.0).coerceIn(0.05, 20.0).toFloat() else 1f,
                visibleTime = if (o.has("visibleTime")) o.optDouble("visibleTime", 999999.0).coerceIn(0.0, 999999.0).toFloat() else 999999f
            ))
            st.reveal(t, end)
            return true
        }

        if (o.has("nl")) {
            val id = o.optInt("nl", st.newLineId)
            val pos = o.optInt("pos", 60).coerceIn(0, 100)
            // 没写 speed 时沿用主判定线的流速（写谱不能把用户的「流速」冲掉）
            val fallbackSpeed = st.lines.firstOrNull()?.speed ?: 1f
            val sp = clampLineSpeed(
                if (o.has("speed")) o.optDouble("speed", fallbackSpeed.toDouble()).toFloat() else fallbackSpeed
            )
            val old = st.lines.firstOrNull { it.id == id }
            val l = Line(id).apply {
                posPct = pos
                speed = sp
                name = o.optString("name", "line" + id)
                // 图片线/文字线：tex = 贴图文件名（图片线），text = 文字内容（文字线）；
                // 都不给 = 普通判定线。素材属性让 AI 也能建图片线/文字线。
                if (o.has("text")) lineText = o.optString("text")
                else if (o.has("tex")) texture = o.optString("tex")
                if (o.has("scaleX")) scaleX = o.optDouble("scaleX", 1.0).coerceIn(0.01, 50.0).toFloat()
                if (o.has("scaleY")) scaleY = o.optDouble("scaleY", 1.0).coerceIn(0.01, 50.0).toFloat()
                if (o.has("layer")) zOrder = o.optInt("layer", 0).coerceIn(0, 9999)
                if (o.has("alpha")) alpha = o.optDouble("alpha", 1.0).coerceIn(-1.0, 1.0).toFloat()
                invalidate()
            }
            if (old != null) st.lines.remove(old)
            st.lines.add(l)
            st.newLineId = maxOf(st.newLineId, id + 1)
            return true
        }

        if (o.has("line")) {
            // 自动写谱不允许改判定线属性（模型常顺手把 speed 写成 1，冲掉用户设的流速）
            if (st.writeMode) return true
            val l = lineOf(st, o.optInt("line", 0))
            if (o.has("pos")) l.posPct = o.optInt("pos", l.posPct).coerceIn(0, 100)
            if (o.has("speed")) l.speed = clampLineSpeed(o.optDouble("speed", 1.0).toFloat())
            if (o.has("rot")) l.rotate = o.optDouble("rot", 0.0).toFloat()
            if (o.has("x")) l.moveX = o.optDouble("x", 0.0).coerceIn(-1.0, 1.0).toFloat()
            if (o.has("y")) l.moveY = o.optDouble("y", 0.0).coerceIn(-1.0, 1.0).toFloat()
            // 素材线内容 / 缩放 / 图层 / 整线透明度（玩家能做的 AI 现在也能做）
            if (o.has("alpha")) l.alpha = o.optDouble("alpha", l.alpha.toDouble()).coerceIn(-1.0, 1.0).toFloat()
            if (o.has("scaleX")) l.scaleX = o.optDouble("scaleX", l.scaleX.toDouble()).coerceIn(0.01, 50.0).toFloat()
            if (o.has("scaleY")) l.scaleY = o.optDouble("scaleY", l.scaleY.toDouble()).coerceIn(0.01, 50.0).toFloat()
            if (o.has("layer")) l.zOrder = o.optInt("layer", l.zOrder).coerceIn(0, 9999)
            if (o.has("text")) {
                // 改成文字线（lineText 优先级高于 texture）
                l.lineText = o.optString("text")
                if (l.lineText.isNotEmpty()) l.texture = ""
            }
            if (o.has("tex")) {
                // 换贴图（图片线）；换图的同时清掉文字线内容，避免 kind 判成文字线
                l.texture = o.optString("tex")
                if (l.texture.isNotEmpty()) l.lineText = ""
            }
            l.invalidate()
            return true
        }

        if (o.has("kf")) {
            // 自动写谱不写判定线关键帧（会连带改流速，和用户设置的「流速」冲突）
            if (st.writeMode) return true
            val l = lineOf(st, o.optInt("kf", 0))
            val t = ms(o.optDouble("t", 0.0), durationMs)
            // 同一时刻已经有关键帧 → 在它的基础上改（只覆盖给了的字段）。
            // 以前这里每个字段都默认 0，AI 只想改流速时会把姿态一起冲成 0。
            val exist = l.keyframes.firstOrNull { it.timeMs == t }
            val baseX = exist?.moveX ?: l.moveX
            val baseY = exist?.moveY ?: l.moveY
            val baseR = exist?.rotate ?: l.rotate
            val baseSp = exist?.speed ?: l.speed
            val baseA = exist?.alpha ?: l.alpha
            val x = if (o.has("x")) o.optDouble("x", baseX.toDouble()).coerceIn(-1.0, 1.0).toFloat() else baseX
            val y = if (o.has("y")) o.optDouble("y", baseY.toDouble()).coerceIn(-1.0, 1.0).toFloat() else baseY
            val r = if (o.has("r")) o.optDouble("r", baseR.toDouble()).toFloat() else baseR
            val sp = clampLineSpeed(
                if (o.has("sp")) o.optDouble("sp", baseSp.toDouble()).toFloat() else baseSp
            )
            // AI 没给 a 时必须原样保留（可能是负数 = 隐藏线+音符），给 了才夹取
            val a = (if (o.has("a")) o.optDouble("a", baseA.toDouble()) else baseA.toDouble())
                .coerceIn(-1.0, 1.0).toFloat()
            l.keyframes.removeAll { it.timeMs == t }
            l.keyframes.add(LineKeyframe(t, x, y, r, sp, a))
            l.invalidate()
            st.reveal(t)
            return true
        }

        if (o.has("kfdel")) {
            // 删一个关键帧：{"kfdel":判定线id,"t":毫秒}
            if (st.writeMode) return true
            if (!o.has("t")) return false
            val l = lineOf(st, o.optInt("kfdel", 0))
            val t = ms(o.optDouble("t", 0.0), durationMs)
            val ok = l.removeKeyframeAt(t)
            l.invalidate()
            return ok
        }

        if (o.has("na")) {
            // 新建噪域：{"na":1,"l":-250,"b":-150,"r":250,"t":150,"appear":0,"disappear":120000,
            //          "invert":0,"visible":1,"warning":0,"layer":"1"}
            val now = ms(0.0, durationMs)
            // 没选音乐 / 时长未知时 durationMs 会被夹到 1ms —— 那时消失时间写成 1ms，
            // 噪域只在第 0~1 毫秒存在，编辑器里等于「看不见」。给个够长的兜底（10 分钟）。
            val chartDur = if (durationMs > 1000L) durationMs else 600_000L
            val appear = if (o.has("appear")) ms(o.optDouble("appear", 0.0), durationMs) else now
            val disappear = if (o.has("disappear"))
                ms(o.optDouble("disappear", chartDur.toDouble()), durationMs).toDouble()
            else chartDur.toDouble()
            val l = o.optDouble("l", -250.0).coerceIn(-2000.0, 2000.0).toFloat()
            val b = o.optDouble("b", -150.0).coerceIn(-2000.0, 2000.0).toFloat()
            val r = o.optDouble("r", 250.0).coerceIn(-2000.0, 2000.0).toFloat()
            val t = o.optDouble("t", 150.0).coerceIn(-2000.0, 2000.0).toFloat()
            val invert = o.optInt("invert", 0) != 0
            val visible = !o.has("visible") || o.optInt("visible", 1) != 0
            val warning = o.optInt("warning", 0) != 0
            val layer = o.optString("layer", "")
            val a = NoiseArea(
                name = "", left = l, bottom = b, right = r, top = t,
                appearMs = appear.toDouble(), disappearMs = disappear,
                invert = invert,
                intervals = listOf(NoiseArea.Interval(appear.toDouble(), disappear, 0.5)),
                layerId = layer, rectKeys = listOf(NoiseArea.RectKey(appear, l, b, r, t)),
                visible = visible, warning = warning, dirty = true
            )
            st.noises.add(a)
            st.reveal(appear, disappear.toLong())
            return true
        }

        if (o.has("area")) {
            // 改 / 删噪域：下标 = 【当前谱面】噪域列表里的 i。
            // {"area":0,"l":..,"b":..,"r":..,"t":..,"appear":..,"disappear":..,"invert":..,"visible":..,"warning":..,"layer":".."}
            // {"area":0,"delete":1}
            val i = o.optInt("area", -1)
            if (i !in st.noises.indices) return false
            if (o.optInt("delete", 0) != 0) {
                st.noises.removeAt(i)
                return true
            }
            val a = st.noises[i]
            val l = if (o.has("l")) o.optDouble("l", a.left.toDouble()).coerceIn(-2000.0, 2000.0).toFloat() else a.left
            val b = if (o.has("b")) o.optDouble("b", a.bottom.toDouble()).coerceIn(-2000.0, 2000.0).toFloat() else a.bottom
            val r = if (o.has("r")) o.optDouble("r", a.right.toDouble()).coerceIn(-2000.0, 2000.0).toFloat() else a.right
            val t = if (o.has("t")) o.optDouble("t", a.top.toDouble()).coerceIn(-2000.0, 2000.0).toFloat() else a.top
            val appear = if (o.has("appear")) ms(o.optDouble("appear", a.appearMs), durationMs).toDouble() else a.appearMs
            val disappear = if (o.has("disappear"))
                ms(o.optDouble("disappear", a.disappearMs.coerceAtMost(durationMs.toDouble())), durationMs).toDouble()
            else a.disappearMs
            val invert = if (o.has("invert")) o.optInt("invert", if (a.invert) 1 else 0) != 0 else a.invert
            val visible = if (o.has("visible")) o.optInt("visible", if (a.visible) 1 else 0) != 0 else a.visible
            val warning = if (o.has("warning")) o.optInt("warning", if (a.warning) 1 else 0) != 0 else a.warning
            val layer = if (o.has("layer")) o.optString("layer") else a.layerId
            // 只替换列表里的元素（不动旧对象，holder 快照与 UI 数据分离）
            st.noises[i] = NoiseArea(
                name = a.name, left = l, bottom = b, right = r, top = t,
                appearMs = appear, disappearMs = disappear, invert = invert,
                intervals = a.intervals, tracks = a.tracks, layerId = layer,
                rectKeys = a.rectKeys, visible = visible, warning = warning, dirty = true
            )
            st.reveal(appear.toLong(), disappear.toLong())
            return true
        }

        if (o.has("del")) {
            val arr = o.optJSONArray("del")
            val idx = ArrayList<Int>()
            if (arr != null) {
                for (i in 0 until arr.length()) idx.add(arr.optInt(i, -1))
            } else {
                idx.add(o.optInt("del", -1))
            }
            for (i in idx.sortedDescending()) if (i in st.notes.indices) st.notes.removeAt(i)
            return true
        }

        if (o.has("fake") || o.has("rev")) {
            val key = if (o.has("fake")) "fake" else "rev"
            val arr = o.optJSONArray(key)
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val k = arr.optInt(i, -1)
                    if (k !in st.notes.indices) continue
                    st.notes[k] = if (key == "fake") st.notes[k].copy(fake = true)
                    else st.notes[k].copy(above = false)
                }
            } else {
                val k = o.optInt(key, -1)
                if (k in st.notes.indices) {
                    st.notes[k] = if (key == "fake") st.notes[k].copy(fake = true)
                    else st.notes[k].copy(above = false)
                }
            }
            return true
        }
        return false
    }

    private fun ms(v: Double, durationMs: Long): Long {
        val t = Math.round(v)
        return t.coerceIn(0L, durationMs.coerceAtLeast(0L))
    }

    /** 音符级流速夹取（RPE speed，可小于/大于 1；0 会冻结下落，夹到 0.05 起） */
    private fun clampNoteSpeed(v: Double): Float = v.coerceIn(0.05, 50.0).toFloat()

    /** 音符类型 → 录制阶段（1 tap / 4 drag / 3 flick / 2 hold） */
    private fun passOfType(type: Int): Int = when (type) {
        1 -> 1
        4 -> 2
        3 -> 3
        else -> 4
    }

    /** 把谱面压成文本给 AI 看（时间点/位置/类型），太多时抽样（省 token：只保留分布，不逐条全给） */
    fun chartDigest(
        lines: List<Line>,
        notes: List<RecordedNote>,
        maxNotes: Int = 250,
        noises: List<NoiseArea> = emptyList()
    ): String {
        val sb = StringBuilder()
        sb.append("判定线：")
        for (l in lines) {
            sb.append("id=").append(l.id).append(" pos=").append(l.posPct)
                .append(" speed=").append(trim(l.speed.toDouble()))
                .append(" rot=").append(trim(l.rotate.toDouble()))
                .append(" kf=").append(l.keyframes.size)
                // 素材线带类型与素材名、图层号也给出，AI 换图/换字/改层才有依据
                if (l.lineText.isNotEmpty()) sb.append(" text=").append(l.lineText)
                else if (l.texture.isNotEmpty()) sb.append(" tex=").append(l.texture)
                if (l.zOrder != 0) sb.append(" layer=").append(l.zOrder)
                sb.append("；")
        }
        // 关键帧明细：AI 要"调整判定线关键帧"就必须知道现在有哪些帧（时间 + 姿态 + 流速 + 透明度）；
        // 超过 40 帧只给前 40 个（多数谱面的帧集中在主线上，足够 AI 判断）
        var kfShown = 0
        var kfCut = false
        for (l in lines) {
            for (k in l.sortedKeyframes()) {
                if (kfShown >= 40) { kfCut = true; break }
                sb.append("\nkf line=").append(l.id).append(" t=").append(k.timeMs)
                    .append(" x=").append(trim(k.moveX.toDouble()))
                    .append(" y=").append(trim(k.moveY.toDouble()))
                    .append(" r=").append(trim(k.rotate.toDouble()))
                    .append(" sp=").append(trim(k.speed.toDouble()))
                    .append(" a=").append(trim(k.alpha.toDouble()))
                kfShown++
            }
            if (kfCut) break
        }
        if (kfCut) sb.append("\nkf …（更多关键帧省略）")

        sb.append("\n音符共 ").append(notes.size).append(" 个")
        val step = if (notes.size > maxNotes) Math.ceil(notes.size.toDouble() / maxNotes).toInt() else 1
        sb.append(if (step > 1) "（每 " + step + " 个抽 1 个展示）" else "").append("：\n")
        for (i in notes.indices step step) {
            val n = notes[i]
            sb.append(i).append(":").append(n.type).append(" t=").append(n.startMs)
            if (n.endMs > n.startMs) sb.append(" end=").append(n.endMs)
            sb.append(" x=").append(trim(n.posX.toDouble()))
            sb.append(" line=").append(n.lineId)
            if (!n.above) sb.append(" rev")
            if (n.fake) sb.append(" fake")
            sb.append('\n')
        }

        // 噪域清单：i 下标 = area 命令的下标；官谱能有一万多块 ⇒ 只给前 60 个（抽样展示）
        if (noises.isNotEmpty()) {
            sb.append("\n噪域共 ").append(noises.size).append(" 块")
            val hStep = if (noises.size > 60) Math.ceil(noises.size / 60.0).toInt() else 1
            sb.append(if (hStep > 1) "（每 " + hStep + " 块抽 1 块展示）" else "").append("：\n")
            for (i in noises.indices step hStep) {
                val a = noises[i]
                sb.append(i).append(": l=").append(trim(a.left.toDouble()))
                    .append(" b=").append(trim(a.bottom.toDouble()))
                    .append(" r=").append(trim(a.right.toDouble()))
                    .append(" t=").append(trim(a.top.toDouble()))
                    .append(" appear=").append(trim(a.appearMs))
                    .append(" disappear=")
                    .append(if (a.disappearMs >= Double.MAX_VALUE / 2) "一直" else trim(a.disappearMs))
                    .append(if (a.invert) " invert" else "")
                    .append(if (!a.visible) " 隐藏" else "")
                    .append(if (a.warning) " 警示区" else "")
                if (a.layerId.isNotEmpty()) sb.append(" layer=").append(a.layerId)
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    /** 节拍特征文本：`时间|强度0-9+主频段(L/M/H)+维持时长(×100ms)`，一行一个点（省 token：上限 5500 字符） */
    fun beatsText(beats: List<AudioBeats.Beat>): String {
        if (beats.isEmpty()) return "（没解析到音频重音，请按 BPM 均匀节拍编排）"
        val sb = StringBuilder()
        for ((i, b) in beats.withIndex()) {
            if (i > 0) sb.append('\n')
            sb.append(b.t).append('|')
                .append((b.strength * 9f).toInt().coerceIn(0, 9))
                .append(b.dominant)
                .append(b.sustainMs / 100)
            if (sb.length > 5500) { sb.append("\n…"); break }
        }
        return sb.toString()
    }

    private fun trim(v: Double): String =
        if (v == Math.floor(v)) v.toLong().toString() else String.format(java.util.Locale.US, "%.2f", v)
}
