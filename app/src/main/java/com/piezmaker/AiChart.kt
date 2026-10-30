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
        /** 自动写谱：强制正面音符（above=1）、非假音符 */
        var writeMode: Boolean = false,
        /** 自动写谱：如果 AI 全给了 Tap，套用时按比例补成 Drag/Flick */
        var diversify: Boolean = false,
        /** 套用时用的难度（0~20），用于控制键型/长条比例 */
        var difficulty: Double = 8.0
    )

    fun copyLines(src: List<Line>): MutableList<Line> = src.map { s ->
        Line(s.id).apply {
            posPct = s.posPct
            moveX = s.moveX; moveY = s.moveY; rotate = s.rotate
            speed = s.speed; alpha = s.alpha; name = s.name
            keyframes.addAll(s.keyframes)
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
            st.notes.add(RecordedNote(type, t, end, x, passOfType(type), line.id, above, fake))
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
            l.invalidate()
            return true
        }

        if (o.has("kf")) {
            // 自动写谱不写判定线关键帧（会连带改流速，和用户设置的「流速」冲突）
            if (st.writeMode) return true
            val l = lineOf(st, o.optInt("kf", 0))
            val t = ms(o.optDouble("t", 0.0), durationMs)
            val x = o.optDouble("x", 0.0).coerceIn(-1.0, 1.0).toFloat()
            val y = o.optDouble("y", 0.0).coerceIn(-1.0, 1.0).toFloat()
            val r = o.optDouble("r", 0.0).toFloat()
            val sp = clampLineSpeed(o.optDouble("sp", l.speed.toDouble()).toFloat())
            val a = o.optDouble("a", 1.0).coerceIn(0.0, 1.0).toFloat()
            l.putKeyframe(t, x, y, r, sp)
            l.keyframes.firstOrNull { it.timeMs == t }?.let { k ->
                val i = l.keyframes.indexOf(k)
                if (i >= 0) l.keyframes[i] = k.copy(alpha = a)
            }
            l.invalidate()
            return true
        }

        if (o.has("fix")) {
            val i = o.optInt("fix", -1)
            if (i !in st.notes.indices) return false
            val n = st.notes[i]
            val t = if (o.has("t")) ms(o.optDouble("t", n.startMs.toDouble()), durationMs) else n.startMs
            val end = if (o.has("end")) maxOf(t, ms(o.optDouble("end", n.endMs.toDouble()), durationMs)) else maxOf(t, n.endMs)
            val x = if (o.has("x")) o.optDouble("x", n.posX.toDouble()).coerceIn(-1.0, 1.0).toFloat() else n.posX
            st.notes[i] = n.copy(startMs = t, endMs = end, posX = x)
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

    /** 音符类型 → 录制阶段（1 tap / 4 drag / 3 flick / 2 hold） */
    private fun passOfType(type: Int): Int = when (type) {
        1 -> 1
        4 -> 2
        3 -> 3
        else -> 4
    }

    /** 把谱面压成文本给 AI 看（时间点/位置/类型），太多时抽样 */
    fun chartDigest(lines: List<Line>, notes: List<RecordedNote>, maxNotes: Int = 600): String {
        val sb = StringBuilder()
        sb.append("判定线：")
        for (l in lines) {
            sb.append("id=").append(l.id).append(" pos=").append(l.posPct)
                .append(" speed=").append(trim(l.speed.toDouble()))
                .append(" rot=").append(trim(l.rotate.toDouble()))
                .append(" kf=").append(l.keyframes.size).append("；")
        }
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
        return sb.toString()
    }

    /** 节拍特征文本：`时间|强度0-9+主频段(L/M/H)+维持时长(×100ms)`，一行一个点 */
    fun beatsText(beats: List<AudioBeats.Beat>): String {
        if (beats.isEmpty()) return "（没解析到音频重音，请按 BPM 均匀节拍编排）"
        val sb = StringBuilder()
        for ((i, b) in beats.withIndex()) {
            if (i > 0) sb.append('\n')
            sb.append(b.t).append('|')
                .append((b.strength * 9f).toInt().coerceIn(0, 9))
                .append(b.dominant)
                .append(b.sustainMs / 100)
            if (sb.length > 7000) { sb.append("\n…"); break }
        }
        return sb.toString()
    }

    private fun trim(v: Double): String =
        if (v == Math.floor(v)) v.toLong().toString() else String.format(java.util.Locale.US, "%.2f", v)
}
