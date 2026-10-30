package com.piezmaker

/**
 * 延迟校准的纯计算部分（不依赖 Android，可单元测试）。
 *
 * 校准节奏：「嗒 嗒 嗒 嗒 — 滴」，一轮 2000ms，滴在 1600ms 处；
 * 玩家跟着"滴"点，记录（点击的媒体位置 − 滴的媒体位置）。
 */
object CalibMath {

    /** 一次点击的匹配结果 */
    class Tap(val dipPos: Long, val delta: Long)

    /**
     * 把一次点击的媒体位置映射到最近的「滴」。
     *
     * @param posMs 点击时的媒体位置（MediaPlayer.currentPosition）
     * @param dipOffsetMs 第一声「滴」在录音里的位置（1600）
     * @param cycleMs 一轮的长度（2000）
     * @param earlyMs 允许提前多少（负数下限的绝对值）
     * @param lateMs 允许延后多少
     * @return null 表示这次点击不该计数（还没到第一声滴 / 离滴太远，多半点在了「嗒」上）
     */
    fun mapTap(posMs: Long, dipOffsetMs: Long, cycleMs: Long, earlyMs: Long, lateMs: Long): Tap? {
        if (posMs < dipOffsetMs - earlyMs) return null       // 第一声「滴」之前
        val n = Math.round((posMs - dipOffsetMs).toDouble() / cycleMs)
        val dipPos = dipOffsetMs + n * cycleMs
        val delta = posMs - dipPos
        if (delta < -earlyMs || delta > lateMs) return null  // 「嗒」在滴前 400/800/1200ms，明显不是跟滴点的
        return Tap(dipPos, delta)
    }

    /**
     * 建议延迟：取中位数。
     * 平均值对单个点歪的样本太敏感（一次 -800 就能把结果拖偏 80ms），中位数不受影响。
     */
    fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }
}
