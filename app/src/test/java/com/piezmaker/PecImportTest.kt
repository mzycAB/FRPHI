package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PEC（Phigros 文本谱）导入：不依赖真实谱面，只用合成的小样例验证
 * 时间换算 / 音符字段 / 线姿态关键帧 / 流速。
 */
class PecImportTest {

    private val sample = """
        175
        bp 0 120
        bp 4 240
        cp 0 0 0 0
        cv 0 0 5.85
        cd 0 0 -30
        n1 0 1 0 1 0
        n2 0 2 4 256 1 0 # 2.0 & 1.5
        n3 0 3 -512 1 0
        n4 0 4 1024 0 0
        cm 0 0 2 512 700 1
    """.trimIndent()

    @Test
    fun importsPecText() {
        val c = PezImporter.importText(sample, "test")
        assertEquals(1, c.lines.size)
        val line = c.lines[0]

        // 首行 offset = 175ms/1000 - 0.15 = 0.025s
        // BPM：0~4 拍 120（1 拍 0.5s），4 拍后 240（1 拍 0.25s）
        val notes = line.notes
        assertEquals(4, notes.size)
        // n1 beat 1 → 0.5s + 0.025s
        assertEquals(525L, notes[0].startMs)
        assertEquals(1, notes[0].type)
        assertEquals(0f, notes[0].posX, 1e-5f)
        // n2 hold beat 2~4 → 1.0s~2.0s + offset
        assertEquals(1025L, notes[1].startMs)
        assertEquals(2025L, notes[1].endMs)
        assertEquals(2, notes[1].type)
        assertEquals(0.25f, notes[1].posX, 1e-5f)
        assertEquals(2f, notes[1].speed, 1e-5f)
        assertEquals(1.5f, notes[1].size, 1e-5f)
        // n3 flick / n4 drag
        assertEquals(3, notes[2].type)
        assertEquals(4, notes[3].type)
        assertTrue(notes[3].posX > 0.99f)

        // 线：cp(0,0) → cm 到 (512,700) 的过渡；y 归一化 (700/1400*2-1)=0
        assertEquals(50, line.posPct)
        assertTrue(line.keyframes.isNotEmpty())
        // cv 5.85 → Phira 1 → 本项目 0.7692308
        assertEquals(0.7692308f, line.speed, 1e-4f)
        // cd -30 → 本项目取反 → 30 度
        assertEquals(30f, line.rotate, 1e-3f)
    }
}
