package com.piezmaker

/**
 * 延迟相关的纯计算（不碰 Android API，可直接单元测试）。
 *
 * 背景 / 为什么不能「顺手」把输出延迟从播放头里减掉：
 *  - 网页端用 AudioContext.getOutputTimestamp() 能直接拿到「此刻正在被耳朵听到的
 *    音频帧」，所以 FRPHI_HTML 可以放心把输出延迟补进时钟；而它的 userDelay 只作用于
 *    输入侧，导出时不参与，不存在重复补偿。
 *  - 安卓 MediaPlayer 没有等价 API，且 FRPHI 的 `meta.delayMs` 是**导出时**对音符
 *    时间的平移（见 PezExporter: startAdj = startMs - delayMs - bpmOffsetMs）。
 *    校准页测出的 delay 本身就包含了设备的输出延迟 L。若再在 currentPlayhead()
 *    里减一次 L，导出的谱会整体早 L ms —— 双重补偿。
 *  ⇒ 这里只提供「测量」与「无系统偏移的抖动消除」，不擅自改变时间轴基准。
 */
object AudioLatency {

    /**
     * 音频输出延迟估算（DAC + 系统混音 + 蓝牙）。
     * 用 AudioManager 官方属性：PROPERTY_OUTPUT_FRAMES_PER_BUFFER /
     * PROPERTY_OUTPUT_SAMPLE_RATE，这是 Android 音游在没有 AudioTrack.getTimestamp()
     * 时的标准做法。拿不到属性时返回 0（不影响行为，只影响诊断数值）。
     */
    fun outputLatencyMs(framesPerBuffer: Int, sampleRateHz: Int): Long =
        if (framesPerBuffer > 0 && sampleRateHz > 0) framesPerBuffer * 1000L / sampleRateHz else 0L

    /**
     * 输入事件排队延迟：回调执行时刻 − MotionEvent.eventTime。
     *
     * 两者都在 SystemClock.uptimeMillis() 时域（**不是** elapsedRealtime()，
     * 后者包含 deep sleep，与 eventTime 不同域，直接相减会得到巨大的假值）。
     * 主线程卡顿时这个值能到几十毫秒，直接决定录进去的音符准不准。
     * 夹到 [0, 200]：异常事件（比如被系统延迟派发的旧事件）不该把播放头拽回去。
     */
    fun inputLagMs(nowUptimeMs: Long, eventUptimeMs: Long): Long =
        (nowUptimeMs - eventUptimeMs).coerceIn(0L, 200L)

    /** 用事件时刻回推播放头；只做抖动补偿，不改变时间轴的系统性基准 */
    fun playheadAtEventMs(playheadNowMs: Long, inputLagMs: Long): Long =
        playheadNowMs - inputLagMs
}
