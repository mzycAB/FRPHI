package com.piezmaker

/**
 * 谱面草稿的编解码（纯逻辑，不依赖 Android，可单元测试）。
 *
 * 为什么不用 org.json：一个 1 万音符的谱面，org.json 要建 1 万个 JSONObject + 拼接字符串，
 * 解析和生成各要上百毫秒，而这两件事都在 UI 线程（导入、进编辑页、退出保存）。
 * 这里用逐行文本，生成靠 StringBuilder，解析靠手写扫描，快一个数量级。
 *
 * 格式 v3（行首标记 + 空格分隔）：
 * ```
 * #FRPHI-DRAFT 3
 * L 0 60 0.12 -0.4 0 1.6      # 判定线：id posPct moveX moveY rotate speed
 * K 0 1000 0.12 -0.4 0 1      # 关键帧：lineId 时间 moveX moveY rotate speed
 * N 0 1 1000 1000 0.5 1 1 0   # 音符：lineId type startMs endMs posX pass above fake
 * ```
 * 兼容：
 *  - v2（只有一条判定线）：`mx / my / rot / spd` 四个全局值 + `K t x y r` + `N type s e x pass`
 *  - 更早的 RPEJSON 风格草稿（以 `{` 开头）会自动识别并走 JSON 分支
 */
object DraftCodec {

    const val HEADER = "#FRPHI-DRAFT 3"

    /** 解出来的草稿内容（判定线可能有多条） */
    class Draft(
        val lines: List<Line>,
        val notes: List<RecordedNote>,
        val clip: List<RecordView.ClipEntry> = emptyList()
    ) {
        /** 兼容旧调用：主判定线的关键帧/姿态/流速 */
        val keyframes: List<LineKeyframe> get() = lines.firstOrNull()?.keyframes ?: emptyList()
        val moveX: Float get() = lines.firstOrNull()?.moveX ?: 0f
        val moveY: Float get() = lines.firstOrNull()?.moveY ?: 0f
        val rotate: Float get() = lines.firstOrNull()?.rotate ?: 0f
        val speed: Float get() = lines.firstOrNull()?.speed ?: 1f
    }

    fun encode(
        lines: List<Line>,
        notes: List<RecordedNote>,
        clip: List<RecordView.ClipEntry> = emptyList()
    ): String {
        val sb = StringBuilder(160 + notes.size * 34 + lines.sumOf { it.keyframes.size } * 34)
        sb.append(HEADER).append('\n')
        for (l in lines) {
            sb.append("L ").append(l.id).append(' ').append(l.posPct).append(' ')
                .append(f(l.moveX)).append(' ').append(f(l.moveY)).append(' ')
                .append(f(l.rotate)).append(' ').append(f(clampLineSpeed(l.speed))).append(' ')
                .append(f(l.alpha.coerceIn(0f, 1f))).append('\n')
            for (k in l.keyframes) {
                sb.append("K ").append(l.id).append(' ').append(k.timeMs).append(' ')
                    .append(f(k.moveX)).append(' ').append(f(k.moveY)).append(' ')
                    .append(f(k.rotate)).append(' ').append(f(clampLineSpeed(k.speed))).append(' ')
                    .append(f(k.alpha.coerceIn(0f, 1f))).append('\n')
            }
            // 判定线名字（RPE 的 Name）：单独一行，名字里可以有空格，取整行剩余
            if (l.name.isNotEmpty()) {
                sb.append("LN ").append(l.id).append(' ').append(l.name).append('\n')
            }
        }
        for (n in notes) {
            sb.append("N ").append(n.lineId).append(' ').append(n.type).append(' ')
                .append(n.startMs).append(' ').append(n.endMs).append(' ')
                .append(f(n.posX)).append(' ').append(n.pass).append(' ')
                .append(if (n.above) 1 else 0).append(' ').append(if (n.fake) 1 else 0).append('\n')
        }
        // 剪贴板（联机同步用）：每条一个 CB 头 + 若干 N。
        // 以前这里**根本没写** clip（decode 那边却早就会读 CB），所以联机时剪贴板永远同步不过去。
        for (e in clip) {
            sb.append("CB ").append(if (e.cut) 1 else 0).append('\n')
            for (n in e.notes) {
                sb.append("N ").append(n.lineId).append(' ').append(n.type).append(' ')
                    .append(n.startMs).append(' ').append(n.endMs).append(' ')
                    .append(f(n.posX)).append(' ').append(n.pass).append(' ')
                    .append(if (n.above) 1 else 0).append(' ').append(if (n.fake) 1 else 0).append('\n')
            }
        }
        return sb.toString()
    }

    /** 兼容旧调用：单判定线 */
    fun encode(
        notes: List<RecordedNote>,
        keyframes: List<LineKeyframe>,
        moveX: Float,
        moveY: Float,
        rotate: Float,
        speed: Float
    ): String {
        val l = Line(0)
        l.moveX = moveX; l.moveY = moveY; l.rotate = rotate; l.speed = speed
        l.keyframes.addAll(keyframes)
        l.invalidate()
        return encode(listOf(l), notes)
    }

    /** 解析草稿；无法识别返回 null（调用方保持原样即可） */
    fun decode(text: String): Draft? {
        val t = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (t.isEmpty()) return null
        return if (t.startsWith("{")) decodeJson(t) else decodeText(t)
    }

    // ------------------------------------------------------------------ 文本（v2 / v3）

    private fun decodeText(text: String): Draft? {
        var mx = 0f
        var my = 0f
        var rot = 0f
        var spd = 1f
        val notes = ArrayList<RecordedNote>()
        val lines = LinkedHashMap<Int, Line>()
        var sawHeader = false
        var sawLineHeader = false
        var clipBuf: MutableList<RecordedNote>? = null
        var clipCut = false
        val clipEntries = ArrayList<RecordView.ClipEntry>()

        fun lineOf(id: Int): Line = lines.getOrPut(id) { Line(id) }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line[0] == '#') { sawHeader = true; continue }
            val sp = line.indexOf(' ')
            if (sp <= 0) continue
            val key = line.substring(0, sp)
            val rest = line.substring(sp + 1)
            when (key) {
                "mx" -> mx = rest.toFloatOrNull() ?: mx
                "my" -> my = rest.toFloatOrNull() ?: my
                "rot" -> rot = rest.toFloatOrNull() ?: rot
                "spd" -> spd = rest.toFloatOrNull() ?: spd
                // v3：L id posPct mx my rot spd
                "L" -> {
                    val p = rest.split(' ')
                    if (p.size >= 6) {
                        val id = p[0].toIntOrNull() ?: 0
                        val pct = p[1].toIntOrNull() ?: 60
                        val l = lineOf(id)
                        l.posPct = pct.coerceIn(0, 100)
                        l.moveX = p[2].toFloatOrNull() ?: 0f
                        l.moveY = p[3].toFloatOrNull() ?: 0f
                        l.rotate = p[4].toFloatOrNull() ?: 0f
                        l.speed = clampLineSpeed(p[5].toFloatOrNull() ?: 1f)
                        l.alpha = (p.getOrNull(6)?.toFloatOrNull() ?: 1f).coerceIn(0f, 1f)
                        sawLineHeader = true
                    }
                }
                // LN lineId name…（名字可以带空格）
                "LN" -> {
                    val p = rest.split(' ', limit = 2)
                    val lid = p.getOrNull(0)?.toIntOrNull()
                    val nm = p.getOrNull(1) ?: ""
                    if (lid != null) lineOf(lid).name = nm else lines.values.lastOrNull()?.name = nm
                }
                // v3：K lineId t mx my rot spd ；v2：K t mx my rot
                "K" -> {
                    val p = rest.split(' ')
                    if (p.size >= 6) {
                        val t = p[1].toLongOrNull()
                        val x = p[2].toFloatOrNull()
                        val y = p[3].toFloatOrNull()
                        val r = p[4].toFloatOrNull()
                        val s = p[5].toFloatOrNull()
                        if (t != null && x != null && y != null && r != null) {
                            val id = p[0].toIntOrNull() ?: 0
                            val al = (p.getOrNull(6)?.toFloatOrNull() ?: 1f).coerceIn(0f, 1f)
                            lineOf(id).keyframes.add(LineKeyframe(t, x, y, r, clampLineSpeed(s ?: 1f), al))
                        }
                    } else if (p.size >= 4) {
                        val t = p[0].toLongOrNull()
                        val x = p[1].toFloatOrNull()
                        val y = p[2].toFloatOrNull()
                        val r = p[3].toFloatOrNull()
                        if (t != null && x != null && y != null && r != null) {
                            lineOf(0).keyframes.add(LineKeyframe(t, x, y, r))
                        }
                    }
                }
                // v3：N lineId type s e x pass above fake ；v2：N type s e x pass
                "CB" -> {
                    clipBuf?.let { if (it.isNotEmpty()) clipEntries.add(RecordView.ClipEntry(it, clipCut)) }
                    clipCut = (rest.split(' ').firstOrNull()?.toIntOrNull() ?: 0) != 0
                    clipBuf = ArrayList()
                }
                "N" -> {
                    val p = rest.split(' ')
                    if (p.size >= 8) {
                        val id = p[0].toIntOrNull() ?: 0
                        val ty = p[1].toIntOrNull()
                        val st = p[2].toLongOrNull()
                        val en = p[3].toLongOrNull()
                        val x = p[4].toFloatOrNull()
                        if (ty != null && st != null && en != null && x != null) {
                            val clipTarget = clipBuf
                            if (clipTarget != null) {
                                clipTarget.add(
                                    RecordedNote(
                                        type = ty, startMs = st, endMs = en, posX = x,
                                        pass = p[5].toIntOrNull() ?: 1, lineId = id,
                                        above = (p[6].toIntOrNull() ?: 1) != 0,
                                        fake = (p[7].toIntOrNull() ?: 0) != 0
                                    )
                                )
                                continue
                            }
                            notes.add(
                                RecordedNote(
                                    type = ty, startMs = st, endMs = en, posX = x,
                                    pass = p[5].toIntOrNull() ?: 1, lineId = id,
                                    above = (p[6].toIntOrNull() ?: 1) != 0,
                                    fake = (p[7].toIntOrNull() ?: 0) != 0
                                )
                            )
                        }
                    } else if (p.size >= 5) {
                        val ty = p[0].toIntOrNull()
                        val st = p[1].toLongOrNull()
                        val en = p[2].toLongOrNull()
                        val x = p[3].toFloatOrNull()
                        if (ty != null && st != null && en != null && x != null) {
                            notes.add(RecordedNote(ty, st, en, x, p[4].toIntOrNull() ?: 1))
                        }
                    }
                }
            }
        }
        clipBuf?.let { if (it.isNotEmpty()) clipEntries.add(RecordView.ClipEntry(it, clipCut)) }
        clipBuf = null
        if (!sawHeader && notes.isEmpty() && lines.isEmpty()) return null

        // 没有 L 行 = v2 草稿（只有 mx/my/rot/spd 全局值 + 单线关键帧）→ 折成唯一一条判定线
        if (!sawLineHeader) {
            if (lines.isEmpty()) lines[0] = Line(0)
            val l = lines[0]!!
            l.posPct = -1                     // -1 = 沿用设置页的判定线位置（v2 草稿没存）
            l.moveX = mx; l.moveY = my; l.rotate = rot; l.speed = clampLineSpeed(spd)
        }
        val list = lines.values.toMutableList()
        list.sortBy { it.id }
        list.forEach { it.invalidate() }

        // 音符的 lineId 可能指向不存在的线（手改文件）→ 归到第一条
        val ids = list.map { it.id }.toHashSet()
        val firstId = list.first().id
        val fixed = notes.map { if (it.lineId in ids) it else it.copy(lineId = firstId) }
        return Draft(list, fixed, clipEntries)
    }

    // ------------------------------------------------------------------ 旧 JSON

    private fun decodeJson(text: String): Draft? {
        return try {
            val doc = org.json.JSONObject(text)
            val notes = ArrayList<RecordedNote>()
            doc.optJSONArray("notes")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    notes.add(
                        RecordedNote(
                            o.optInt("t", 1),
                            o.optLong("s", 0L),
                            o.optLong("e", 0L),
                            o.optDouble("x", 0.0).toFloat(),
                            o.optInt("p", 1),
                            lineId = o.optInt("l", 0),
                            above = o.optInt("ab", 1) != 0,
                            fake = o.optInt("fk", 0) != 0
                        )
                    )
                }
            }
            val l = Line(0)
            l.moveX = doc.optDouble("mx", 0.0).toFloat()
            l.moveY = doc.optDouble("my", 0.0).toFloat()
            l.rotate = doc.optDouble("rot", 0.0).toFloat()
            l.speed = clampLineSpeed(doc.optDouble("spd", 1.0).toFloat())
            l.posPct = doc.optInt("pct", -1)
            doc.optJSONArray("kf")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    l.keyframes.add(
                        LineKeyframe(
                            o.optLong("t", 0L),
                            o.optDouble("x", 0.0).toFloat(),
                            o.optDouble("y", 0.0).toFloat(),
                            o.optDouble("r", 0.0).toFloat(),
                            clampLineSpeed(o.optDouble("s", 1.0).toFloat())
                        )
                    )
                }
            }
            l.invalidate()
            Draft(listOf(l), notes)
        } catch (_: Exception) {
            null
        }
    }

    /** 浮点转文本：最多 4 位小数，去掉多余的 0 */
    private fun f(v: Float): String {
        if (!v.isFinite()) return "0"
        val r = Math.round(v * 10000f) / 10000f
        if (r == Math.floor(r.toDouble()).toFloat() && Math.abs(r) < 1e9f) return r.toInt().toString()
        return r.toString()
    }
}
