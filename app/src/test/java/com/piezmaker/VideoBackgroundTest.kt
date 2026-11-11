package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 视频背景：草稿 VB 行往返 + Phira extra.json 生成 */
class VideoBackgroundTest {

    @Test
    fun draftRoundTripKeepsVideoFields() {
        val v = VideoBg(
            fileName = "bga.mp4",
            startMs = 1500L,
            scaleMode = VideoBg.SCALE_INSIDE,
            alpha = 0.75f,
            dim = 0.25f
        )
        val text = DraftCodec.encode(
            listOf(Line(0)), emptyList(),
            videos = listOf(v)
        )
        val back = DraftCodec.decode(text)!!.videos
        assertEquals(1, back.size)
        assertEquals("bga.mp4", back[0].fileName)
        assertEquals(1500L, back[0].startMs)
        assertEquals(VideoBg.SCALE_INSIDE, back[0].scaleMode)
        assertEquals(0.75f, back[0].alpha, 1e-4f)
        assertEquals(0.25f, back[0].dim, 1e-4f)
    }

    @Test
    fun extraJsonWritesPhiraVideoFields() {
        val json = PezExporter.buildExtraJson(
            120.0, emptyList(),
            listOf(VideoBg("bga.mp4", 1000L, VideoBg.SCALE_FIT, 0.5f, 0.3f))
        )
        assertTrue(json.contains("\"videos\""))
        assertTrue(json.contains("\"path\": \"bga.mp4\""))
        assertTrue(json.contains("\"scale\": \"fit\""))
        assertTrue(json.contains("\"alpha\": 0.5"))
        assertTrue(json.contains("\"dim\": 0.3"))
        // 1000ms @120bpm = 2 拍
        assertTrue(json.contains("[2, 0, 1]"))
    }
}
