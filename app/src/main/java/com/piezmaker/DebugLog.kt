package com.piezmaker

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 轻量运行日志（环形缓冲，最多 400 条）。
 *
 * 目的：手机上出现"看起来不对"的现象时，主界面左侧抽屉「日志」块一键导出
 * （联机日志 + 这里的最近操作序列），定位问题不用再靠猜。
 *
 * 纯 JVM，不依赖 Android（时间用 System.currentTimeMillis）。
 */
object DebugLog {

    private const val MAX = 400

    private val buf = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(tag: String, msg: String) {
        buf.addLast("${timeFmt.format(Date())} [$tag] $msg")
        while (buf.size > MAX) buf.removeFirst()
    }

    @Synchronized
    fun dump(): String = buf.joinToString("\n")

    @Synchronized
    fun clear() {
        buf.clear()
    }

    /** 统一的小数格式（避免某些地区用逗号当小数点） */
    fun f3(v: Float): String = String.format(Locale.US, "%.3f", v)
    fun f3(v: Double): String = String.format(Locale.US, "%.3f", v)
}
