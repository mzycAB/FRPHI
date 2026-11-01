package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random

/** 草稿编解码：v2 文本往返、旧 JSON 兼容、大谱面编解码基准 */
class DraftCodecTest {

    private fun sampleNotes(n: Int, seed: Int = 42): List<RecordedNote> {
        val rnd = Random(seed.toLong())
        return (0 until n).map {
            val s = rnd.nextInt(240000).toLong()
            RecordedNote(
                type = 1 + rnd.nextInt(4),
                startMs = s,
                endMs = if (rnd.nextBoolean()) s + rnd.nextInt(500) else s,
                posX = (rnd.nextFloat() * 2 - 1),
                pass = 1 + rnd.nextInt(4)
            )
        }
    }

    @Test
    fun `文本格式往返一致`() {
        val notes = sampleNotes(500)
        val kfs = listOf(
            LineKeyframe(0L, 0f, 0f, 0f),
            LineKeyframe(1200L, 0.25f, -0.5f, -15f),
            LineKeyframe(4000L, -1f, 1f, 90f)
        )
        val text = DraftCodec.encode(notes, kfs, 0.125f, -0.5f, 12.5f, 1.6f)
        assert(text.startsWith(DraftCodec.HEADER)) { "应以 v2 头开始：${text.take(40)}" }

        val d = DraftCodec.decode(text)
        assertNotNull(d)
        d!!
        assertEquals(notes.size, d.notes.size)
        for (i in notes.indices) {
            assertEquals("type@$i", notes[i].type, d.notes[i].type)
            assertEquals("start@$i", notes[i].startMs, d.notes[i].startMs)
            assertEquals("end@$i", notes[i].endMs, d.notes[i].endMs)
            assertEquals("posX@$i", notes[i].posX, d.notes[i].posX, 1e-3f)
            assertEquals("pass@$i", notes[i].pass, d.notes[i].pass)
        }
        assertEquals(3, d.keyframes.size)
        assertEquals(1200L, d.keyframes[1].timeMs)
        assertEquals(-0.5f, d.keyframes[1].moveY, 1e-5f)
        assertEquals(0.125f, d.moveX, 1e-5f)
        assertEquals(-0.5f, d.moveY, 1e-5f)
        assertEquals(12.5f, d.rotate, 1e-5f)
        assertEquals(1.6f, d.speed, 1e-5f)
    }

    @Test
    fun `旧的JSON草稿仍然能读`() {
        val old = """
            {"notes":[{"t":1,"s":100,"e":100,"x":0.5,"p":1},{"t":2,"s":200,"e":600,"x":-0.25,"p":4}],
             "kf":[{"t":0,"x":0,"y":0,"r":0}],"mx":0.1,"my":-0.2,"rot":5,"spd":1.5}
        """.trimIndent()
        val d = DraftCodec.decode(old)
        assertNotNull(d)
        assertEquals(2, d!!.notes.size)
        assertEquals(2, d.notes[1].type)
        assertEquals(600L, d.notes[1].endMs)
        assertEquals(-0.25f, d.notes[1].posX, 1e-6f)
        assertEquals(4, d.notes[1].pass)
        assertEquals(1, d.keyframes.size)
        assertEquals(1.5f, d.speed, 1e-6f)
    }

    @Test
    fun `空内容与乱码不会崩`() {
        assertNull(DraftCodec.decode(""))
        assertNull(DraftCodec.decode("   \n  "))
        // 只有头没有内容 → 空草稿（合法）
        val d = DraftCodec.decode(DraftCodec.HEADER)
        assertNotNull(d)
        assertEquals(0, d!!.notes.size)
        // 半截/坏行：跳过坏行，不抛异常
        val bad = DraftCodec.HEADER + "\nN 1 xx 100 0.5 1\nN 2 200 200 0.5 1\n"
        val d2 = DraftCodec.decode(bad)
        assertEquals(1, d2!!.notes.size)
    }

    @Test
    fun `基准_草稿编解码`() {
        val n = 10000
        val notes = sampleNotes(n)
        val kfs = (0 until 40).map { LineKeyframe(it * 1000L, 0f, 0f, 0f) }

        // 旧实现：org.json 逐条建对象
        val jsonMs = measure(3) {
            val doc = org.json.JSONObject()
            val arr = org.json.JSONArray()
            for (x in notes) {
                arr.put(
                    org.json.JSONObject().put("t", x.type).put("s", x.startMs)
                        .put("e", x.endMs).put("x", x.posX.toDouble()).put("p", x.pass)
                )
            }
            doc.put("notes", arr)
            doc.put("kf", org.json.JSONArray())
            doc.put("mx", 0.0).put("my", 0.0).put("rot", 0.0).put("spd", 1.0)
            doc.toString()
        }

        val text = DraftCodec.encode(notes, kfs, 0f, 0f, 0f, 1f)
        val newMs = measure(3) { DraftCodec.encode(notes, kfs, 0f, 0f, 0f, 1f) }

        val json = org.json.JSONObject().apply {
            val arr = org.json.JSONArray()
            for (x in notes) {
                arr.put(
                    org.json.JSONObject().put("t", x.type).put("s", x.startMs)
                        .put("e", x.endMs).put("x", x.posX.toDouble()).put("p", x.pass)
                )
            }
            put("notes", arr)
        }.toString()
        val oldDecodeMs = measure(3) { DraftCodec.decode(json) }
        val newDecodeMs = measure(3) { DraftCodec.decode(text) }

        println("BENCH draft $n notes  编码 %.1f ms → %.1f ms  (×%.1f)".format(jsonMs, newMs, jsonMs / newMs))
        println("BENCH draft $n notes  解码 %.1f ms → %.1f ms  (×%.1f)".format(oldDecodeMs, newDecodeMs, oldDecodeMs / newDecodeMs))
        println("BENCH draft 体积 ${json.length / 1024} KB → ${text.length / 1024} KB")
    }

    @Test
    fun `剪贴板随草稿一起往返`() {
        val line = Line(0)
        val notes = sampleNotes(20)
        val copy1 = notes.take(3)
        val copy2 = notes.drop(5).take(4)
        val clip = listOf(
            RecordView.ClipEntry(copy1, cut = false, id = 1),
            RecordView.ClipEntry(copy2, cut = true, id = 2)
        )
        val text = DraftCodec.encode(listOf(line), notes, clip)
        assert(text.contains("CB ")) { "剪贴板必须写进草稿文本（联机同步靠它）" }

        val d = DraftCodec.decode(text)
        assertNotNull(d)
        d!!
        assertEquals(notes.size, d.notes.size)
        assertEquals(2, d.clip.size)
        assertEquals(3, d.clip[0].notes.size)
        assertEquals(4, d.clip[1].notes.size)
        assert(d.clip[0].cut.not())
        assert(d.clip[1].cut)
        assertEquals(copy2[0].startMs, d.clip[1].notes[0].startMs)
        assertEquals(copy2[0].posX, d.clip[1].notes[0].posX, 0.0001f)
        assertEquals(copy2[0].type, d.clip[1].notes[0].type)
        // 剪贴板里的音符不能混进正谱
        assertEquals(notes.size, d.notes.size)
    }

    @Test
    fun `没有剪贴板时文本不带 CB`() {
        val text = DraftCodec.encode(listOf(Line(0)), sampleNotes(5))
        assert(!text.contains("CB ")) { "本地草稿不该写剪贴板" }
        assertEquals(0, DraftCodec.decode(text)!!.clip.size)
    }

    private fun measure(reps: Int, block: () -> Unit): Double {
        repeat(2) { block() }
        val t0 = System.nanoTime()
        repeat(reps) { block() }
        return (System.nanoTime() - t0) / 1e6 / reps
    }
}
