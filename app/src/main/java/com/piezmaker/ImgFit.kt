package com.piezmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * 导入图片的「抠边」：**图里有透明部分就把四周的透明边裁掉**（用户点名）。
 *
 * 选曲页的 9:16 长方形、背景图、结算界面右侧的长方形都走这里加载。
 * 用 `centerCrop` 铺满时，透明边会露出底下的灰色，看着像图没铺满；
 * 先把不透明内容的包围盒裁出来，图就实打实铺满整个框。
 */
object ImgFit {

    /** 解码（按 [maxW]/[maxH] 降采样）→ 裁掉四周全透明部分 */
    fun loadTrimmed(ctx: Context, uri: Uri, maxW: Int, maxH: Int): Bitmap? {
        return try {
            val res = ctx.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            res.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = res.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null
            trim(bmp)
        } catch (_: Exception) {
            null
        }
    }

    /** 裁掉四周 alpha 为 0 的边；没有透明像素就原样返回（同一对象） */
    fun trim(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((px[row + x] ushr 24) > 8) {          // 几乎全透明才算透明
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < left || bottom < top) return src      // 整张全透明：不裁
        if (left == 0 && top == 0 && right == w - 1 && bottom == h - 1) return src
        return try {
            Bitmap.createBitmap(src, left, top, right - left + 1, bottom - top + 1)
        } catch (_: Exception) {
            src
        }
    }
}
