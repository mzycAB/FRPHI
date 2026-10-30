package com.piezmaker

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多判定线 / 流速变化 / 假音符 / 反面音符 / 不吸附导出 —— 纯 JVM 测试。
 * 走 buildChartJsonCore（不吃 ChartMeta，避免在 JVM 上造 android.net.Uri）。
 */
class MultiLineExportTest {

    private fun chartJson(
        lines: List<Line>,
        notes: List<RecordedNote>,
        bpm: Double = 120.0,
        delayMs: Int = 0
    ): JSONObject = JSONObject(
        PezExporter.buildChartJsonCore(
            name = "t", level = "1", charter = "a", composer = "c",
            bpm = bpm, delayMs = delayMs, fallbackPosPct = 60,
            lines = lines, notes = notes, musicName = "m.ogg", imageName = "bg.png"
        )
    )

    private fun lineOf(id: Int, pct: Int, speed: Float = 1f): Line = Line(id).apply {
        posPct = pct
        this.speed = speed
        invalidate()
    }

    @Test
    fun `时间按原样导出不被吸附`() {
        // 1234ms @120BPM = 2.468 拍 = 2 + 117/250（精确约分，不是 1/4 拍格）
        assertEquals("[2, 117, 250]", PezExporter.beatTriple(1234L, 120.0))
        // 整拍仍然是干净的整数
        assertEquals("[1, 0, 1]", PezExporter.beatTriple(500L, 120.0))
        // 1/3 拍这种三连音：250ms @120BPM = 0.5 拍；换成 1/3 拍 = 166.67ms 不可精确，
        // 用 1000ms @180BPM 验证：1000ms = 3 拍整
        assertEquals("[3, 0, 1]", PezExporter.beatTriple(1000L, 180.0))
        // 带小数的 BPM 也要精确：1000ms @128.5BPM = 1285/600 拍 = 2 + 17/120
        assertEquals("[2, 17, 120]", PezExporter.beatTriple(1000L, 128.5))
        // 三连音 1/3 拍：120BPM 下一拍 500ms，1/3 拍 ≈ 166.667ms。
        // 用 500/3 ms 不可能是整数，所以取 334ms 验证"非格点时间不被吸到 0.25 拍"
        val t = PezExporter.beatTriple(334L, 120.0)
        assertTrue("334ms 不应被吸到整格：$t", t != "[0, 1, 4]" && t != "[0, 0, 1]")
    }

    @Test
    fun `位置按原样导出不被吸附`() {
        val l = lineOf(0, 60)
        val notes = listOf(
            RecordedNote(1, 1000L, 1000L, 0.1234f),   // 不在 1/8 格上
            RecordedNote(1, 1500L, 1500L, -0.7777f)
        )
        val json = chartJson(listOf(l), notes)
        val arr = json.getJSONArray("judgeLineList").getJSONObject(0).getJSONArray("notes")
        assertEquals(83.295, arr.getJSONObject(0).getDouble("positionX"), 1e-3)
        assertEquals(-524.948, arr.getJSONObject(1).getDouble("positionX"), 1e-3)
    }

    @Test
    fun `多判定线_假音符_反面音符_流速事件都写进 chart`() {
        val l0 = lineOf(0, 80)
        val l1 = lineOf(1, 30)
        // 第二条线：两个关键帧，流速 1 → 2（就是"流速变化"）
        l1.keyframes.add(LineKeyframe(0L, 0f, 0f, 0f, 1f))
        l1.keyframes.add(LineKeyframe(2000L, 0f, 0f, 0f, 2f))
        l1.invalidate()

        val notes = listOf(
            RecordedNote(1, 1000L, 1000L, 0f, 1, lineId = 0),
            RecordedNote(2, 1000L, 2000L, 0.5f, 4, lineId = 1, above = false),
            RecordedNote(1, 1500L, 1500L, -0.5f, 1, lineId = 1, fake = true)
        )
        val json = chartJson(listOf(l0, l1), notes)
        val lines = json.getJSONArray("judgeLineList")
        assertEquals(2, lines.length())

        // 线 0：1 个音符
        assertEquals(1, lines.getJSONObject(0).getJSONArray("notes").length())
        // 线 1：2 个音符，其中一个 above=0、一个 isFake=1
        val n1 = lines.getJSONObject(1).getJSONArray("notes")
        assertEquals(2, n1.length())
        val aboveVals = (0 until n1.length()).map { n1.getJSONObject(it).getInt("above") }.sorted()
        val fakeVals = (0 until n1.length()).map { n1.getJSONObject(it).getInt("isFake") }.sorted()
        assertEquals(listOf(0, 1), aboveVals)
        assertEquals(listOf(0, 1), fakeVals)

        // 线 1 的 speedEvents 必须有两个值 1 和 2（分段常速）
        val speed = lines.getJSONObject(1)
            .getJSONArray("eventLayers").getJSONObject(0)
            .getJSONArray("speedEvents")
        assertEquals(2, speed.length())
        assertEquals(1.0, speed.getJSONObject(0).getDouble("start"), 1e-9)
        assertEquals(2.0, speed.getJSONObject(1).getDouble("start"), 1e-9)

        // 线 0 是恒速 1
        val speed0 = lines.getJSONObject(0)
            .getJSONArray("eventLayers").getJSONObject(0)
            .getJSONArray("speedEvents")
        assertEquals(1.0, speed0.getJSONObject(0).getDouble("start"), 1e-9)
    }

    @Test
    fun `流速曲线积分_关键帧之间线性过渡（RPE 语义）`() {
        val l = lineOf(0, 60)
        l.keyframes.add(LineKeyframe(0L, 0f, 0f, 0f, 1f))
        l.keyframes.add(LineKeyframe(1000L, 0f, 0f, 0f, 2f))
        l.invalidate()
        val tr = l.speedTrack()
        // 0~1000ms 流速从 1 线性升到 2（RPE/Phiedit 的 speedEvents 就是插值的）：
        //   H(1000) = (1+2)/2 × 1000 = 1500
        //   之后恒为 2：H(2000) = 1500 + 2×1000 = 3500
        //   中间点 t=500：k=0.5，H = 1×500 + (2-1)×500²/(2×1000) = 500 + 125 = 625
        assertEquals(1500.0, tr.heightAt(1000L), 1e-6)
        assertEquals(3500.0, tr.heightAt(2000L), 1e-6)
        assertEquals(625.0, tr.heightAt(500L), 1e-6)
        assertEquals(1.5f, tr.speedAt(500L), 1e-6f)
        assertEquals(2f, tr.speedAt(1500L), 1e-6f)
    }

    @Test
    fun `草稿v3往返_保留多线与假音符反面音符`() {
        val l0 = lineOf(0, 70)
        val l1 = lineOf(3, 25, 2.5f)
        l1.keyframes.add(LineKeyframe(0L, 0.1f, -0.2f, 15f, 1f))
        l1.keyframes.add(LineKeyframe(900L, 0.1f, -0.2f, 15f, 3f))
        l1.invalidate()
        val notes = listOf(
            RecordedNote(1, 100L, 100L, 0.25f, 1, lineId = 0),
            RecordedNote(2, 200L, 700L, -0.5f, 4, lineId = 3, above = false, fake = true)
        )
        val text = DraftCodec.encode(listOf(l0, l1), notes)
        assertTrue(text.startsWith(DraftCodec.HEADER))

        val d = DraftCodec.decode(text)!!
        assertEquals(2, d.lines.size)
        assertEquals(70, d.lines[0].posPct)
        assertEquals(25, d.lines[1].posPct)
        assertEquals(2.5f, d.lines[1].speed, 1e-6f)
        assertEquals(2, d.lines[1].keyframes.size)
        assertEquals(3f, d.lines[1].keyframes[1].speed, 1e-6f)
        assertEquals(2, d.notes.size)
        val n = d.notes.first { it.lineId == 3 }
        assertEquals(2, n.type)
        assertEquals(false, n.above)
        assertEquals(true, n.fake)
        assertEquals(700L, n.endMs)
    }

    @Test
    fun `旧v2草稿仍能读_并折成一条判定线`() {
        val v2 = """
            #FRPHI-DRAFT 2
            mx 0.12
            my -0.4
            rot 5
            spd 1.6
            K 1000 0.12 -0.4 0
            N 1 1000 1000 0.5 1
        """.trimIndent()
        val d = DraftCodec.decode(v2)!!
        assertEquals(1, d.lines.size)
        assertEquals(-1, d.lines[0].posPct)     // -1 = 沿用设置页的判定线位置
        assertEquals(0.12f, d.lines[0].moveX, 1e-6f)
        assertEquals(1.6f, d.lines[0].speed, 1e-6f)
        assertEquals(1, d.lines[0].keyframes.size)
        assertEquals(1, d.notes.size)
        assertEquals(0, d.notes[0].lineId)
        assertEquals(true, d.notes[0].above)
        assertEquals(false, d.notes[0].fake)
    }
}
