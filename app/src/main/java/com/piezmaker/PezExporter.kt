package com.piezmaker

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 谱面参数（设置页收集） */
data class ChartMeta(
    val name: String,
    val composer: String,
    val charter: String,
    val level: String,
    val bpm: Double,
    val speed: Double,        // 流速：音符下落倍率（默认 1.0）
    val linePosPct: Int,      // 判定线位置：屏幕纵向百分比 0~100（默认 60）
    val delayMs: Int,         // 输入/音频延迟（ms），导出时用于校正音符时间
    val hLines: Int,          // 横线数：每拍分几格（时间量化）
    val vLines: Int,          // 竖线数：位置量化
    val musicUri: Uri,
    val imageUri: Uri,
    val lineMoveX: Float = 0f,   // 判定线全局偏移（归一化 -1..1）
    val lineMoveY: Float = 0f,
    val lineRotate: Float = 0f,  // 判定线全局旋转（度）
    val tabId: String = "",      // 项目标签 id（草稿持久化用）
    val bpmOffsetMs: Double = 0.0,    // BPM 偏移（ms，支持 3 位小数；正数 = 谱面整体比音乐延后，同 Phiedit）
    /** 多 BPM 分段（毫秒 + bpm）；空 = 全程用 [bpm] */
    val bpmSegments: List<BpmSegment> = emptyList()
) : android.os.Parcelable {
    constructor(p: android.os.Parcel) : this(
        p.readString() ?: "",
        p.readString() ?: "",
        p.readString() ?: "",
        p.readString() ?: "",
        p.readDouble(),
        p.readDouble(),
        p.readInt(),
        p.readInt(),
        p.readInt(),
        p.readInt(),
        p.readParcelable(Uri::class.java.classLoader) ?: Uri.EMPTY,
        p.readParcelable(Uri::class.java.classLoader) ?: Uri.EMPTY,
        p.readFloat(),
        p.readFloat(),
        p.readFloat(),
        p.readString() ?: "",
        p.readDouble(),
        readSegments(p)
    )

    override fun describeContents() = 0
    override fun writeToParcel(dest: android.os.Parcel, flags: Int) {
        dest.writeString(name)
        dest.writeString(composer)
        dest.writeString(charter)
        dest.writeString(level)
        dest.writeDouble(bpm)
        dest.writeDouble(speed)
        dest.writeInt(linePosPct)
        dest.writeInt(delayMs)
        dest.writeInt(hLines)
        dest.writeInt(vLines)
        dest.writeParcelable(musicUri, flags)
        dest.writeParcelable(imageUri, flags)
        dest.writeFloat(lineMoveX)
        dest.writeFloat(lineMoveY)
        dest.writeFloat(lineRotate)
        dest.writeString(tabId)
        dest.writeDouble(bpmOffsetMs)
        dest.writeInt(bpmSegments.size)
        for (s in bpmSegments) {
            dest.writeLong(s.startMs)
            dest.writeDouble(s.bpm)
        }
    }

    companion object {
        private fun readSegments(p: android.os.Parcel): List<BpmSegment> {
            val n = p.readInt()
            if (n <= 0 || n > 4096) return emptyList()
            val out = ArrayList<BpmSegment>(n)
            repeat(n) { out.add(BpmSegment(p.readLong(), p.readDouble())) }
            return out
        }

        @JvmField
        val CREATOR = object : android.os.Parcelable.Creator<ChartMeta> {
            override fun createFromParcel(source: android.os.Parcel) = ChartMeta(source)
            override fun newArray(size: Int) = arrayOfNulls<ChartMeta?>(size)
        }
    }
}

/**
 * RPEJSON 谱面生成 + PEZ 打包。
 *
 * 时间单位：beat；beat = ms/1000 * bpm/60。
 * **不再吸附**：音符时间按 `ms×bpm/60000` 精确约分成 `[整数, 分子, 分母]`，
 * 位置按 `posX × 675` 原样写出（以前会被吸到 1/hLines 拍、1/vLines 格上）。
 *
 * 坐标：positionX 范围 [-675, 675]（解析端除以 675 归一化）。
 * 多判定线：每条判定线各写一份 eventLayers（含自己的流速事件）+ 自己的音符。
 *
 * 素材处理（杜绝"扩展名与内容不匹配"）：
 *  - 图片：读取字节 -> 按魔数解码 -> 重编码为真 PNG（bg.png）
 *  - 音频：读取魔数判断真实扩展名 -> 原样打包（music.<ext>）
 */
object PezExporter {

    /** 分母上限（防止极端 BPM 出现天文数字分母，1e6 分之一拍 ≈ 0.5 微秒，绝对听不出） */
    private const val MAX_DEN = 1_000_000L
    /** 时间表示允许的误差（拍）：1e-6 拍 ≈ 0.0005 ms */
    private const val TIME_EPS = 1e-6
    /** 优先尝试的"好看"分母（能被 RPE 编辑器直观显示） */
    private val NICE_DEN = intArrayOf(1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 384, 768, 960, 1920)

    /**
     * 毫秒 → RPE Triple `[i, n, d]`（= i + n/d 拍），**精确、不吸附**。
     *
     * bpm 取到 1/1000，于是 beat = ms×bpmMilli / 60_000_000 是个精确分数，
     * 约分后就能得到最小分母的准确表示（例如 1234ms@120bpm → 2 + 117/250）。
     */
    fun beatTriple(ms: Long, bpm: Double): String = beatTripleMs(ms.toDouble(), bpm)

    /** 同上，但毫秒可以是小数（BPM 偏移支持 3 位小数，所以时间可能带 0.001ms 量级的小数） */
    fun beatTripleMs(ms: Double, bpm: Double): String {
        val safeMs = if (!ms.isFinite() || ms < 0.0) 0.0 else ms
        val bpmMilli = Math.round((if (bpm.isFinite() && bpm > 0) bpm else 120.0) * 1000.0).toLong()
        val msMilli = Math.round(safeMs * 1000.0)   // 毫秒按 1/1000ms 整数化，能精确表达 3 位小数
        var num = msMilli * bpmMilli
        var den = 60_000_000_000L
        val g = gcd(num, den)
        if (g > 1) { num /= g; den /= g }
        // 分母太大时先看能不能用"好看"的分母精确表示（误差在 1e-6 拍内）
        if (den > 1920L) {
            val beat = num.toDouble() / den.toDouble()
            for (d in NICE_DEN.reversed()) {
                val n = Math.round(beat * d).toLong()
                if (Math.abs(n.toDouble() / d - beat) <= TIME_EPS && n <= d * 1_000_000L) {
                    return "[${n / d}, ${n % d}, $d]"
                }
            }
        }
        if (den > MAX_DEN) {
            val beat = num.toDouble() / den.toDouble()
            val n = Math.round(beat * MAX_DEN)
            return "[${n / MAX_DEN}, ${n % MAX_DEN}, $MAX_DEN]"
        }
        return "[${num / den}, ${num % den}, $den]"
    }

    /**
     * 拍数（Double）→ RPE Triple。多 BPM 时时间不是"毫秒×常数"，只能先算成拍再约分。
     * 优先凑"好看"的分母（1/2/3/4/6/8…），误差在 1e-6 拍内即可。
     */
    fun beatsTriple(beats: Double): String {
        val b = if (beats.isFinite() && beats > 0.0) beats else 0.0
        val ip = Math.floor(b).toLong()
        val frac = b - ip
        if (frac < 1e-9) return "[$ip, 0, 1]"
        if (frac > 1.0 - 1e-9) return "[" + (ip + 1) + ", 0, 1]"
        // 从"最好看"的小分母往大试，第一个误差够小的就用它。
        // 注意：**不能**在 n>=d 时直接进位 —— 那只是说明"这个分母表示不了"，
        // 继续试下一个即可。以前在 d=1 时 round(0.57)=1 就进位成整拍，导致 128~175ms 的时间漂移。
        for (d in NICE_DEN) {
            val n = Math.round(frac * d)
            if (n <= 0L || n >= d) continue
            if (Math.abs(n.toDouble() / d - frac) <= TIME_EPS) return "[$ip, $n, $d]"
        }
        val n = Math.round(frac * MAX_DEN)
        if (n >= MAX_DEN) return "[" + (ip + 1) + ", 0, 1]"
        if (n <= 0L) return "[$ip, 0, 1]"
        return "[$ip, $n, $MAX_DEN]"
    }

    /** 构建 chart.json 内容（musicName/imageName 为最终打包文件名） */
    fun buildChartJson(
        meta: ChartMeta,
        lines: List<Line>,
        notes: List<RecordedNote>,
        musicName: String,
        imageName: String
    ): String = buildChartJsonCore(
        meta.name,
        meta.level,          // 没填就保持空字符串，Phira 怎么处理是它的事
        meta.charter, meta.composer, meta.bpm, meta.delayMs,
        meta.linePosPct, lines, notes, musicName, imageName, meta.bpmOffsetMs,
        meta.bpmSegments
    )

    /**
     * 不依赖 Android 的 chart.json 构建（单元测试直接用这个，不用造 Uri）。
     */
    fun buildChartJsonCore(
        name: String,
        level: String,
        charter: String,
        composer: String,
        bpm: Double,
        delayMs: Int,
        fallbackPosPct: Int,
        lines: List<Line>,
        notes: List<RecordedNote>,
        musicName: String,
        imageName: String,
        bpmOffsetMs: Double = 0.0,
        /** 多 BPM 分段（毫秒 + bpm）；空或只有一段时按单一 bpm 写 */
        bpmSegments: List<BpmSegment> = emptyList()
    ): String {
        // 分段规范化：排序、去非法、保证第一段从 0 开始
        val segs = BpmTimeline.normalize(bpmSegments, bpm)
        val multi = segs.size > 1
        val sb = StringBuilder(4096 + notes.size * 420)
        sb.append("{\n")
        // META
        sb.append("  \"META\": {\n")
        sb.append("    \"id\": \"1\",\n")
        sb.append("    \"name\": ").append(jstr(name)).append(",\n")
        sb.append("    \"level\": ").append(jstr(level)).append(",\n")
        sb.append("    \"background\": ").append(jstr(imageName)).append(",\n")
        sb.append("    \"charter\": ").append(jstr(charter)).append(",\n")
        sb.append("    \"composer\": ").append(jstr(composer)).append(",\n")
        sb.append("    \"illustration\": \"\",\n")
        sb.append("    \"song\": ").append(jstr(musicName)).append(",\n")
        sb.append("    \"offset\": ").append(trimNum(bpmOffsetMs)).append(",\n")
        sb.append("    \"RPEVersion\": 160\n")
        sb.append("  },\n")
        // BPMList：多 BPM 时按分段写（startTime 是该段起点的拍数）
        sb.append("  \"BPMList\": [\n")
        if (!multi) {
            sb.append("    { \"bpm\": ").append(trimNum(bpm)).append(", \"startTime\": [0, 0, 1] }\n")
        } else {
            val sBeats = BpmTimeline.startBeats(segs)
            for ((i, seg) in segs.withIndex()) {
                sb.append("    { \"bpm\": ").append(trimNum(seg.bpm))
                    .append(", \"startTime\": ").append(beatsTriple(sBeats[i])).append(" }")
                if (i != segs.lastIndex) sb.append(",")
                sb.append("\n")
            }
        }
        sb.append("  ],\n")
        // judgeLineList（多条）
        sb.append("  \"judgeLineList\": [\n")

        val safeLines = if (lines.isEmpty()) listOf(Line(0).also { it.posPct = fallbackPosPct }) else lines
        val lineIds = safeLines.map { it.id }.toHashSet()
        val firstId = safeLines.first().id

        for ((li, line) in safeLines.withIndex()) {
            appendLine(sb, bpm, segs, delayMs, bpmOffsetMs, line, li, notes, lineIds, firstId)
            if (li != safeLines.lastIndex) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun appendLine(
        sb: StringBuilder,
        bpm: Double,
        segs: List<BpmSegment>,
        delayMs: Int,
        bpmOffsetMs: Double,
        line: Line,
        index: Int,
        allNotes: List<RecordedNote>,
        lineIds: Set<Int>,
        firstId: Int
    ) {
        sb.append("    {\n")
        // Name 是判定线的"身份"，跟位置/顺序无关（Phiedit 里也是固定名，默认 "Untitled"）
        sb.append("      \"Name\": ").append(jstr(line.name.ifEmpty { "line" })).append(",\n")
        sb.append("      \"Texture\": \"line.png\",\n")
        sb.append("      \"father\": ").append(line.fatherId).append(",\n")
        sb.append("      \"rotateWithFather\": ").append(line.rotWithFather).append(",\n")

        // 判定线位置：RPE 的 moveY 向上为正，而屏幕百分比向下递增，所以符号取反
        // 50% = 屏幕中心(0)，每偏离 1% = 9 个 RPE 单位（RPE_HEIGHT=900）
        val lineY = ((50 - line.posPct) * 9).toDouble()
        // 全局姿态（没有关键帧时直接用它，避免"拖动判定线但不打关键帧"的偏移丢失）
        val globalX = line.moveX * 675.0
        val globalY = lineY - line.moveY * 450.0
        val globalR = -line.rotate
        // 关键帧存的是归一化值（-1..1）：x 乘 675（RPE 半宽）、y 乘 450（RPE 半高 900/2）
        val kvX = { k: LineKeyframe -> trimNum(k.moveX * 675.0) }
        val kvY = { k: LineKeyframe -> trimNum(lineY - k.moveY * 450.0) }
        val kvR = { k: LineKeyframe -> trimNum(-k.rotate) }

        // 时间 → Triple 字符串（不吸附）：多 BPM 时先按分段换算成拍，再写成分数
        // 时间可以是小数毫秒（BPM 偏移支持 3 位小数），所以这里吃 Double
        val tS: (Double) -> String = if (segs.size > 1) {
            { ms: Double -> beatsTriple(BpmTimeline.beatsAt(ms, segs)) }
        } else {
            { ms: Double -> beatTripleMs(ms, bpm) }
        }
        fun eventsFor(gen: (LineKeyframe) -> String, global: () -> String): String {
            val kfs = line.sortedKeyframes()
            // 无关键帧：输出一个完整事件对象（裸数字会让 Phira 的 RPEEvent 解析失败）
            if (kfs.isEmpty()) {
                val v = global()
                return "{ \"startTime\": [0,0,1], \"endTime\": [100000,0,1], \"start\": " + v +
                    ", \"end\": " + v + ", \"easingType\": 0 }"
            }
            val sb2 = StringBuilder()
            for ((i, k) in kfs.withIndex()) {
                val s0 = gen(k)
                if (i + 1 < kfs.size) {
                    val k2 = kfs[i + 1]
                    sb2.append("{ \"startTime\": ").append(tS(k.timeMs.toDouble())).append(", \"endTime\": ").append(tS(k2.timeMs.toDouble()))
                        .append(", \"start\": ").append(s0).append(", \"end\": ").append(gen(k2)).append(", \"easingType\": 0 }, ")
                } else {
                    sb2.append("{ \"startTime\": ").append(tS(k.timeMs.toDouble())).append(", \"endTime\": [100000,0,1], \"start\": ").append(s0)
                        .append(", \"end\": ").append(s0).append(", \"easingType\": 0 }")
                }
            }
            return sb2.toString()
        }

        // 流速事件：每个关键帧的 speed 管到下一个关键帧（分段常速）→ 就是"流速变化"
        fun speedEvents(): String {
            val kfs = line.sortedKeyframes()
            val base = trimNum(clampLineSpeed(line.speed))
            if (kfs.isEmpty()) {
                return "{ \"startTime\": [0,0,1], \"endTime\": [100000,0,1], \"start\": $base, \"end\": $base, \"easingType\": 0 }"
            }
            val sb2 = StringBuilder()
            for ((i, k) in kfs.withIndex()) {
                val s0 = trimNum(clampLineSpeed(k.speed))
                if (i + 1 < kfs.size) {
                    val k2 = kfs[i + 1]
                    sb2.append("{ \"startTime\": ").append(tS(k.timeMs.toDouble())).append(", \"endTime\": ").append(tS(k2.timeMs.toDouble()))
                        .append(", \"start\": ").append(s0).append(", \"end\": ")
                        .append(trimNum(clampLineSpeed(k2.speed))).append(", \"easingType\": 0 }, ")
                } else {
                    sb2.append("{ \"startTime\": ").append(tS(k.timeMs.toDouble())).append(", \"endTime\": [100000,0,1], \"start\": ")
                        .append(s0).append(", \"end\": ").append(s0).append(", \"easingType\": 0 }")
                }
            }
            return sb2.toString()
        }

        // 透明度事件：0~255；负数 = Phira 的"线和音符一起隐藏"（PE alpha extension），原样导出
        fun alphaEvents(): String {
            val kfs = line.sortedKeyframes()
            fun a255(v: Float): String = trimNum((v.coerceIn(-1f, 1f) * 255f))
            if (kfs.isEmpty()) {
                val a = a255(line.alpha)
                return "{ \"startTime\": [0,0,1], \"endTime\": [100000,0,1], \"start\": $a, \"end\": $a, \"easingType\": 0 }"
            }
            val sb2 = StringBuilder()
            for ((i, k) in kfs.withIndex()) {
                if (i + 1 < kfs.size) {
                    val k2 = kfs[i + 1]
                    sb2.append("{ \"startTime\": ").append(tS(k.timeMs.toDouble())).append(", \"endTime\": ").append(tS(k2.timeMs.toDouble()))
                        .append(", \"start\": ").append(a255(k.alpha)).append(", \"end\": ").append(a255(k2.alpha))
                        .append(", \"easingType\": 0 }, ")
                } else {
                    sb2.append("{ \"startTime\": ").append(tS(k.timeMs.toDouble())).append(", \"endTime\": [100000,0,1], \"start\": ")
                        .append(a255(k.alpha)).append(", \"end\": ").append(a255(k.alpha)).append(", \"easingType\": 0 }")
                }
            }
            return sb2.toString()
        }

        sb.append("      \"eventLayers\": [\n")
        sb.append("        {\n")
        sb.append("          \"alphaEvents\": [ ").append(alphaEvents()).append(" ],\n")
        sb.append("          \"moveXEvents\": [ ").append(eventsFor(kvX) { trimNum(globalX) }).append(" ],\n")
        sb.append("          \"moveYEvents\": [ ").append(eventsFor(kvY) { trimNum(globalY) }).append(" ],\n")
        sb.append("          \"rotateEvents\": [ ").append(eventsFor(kvR) { trimNum(globalR) }).append(" ],\n")
        sb.append("          \"speedEvents\": [ ").append(speedEvents()).append(" ]\n")
        sb.append("        }\n")
        sb.append("      ],\n")
        // incline 倾斜事件（extended.inclineEvents，度）：只在有事件时写（Phira 里 extended 可省略）
        if (line.inclineEvents.isNotEmpty()) {
            sb.append("      \"extended\": {\n")
            sb.append("        \"inclineEvents\": [\n")
            for ((i, e) in line.inclineEvents.withIndex()) {
                sb.append("          { \"startTime\": ").append(tS(e.startMs.toDouble()))
                    .append(", \"endTime\": ").append(tS(e.endMs.toDouble()))
                    .append(", \"start\": ").append(trimNum(e.start))
                    .append(", \"end\": ").append(trimNum(e.end))
                    .append(", \"easingType\": ").append(e.easing).append(" }")
                if (i != line.inclineEvents.lastIndex) sb.append(",")
                sb.append("\n")
            }
            sb.append("        ]\n")
            sb.append("      },\n")
        }
        sb.append("      \"notes\": [\n")

        // 属于这条线的音符：按时间排序；delay 校正（用户点击偏晚 → 音符时间提前，减掉延迟）
        val mine = allNotes.filter { (if (it.lineId in lineIds) it.lineId else firstId) == line.id }
            .sortedBy { it.startMs }
        for ((idx, note) in mine.withIndex()) {
            // 时间轴坐标 → 谱面时间：减掉输入延迟，再减掉 BPM 偏移（偏移已写进 META.offset）
            val startAdj = (note.startMs - delayMs - bpmOffsetMs).coerceAtLeast(0.0)
            val endAdj = (note.endMs - delayMs - bpmOffsetMs).coerceAtLeast(startAdj)
            sb.append("        {\n")
            sb.append("          \"type\": ").append(note.type).append(",\n")
            sb.append("          \"above\": ").append(if (note.above) 1 else 0).append(",\n")
            // 音符时间也必须走 tS：多 BPM 时"毫秒×单 BPM"算出来的拍是错的
            sb.append("          \"startTime\": ").append(tS(startAdj)).append(",\n")
            sb.append("          \"endTime\": ").append(tS(endAdj)).append(",\n")
            sb.append("          \"positionX\": ").append(trimNum(note.posX * 675f)).append(",\n")
            sb.append("          \"yOffset\": ").append(trimNum(note.yOffset)).append(",\n")
            sb.append("          \"alpha\": ").append(if (note.alpha >= 0.999f) 255 else Math.round(note.alpha.coerceIn(0f, 1f) * 255f)).append(",\n")
            sb.append("          \"size\": ").append(trimNum(note.size)).append(",\n")
            sb.append("          \"speed\": ").append(trimNum(note.speed)).append(",\n")
            sb.append("          \"isFake\": ").append(if (note.fake) 1 else 0).append(",\n")
            sb.append("          \"visibleTime\": ").append(if (note.visibleTime >= 999998f) "999999.0" else trimNum(note.visibleTime)).append("\n")
            sb.append("        }")
            if (idx != mine.lastIndex) sb.append(",")
            sb.append("\n")
        }
        sb.append("      ],\n")
        sb.append("      \"isCover\": 1,\n")
        sb.append("      \"isGif\": false,\n")
        sb.append("      \"zOrder\": 0,\n")
        // ctrl 对象：{ easing, x, <alpha|pos|size|y> }，x = 音符到线的落点距离（RPE px）
        fun ctrlEvents(evts: List<CtrlEvent>, key: String): String {
            if (evts.isEmpty()) return ""
            return evts.joinToString(", ") {
                "{ \"easing\": ${it.easing}, \"x\": ${trimNum(it.x)}, \"$key\": ${trimNum(it.value)} }"
            }
        }
        sb.append("      \"posControl\": [").append(ctrlEvents(line.ctrlPosEvents, "pos")).append("],\n")
        sb.append("      \"sizeControl\": [").append(ctrlEvents(line.ctrlSizeEvents, "size")).append("],\n")
        sb.append("      \"alphaControl\": [").append(ctrlEvents(line.ctrlAlphaEvents, "alpha")).append("],\n")
        sb.append("      \"yControl\": [").append(ctrlEvents(line.ctrlYEvents, "y")).append("]\n")
        sb.append("    }")
    }

    /** info.txt（RPE 标准：首行 #，之后 Key: Value） */
    fun buildInfoTxt(meta: ChartMeta, chartFileName: String, musicName: String, imageName: String): String {
        val sb = StringBuilder()
        sb.append("#\n")
        sb.append("Name: ").append(singleLine(meta.name)).append("\n")
        sb.append("Path: 1\n")
        sb.append("Song: ").append(musicName).append("\n")
        sb.append("Picture: ").append(imageName).append("\n")
        sb.append("Chart: ").append(chartFileName).append("\n")
        sb.append("Level: ").append(singleLine(meta.level)).append("\n")
        sb.append("Composer: ").append(singleLine(meta.composer)).append("\n")
        sb.append("Charter: ").append(singleLine(meta.charter)).append("\n")
        sb.append("Illustration: \n")
        return sb.toString()
    }

    /**
     * 导出 PEZ。
     * @param contentResolver 用于读取音频/图片内容 URI
     * @param dest 输出文件
     */
    fun export(
        meta: ChartMeta,
        lines: List<Line>,
        notes: List<RecordedNote>,
        contentResolver: ContentResolver,
        dest: File
    ): Boolean {
        return try {
            // 1. 图片重编码为真 PNG
            val pngBytes = loadImageAsPng(contentResolver, meta.imageUri) ?: return false
            val imageName = "bg.png"
            // 2. 音频按魔数定真实扩展名
            val musicName = "music.${sniffAudioExt(contentResolver, meta.musicUri)}"

            val chartJson = buildChartJson(meta, lines, notes, musicName, imageName)
            val infoTxt = buildInfoTxt(meta, "chart.json", musicName, imageName)

            val bos = BufferedOutputStream(FileOutputStream(dest))
            val zos = ZipOutputStream(bos)
            zos.setLevel(9)

            zos.putNextEntry(ZipEntry("chart.json"))
            zos.write(chartJson.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("info.txt"))
            zos.write(infoTxt.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry(musicName))
            copyStream(contentResolver, meta.musicUri, zos)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry(imageName))
            zos.write(pngBytes)
            zos.closeEntry()

            zos.finish()
            zos.close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ------------- 素材处理 -------------

    /** 读取图片字节并解码重编码为 PNG；返回 null 表示解码失败 */
    private fun loadImageAsPng(resolver: ContentResolver, uri: Uri): ByteArray? {
        val bytes = readAll(resolver, uri) ?: return null
        // 先按大小降采样，避免超清图 OOM
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        try {
            val out = ByteArrayOutputStream()
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
            // 重编码出真 PNG 头
            return out.toByteArray()
        } finally {
            bmp.recycle()
        }
    }

    /** 嗅探音频真实扩展名（读到前 12 字节判断魔数，兜底 mp3） */
    private fun sniffAudioExt(resolver: ContentResolver, uri: Uri): String {
        val head = ByteArray(12)
        var off = 0
        try {
            resolver.openInputStream(uri)?.use { ins ->
                while (off < head.size) {
                    val r = ins.read(head, off, head.size - off)
                    if (r < 0) break
                    off += r
                }
            } ?: return "mp3"
            val b = head
            // OggS
            if (off >= 4 && b[0] == 'O'.code.toByte() && b[1] == 'g'.code.toByte() && b[2] == 'g'.code.toByte() && b[3] == 'S'.code.toByte()) return "ogg"
            // RIFF .... WAVE
            if (off >= 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte()
                && b[8] == 'W'.code.toByte() && b[9] == 'A'.code.toByte() && b[10] == 'V'.code.toByte() && b[11] == 'E'.code.toByte()) return "wav"
            // fLaC
            if (off >= 4 && b[0] == 'f'.code.toByte() && b[1] == 'L'.code.toByte() && b[2] == 'a'.code.toByte() && b[3] == 'C'.code.toByte()) return "flac"
            // ftyp (m4a/mp4)
            if (off >= 8 && b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() && b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte()) return "m4a"
            // ID3v2 或 MPEG 帧头 -> mp3
            if (off >= 3 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()) return "mp3"
            if (off >= 2 && b[0] == 0xFF.toByte() && (b[1].toInt() and 0xE0) == 0xE0) return "mp3"
            return "mp3"
        } catch (_: Exception) {
            return "mp3"
        }
    }

    private fun readAll(resolver: ContentResolver, uri: Uri): ByteArray? {
        return try {
            resolver.openInputStream(uri)?.use { ins ->
                val buf = ByteArrayOutputStream()
                val tmp = ByteArray(64 * 1024)
                while (true) {
                    val r = ins.read(tmp)
                    if (r < 0) break
                    buf.write(tmp, 0, r)
                }
                buf.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun copyStream(resolver: ContentResolver, uri: Uri, out: OutputStream) {
        resolver.openInputStream(uri)?.use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = ins.read(buf)
                if (r < 0) break
                out.write(buf, 0, r)
            }
        } ?: throw IllegalStateException("cannot open uri: $uri")
    }

    // ---- 工具 ----

    private fun gcd(a: Long, b: Long): Long {
        var x = Math.abs(a)
        var y = Math.abs(b)
        while (y != 0L) {
            val t = x % y
            x = y
            y = t
        }
        return if (x == 0L) 1L else x
    }

    private fun singleLine(s: String): String =
        s.replace(Regex("[\\x00-\\x1f\\x7f]"), " ").trim()

    private fun jstr(s: String): String {
        val e = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
        return "\"$e\""
    }

    private fun trimNum(d: Double): String {
        val v = Math.round(d * 1000) / 1000.0
        return if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
    }

    private fun trimNum(f: Float): String {
        val v = Math.round(f * 1000) / 1000.0
        return if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
    }
}
