package com.piezmaker

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃落盘。
 *
 * DebugLog 是**内存环形缓冲**，应用一崩进程重启就全没了 —— 用户导出的日志一片空白，
 * 完全没法定位。这里在进程死之前把「堆栈 + 崩溃前的 DebugLog」写进 filesDir，
 * 下次从主界面导出日志时自动附上（见 MainActivity.buildLogText）。
 */
object CrashLog {

    private const val FILE = "frphi_crash.txt"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is Handler) return          // 别重复套娃
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, prev))
    }

    private class Handler(
        private val ctx: Context,
        private val prev: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                val sb = StringBuilder()
                sb.append("==== 上次崩溃 ====\n")
                sb.append("时间: ").append(ts).append('\n')
                sb.append("线程: ").append(t.name).append('\n')
                sb.append("异常: ").append(e.javaClass.name).append(": ").append(e.message).append('\n')
                sb.append('\n').append(Log.getStackTraceString(e)).append('\n')
                sb.append("\n==== 崩溃前最近操作日志 ====\n")
                sb.append(DebugLog.dump()).append('\n')
                ctx.openFileOutput(FILE, Context.MODE_PRIVATE).use {
                    it.write(sb.toString().toByteArray(Charsets.UTF_8))
                }
            } catch (_: Throwable) {
                // 落盘失败也不能挡住系统默认的崩溃处理
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** 读走并清掉上次崩溃记录（导出日志时调用） */
    fun take(ctx: Context): String = try {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) "" else {
            val s = f.readText(Charsets.UTF_8)
            f.delete()
            s
        }
    } catch (_: Exception) {
        ""
    }
}
