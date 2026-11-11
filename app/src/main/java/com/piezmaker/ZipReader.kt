package com.piezmaker

import java.nio.charset.Charset
import java.util.zip.Inflater

/**
 * 宽容 ZIP 字节解析（纯 JVM，零依赖）。
 *
 * ★ 背景：Java 21 与新版 Android 的 `ZipInputStream` 在 `readLOC` 阶段会对**非 UTF-8
 *   编码的文件名**（日文/中文 zip 很常见：GBK / Shift_JIS）抛
 *   `IllegalArgumentException: malformed input off:0,length:1` —— 用户导入这种官方谱面
 *   包直接看到「MALFORMED [1]」。这里绕开 ZipInputStream，自己扫 EOCD + Central
 *   Directory，文件名按「UTF-8 严格校验 → GBK → Shift_JIS → windows-1252」逐档探测，
 *   任何名字都不会让导入失败。
 */
object ZipReader {

    class Entry(
        val name: String,
        val method: Int,        // 0=store 8=deflate
        val compressed: Int,
        val size: Int,          // 解压后大小
        val localOffset: Int,
        val crc: Long
    )

    private fun u16(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 255) or ((b[p + 1].toInt() and 255) shl 8)

    private fun u32(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 255) or ((b[p + 1].toInt() and 255) shl 8) or
            ((b[p + 2].toInt() and 255) shl 16) or ((b[p + 3].toInt() and 255) shl 24)

    fun list(bytes: ByteArray): List<Entry> {
        val out = ArrayList<Entry>()
        if (bytes.size < 22) return out
        // 从尾部回扫 EOCD（zip 允许最多 64KB 的注释尾巴）
        val start = (bytes.size - 22 - 65536).coerceAtLeast(0)
        var eocd = -1
        var i = bytes.size - 22
        while (i >= start) {
            if ((bytes[i].toInt() and 0xFF) == 0x50 && (bytes[i + 1].toInt() and 0xFF) == 0x4B &&
                    (bytes[i + 2].toInt() and 0xFF) == 0x05 && (bytes[i + 3].toInt() and 0xFF) == 0x06) {
                eocd = i; break
            }
            i--
        }
        if (eocd < 0) return out
        val count = u16(bytes, eocd + 10)
        var p = u32(bytes, eocd + 16)
        repeat(count) {
            if (p + 46 > bytes.size || u32(bytes, p) != 0x02014b50) return@repeat
            val flag = u16(bytes, p + 8)
            val method = u16(bytes, p + 10)
            val crc = u32(bytes, p + 16).toLong() and 0xFFFFFFFFL
            val compSize = u32(bytes, p + 20)
            val size = u32(bytes, p + 24)
            val nameLen = u16(bytes, p + 28)
            val extraLen = u16(bytes, p + 30)
            val commentLen = u16(bytes, p + 32)
            val localOff = u32(bytes, p + 42)
            if (nameLen > 0 && nameLen <= 65535 && p + 46 + nameLen <= bytes.size) {
                val nameBytes = bytes.copyOfRange(p + 46, p + 46 + nameLen)
                out.add(Entry(decodeName(nameBytes, flag), method, compSize, size, localOff, crc))
            }
            p += 46 + nameLen + extraLen + commentLen
        }
        return out
    }

    private fun decodeName(b: ByteArray, flag: Int): String {
        // bit 11 = 明确标注 UTF-8：字符串构造失败就换宽容编码
        val utf8Flag = (flag and 0x800) != 0
        if (utf8Flag) {
            try { return String(b, Charsets.UTF_8) } catch (_: Exception) {}
        }
        if (isValidUtf8(b)) return String(b, Charsets.UTF_8)
        val skips = arrayOf("GBK", "Shift_JIS", "windows-1252")
        for (cs in skips) {
            try { return String(b, Charset.forName(cs)) } catch (_: Exception) {}
        }
        return String(b, Charsets.ISO_8859_1)
    }

    /** 手写严格 UTF-8 校验（不抛异常、不替换），避免 Java 21 String 构造的 malformed */
    internal fun isValidUtf8(b: ByteArray): Boolean {
        var i = 0
        while (i < b.size) {
            val c = b[i].toInt() and 255
            when {
                c < 0x80 -> i++
                c in 0xC2..0xDF -> { if (i + 1 >= b.size) return false; if ((b[i + 1].toInt() and 255) !in 0x80..0xBF) return false; i += 2 }
                c == 0xE0 -> { if (i + 2 >= b.size) return false; val c1 = b[i + 1].toInt() and 255; val c2 = b[i + 2].toInt() and 255
                    if (c1 !in 0xA0..0xBF || c2 !in 0x80..0xBF) return false; i += 3 }
                c in 0xE1..0xEC -> { if (i + 2 >= b.size) return false; val c1 = b[i + 1].toInt() and 255; val c2 = b[i + 2].toInt() and 255
                    if (c1 !in 0x80..0xBF || c2 !in 0x80..0xBF) return false; i += 3 }
                c == 0xED -> { if (i + 2 >= b.size) return false; val c1 = b[i + 1].toInt() and 255; val c2 = b[i + 2].toInt() and 255
                    if (c1 !in 0x80..0x9F || c2 !in 0x80..0xBF) return false; i += 3 }
                c in 0xEE..0xEF -> { if (i + 2 >= b.size) return false; val c1 = b[i + 1].toInt() and 255; val c2 = b[i + 2].toInt() and 255
                    if (c1 !in 0x80..0xBF || c2 !in 0x80..0xBF) return false; i += 3 }
                c == 0xF0 -> { if (i + 3 >= b.size) return false; val c1 = b[i + 1].toInt() and 255; val c2 = b[i + 2].toInt() and 255; val c3 = b[i + 3].toInt() and 255
                    if (c1 !in 0x90..0xBF || c2 !in 0x80..0xBF || c3 !in 0x80..0xBF) return false; i += 4 }
                c in 0xF1..0xF3 -> { if (i + 3 >= b.size) return false; val c1 = b[i + 1].toInt() and 255; val c2 = b[i + 2].toInt() and 255; val c3 = b[i + 3].toInt() and 255
                    if (c1 !in 0x80..0xBF || c2 !in 0x80..0xBF || c3 !in 0x80..0xBF) return false; i += 4 }
                c == 0xF4 -> { if (i + 3 >= b.size) return false; val c1 = b[i + 1].toInt() and 255; val c2 = b[i + 2].toInt() and 255; val c3 = b[i + 3].toInt() and 255
                    if (c1 !in 0x80..0x8F || c2 !in 0x80..0xBF || c3 !in 0x80..0xBF) return false; i += 4 }
                else -> return false
            }
        }
        return true
    }

    /** 按条目提取内容（store 直接取 / deflate 解压），失败返回 null */
    fun bytes(zip: ByteArray, e: Entry): ByteArray? {
        val p = e.localOffset
        if (p < 0 || p + 30 > zip.size || u32(zip, p) != 0x04034b50) return null
        val nameLen = u16(zip, p + 26)
        val extraLen = u16(zip, p + 28)
        val dataStart = p + 30 + nameLen + extraLen
        if (dataStart < 0 || dataStart + e.compressed > zip.size || e.compressed < 0) return null
        val comp = zip.copyOfRange(dataStart, dataStart + e.compressed)
        return when (e.method) {
            0 -> comp
            8 -> inflate(comp, e.size)
            else -> null
        }
    }

    private fun inflate(d: ByteArray, expected: Int): ByteArray? = try {
        val inf = Inflater(true)
        inf.setInput(d)
        val out = ByteArray(expected.coerceAtLeast(0))
        var n = 0
        while (!inf.finished() && n < out.size) {
            val r = inf.inflate(out, n, out.size - n)
            if (r <= 0) break
            n += r
        }
        inf.end()
        if (n == out.size) out else if (n > 0) out.copyOf(n) else null
    } catch (e: Exception) {
        null
    }
}