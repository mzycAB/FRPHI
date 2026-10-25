package com.piezmaker

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 谱面参数（设置页收集） */
/** 判定线关键帧：时间 + 姿态（moveX/moveY 归一化 -1..1，rotate 度） */
data class LineKeyframe(
    val timeMs: Long,
    val moveX: Float,
    val moveY: Float,
    val rotate: Float
)

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
    val tabId: String = ""       // 项目标签 id（草稿持久化用）
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
        p.readString() ?: ""
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
    }

    companion object {
        @JvmField
        val CREATOR = object : android.os.Parcelable.Creator<ChartMeta> {
            override fun createFromParcel(source: android.os.Parcel) = ChartMeta(source)
            override fun newArray(size: Int) = arrayOfNulls<ChartMeta?>(size)
        }
    }
}

/**
 * RPEJSON 谱面生成 + PEZ 打包。
 * 时间单位：beat；beat = ms/1000 * bpm/60。量化到 1/hLines 拍。
 * 坐标：positionX 范围 [-675, 675]（解析端除以 675 归一化）。
 *
 * 素材处理（杜绝"扩展名与内容不匹配"）：
 *  - 图片：读取字节 -> 按魔数解码 -> 重编码为真 PNG（bg.png）
 *  - 音频：读取魔数判断真实扩展名 -> 原样打包（music.<ext>）
 */
object PezExporter {

    private data class Quantized(val i: Int, val n: Int, val d: Int)

    /** 毫秒 -> Triple [i, n, d]，量化到 1/hLines 拍 */
    private fun quantizeBeat(ms: Long, bpm: Double, hLines: Int): Quantized {
        val beat = ms / 1000.0 * bpm / 60.0
        val k = Math.round(beat * hLines).toInt().coerceAtLeast(0)
        return Quantized(k / hLines, k % hLines, hLines)
    }

    /** 归一化位置 [-1,1] -> RPE positionX [-675, 675]，量化到竖线格 */
    private fun quantizePosX(norm: Float, vLines: Int): Float {
        val cell = 2f / vLines
        val k = Math.round((norm + 1f) / cell).toInt().coerceIn(0, vLines)
        return -675f + k * cell * 675f
    }

    /** 构建 chart.json 内容（musicName/imageName 为最终打包文件名） */
    fun buildChartJson(meta: ChartMeta, notes: List<RecordedNote>, musicName: String, imageName: String, keyframes: List<LineKeyframe> = emptyList()): String {
        val sb = StringBuilder()
        sb.append("{\n")
        // META
        sb.append("  \"META\": {\n")
        sb.append("    \"id\": \"1\",\n")
        sb.append("    \"name\": ").append(jstr(meta.name)).append(",\n")
        sb.append("    \"level\": ").append(jstr(meta.level)).append(",\n")
        sb.append("    \"background\": ").append(jstr(imageName)).append(",\n")
        sb.append("    \"charter\": ").append(jstr(meta.charter)).append(",\n")
        sb.append("    \"composer\": ").append(jstr(meta.composer)).append(",\n")
        sb.append("    \"illustration\": \"\",\n")
        sb.append("    \"song\": ").append(jstr(musicName)).append(",\n")
        sb.append("    \"offset\": 0,\n")
        sb.append("    \"RPEVersion\": 160\n")
        sb.append("  },\n")
        // BPMList
        sb.append("  \"BPMList\": [\n")
        sb.append("    { \"bpm\": ").append(trimNum(meta.bpm)).append(", \"startTime\": [0, 0, 1] }\n")
        sb.append("  ],\n")
        // judgeLineList（单条）
        sb.append("  \"judgeLineList\": [\n")
        sb.append("    {\n")
        sb.append("      \"Name\": \"line\",\n")
        sb.append("      \"Texture\": \"line.png\",\n")
        sb.append("      \"father\": -1,\n")
        sb.append("      \"rotateWithFather\": false,\n")
        // 判定线位置：RPE 的 moveY 向上为正，而屏幕百分比向下递增，所以符号取反
        // 50% = 屏幕中心(0)，每偏离 1% = 9 个 RPE 单位（RPE_HEIGHT=900）
        val lineY = ((50 - meta.linePosPct) * 9).toDouble()
        // 事件数组生成：有关键帧 → 相邻帧线性插值；无关键帧 → 全局姿态平铺
        val mxBase = meta.lineMoveX * 675.0
        val myBase = lineY + meta.lineMoveY * 675.0
        val rotBase = -meta.lineRotate
        val mkX = { k: LineKeyframe -> meta.lineMoveX * 675.0 }        // 关键帧存的是绝对偏移? 见下方注释
        val kvX = { k: LineKeyframe -> trimNum(k.moveX * 675.0) }
        val kvY = { k: LineKeyframe -> trimNum(lineY + k.moveY * 675.0) }
        val kvR = { k: LineKeyframe -> trimNum(-k.rotate) }
        // 时间转 Triple 字符串
        val tS = { ms: Long -> val q = quantizeBeat(ms, meta.bpm, meta.hLines); "[${q.i}, ${q.n}, ${q.d}]" }
        fun eventsFor(gen: (LineKeyframe) -> String): String {
            val kfs = keyframes.sortedBy { it.timeMs }
            // 无关键帧：输出一个完整事件对象（裸数字会让 Phira 的 RPEEvent 解析失败）
            if (kfs.isEmpty()) {
                val v = gen(LineKeyframe(0L, 0f, 0f, 0f))
                return "{ \"startTime\": [0,0,1], \"endTime\": [100000,0,1], \"start\": " + v + ", \"end\": " + v + ", \"easingType\": 0 }"
            }
            val sb2 = StringBuilder()
            for ((i, k) in kfs.withIndex()) {
                val s0 = gen(k)
                if (i + 1 < kfs.size) {
                    val k2 = kfs[i + 1]
                    sb2.append("{ \"startTime\": ").append(tS(k.timeMs)).append(", \"endTime\": ").append(tS(k2.timeMs))
                        .append(", \"start\": ").append(s0).append(", \"end\": ").append(gen(k2)).append(", \"easingType\": 0 }, ")
                } else {
                    sb2.append("{ \"startTime\": ").append(tS(k.timeMs)).append(", \"endTime\": [100000,0,1], \"start\": ").append(s0)
                        .append(", \"end\": ").append(s0).append(", \"easingType\": 0 }")
                }
            }
            return sb2.toString()
        }
        sb.append("      \"eventLayers\": [\n")
        sb.append("        {\n")
        sb.append("          \"alphaEvents\": [ { \"startTime\": [0,0,1], \"endTime\": [100000,0,1], \"start\": 255.0, \"end\": 255.0, \"easingType\": 0 } ],\n")
        sb.append("          \"moveXEvents\": [ ").append(eventsFor(kvX)).append(" ],\n")
        sb.append("          \"moveYEvents\": [ ").append(eventsFor(kvY)).append(" ],\n")
        sb.append("          \"rotateEvents\": [ ").append(eventsFor(kvR)).append(" ],\n")
        sb.append("          \"speedEvents\": [ { \"startTime\": [0,0,1], \"endTime\": [100000,0,1], \"start\": ").append(trimNum(meta.speed)).append(", \"end\": ").append(trimNum(meta.speed)).append(", \"easingType\": 0 } ]\n")
        sb.append("        }\n")
        sb.append("      ],\n")
        sb.append("      \"notes\": [\n")

        // notes：按时间排序；delay 校正（用户点击偏晚 → 音符时间提前，减掉延迟）
        val sorted = notes.sortedBy { it.startMs }
        for ((idx, note) in sorted.withIndex()) {
            val startAdj = (note.startMs - meta.delayMs).coerceAtLeast(0L)
            val endAdj = (note.endMs - meta.delayMs).coerceAtLeast(startAdj)
            val t0 = quantizeBeat(startAdj, meta.bpm, meta.hLines)
            val t1 = quantizeBeat(endAdj, meta.bpm, meta.hLines)
            val x = quantizePosX(note.posX, meta.vLines)
            sb.append("        {\n")
            sb.append("          \"type\": ").append(note.type).append(",\n")
            sb.append("          \"above\": 1,\n")
            sb.append("          \"startTime\": [").append(t0.i).append(", ").append(t0.n).append(", ").append(t0.d).append("],\n")
            sb.append("          \"endTime\": [").append(t1.i).append(", ").append(t1.n).append(", ").append(t1.d).append("],\n")
            sb.append("          \"positionX\": ").append(trimNum(x)).append(",\n")
            sb.append("          \"yOffset\": 0.0,\n")
            sb.append("          \"alpha\": 255,\n")
            sb.append("          \"size\": 1.0,\n")
            sb.append("          \"speed\": 1.0,\n")
            sb.append("          \"isFake\": 0,\n")
            sb.append("          \"visibleTime\": 999999.0\n")
            sb.append("        }")
            if (idx != sorted.lastIndex) sb.append(",")
            sb.append("\n")
        }
        sb.append("      ],\n")
        sb.append("      \"isCover\": 1,\n")
        sb.append("      \"isGif\": false,\n")
        sb.append("      \"zOrder\": 0,\n")
        sb.append("      \"posControl\": [],\n")
        sb.append("      \"sizeControl\": [],\n")
        sb.append("      \"alphaControl\": [],\n")
        sb.append("      \"yControl\": []\n")
        sb.append("    }\n")
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
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
        notes: List<RecordedNote>,
        contentResolver: ContentResolver,
        dest: File,
        keyframes: List<LineKeyframe> = emptyList()
    ): Boolean {
        return try {
            // 1. 图片重编码为真 PNG
            val pngBytes = loadImageAsPng(contentResolver, meta.imageUri) ?: return false
            val imageName = "bg.png"
            // 2. 音频按魔数定真实扩展名
            val musicName = "music.${sniffAudioExt(contentResolver, meta.musicUri)}"

            val chartJson = buildChartJson(meta, notes, musicName, imageName)
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