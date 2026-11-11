package com.piezmaker

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build

/**
 * Phira `extra.json` 里的视频背景（`videos` 数组的一项）。
 *
 * 语义和 Phira 保持一致：**全屏背景**，从 [startMs] 开始播放；
 * 只支持缩放方式 / 透明度 / 昏暗度，不能像图片线那样自由摆放。
 */
class VideoBg(
    /** 包内文件名（项目导入目录里的名字，导出时按这个名字打包） */
    var fileName: String = "",
    /** 开始时间（谱面时间，毫秒） */
    var startMs: Long = 0L,
    /** 缩放方式：0=裁剪居中 1=完整显示 2=拉伸铺满 */
    var scaleMode: Int = SCALE_CROP,
    /** 不透明度 0~1 */
    var alpha: Float = 1f,
    /** 昏暗度 0~1（越大越暗） */
    var dim: Float = 0f
) {
    /** 本机绝对路径（不进草稿/导出，载入时按项目目录重新解析） */
    var localPath: String = ""

    fun copy(): VideoBg = VideoBg(fileName, startMs, scaleMode, alpha, dim).also { it.localPath = localPath }

    companion object {
        const val SCALE_CROP = 0
        const val SCALE_INSIDE = 1
        const val SCALE_FIT = 2

        fun scaleName(mode: Int): String = when (mode) {
            SCALE_INSIDE -> "完整"
            SCALE_FIT -> "拉伸"
            else -> "裁剪"
        }

        fun scaleKey(mode: Int): String = when (mode) {
            SCALE_INSIDE -> "inside"
            SCALE_FIT -> "fit"
            else -> "cropCenter"
        }

        fun scaleFromKey(key: String): Int = when (key) {
            "inside" -> SCALE_INSIDE
            "fit" -> SCALE_FIT
            else -> SCALE_CROP
        }
    }
}

/**
 * 视频帧解码器：后台线程按需取一帧（缩到 640 宽，够当背景用），
 * 主线程只读 [bitmap]。
 *
 * 编辑器和 Autoplay 都只是"把当前时间点的画面画出来"，不需要解码音频，
 * 也不需要和播放器逐帧同步；用 MediaMetadataRetriever 取帧最省事，
 * 请求会合并（只保留最新一帧），搓时间轴时不会堆任务。
 */
class VideoFrameLoader(private val path: String) {
    @Volatile
    var bitmap: Bitmap? = null
        private set

    /** 视频总时长（毫秒）；0 = 还没读到 */
    @Volatile
    var durationMs: Long = 0L
        private set

    @Volatile
    var failed = false
        private set

    /** 新帧就绪时回调（主线程调用，用来触发重绘） */
    @Volatile
    var onFrame: (() -> Unit)? = null

    private val lock = Object()
    private var requestedMs = -1L
    private var closed = false

    private val thread = Thread({ loop() }, "frphi-video").apply {
        isDaemon = true
        start()
    }

    /** 请求某一时刻的画面（毫秒，相对视频开头）；连续请求只保留最后一个 */
    fun request(atMs: Long) {
        synchronized(lock) {
            requestedMs = atMs.coerceAtLeast(0L)
            lock.notifyAll()
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }

    private fun loop() {
        var retriever: MediaMetadataRetriever? = null
        try {
            while (true) {
                val target: Long
                synchronized(lock) {
                    while (!closed && requestedMs < 0) lock.wait()
                    if (closed) return
                    target = requestedMs
                    requestedMs = -1L
                }
                if (retriever == null) {
                    retriever = MediaMetadataRetriever()
                    retriever.setDataSource(path)
                    durationMs = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                }
                val frame = decode(retriever, target) ?: continue
                bitmap = frame
                onFrame?.invoke()
            }
        } catch (_: Throwable) {
            failed = true
        } finally {
            runCatching { retriever?.release() }
        }
    }

    private fun decode(r: MediaMetadataRetriever, atMs: Long): Bitmap? {
        val us = atMs * 1000L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (vw > 0 && vh > 0) {
                val tw = minOf(640, vw)
                val th = (tw.toLong() * vh / vw).toInt().coerceAtLeast(2)
                r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, tw, th)?.let { return it }
            }
        }
        val full = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
        if (full.width <= 640) return full
        val s = 640f / full.width
        val small = Bitmap.createScaledBitmap(
            full, 640, (full.height * s).toInt().coerceAtLeast(2), true
        )
        if (small !== full) full.recycle()
        return small
    }
}
