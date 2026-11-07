package com.piezmaker

/**
 * 背景高斯模糊的纯像素运算（纯 JVM，可单测）。
 *
 * 打歌背景模糊（Phira 风格）：先缩到 1/16 小图，再做 **3 次可分离盒式滤波**
 * （三次盒式 ≈ 高斯，标准的快速近似；合成核接近 σ ≈ r × 1.7 的高斯），
 * 画回大图时双线性放大。旧实现只做「缩到 1/20 再双线性放大」= 像素模糊，块状感明显。
 *
 * 全部 O(w×h) 与半径无关（滑动窗口均值）；背景图按不透明处理（alpha 恒 255）。
 */
object BgBlur {

    /** 就地做 3 次盒式滤波；[px] 为 ARGB 像素（行主序），返回同一数组 */
    fun blur(px: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val buf = IntArray(px.size)
        val r = radius.coerceAtLeast(1)
        repeat(3) {
            boxBlur(px, buf, w, h, r, horizontal = true)
            boxBlur(buf, px, w, h, r, horizontal = false)
        }
        return px
    }

    /** 一次盒式滤波（滑动窗口均值）；窗口出界处按边缘像素延伸 */
    fun boxBlur(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val n = if (horizontal) w else h       // 沿滤波方向的长度
        val lines = if (horizontal) h else w   // 线数
        val win = 2 * r + 1
        for (line in 0 until lines) {
            var rs = 0; var gs = 0; var bs = 0
            for (k in -r..r) {
                val x = k.coerceIn(0, n - 1)
                val p = if (horizontal) src[line * w + x] else src[x * w + line]
                rs += (p ushr 16) and 0xFF; gs += (p ushr 8) and 0xFF; bs += p and 0xFF
            }
            for (i in 0 until n) {
                val out = (0xFF shl 24) or ((rs / win) shl 16) or ((gs / win) shl 8) or (bs / win)
                if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
                val pa = if (horizontal) src[line * w + (i + r + 1).coerceAtMost(n - 1)]
                         else src[(i + r + 1).coerceAtMost(n - 1) * w + line]
                val ps = if (horizontal) src[line * w + (i - r).coerceAtLeast(0)]
                         else src[(i - r).coerceAtLeast(0) * w + line]
                rs += ((pa ushr 16) and 0xFF) - ((ps ushr 16) and 0xFF)
                gs += ((pa ushr 8) and 0xFF) - ((ps ushr 8) and 0xFF)
                bs += (pa and 0xFF) - (ps and 0xFF)
            }
        }
    }
}
