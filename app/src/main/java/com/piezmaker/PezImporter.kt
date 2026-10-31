package com.piezmaker

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 从 .pez（Phira / RPE 谱面包）解析出的一条判定线。
 * 纯数据 + 纯 JVM 逻辑，不碰任何 Android API，便于单元测试。
 */
class ImportedLine(
    /** 本项目里的判定线 id（就是 RPE 里的下标） */
    val id: Int,
    /** RPE 里的判定线 Name（原样保留，导出时写回） */
    val name: String = "",
    /** 判定线位置：屏幕纵向百分比 0~100（与设置页「判定线位置」同一含义） */
    val posPct: Int,
    /** 全局偏移（归一化 -1..1）与旋转（度），与 RecordView 的 line.moveX/Y/rotate 一致 */
    val moveX: Float,
    val moveY: Float,
    val rotate: Float,
    /** 没有关键帧时的流速 */
    val speed: Float,
    /** 没有关键帧时的透明度 0~1（RPE alphaEvents / 255） */
    val alpha: Float,
    /** 判定线动画关键帧（含逐帧流速；空 = 全程静止） */
    val keyframes: List<LineKeyframe>,
    val notes: List<RecordedNote>
)

/**
 * 从 .pez（Phira / RPE 谱面包）解析出的谱面数据。
 * 纯数据 + 纯 JVM 逻辑，不碰任何 Android API，便于单元测试。
 */
class ImportedChart(
    val name: String,
    val composer: String,
    val charter: String,
    val level: String,
    val bpm: Double,
    val bpmChanges: Int,
    /** 主判定线流速（RPE speed 值，1.0 = 正常） */
    val speed: Double,
    /** 主判定线的位置/偏移/旋转（设置页字段用） */
    val linePosPct: Int,
    val lineMoveX: Float,
    val lineMoveY: Float,
    val lineRotate: Float,
    /** 判定线（至少一条，含各自的关键帧与音符） */
    val lines: List<ImportedLine>,
    /** 横线数：时间量化（每拍几格），由谱面时间分母反推（只影响编辑网格） */
    val hLines: Int,
    /** 竖线数：位置量化，由 positionX 反推（只影响编辑网格） */
    val vLines: Int,
    /** 包内音频条目名（null = 没找到音频） */
    val musicName: String?,
    /** 包内背景图条目名（null = 没找到背景图） */
    val imageName: String?,
    val warnings: List<String>
) {
    /** 全部音符（按时间排序，跨判定线合并） */
    val notes: List<RecordedNote> = lines.flatMap { it.notes }.sortedBy { it.startMs }

    /** 判定线条数 */
    val lineCount: Int get() = lines.size

    /** 兼容旧调用：主判定线的关键帧 */
    val keyframes: List<LineKeyframe> get() = lines.firstOrNull()?.keyframes ?: emptyList()
}

/**
 * PEZ 导入器：解析 RPE JSON 谱面 + 提取音频/背景图。
 *
 * 对照 Phira 解析端 `prpr/src/parse/rpe.rs` 实现，关键点：
 *  - BPMList 支持多 BPM 分段换算（Triple `[i,n,d]` = `i + n/d` 拍）
 *  - META.offset（毫秒）：Phira 里 `谱面时间 = 音乐时间 - offset`，所以音乐时间 = 谱面时间 + offset
 *  - judgeLine 的 moveX/moveY/rotate/speed 事件 → 本项目的全局姿态 + 线性关键帧
 *  - positionX 除以 675 归一化；moveY 除以 450 归一化（RPE_HEIGHT=900 的一半）
 *  - 多层 eventLayers 是**相加**关系（Phira `Anim::chain`）
 */
object PezImporter {

    val AUDIO_EXTS = setOf("mp3", "ogg", "wav", "flac", "aac", "m4a")
    val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "bmp", "gif", "webp", "avif")
    private val TEXT_EXTS = setOf("json", "txt", "yml", "yaml", "csv")
    private const val MAX_TEXT = 8L * 1024 * 1024
    /** 横线数上限（再密网格就没有可读性了） */
    private const val MAX_HLINES = 96
    private const val MAX_KEYFRAMES = 2000

    private val H_CANDIDATES = intArrayOf(1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96)
    private val V_CANDIDATES = intArrayOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 24, 32, 40, 48, 64)

    /**
     * 解析 pez。
     * @param openZip 每次调用返回一个**新的** ZipInputStream（需要遍历两遍：先读文本，再抽素材）
     * @param saveAsset 抽素材回调：条目名 + 该条目的输入流，返回是否成功（默认不抽）
     */
    fun import(
        openZip: () -> ZipInputStream,
        saveAsset: (String, InputStream) -> Boolean = { _, _ -> false }
    ): ImportedChart {
        val warnings = ArrayList<String>()

        // ---------- 第一遍：只读文本类条目（谱面 JSON / info.txt） ----------
        val texts = LinkedHashMap<String, ByteArray>()
        val names = ArrayList<String>()
        openZip().use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val n = cleanName(e.name)
                if (n.isEmpty()) continue
                names.add(n)
                if (n.substringAfterLast('.', "").lowercase() in TEXT_EXTS) {
                    readBounded(z, MAX_TEXT)?.let { texts[n] = it }
                }
            }
        }
        if (names.isEmpty()) throw IllegalArgumentException("压缩包是空的")

        // ---------- info.txt（RPE 标准：# 开头 + Key: Value） ----------
        val info = texts.entries.firstOrNull { baseName(it.key).equals("info.txt", true) }
            ?.let { runCatching { parseInfoTxt(decodeText(it.value)) }.getOrNull() }
            ?: emptyMap()

        // ---------- 谱面 JSON ----------
        val picked = pickChart(texts, info["Chart"])
            ?: run {
                val hasPec = names.any { it.substringAfterLast('.', "").equals("pec", true) }
                throw IllegalArgumentException(
                    if (hasPec) "这是 pec 格式谱面，暂不支持（只支持 RPE JSON）"
                    else "压缩包里没有找到谱面 JSON"
                )
            }
        val doc = picked

        val meta = doc.optJSONObject("META")
        val offsetMs = meta?.optDouble("offset", 0.0)?.takeIf { it.isFinite() } ?: 0.0

        // ---------- BPM ----------
        val bpmItems = ArrayList<Pair<Double, Double>>()   // (beats, bpm)
        val bpmArr = doc.optJSONArray("BPMList")
        if (bpmArr != null) for (i in 0 until bpmArr.length()) {
            val o = bpmArr.optJSONObject(i) ?: continue
            val b = o.optDouble("bpm", 120.0)
            if (!b.isFinite() || b <= 0.0) continue
            val st = o.optJSONArray("startTime")
            bpmItems.add((if (st != null) tripleBeats(st) else 0.0) to b)
        }
        if (bpmItems.isEmpty()) bpmItems.add(0.0 to 120.0)
        bpmItems.sortBy { it.first }
        val bpmMap = BpmMap(bpmItems)
        val bpm0 = bpmItems.first().second
        if (bpmItems.map { it.second }.distinct().size > 1) {
            warnings.add("多 BPM 谱面（${bpmItems.size} 段）：已按首 BPM ${trimNum(bpm0)} 记录，导出后节奏可能与原谱不同")
        }

        // ---------- 判定线（保留多线，不再合并） ----------
        val linesArr = doc.optJSONArray("judgeLineList")
            ?: throw IllegalArgumentException("谱面缺少 judgeLineList")
        if (linesArr.length() == 0) throw IllegalArgumentException("谱面里没有判定线")

        val parsedLines = (0 until linesArr.length()).mapNotNull { i ->
            linesArr.optJSONObject(i)?.let { parseLine(it, bpmMap) }
        }
        if (parsedLines.isEmpty()) throw IllegalArgumentException("谱面里没有可用的判定线")

        val importLines = ArrayList<ImportedLine>()
        var fakeCount = 0
        var backCount = 0
        var yOffsetCount = 0
        var dropped = 0
        var emptyLines = 0
        val beatsForGrid = ArrayList<Double>()
        val denomsForGrid = ArrayList<Int>()
        val posForGrid = ArrayList<Double>()

        for ((li, line) in parsedLines.withIndex()) {
            val pose = buildPose(line, offsetMs / 1000.0, warnings)
            val lineNotes = ArrayList<RecordedNote>()
            for (n in line.rawNotes) {
                val kind = n.type
                if (kind !in 1..4) { dropped++; continue }
                if (n.fake) fakeCount++
                if (!n.above) backCount++
                if (Math.abs(n.yOffset) > 1e-6) yOffsetCount++

                val startMs = (n.startSec * 1000.0 + offsetMs).let { if (it < 0) 0.0 else it }
                val endMs = if (kind == 2) (n.endSec * 1000.0 + offsetMs).coerceAtLeast(startMs) else startMs
                val posX = (n.positionX / 675.0).coerceIn(-1.0, 1.0)

                lineNotes.add(
                    RecordedNote(
                        type = kind,
                        startMs = Math.round(startMs),
                        endMs = Math.round(endMs),
                        posX = posX.toFloat(),
                        pass = passOf(kind),
                        lineId = li,
                        above = n.above,
                        fake = n.fake
                    )
                )
                beatsForGrid.add(n.startBeats)
                denomsForGrid.add(n.startDenom)
                posForGrid.add(n.positionX / 675.0)
            }
            // 没有音符的判定线也要保留：那是表演用的装饰线（只有关键帧动画，不挂音符）
            if (lineNotes.isEmpty()) emptyLines++
            lineNotes.sortBy { it.startMs }
            importLines.add(
                ImportedLine(
                    id = li,
                    name = line.name,
                    posPct = pose.pct,
                    moveX = pose.moveX,
                    moveY = pose.moveY,
                    rotate = pose.rotate,
                    speed = pose.speed,
                    alpha = pose.alpha,
                    keyframes = pose.keyframes,
                    notes = lineNotes
                )
            )
        }
        if (importLines.isEmpty()) throw IllegalArgumentException("谱面里没有可导入的音符")
        if (emptyLines > 0) warnings.add("有 $emptyLines 条判定线没有音符（表演用的装饰线），已按原样保留")
        if (fakeCount > 0) warnings.add("已识别 $fakeCount 个假音符（isFake：只显示、不判定）")
        if (backCount > 0) warnings.add("已识别 $backCount 个反面音符（above=0：从判定线另一侧落下）")
        if (yOffsetCount > 0) warnings.add("$yOffsetCount 个音符有 yOffset（纵向偏移），本项目不支持，已忽略")
        if (dropped > 0) warnings.add("已跳过 $dropped 个未知类型音符")

        // 主判定线 = 音符最多的一条（设置页的"判定线位置/流速"填它）
        val main = importLines.maxByOrNull { it.notes.size } ?: importLines.first()

        // ---------- 横线 / 竖线 ----------
        val hLines = inferHLines(beatsForGrid, denomsForGrid, warnings)
        val vLines = inferVLines(posForGrid, warnings)

        // ---------- 素材 ----------
        val songName = meta?.optString("song")?.takeIf { it.isNotBlank() }
            ?: info["Song"] ?: info["Music"]
        val bgName = meta?.optString("background")?.takeIf { it.isNotBlank() }
            ?: info["Picture"] ?: info["Image"]

        val musicEntry = pickEntry(names, songName, AUDIO_EXTS)
        val imageEntry = pickEntry(names, bgName, IMAGE_EXTS)
        if (musicEntry != null || imageEntry != null) {
            // 第二遍：只抽这两个条目，边读边写，不整包进内存
            val want = setOfNotNull(musicEntry, imageEntry)
            runCatching {
                openZip().use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        if (e.isDirectory) continue
                        val n = cleanName(e.name)
                        if (n in want) saveAsset(n, z)
                    }
                }
            }.onFailure { warnings.add("素材提取失败：${it.message}") }
        }

        // ---------- 文本信息 ----------
        val name = meta?.optString("name")?.takeIf { it.isNotBlank() }
            ?: info["Name"]?.takeIf { it.isNotBlank() } ?: "导入谱面"
        val composer = meta?.optString("composer")?.takeIf { it.isNotBlank() }
            ?: info["Composer"] ?: info["Artist"] ?: info["Musician"] ?: ""
        val charter = meta?.optString("charter")?.takeIf { it.isNotBlank() }
            ?: info["Charter"] ?: info["Designer"] ?: ""
        val level = meta?.optString("level")?.takeIf { it.isNotBlank() }
            ?: info["Level"] ?: ""

        return ImportedChart(
            name = name,
            composer = composer,
            charter = charter,
            level = level,
            bpm = bpm0,
            bpmChanges = bpmItems.size,
            speed = main.speed.toDouble(),
            linePosPct = main.posPct,
            lineMoveX = main.moveX,
            lineMoveY = main.moveY,
            lineRotate = main.rotate,
            lines = importLines,
            hLines = hLines,
            vLines = vLines,
            musicName = musicEntry,
            imageName = imageEntry,
            warnings = warnings
        )
    }

    // ------------------------------------------------------------------ 判定线

    /** 单条判定线：事件 + 原始音符 */
    private class ParsedLine(
        val name: String,
        val texture: String,
        val moveX: LayerAnim,
        val moveY: LayerAnim,
        val rotate: LayerAnim,
        val speed: LayerAnim,
        /** 透明度 0~255 的动画（alphaEvents） */
        val alpha: LayerAnim,
        val rawNotes: List<RawNote>
    )

    private class RawNote(
        val type: Int,
        val startSec: Double,
        val endSec: Double,
        val startBeats: Double,
        val startDenom: Int,
        val above: Boolean,
        val positionX: Double,
        val yOffset: Double,
        val fake: Boolean
    )

    private fun parseLine(line: JSONObject, bpm: BpmMap): ParsedLine {
        val texture = line.optString("Texture", "")

        val moveX = ArrayList<Anim>()
        val moveY = ArrayList<Anim>()
        val rotate = ArrayList<Anim>()
        val speed = ArrayList<Anim>()
        val alphaEv = ArrayList<Anim>()

        val layers = line.optJSONArray("eventLayers")
        if (layers != null) for (i in 0 until layers.length()) {
            val l = layers.optJSONObject(i) ?: continue
            toAnim(bpm, l.optJSONArray("moveXEvents")).takeIf { !it.isEmpty }?.let { moveX.add(it) }
            toAnim(bpm, l.optJSONArray("moveYEvents")).takeIf { !it.isEmpty }?.let { moveY.add(it) }
            toAnim(bpm, l.optJSONArray("rotateEvents")).takeIf { !it.isEmpty }?.let { rotate.add(it) }
            toAnim(bpm, l.optJSONArray("speedEvents")).takeIf { !it.isEmpty }?.let { speed.add(it) }
            toAnim(bpm, l.optJSONArray("alphaEvents")).takeIf { !it.isEmpty }?.let { alphaEv.add(it) }
        }

        val notes = ArrayList<RawNote>()
        val arr = line.optJSONArray("notes")
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val st = o.optJSONArray("startTime") ?: continue
            val et = o.optJSONArray("endTime") ?: st
            val startSec = bpm.seconds(tripleBeats(st))
            notes.add(
                RawNote(
                    type = o.optInt("type", 1),
                    startSec = startSec,
                    endSec = bpm.seconds(tripleBeats(et)),
                    startBeats = tripleBeats(st),
                    startDenom = st.optInt(2, 1),
                    above = o.optInt("above", 1) == 1,
                    positionX = o.optDouble("positionX", 0.0).takeIf { it.isFinite() } ?: 0.0,
                    yOffset = o.optDouble("yOffset", 0.0).takeIf { it.isFinite() } ?: 0.0,
                    fake = o.optInt("isFake", 0) != 0
                )
            )
        }
        return ParsedLine(
            line.optString("Name", ""),
            texture, LayerAnim(moveX), LayerAnim(moveY), LayerAnim(rotate), LayerAnim(speed),
            LayerAnim(alphaEv), notes
        )
    }

    private class Pose(
        val pct: Int,
        val moveX: Float,
        val moveY: Float,
        val rotate: Float,
        val speed: Float,
        val alpha: Float,
        val keyframes: List<LineKeyframe>
    )

    /**
     * 事件 → 本项目姿态 + 流速关键帧：
     *  - 屏幕 y = 判定线基准(百分比) + moveY归一化 × 半屏高
     *  - RPE moveY 向上为正 → 百分比 pct = 50 - moveY/9（与 PezExporter 的 (50-pct)×9 互逆）
     *  - 归一化：moveX / 675、moveY / 450、rotate 取反（Phira 里 rotation = 事件值 × -1）
     *  - 流速：每个采样点的 speed 值写进同一个关键帧 → 导出时就是 RPE 的 speedEvents（变速）
     */
    private fun buildPose(line: ParsedLine, offsetSec: Double, warnings: MutableList<String>): Pose {
        val xAt = { t: Double -> line.moveX.at(t) }            // RPE 单位（像素）
        val yAt = { t: Double -> line.moveY.at(t) }            // RPE 单位
        val rAt = { t: Double -> line.rotate.at(t) }           // 度
        val hasSpeedEvents = !line.speed.isEmpty
        val sAt = { t: Double -> if (!hasSpeedEvents) 1f else safeSpeed(line.speed.at(t)) } // 流速倍率
        val hasAlphaEvents = !line.alpha.isEmpty
        val aAt = { t: Double ->
            if (!hasAlphaEvents) 1f else (line.alpha.at(t) / 255.0).coerceIn(0.0, 1.0).toFloat()
        }

        val y0 = yAt(0.0)
        val pct = Math.round(50.0 - y0 / 9.0).toInt().coerceIn(0, 100)
        // y 归一化：pct/100 + yNorm/2 = 0.5 - v/900  →  yNorm = 1 - v/450 - pct/50
        val yNorm = { t: Double -> (1.0 - yAt(t) / 450.0 - pct / 50.0).toFloat() }
        val xNorm = { t: Double -> (xAt(t) / 675.0).toFloat() }
        val rot = { t: Double -> (-rAt(t)).toFloat() }

        val times = ArrayList<Double>()
        line.moveX.collectTimes(times)
        line.moveY.collectTimes(times)
        line.rotate.collectTimes(times)
        line.speed.collectTimes(times)
        line.alpha.collectTimes(times)
        times.add(0.0)
        times.sort()

        val distinct = ArrayList<Double>()
        for (t in times) {
            if (t < 0) continue
            if (distinct.isEmpty() || t - distinct.last() > 1e-6) distinct.add(t)
        }

        val mx0 = xNorm(0.0); val my0 = yNorm(0.0); val r0 = rot(0.0); val s0 = sAt(0.0); val a0 = aAt(0.0)
        val isStatic = distinct.size <= 1 || distinct.all {
            Math.abs(xNorm(it) - mx0) < 1e-5 && Math.abs(yNorm(it) - my0) < 1e-5 &&
                Math.abs(rot(it) - r0) < 1e-5 && Math.abs(sAt(it) - s0) < 1e-4 &&
                Math.abs(aAt(it) - a0) < 1e-4
        }
        if (isStatic) return Pose(pct, mx0, my0, r0, s0, a0, emptyList())

        val kfs = ArrayList<LineKeyframe>()
        for (t in distinct) {
            kfs.add(
                LineKeyframe(
                    Math.round((t + offsetSec) * 1000.0),
                    xNorm(t), yNorm(t), rot(t), sAt(t), aAt(t)
                )
            )
            if (kfs.size >= MAX_KEYFRAMES) break
        }
        if (kfs.size >= MAX_KEYFRAMES) warnings.add("判定线关键帧过多，已截断到 $MAX_KEYFRAMES 个")
        return Pose(pct, mx0, my0, r0, s0, a0, kfs)
    }

    private fun safeSpeed(v: Double): Float =
        if (!v.isFinite() || v < 0.0) 1f else v.coerceIn(0.0, 100.0).toFloat()

    // ------------------------------------------------------------------ 量化推断

    private fun inferHLines(beats: List<Double>, denoms: List<Int>, warnings: MutableList<String>): Int {
        if (beats.isEmpty()) return 4
        // 1) 优先沿用谱师自己的量化格：所有时间分母的最小公倍数（如全 4 分格 → 4）
        var lcm = 1L
        for (d in denoms) {
            if (d <= 0) continue
            lcm = lcm / gcd(lcm, d.toLong()) * d
            if (lcm > MAX_HLINES) break
        }
        if (lcm in 1..MAX_HLINES.toLong() && beats.all { nearInt(it * lcm) }) return lcm.toInt()
        // 2) 分母不可用（太大/不自洽）→ 取能精确表示所有时间的最粗格
        for (h in H_CANDIDATES) {
            if (h > MAX_HLINES) break
            if (beats.all { nearInt(it * h) }) return h
        }
        // 3) 都没命中（非标准分母）：取最密格（只影响编辑网格，导出按原时间写出）
        warnings.add("音符时间不在标准横线格上：横线数取 $MAX_HLINES（只影响编辑网格，导出按原时间写出）")
        return MAX_HLINES
    }

    private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    private fun inferVLines(pos: List<Double>, warnings: MutableList<String>): Int {
        if (pos.isEmpty()) return 8
        for (v in V_CANDIDATES) if (pos.all { nearInt((it + 1.0) * v / 2.0) }) return v
        warnings.add("音符位置不在标准竖线格上：竖线数取 16（只影响编辑网格，导出按原位置写出）")
        return 16
    }

    private fun nearInt(v: Double): Boolean = Math.abs(v - Math.round(v)) < 1e-4

    private fun passOf(type: Int): Int = when (type) {
        4 -> 2      // Drag → 第二阶段
        3 -> 3      // Flick → 第三阶段
        2 -> 4      // Hold → 第四次
        else -> 1   // Tap
    }

    // ------------------------------------------------------------------ 事件求值

    /** 单个事件动画（一条 eventLayer 里的一组事件，Phira `parse_events` 的 keyframe 模型） */
    private class Anim(val kfs: List<KF>) {
        class KF(val t: Double, val v: Double, val ease: Int)

        val isEmpty: Boolean get() = kfs.isEmpty()

        fun at(t: Double): Double {
            if (kfs.isEmpty()) return 0.0
            if (t <= kfs[0].t) return kfs[0].v
            val last = kfs[kfs.size - 1]
            if (t >= last.t) return last.v
            var lo = 0
            var hi = kfs.size - 1
            while (lo + 1 < hi) {
                val mid = (lo + hi) / 2
                if (kfs[mid].t <= t) lo = mid else hi = mid
            }
            val a = kfs[lo]
            val b = kfs[hi]
            val span = b.t - a.t
            if (span <= 1e-9) return b.v
            val x = ((t - a.t) / span).coerceIn(0.0, 1.0)
            return a.v + (b.v - a.v) * Ease.of(a.ease, x)
        }

        /** 采样点：所有关键帧时间 + 非线性段的中途采样（线性关键帧近似曲线） */
        fun collectTimes(out: MutableList<Double>) {
            for (i in kfs.indices) {
                out.add(kfs[i].t)
                if (i + 1 < kfs.size) {
                    val a = kfs[i]
                    val b = kfs[i + 1]
                    if (b.t - a.t > 1e-9 && Ease.isNonLinear(a.ease)) {
                        for (k in 1..3) out.add(a.t + (b.t - a.t) * k / 4.0)
                    }
                }
            }
        }
    }

    /** 多层 eventLayer：Phira 里是相加 */
    private class LayerAnim(val layers: List<Anim>) {
        val isEmpty: Boolean get() = layers.isEmpty()
        fun at(t: Double): Double = if (layers.isEmpty()) 0.0 else layers.sumOf { it.at(t) }
        fun collectTimes(out: MutableList<Double>) { layers.forEach { it.collectTimes(out) } }
    }

    private fun toAnim(bpm: BpmMap, arr: JSONArray?): Anim {
        if (arr == null || arr.length() == 0) return Anim(emptyList())
        class Ev(val t0: Double, val t1: Double, val v0: Double, val v1: Double, val ease: Int)
        val evs = ArrayList<Ev>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val st = o.optJSONArray("startTime") ?: continue
            val et = o.optJSONArray("endTime") ?: st
            evs.add(
                Ev(
                    bpm.seconds(tripleBeats(st)),
                    bpm.seconds(tripleBeats(et)),
                    o.optDouble("start", 0.0).takeIf { it.isFinite() } ?: 0.0,
                    o.optDouble("end", 0.0).takeIf { it.isFinite() } ?: 0.0,
                    o.optInt("easingType", 0)
                )
            )
        }
        evs.sortBy { it.t0 }
        val kfs = ArrayList<Anim.KF>(evs.size * 2)
        for (e in evs) {
            kfs.add(Anim.KF(e.t0, e.v0, e.ease))
            kfs.add(Anim.KF(e.t1, e.v1, 0))
        }
        return Anim(kfs)
    }

    /** RPE easingType（对应 Phira 的 RPE_TWEEN_MAP，0/1 = 线性） */
    private object Ease {
        fun isNonLinear(t: Int): Boolean = t != 0 && t != 1

        fun of(type: Int, x: Double): Double {
            val t = x.coerceIn(0.0, 1.0)
            return when (type) {
                2 -> sinOut(t); 3 -> sinIn(t); 6 -> sinInOut(t)
                4 -> quadOut(t); 5 -> quadIn(t); 7 -> quadInOut(t)
                8 -> cubicOut(t); 9 -> cubicIn(t); 12 -> cubicInOut(t)
                10 -> quartOut(t); 11 -> quartIn(t); 13 -> quartInOut(t)
                14 -> quintOut(t); 15 -> quintIn(t)
                16 -> expoOut(t); 17 -> expoIn(t)
                18 -> circOut(t); 19 -> circIn(t); 22 -> circInOut(t)
                20 -> backOut(t); 21 -> backIn(t); 23 -> backInOut(t)
                24 -> elasticOut(t); 25 -> elasticIn(t); 29 -> elasticInOut(t)
                26 -> bounceOut(t); 27 -> bounceIn(t); 28 -> bounceInOut(t)
                else -> t
            }
        }

        private fun sinIn(t: Double) = 1.0 - Math.cos(t * Math.PI / 2.0)
        private fun sinOut(t: Double) = Math.sin(t * Math.PI / 2.0)
        private fun sinInOut(t: Double) = -(Math.cos(Math.PI * t) - 1.0) / 2.0

        private fun quadIn(t: Double) = t * t
        private fun quadOut(t: Double) = 1.0 - (1.0 - t) * (1.0 - t)
        private fun quadInOut(t: Double) = if (t < 0.5) 2.0 * t * t else 1.0 - Math.pow(-2.0 * t + 2.0, 2.0) / 2.0

        private fun cubicIn(t: Double) = t * t * t
        private fun cubicOut(t: Double) = 1.0 - Math.pow(1.0 - t, 3.0)
        private fun cubicInOut(t: Double) =
            if (t < 0.5) 4.0 * t * t * t else 1.0 - Math.pow(-2.0 * t + 2.0, 3.0) / 2.0

        private fun quartIn(t: Double) = t * t * t * t
        private fun quartOut(t: Double) = 1.0 - Math.pow(1.0 - t, 4.0)
        private fun quartInOut(t: Double) =
            if (t < 0.5) 8.0 * t * t * t * t else 1.0 - Math.pow(-2.0 * t + 2.0, 4.0) / 2.0

        private fun quintIn(t: Double) = t * t * t * t * t
        private fun quintOut(t: Double) = 1.0 - Math.pow(1.0 - t, 5.0)

        private fun expoIn(t: Double) = if (t <= 0.0) 0.0 else Math.pow(2.0, 10.0 * t - 10.0)
        private fun expoOut(t: Double) = if (t >= 1.0) 1.0 else 1.0 - Math.pow(2.0, -10.0 * t)

        private fun circIn(t: Double) = 1.0 - Math.sqrt((1.0 - t * t).coerceAtLeast(0.0))
        private fun circOut(t: Double) = Math.sqrt((1.0 - (t - 1.0) * (t - 1.0)).coerceAtLeast(0.0))
        private fun circInOut(t: Double) = if (t < 0.5) {
            (1.0 - Math.sqrt((1.0 - 4.0 * t * t).coerceAtLeast(0.0))) / 2.0
        } else {
            (Math.sqrt((1.0 - Math.pow(-2.0 * t + 2.0, 2.0)).coerceAtLeast(0.0)) + 1.0) / 2.0
        }

        private const val C1 = 1.70158
        private const val C2 = C1 * 1.525
        private const val C3 = C1 + 1.0
        private fun backIn(t: Double) = C3 * t * t * t - C1 * t * t
        private fun backOut(t: Double) = 1.0 + C3 * Math.pow(t - 1.0, 3.0) + C1 * Math.pow(t - 1.0, 2.0)
        private fun backInOut(t: Double) = if (t < 0.5) {
            Math.pow(2.0 * t, 2.0) * ((C2 + 1.0) * 2.0 * t - C2) / 2.0
        } else {
            (Math.pow(2.0 * t - 2.0, 2.0) * ((C2 + 1.0) * (t * 2.0 - 2.0) + C2) + 2.0) / 2.0
        }

        private fun elasticOut(t: Double): Double {
            if (t <= 0.0) return 0.0
            if (t >= 1.0) return 1.0
            val c4 = 2.0 * Math.PI / 3.0
            return Math.pow(2.0, -10.0 * t) * Math.sin((t * 10.0 - 0.75) * c4) + 1.0
        }

        private fun elasticIn(t: Double): Double {
            if (t <= 0.0) return 0.0
            if (t >= 1.0) return 1.0
            val c4 = 2.0 * Math.PI / 3.0
            return -Math.pow(2.0, 10.0 * t - 10.0) * Math.sin((t * 10.0 - 10.75) * c4)
        }

        private fun elasticInOut(t: Double): Double {
            if (t <= 0.0) return 0.0
            if (t >= 1.0) return 1.0
            val c5 = 2.0 * Math.PI / 4.5
            return if (t < 0.5) {
                -(Math.pow(2.0, 20.0 * t - 10.0) * Math.sin((20.0 * t - 11.125) * c5)) / 2.0
            } else {
                (Math.pow(2.0, -20.0 * t + 10.0) * Math.sin((20.0 * t - 11.125) * c5)) / 2.0 + 1.0
            }
        }

        private fun bounceOut(t: Double): Double {
            val n1 = 7.5625
            val d1 = 2.75
            return when {
                t < 1.0 / d1 -> n1 * t * t
                t < 2.0 / d1 -> { val x = t - 1.5 / d1; n1 * x * x + 0.75 }
                t < 2.5 / d1 -> { val x = t - 2.25 / d1; n1 * x * x + 0.9375 }
                else -> { val x = t - 2.625 / d1; n1 * x * x + 0.984375 }
            }
        }

        private fun bounceIn(t: Double) = 1.0 - bounceOut(1.0 - t)
        private fun bounceInOut(t: Double) = if (t < 0.5) {
            (1.0 - bounceOut(1.0 - 2.0 * t)) / 2.0
        } else {
            (1.0 + bounceOut(2.0 * t - 1.0)) / 2.0
        }
    }

    // ------------------------------------------------------------------ BPM

    /** BPMList：`(beats, bpm)` 分段，秒数 = 段起点秒 + (beats - 段起点拍) × 60/bpm（与 Phira 一致） */
    private class BpmMap(items: List<Pair<Double, Double>>) {
        private val startBeats = DoubleArray(items.size)
        private val startSec = DoubleArray(items.size)
        private val bpm = DoubleArray(items.size)

        init {
            var time = 0.0
            var lastBeats = 0.0
            var lastBpm = 0.0
            var hasLast = false
            for ((i, it) in items.withIndex()) {
                val (beats, b) = it
                if (hasLast) time += (beats - lastBeats) * (60.0 / lastBpm)
                startBeats[i] = beats
                startSec[i] = time
                bpm[i] = b
                lastBeats = beats
                lastBpm = b
                hasLast = true
            }
        }

        fun seconds(beats: Double): Double {
            var idx = 0
            for (i in startBeats.indices) if (startBeats[i] <= beats) idx = i
            return startSec[idx] + (beats - startBeats[idx]) * (60.0 / bpm[idx])
        }
    }

    // ------------------------------------------------------------------ 工具

    /** Triple `[i, n, d]` = i + n/d（拍） */
    private fun tripleBeats(a: JSONArray): Double {
        val d = a.optInt(2, 1).takeIf { it > 0 } ?: 1
        return a.optInt(0, 0) + a.optInt(1, 0).toDouble() / d
    }

    private fun parseInfoTxt(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (raw in text.removePrefix("\uFEFF").lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line == "#") continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        return map
    }

    /** 找谱面 JSON：info.Chart → chart.json → 任何含 judgeLineList/BPMList 的 json */
    private fun pickChart(texts: Map<String, ByteArray>, want: String?): JSONObject? {
        val cands = texts.keys.filter { it.substringAfterLast('.', "").equals("json", true) }
        val ordered = ArrayList<String>()
        if (!want.isNullOrBlank()) cands.firstOrNull { baseName(it).equals(baseName(want), true) }?.let { ordered.add(it) }
        cands.firstOrNull { baseName(it).equals("chart.json", true) }?.let { ordered.add(it) }
        ordered.addAll(cands)
        for (n in ordered.distinct()) {
            val bytes = texts[n] ?: continue
            val o = runCatching { JSONObject(decodeText(bytes)) }.getOrNull() ?: continue
            if (o.has("judgeLineList") || o.has("BPMList")) return o
        }
        return null
    }

    /** 按 info/META 里给的文件名找条目，找不到就按扩展名兜底 */
    private fun pickEntry(names: List<String>, want: String?, exts: Set<String>): String? {
        if (!want.isNullOrBlank()) {
            names.firstOrNull { it.equals(want, true) }?.let { return it }
            names.firstOrNull { baseName(it).equals(baseName(want), true) }?.let { return it }
        }
        // 兜底：优先根目录、常见扩展名
        return names.filter { it.substringAfterLast('.', "").lowercase() in exts }
            .minByOrNull { if (it.contains('/')) 1 else 0 }
    }

    /** 解码文本：优先 UTF-8（去掉 BOM），出现乱码再按 GBK 试一次（RPE 在中文 Windows 上常存 GBK） */
    private fun decodeText(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        if (utf8.indexOf('\uFFFD') < 0) return utf8.removePrefix("\uFEFF")
        val gbk = runCatching { String(bytes, charset("GBK")) }.getOrNull()
        return (gbk ?: utf8).removePrefix("\uFEFF")
    }

    private fun cleanName(name: String): String {
        var n = name.replace('\\', '/')
        while (n.startsWith("./")) n = n.substring(2)
        return n.trim()
    }

    private fun baseName(path: String): String = path.replace('\\', '/').substringAfterLast('/')

    private fun readBounded(z: ZipInputStream, max: Long): ByteArray? {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val r = z.read(buf)
            if (r < 0) break
            total += r
            if (total > max) return null
            bos.write(buf, 0, r)
        }
        return bos.toByteArray()
    }

    private fun trimNum(v: Double): String {
        val r = Math.round(v * 1000.0) / 1000.0
        return if (r == Math.floor(r) && !r.isInfinite()) r.toLong().toString() else r.toString()
    }
}
