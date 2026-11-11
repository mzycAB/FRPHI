package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 草稿编解码热路径的数值一致性（大谱性能优化那轮改的 appF/Toks）：
 * encode 走手工 4 位小数出 digits，decode 走就地数字扫描（m ≥ 2^24 或非十进制形态退回标准解析）。
 * 两端的最终 Float 必须和旧实现逐位一致，这里用边界值锁死。
 */
class DraftFastCodecTest {

    private fun roundtripKf(kf: LineKeyframe, scaleX: Float = 1f, scaleY: Float = 1f): LineKeyframe {
        val l = Line(7).apply {
            name = "t"
            keyframes.add(kf)
            this.scaleX = scaleX
            this.scaleY = scaleY
        }
        val text = DraftCodec.encode(listOf(l), emptyList())
        val d = DraftCodec.decode(text) ?: error("decode 失败")
        val back = d.lines.first { it.id == 7 }
        assertEquals(1, back.keyframes.size)
        return back.keyframes[0]
    }

    @Test
    fun `关键帧数值往返逐位一致_快路径`() {
        // 全部落在"简单十进制 + m < 2^24"快路径的小值
        val kf = LineKeyframe(
            timeMs = 123456L, moveX = -1.2345f, moveY = 0.5678f, rotate = -90f,
            speed = 2.5f, alpha = -0.5f, scaleX = 1.2345f, scaleY = 0.125f
        )
        assertEquals(kf, roundtripKf(kf))
    }

    @Test
    fun `关键帧数值往返逐位一致_回退路径`() {
        // m ≥ 2^24（大整数部分）走 substring + Float.parseFloat 回退
        val kf = LineKeyframe(
            timeMs = 987654321L, moveX = 2000.1234f, moveY = -16777216f / 10000f,
            rotate = 7200.5f, speed = 0.0001f, alpha = 1f, scaleX = 9999.9999f, scaleY = 20f
        )
        val back = roundtripKf(kf)
        assertEquals(kf.timeMs, back.timeMs)
        assertEquals(kf.moveX, back.moveX)
        assertEquals(kf.rotate, back.rotate)
        assertEquals(kf.speed, back.speed)
        assertEquals(kf.scaleX, back.scaleX)
    }

    @Test
    fun `线基础字段往返一致`() {
        val l = Line(3).apply {
            posPct = -50
            moveX = 1.5f; moveY = -2.25f; rotate = 44.9999f
            speed = 0.0625f; alpha = -0.75f          // 负 alpha = 隐藏线，原样保存
            name = "名字 带空格"
        }
        val text = DraftCodec.encode(listOf(l), emptyList())
        val d = DraftCodec.decode(text) ?: error("decode 失败")
        val back = d.lines.first { it.id == 3 }
        assertEquals(-50, back.posPct)
        assertEquals(1.5f, back.moveX)
        assertEquals(-2.25f, back.moveY)
        assertEquals(44.9999f, back.rotate)
        assertEquals(0.0625f, back.speed)
        assertEquals(-0.75f, back.alpha)
        assertEquals("名字 带空格", back.name)
    }

    @Test
    fun `空图层声明随草稿往返`() {
        val l = Line(0).apply { name = "t"; zOrder = 2 }
        val text = DraftCodec.encode(listOf(l), emptyList(), emptyList(), emptyList(), setOf(2, 7, 9))
        val d = DraftCodec.decode(text) ?: error("decode 失败")
        assertEquals(setOf(2, 7, 9), d.declaredLayers)
    }

    @Test
    fun `空噪域图层声明随草稿往返`() {
        val l = Line(0).apply { name = "t" }
        val text = DraftCodec.encode(
            listOf(l), emptyList(), emptyList(), emptyList(),
            emptySet(), setOf("3", "skin 2")
        )
        val d = DraftCodec.decode(text) ?: error("decode 失败")
        assertEquals(setOf("3", "skin 2"), d.declaredNoiseLayers)
    }
}
