package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 直接改谱的两类细粒度操作：
 *  - 单个音符：改时间/位置/结束时间/**键型**/所属判定线/正反面/真假，只动给了的字段；
 *  - 判定线关键帧：同一时刻只改给了的字段（只给 sp 不能把姿态冲成 0），还能删掉某一帧。
 */
class AiChartEditTest {

    private fun holder(notes: List<RecordedNote> = mutableListOf(), lines: List<Line> = listOf(Line(0))): AiChart.Holder {
        val ls = lines.map { l ->
            Line(l.id).apply {
                posPct = l.posPct; speed = l.speed; moveX = l.moveX; moveY = l.moveY; rotate = l.rotate
                alpha = l.alpha; name = l.name
                keyframes.addAll(l.keyframes)
                invalidate()
            }
        }.toMutableList()
        return AiChart.Holder(ls, notes.sortedBy { it.startMs }.toMutableList(), 1)
    }

    @Test
    fun `fix 改键型 Tap 变长条会自动补 end`() {
        val h = holder(mutableListOf(RecordedNote(1, 1000, 1000, 0.5f, 1, 0, true, false)))
        val ok = AiChart.applyJsonLine("""{"fix":0,"note":2}""", h, 60_000, 120.0, 1)
        assertTrue(ok)
        val n = h.notes[0]
        assertEquals(2, n.type)
        assertEquals(4, n.pass)
        assertTrue("长条要有结束时间：${n.endMs}", n.endMs > n.startMs)
    }

    @Test
    fun `fix 只给 x 时其它字段不动`() {
        val h = holder(mutableListOf(RecordedNote(3, 800, 800, -0.25f, 3, 0, false, true)))
        AiChart.applyJsonLine("""{"fix":0,"x":0.75}""", h, 60_000, 120.0, 1)
        val n = h.notes[0]
        assertEquals(0.75f, n.posX, 0.0001f)
        assertEquals(3, n.type)
        assertEquals(800L, n.startMs)
        assertFalse("above 不能被动", n.above)
        assertTrue("fake 不能被动", n.fake)
    }

    @Test
    fun `fix 可以改真假与正反面`() {
        val h = holder(mutableListOf(RecordedNote(1, 500, 500, 0f, 1, 0, true, false)))
        AiChart.applyJsonLine("""{"fix":0,"fake":1}""", h, 60_000, 120.0, 1)
        assertTrue(h.notes[0].fake)
        AiChart.applyJsonLine("""{"fix":0,"above":0,"fake":0}""", h, 60_000, 120.0, 1)
        assertFalse(h.notes[0].above)
        assertFalse(h.notes[0].fake)
    }

    @Test
    fun `kf 只给 sp 不会把姿态冲成 0`() {
        val l = Line(0).apply {
            moveX = 0.4f; moveY = -0.2f; rotate = 30f; alpha = 0.75f
            keyframes.add(LineKeyframe(1000, 0.4f, -0.2f, 30f, 1f, 0.75f))
        }
        val h = holder(lines = listOf(l))
        AiChart.applyJsonLine("""{"kf":0,"t":1000,"sp":2.0}""", h, 60_000, 120.0, 1)
        val kf = h.lines[0].keyframes.first { it.timeMs == 1000L }
        assertEquals(2.0f, kf.speed, 0.001f)
        assertEquals(0.4f, kf.moveX, 0.001f)
        assertEquals(-0.2f, kf.moveY, 0.001f)
        assertEquals(30f, kf.rotate, 0.001f)
        assertEquals(0.75f, kf.alpha, 0.001f)
    }

    @Test
    fun `kf 在空线上新建时用线的姿态做基准`() {
        val l = Line(0).apply { moveX = 0.3f; rotate = 15f; alpha = 0.5f }
        val h = holder(lines = listOf(l))
        AiChart.applyJsonLine("""{"kf":0,"t":2000,"sp":1.5}""", h, 60_000, 120.0, 1)
        val kf = h.lines[0].keyframes.first { it.timeMs == 2000L }
        assertEquals(0.3f, kf.moveX, 0.001f)
        assertEquals(15f, kf.rotate, 0.001f)
        assertEquals(0.5f, kf.alpha, 0.001f)
        assertEquals(1.5f, kf.speed, 0.001f)
    }

    @Test
    fun `kfdel 删除指定时刻的帧`() {
        val l = Line(0).apply {
            keyframes.add(LineKeyframe(1000, 0f, 0f, 0f, 1f, 1f))
            keyframes.add(LineKeyframe(2000, 0f, 0f, 90f, 1f, 1f))
            invalidate()
        }
        val h = holder(lines = listOf(l))
        val ok = AiChart.applyJsonLine("""{"kfdel":0,"t":1000}""", h, 60_000, 120.0, 1)
        assertTrue(ok)
        assertEquals(1, h.lines[0].keyframes.size)
        assertEquals(2000L, h.lines[0].keyframes[0].timeMs)
        // 没有 t 就不能乱删
        assertFalse(AiChart.applyJsonLine("""{"kfdel":0}""", h, 60_000, 120.0, 1))
        assertEquals(1, h.lines[0].keyframes.size)
    }

    @Test
    fun `谱面摘要里带有关键帧清单`() {
        val l = Line(0).apply {
            keyframes.add(LineKeyframe(1500, 0.1f, 0.2f, 45f, 1.5f, 0.8f))
            invalidate()
        }
        val digest = AiChart.chartDigest(listOf(l), listOf(RecordedNote(1, 100, 100, 0f, 1, 0, true, false)), 50)
        assertTrue(digest.contains("kf line=0 t=1500"))
        assertTrue(digest.contains("sp=1.5"))
        assertTrue(digest.contains("a=0.8"))
    }

    /** copyLines（AI 每轮编辑/写谱前的快照）必须保留图片线/文字线的素材属性，
     *  否则 AI 一碰谱面，写回草稿时图片线丢贴图、文字线丢文字、缩放全丢 */
    @Test
    fun `copyLines 保留图片线文字线素材属性`() {
        val image = Line(7).apply {
            texture = "bg_cat.png"; texturePath = "/storage/emulated/0/bg_cat.png"
            scaleX = 1.5f; scaleY = 1.8f
            invalidate()
        }
        val text = Line(9).apply {
            lineText = "副标题"
            scaleX = 2f; scaleY = 0.5f
            textEvents.add(TextEvent(1000, 2000, "你好", "世界", 1, "", 0))
            invalidate()
        }
        val copied = AiChart.copyLines(listOf(image, text))
        val ci = copied.first { it.id == 7 }
        val ct = copied.first { it.id == 9 }
        assertEquals(LineKind.Image, ci.kind)
        assertEquals("bg_cat.png", ci.texture)
        assertEquals(ci.scaleX, 1.5f, 1e-6f)
        assertEquals(ci.scaleY, 1.8f, 1e-6f)
        assertEquals(LineKind.Text, ct.kind)
        assertEquals("副标题", ct.lineText)
        assertEquals(ct.scaleX, 2f, 1e-6f)
        assertEquals(ct.scaleY, 0.5f, 1e-6f)
        assertEquals(1, ct.textEvents.size)
        assertEquals("你好", ct.textEvents[0].start)
    }

    @Test
    fun `note 支持音符级扩展属性`() {
        val h = holder()
        val ok = AiChart.applyJsonLine(
            """{"note":1,"t":1000,"x":0,"speed":2.5,"alpha":0.5,"size":1.3,"visibleTime":2,"yOffset":-40}""",
            h, 60_000, 120.0, 1
        )
        assertTrue(ok)
        val n = h.notes[0]
        assertEquals(2.5f, n.speed, 1e-6f)
        assertEquals(0.5f, n.alpha, 1e-6f)
        assertEquals(1.3f, n.size, 1e-6f)
        assertEquals(2f, n.visibleTime, 1e-6f)
        assertEquals(-40f, n.yOffset, 1e-6f)
        // 太离谱的 speed 要夹住
        val bad = AiChart.applyJsonLine("""{"note":1,"t":2000,"speed":9999}""", h, 60_000, 120.0, 1)
        assertTrue(bad)
        assertEquals(50f, h.notes[1].speed, 1e-6f)
    }

    @Test
    fun `fix 只改给了的扩展属性`() {
        val h = holder(mutableListOf(RecordedNote(1, 1000, 1000, 0f, 1, 0, true, false)))
        assertTrue(AiChart.applyJsonLine("""{"fix":0,"speed":3}""", h, 60_000, 120.0, 1))
        val n = h.notes[0]
        assertEquals(3f, n.speed, 1e-6f)
        assertEquals(1f, n.alpha, 1e-6f)      // 没给 alpha 不能动
        assertEquals(1000L, n.startMs)          // 没给 t 不能动
    }

    @Test
    fun `nl 能建图片线和文字线并指定图层`() {
        val h = holder()
        assertTrue(AiChart.applyJsonLine(
            """{"nl":0,"pos":30,"text":"你好谱面","scaleX":2,"layer":5}""", h, 60_000, 120.0, 1))
        val txt = h.lines.first { it.id == 0 }
        assertEquals(LineKind.Text, txt.kind)
        assertEquals("你好谱面", txt.lineText)
        assertEquals(2f, txt.scaleX, 1e-6f)
        assertEquals(5, txt.zOrder)

        assertTrue(AiChart.applyJsonLine(
            """{"nl":1,"pos":70,"tex":"bg.png"}""", h, 60_000, 120.0, 1))
        val img = h.lines.first { it.id == 1 }
        assertEquals(LineKind.Image, img.kind)
        assertEquals("bg.png", img.texture)
        // 普通 nl 还是判定线
        assertTrue(AiChart.applyJsonLine("""{"nl":2,"pos":50}""", h, 60_000, 120.0, 1))
        assertEquals(LineKind.Normal, h.lines.first { it.id == 2 }.kind)
    }

    @Test
    fun `line 能改素材内容缩放图层面板`() {
        val l = Line(0).apply { posPct = 50; invalidate() }
        val h = holder(lines = listOf(l))
        assertTrue(AiChart.applyJsonLine(
            """{"line":0,"alpha":0.4,"scaleY":3,"layer":7,"text":"副标题啊"}""", h, 60_000, 120.0, 1))
        val ln = h.lines[0]
        assertEquals(0.4f, ln.alpha, 1e-6f)
        assertEquals(3f, ln.scaleY, 1e-6f)
        assertEquals(7, ln.zOrder)
        assertEquals(LineKind.Text, ln.kind)
        assertEquals("副标题啊", ln.lineText)
    }

    @Test
    fun `na 新建噪域 area 改与删`() {
        val h = holder()
        assertTrue(AiChart.applyJsonLine(
            """{"na":1,"l":-300,"b":-200,"r":300,"t":200,"appear":0,"disappear":30000,"invert":1,"layer":"2"}""",
            h, 60_000, 120.0, 1))
        assertEquals(1, h.noises.size)
        val a = h.noises[0]
        assertEquals(-300f, a.left, 1e-6f)
        assertEquals(300f, a.right, 1e-6f)
        assertEquals(200f, a.top, 1e-6f)
        assertTrue(a.invert)
        assertEquals("2", a.layerId)
        assertEquals(30_000.0, a.disappearMs, 1e-6)

        // 改位置/显隐
        assertTrue(AiChart.applyJsonLine("""{"area":0,"r":400,"t":0,"visible":0}""", h, 60_000, 120.0, 1))
        assertEquals(400f, h.noises[0].right, 1e-6f)
        assertEquals(0f, h.noises[0].top, 1e-6f)
        assertFalse(h.noises[0].visible)
        assertEquals(-300f, h.noises[0].left, 1e-6f)   // 没给的字段不动

        // 删除
        assertTrue(AiChart.applyJsonLine("""{"area":0,"delete":1}""", h, 60_000, 120.0, 1))
        assertEquals(0, h.noises.size)
    }

    @Test
    fun `digest 输出噪域与素材线信息`() {
        val img = Line(2).apply { texture = "bg.png"; zOrder = 3; invalidate() }
        val a = NoiseArea(left = -100f, bottom = -50f, right = 100f, top = 50f, disappearMs = 5000.0, layerId = "1")
        val d = AiChart.chartDigest(
            listOf(img), listOf(RecordedNote(1, 100, 100, 0f, 1, 0, true, false)),
            noises = listOf(a)
        )
        assertTrue(d.contains("tex=bg.png"))
        assertTrue(d.contains("layer=3"))
        assertTrue(d.contains("噪域共 1 块"))
        assertTrue(d.contains("l=-100 b=-50 r=100 t=50"))
        assertTrue(d.contains("disappear=5000"))
        assertTrue(d.contains("layer=1"))
    }
}
