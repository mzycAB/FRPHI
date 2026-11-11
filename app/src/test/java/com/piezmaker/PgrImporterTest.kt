package com.piezmaker

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Phigros 官谱（Official JSON，formatVersion 1 / 3）导入测试。
 *
 * 对照的是 Phira 的 `prpr/src/parse/pgr.rs`：
 *  - 每条判定线自带 BPM，时间单位 = 60/32/BPM 秒（32 分音符）
 *  - type 1 Tap / 2 Drag / 3 Hold / 4 Flick（和 RPE 的 1/2/3/4 语义不同）
 *  - notesAbove 上落、notesBelow 下落
 *  - offset 单位是秒，正数 = 谱面比音乐快
 *  - 音符 positionX 的归一化系数是 2*9/160 = 0.1125（Phira `parse_notes`），**不是** RPE 的 /675
 */
class PgrImporterTest {

    private fun zipOf(files: List<Pair<String, ByteArray>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, data) in files) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun pgrEvent(t0: Double, t1: Double, v0: Double, v1: Double): JSONObject =
        JSONObject().put("startTime", t0).put("endTime", t1).put("start", v0).put("end", v1)

    private fun pgrMove(t0: Double, t1: Double, x0: Double, x1: Double, y0: Double, y1: Double): JSONObject =
        JSONObject().put("startTime", t0).put("endTime", t1)
            .put("start", x0).put("end", x1).put("start2", y0).put("end2", y1)

    private fun pgrNote(type: Int, time: Double, posX: Double, hold: Double = 0.0, speed: Double = 1.0): JSONObject =
        JSONObject().put("type", type).put("time", time)
            .put("positionX", posX).put("holdTime", hold).put("speed", speed)

    /**
     * 一张 120 BPM 的官谱（formatVersion 3）。
     * 120 BPM ⇒ 1 个单位 = 60/32/120 = 0.015625 秒 = 15.625 毫秒。
     * 所以 time=32 ⇒ 0.5 秒，time=64 ⇒ 1 秒。
     */
    private fun sampleChart(fmt: Int = 3): JSONObject {
        val notes = JSONArray()
            .put(pgrNote(1, 32.0, 0.0))       // Tap   0.5s
            .put(pgrNote(2, 48.0, 0.5))       // Drag  0.75s
            .put(pgrNote(3, 64.0, -0.5, 32.0)) // Hold 1.0s → 1.5s
            .put(pgrNote(4, 80.0, 1.0))       // Flick 1.25s

        val below = JSONArray().put(pgrNote(1, 96.0, -1.0))   // 1.5s，从下方落

        val line = JSONObject()
            .put("bpm", 120.0)
            .put("notesAbove", notes)
            .put("notesBelow", below)
            .put("speedEvents", JSONArray().put(JSONObject()
                .put("startTime", 0.0).put("endTime", 1000.0).put("value", 1.0).put("floorPosition", 0.0)))
            .put("judgeLineMoveEvents", JSONArray().put(pgrMove(0.0, 64.0, 0.0, 0.5, 0.0, 0.0)))
            .put("judgeLineRotateEvents", JSONArray().put(pgrEvent(0.0, 64.0, 0.0, 90.0)))
            .put("judgeLineDisappearEvents", JSONArray().put(pgrEvent(0.0, 64.0, 1.0, 0.0)))

        return JSONObject()
            .put("formatVersion", fmt)
            .put("offset", 0.0)
            .put("judgeLineList", JSONArray().put(line))
    }

    private fun parse(bytes: ByteArray): ImportedChart = PezImporter.import(
        openZip = { bytes.inputStream() },
        saveAsset = { _, _ -> true }
    )

    // ------------------------------------------------------------------ 基本解析

    @Test
    fun parsesNotesWithCorrectKindMapping() {
        val chart = parse(zipOf(listOf("chart.json" to sampleChart().toString().toByteArray())))
        assertEquals(5, chart.notes.size)
        val byTime = chart.notes.groupBy { it.startMs }
        // 官谱 type → FRPHI：1 Tap→1，2 Drag→4，3 Hold→2，4 Flick→3
        assertEquals(1, byTime.getValue(500L).single().type)
        assertEquals(4, byTime.getValue(750L).single().type)
        val hold = byTime.getValue(1000L).single()
        assertEquals(2, hold.type)
        assertEquals(1500L, hold.endMs)
        assertEquals(3, byTime.getValue(1250L).single().type)
        assertEquals(1500L, byTime.getValue(1500L).single().startMs)
    }

    @Test
    fun notesBelowGoToBackSide() {
        val chart = parse(zipOf(listOf("chart.json" to sampleChart().toString().toByteArray())))
        val back = chart.notes.filter { !it.above }
        assertEquals(1, back.size)
        assertEquals(1500L, back[0].startMs)
    }

    @Test
    fun positionXIsScaledByPhiraCoefficient() {
        val chart = parse(zipOf(listOf("chart.json" to sampleChart().toString().toByteArray())))
        val n = chart.notes.first { it.startMs == 500L }
        // 官谱 positionX=0 ⇒ 正中
        assertEquals(0f, n.posX, 1e-6f)
        // 官谱 positionX=0.5 ⇒ 0.5 × 0.1125 = 0.05625（归一化坐标，与 Phira 一致）
        val drag = chart.notes.first { it.startMs == 750L }
        assertEquals((0.5 * 9.0 / 80.0).toFloat(), drag.posX, 1e-5f)
        // 官谱 positionX 的满幅是 ±8.888…，×0.1125 正好到 ±1
        val flick = chart.notes.first { it.startMs == 1250L }
        assertEquals(0.1125f, flick.posX, 1e-5f)
    }

    @Test
    fun positionXIsNotCompressedIntoTheMiddle() {
        // 回归：曾经用 RPE 的 /675，positionX=±8 只落到 ±0.012，所有音符挤在判定线正中
        val line = JSONObject()
            .put("bpm", 120.0)
            .put("notesAbove", JSONArray()
                .put(pgrNote(1, 32.0, -8.0))
                .put(pgrNote(1, 32.0, 8.0)))
            .put("notesBelow", JSONArray())
        val doc = JSONObject().put("formatVersion", 3).put("offset", 0.0)
            .put("judgeLineList", JSONArray().put(line))
        val chart = parse(zipOf(listOf("chart.json" to doc.toString().toByteArray())))
        val xs = chart.notes.map { it.posX }.sorted()
        assertEquals(-0.9f, xs.first(), 1e-4f)
        assertEquals(0.9f, xs.last(), 1e-4f)
    }

    @Test
    fun offsetIsInSeconds() {
        val doc = sampleChart().put("offset", 0.25)   // 250ms
        val chart = parse(zipOf(listOf("chart.json" to doc.toString().toByteArray())))
        assertEquals(750L, chart.notes.first { it.startMs >= 500L }.startMs)
    }

    // ------------------------------------------------------------------ 判定线动画

    @Test
    fun moveAndRotateBecomeKeyframes() {
        val chart = parse(zipOf(listOf("chart.json" to sampleChart().toString().toByteArray())))
        val line = chart.lines.single()
        assertTrue("动作事件应该产生关键帧", line.keyframes.size >= 2)
        // moveX：JSON 值 0 → 0.5，按 Phira fv3 语义 v*2-1 ⇒ -1 → 0
        val first = line.keyframes.first()
        val last = line.keyframes.last()
        assertEquals(-1f, first.moveX, 1e-5f)
        assertEquals(0f, last.moveX, 1e-5f)
        // 旋转 0 → 90 度，官谱是逆时针正，本项目内部也是逆时针正 ⇒ 末尾 +90（不再取反）
        assertEquals(90f, last.rotate, 1e-4f)
        // 透明度 1 → 0（线性插值，和移动/旋转同一套规则）
        assertEquals(1f, first.alpha, 1e-5f)
        assertEquals(0f, last.alpha, 1e-5f)
    }

    @Test
    fun adjacentSegmentsDoNotDrawADiagonal() {
        // 回归：两段首尾相接（0→64 涨到 1，64→128 直接跳回 0.25）不能被插成一条斜线。
        // 判据：段边界 1000ms 的前一刻保持 1.0（段尾值），1000ms 起立刻是 0.25。
        val line = JSONObject()
            .put("bpm", 120.0)
            .put("notesAbove", JSONArray().put(pgrNote(1, 32.0, 0.0)))
            .put("notesBelow", JSONArray())
            .put("judgeLineMoveEvents", JSONArray()
                .put(pgrMove(0.0, 64.0, 0.0, 1.0, 0.0, 0.0))
                .put(pgrMove(64.0, 128.0, 0.25, 0.25, 0.0, 0.0)))
        val doc = JSONObject().put("formatVersion", 3).put("offset", 0.0)
            .put("judgeLineList", JSONArray().put(line))
        val chart = parse(zipOf(listOf("chart.json" to doc.toString().toByteArray())))
        val kfs = chart.lines.single().keyframes
        // 段边界前后：999 仍在第一段的斜线上（JSON 1.0 ⇒ 归一化 +1），1000 立刻跳到 JSON 0.25 ⇒ -0.5
        val before = kfs.last { it.timeMs < 1000L }.moveX
        val at = kfs.first { it.timeMs >= 1000L }.moveX
        assertTrue("边界前的值应贴近段尾值 +1，实际 $before", before > 0.99f)
        assertEquals(-0.5f, at, 1e-4f)
        // 不能再有"斜着滑"的中途帧：1999 应该还在 -0.5 附近，而不是 0 之类
        val mid = kfs.filter { it.timeMs > 1001L && it.timeMs < 2000L }
        assertTrue("段内不该再出现插值帧，实际 ${mid.map { it.moveX }}", mid.all { it.moveX in -0.51f..-0.49f })
    }

    @Test
    fun continuousSegmentsDoNotGetHoldFrames() {
        // 首尾相接且值连续（1.0 → 1.0）时不该补保持帧，否则每段都多 1 帧，66 条线就能堆出 10 万+ 关键帧
        val line = JSONObject()
            .put("bpm", 120.0)
            .put("notesAbove", JSONArray().put(pgrNote(1, 32.0, 0.0)))
            .put("notesBelow", JSONArray())
            .put("judgeLineMoveEvents", JSONArray()
                .put(pgrMove(0.0, 64.0, 0.0, 1.0, 0.0, 0.0))
                .put(pgrMove(64.0, 128.0, 1.0, 0.5, 0.0, 0.0)))
        val doc = JSONObject().put("formatVersion", 3).put("offset", 0.0)
            .put("judgeLineList", JSONArray().put(line))
        val chart = parse(zipOf(listOf("chart.json" to doc.toString().toByteArray())))
        // 3 个采样时刻（0 / 1000 / 2000）就够了，不该多出 999 那种保持帧
        assertEquals(3, chart.lines.single().keyframes.size)
    }

    @Test
    fun lineWithoutAlphaEventsIsOpaque() {
        // 官谱里没写 disappearEvents = 完全不透明；以前空轨道一律给 0 ⇒ 整条线隐形
        val line = JSONObject()
            .put("bpm", 120.0)
            .put("notesAbove", JSONArray().put(pgrNote(1, 32.0, 0.0)))
            .put("notesBelow", JSONArray())
        val doc = JSONObject().put("formatVersion", 3).put("offset", 0.0)
            .put("judgeLineList", JSONArray().put(line))
        val chart = parse(zipOf(listOf("chart.json" to doc.toString().toByteArray())))
        val l = chart.lines.single()
        assertTrue("静态线不该有关键帧", l.keyframes.isEmpty())
        assertEquals(1f, l.alpha, 1e-6f)
    }

    @Test
    fun staticLineProducesNoKeyframes() {
        val doc = sampleChart()
        val line = doc.getJSONArray("judgeLineList").getJSONObject(0)
        line.put("judgeLineMoveEvents", JSONArray())
        line.put("judgeLineRotateEvents", JSONArray())
        line.put("judgeLineDisappearEvents", JSONArray())
        val chart = parse(zipOf(listOf("chart.json" to doc.toString().toByteArray())))
        assertTrue("没有动作事件就不该有关键帧", chart.lines.single().keyframes.isEmpty())
    }

    // ------------------------------------------------------------------ formatVersion 1

    @Test
    fun formatVersion1PacksXYIntoOneInteger() {
        val line = JSONObject()
            .put("bpm", 120.0)
            .put("notesAbove", JSONArray().put(pgrNote(1, 32.0, 0.0)))
            .put("notesBelow", JSONArray())
            .put("speedEvents", JSONArray())
            // v1：start = x*1000 + y；Phira：x = v/440 − 1，y = r/260 − 1
            // x = 440 ⇒ 0，y = 260 ⇒ 0（正中）
            .put("judgeLineMoveEvents", JSONArray().put(
                JSONObject().put("startTime", 0.0).put("endTime", 64.0)
                    .put("start", 440260).put("end", 440260)
            ))
            .put("judgeLineRotateEvents", JSONArray())
            .put("judgeLineDisappearEvents", JSONArray())
        val doc = JSONObject().put("formatVersion", 1).put("offset", 0.0)
            .put("judgeLineList", JSONArray().put(line))
        val chart = parse(zipOf(listOf("chart.json" to doc.toString().toByteArray())))
        val l = chart.lines.single()
        // 官谱 fv1 的 y 是「向上为正」的绝对坐标：260 ⇒ 0（正中）
        assertEquals(0f, l.moveY, 1e-4f)
        assertEquals(50, l.posPct)
        // 左上角：x=0 ⇒ -1，y=0 ⇒ -1（屏幕底部）
        line.put("judgeLineMoveEvents", JSONArray().put(
            JSONObject().put("startTime", 0.0).put("endTime", 64.0)
                .put("start", 0).put("end", 0)
        ))
        val doc2 = JSONObject().put("formatVersion", 1).put("offset", 0.0)
            .put("judgeLineList", JSONArray().put(line))
        val chart2 = parse(zipOf(listOf("chart.json" to doc2.toString().toByteArray())))
        assertEquals(-1f, chart2.lines.single().moveX, 1e-4f)
        // y=-1 ⇒ 屏幕最底部（posPct 100）；关键帧才是相对偏移，t=0 应为 0（不能重复叠加）
        assertEquals(100, chart2.lines.single().posPct)
        assertEquals(0f, chart2.lines.single().moveY, 1e-4f)
    }

    // ------------------------------------------------------------------ 入口与容错

    @Test
    fun singleJsonFileCanBeImportedDirectly() {
        val chart = PezImporter.importText(sampleChart().toString(), "曲子")
        assertEquals(5, chart.notes.size)
    }

    @Test
    fun rpeChartStillDetectedAsRpeNotPgr() {
        // 有 META 就必须走 RPE 分支（不能被官谱分支抢走）
        val rpe = JSONObject()
            .put("META", JSONObject().put("name", "RPE 谱").put("offset", 0.0))
            .put("BPMList", JSONArray().put(JSONObject().put("bpm", 120.0)
                .put("startTime", JSONArray().put(0).put(0).put(1))))
            .put("judgeLineList", JSONArray().put(JSONObject()
                .put("Name", "line").put("Texture", "line.png")
                .put("eventLayers", JSONArray())
                .put("notes", JSONArray().put(JSONObject()
                    .put("type", 1)
                    .put("startTime", JSONArray().put(0).put(0).put(1))
                    .put("endTime", JSONArray().put(0).put(0).put(1))
                    .put("positionX", 0.0).put("above", 1)))))
        val chart = parse(zipOf(listOf("chart.json" to rpe.toString().toByteArray())))
        assertEquals("RPE 谱", chart.name)
        assertEquals(1, chart.notes.size)
    }

    @Test
    fun emptyJudgeLineListThrowsReadableError() {
        val doc = JSONObject().put("formatVersion", 3).put("offset", 0.0)
            .put("judgeLineList", JSONArray())
        val msg = runCatching {
            parse(zipOf(listOf("chart.json" to doc.toString().toByteArray())))
        }.exceptionOrNull()?.message ?: ""
        assertTrue("错误信息要能看懂：$msg", msg.contains("判定线"))
    }

    @Test
    fun chartWithoutNotesThrowsReadableError() {
        val doc = JSONObject().put("formatVersion", 3).put("offset", 0.0)
            .put("judgeLineList", JSONArray().put(JSONObject().put("bpm", 120.0)
                .put("notesAbove", JSONArray()).put("notesBelow", JSONArray())))
        val msg = runCatching {
            parse(zipOf(listOf("chart.json" to doc.toString().toByteArray())))
        }.exceptionOrNull()?.message ?: ""
        assertTrue("错误信息要能看懂：$msg", msg.contains("音符"))
    }

    @Test
    fun infiniteNoteSpeedIsClamped() {
        val line = JSONObject()
            .put("bpm", 120.0)
            .put("notesAbove", JSONArray().put(JSONObject()
                .put("type", 1).put("time", 32.0).put("positionX", 0.0)
                .put("holdTime", 0.0).put("speed", "Infinity")))
            .put("notesBelow", JSONArray())
        val doc = JSONObject().put("formatVersion", 3).put("offset", 0.0)
            .put("judgeLineList", JSONArray().put(line))
        val chart = parse(zipOf(listOf("chart.json" to doc.toString().toByteArray())))
        val n = chart.notes.single()
        assertTrue("Infinity 流速要夹成有限值，实际 ${n.speed}", n.speed.isFinite())
        assertTrue(n.speed > 0f)
    }

    @Test
    fun notesAreSortedByTime() {
        val line = JSONObject()
            .put("bpm", 120.0)
            .put("notesAbove", JSONArray()
                .put(pgrNote(1, 96.0, 0.0))
                .put(pgrNote(1, 32.0, 0.0))
                .put(pgrNote(1, 64.0, 0.0)))
            .put("notesBelow", JSONArray())
        val doc = JSONObject().put("formatVersion", 3).put("offset", 0.0)
            .put("judgeLineList", JSONArray().put(line))
        val chart = parse(zipOf(listOf("chart.json" to doc.toString().toByteArray())))
        assertEquals(listOf(500L, 1000L, 1500L), chart.notes.map { it.startMs })
    }

    // ------------------------------------------------------------------ 真实大谱回归（"ハテ" 那次的症状）

    /**
     * 复刻用户那份 66 条线 / 1397 个音符的官谱症状：
     *  - 关键帧不能爆炸（曾经 10 万+）
     *  - 长条不能退化成 "start ≈ end"（那是把 holdTime 当秒算的后果）
     *  - 音符 x 不能全挤在 0
     *  - 噪域要能读出来
     */
    @Test
    fun bigChartKeepsKeyframesSaneAndKeepsNoiseAreas() {
        val lines = JSONArray()
        // 60 条每帧都在动的线 + 6 条带音符的线
        for (i in 0 until 60) {
            val mv = JSONArray()
            for (k in 0 until 24) {
                val t0 = (k * 32).toDouble()
                mv.put(pgrMove(t0, t0 + 32, (k % 5) * 0.1, ((k + 1) % 5) * 0.1, 0.0, 0.0))
            }
            lines.put(JSONObject()
                .put("bpm", 120.0)
                .put("notesAbove", JSONArray())
                .put("notesBelow", JSONArray())
                .put("judgeLineMoveEvents", mv)
                .put("judgeLineRotateEvents", JSONArray())
                .put("judgeLineDisappearEvents", JSONArray()))
        }
        val notes = JSONArray()
        for (k in 0 until 200) notes.put(pgrNote(3, k * 8.0, ((k % 7) - 3) * 2.0, 16.0))
        lines.put(JSONObject()
            .put("bpm", 120.0)
            .put("notesAbove", notes)
            .put("notesBelow", JSONArray())
            .put("judgeLineMoveEvents", JSONArray())
            .put("judgeLineRotateEvents", JSONArray())
            .put("judgeLineDisappearEvents", JSONArray()))

        val blockArea = JSONObject()
            .put("Name", "噪域1")
            .put("bottomLeft", JSONObject().put("x", -250.0).put("y", -150.0))
            .put("topRight", JSONObject().put("x", 250.0).put("y", 150.0))
            .put("appearTime", JSONArray().put(0).put(0).put(1))
            .put("disappearTime", JSONArray().put(999).put(0).put(1))
            .put("activeIntervals", JSONArray().put(JSONObject()
                .put("startTime", JSONArray().put(8).put(0).put(1))
                .put("endTime", JSONArray().put(16).put(0).put(1))))
        val doc = JSONObject().put("formatVersion", 3).put("offset", 0.0)
            .put("judgeLineList", lines)
            .put("blockAreaList", JSONArray().put(blockArea))

        val chart = parse(zipOf(listOf("chart.json" to doc.toString().toByteArray())))
        // 音符总数
        assertEquals(200, chart.notes.size)
        // 长条必须真有长度：holdTime=16 ⇒ 16 × 15.625 = 250ms
        chart.notes.filter { it.type == 2 }.forEach {
            assertEquals("长条长度不能退化成 0", 250L, it.endMs - it.startMs)
        }
        // x 不能全挤在中间
        val spread = chart.notes.map { it.posX }.distinct().size
        assertTrue("音符 x 应该有分布，实际只有 $spread 种", spread >= 5)
        // 关键帧总量：61 条线不该超过 6 万（以前 104322）
        val totalKf = chart.lines.sumOf { it.keyframes.size }
        assertTrue("关键帧爆炸：$totalKf", totalKf < 60_000)
        // 噪域必须读到
        assertEquals(1, chart.noiseAreas.size)
        assertEquals("噪域1", chart.noiseAreas[0].name)
    }
    /**
     * ★ 回归：官谱 speedEvents 的字段是 `value`，不是 `start`/`end`。
     *
     * 以前借用通用事件解析器读 start/end，每段都退化成 0.0 ⇒ 全谱流速 0 ⇒
     * 所有音符的纵向距离恒为 0，屏幕上看起来就是「音符全挤在判定线上、识别混乱」。
     * 这里用 value = 2.5（不等于默认的 1）钉死：读成 0 会让断言直接炸。
     */
    @Test
    fun speedEventsUseValueField() {
        val line = JSONObject()
            .put("bpm", 120.0)
            .put("notesAbove", JSONArray().put(pgrNote(1, 320.0, 0.0)))   // 320 × 15.625ms = 5s
            .put("speedEvents", JSONArray()
                .put(JSONObject().put("startTime", 0.0).put("endTime", 200000.0).put("value", 2.5))
                .put(JSONObject().put("startTime", 200000.0).put("endTime", 10000000.0).put("value", 0.5)))
            .put("judgeLineMoveEvents", JSONArray().put(pgrMove(-999999.0, 10000000.0, 0.0, 0.0, 0.0, 0.0)))
            .put("judgeLineRotateEvents", JSONArray().put(pgrEvent(-999999.0, 10000000.0, 0.0, 0.0)))
            .put("judgeLineDisappearEvents", JSONArray().put(pgrEvent(-999999.0, 10000000.0, 1.0, 1.0)))
        val chart = JSONObject()
            .put("formatVersion", 3).put("offset", 0.0)
            .put("judgeLineList", JSONArray().put(line))

        val c = parse(zipOf(listOf("chart.json" to chart.toString().toByteArray())))
        assertEquals(1, c.notes.size)
        val ln = c.lines[0]
        val tr = SpeedTrack.build(ln.keyframes, ln.speed)
        // 前段 value = 2.5（官谱单位）⇒ 本项目 2.5 × 4.5 = 11.25；时间单位 15.625ms ⇒ 200000 单位 ≈ 3125 秒
        assertTrue("流速被读成 0 了（speedEvents 字段名不对）", tr.speedAt(1000L) > 11.2f)
        assertTrue("官谱流速没有按 4.5 倍换算：${tr.speedAt(1000L)}", tr.speedAt(1000L) < 11.3f)
        // 累積高度必须真的在增长，否则音符会全堆在判定线上
        assertTrue("H 不增长 ⇒ 音符全挤在判定线上", tr.heightAt(5000L) > tr.heightAt(1000L) + 1000.0)
    }

    /**
     * ★ 回归：官谱判定线的 y 是「绝对位置、向上为正」，本项目的 moveY 是
     * 「相对 posPct、向下为正」。以前两者混用 ⇒ 所有线在 t=0 都被吸到屏幕正中。
     */
    @Test
    fun lineYIsRelativeToItsPosPct() {
        val line = JSONObject()
            .put("bpm", 120.0)
            .put("notesAbove", JSONArray().put(pgrNote(1, 32.0, 0.0)))
            .put("notesBelow", JSONArray())
            // y：顶部（1.0）保持不动；x 也从 0.5 保持
            .put("judgeLineMoveEvents", JSONArray().put(pgrMove(0.0, 10000000.0, 0.5, 0.5, 1.0, 1.0)))
            .put("judgeLineRotateEvents", JSONArray().put(pgrEvent(0.0, 10000000.0, 0.0, 0.0)))
            .put("judgeLineDisappearEvents", JSONArray().put(pgrEvent(0.0, 10000000.0, 1.0, 1.0)))
        val doc = JSONObject().put("formatVersion", 3).put("offset", 0.0)
            .put("judgeLineList", JSONArray().put(line))
        val chart = parse(zipOf(listOf("chart.json" to doc.toString().toByteArray())))
        val l = chart.lines.single()
        // y=1 ⇒ 屏幕顶部
        assertEquals(0, l.posPct)
        assertEquals(0f, l.moveY, 1e-5f)
        assertEquals(0f, l.moveX, 1e-5f)

        // 动态：y 从 1.0（顶部）走到 0.5（中间）⇒ 位移残量 = +1（本项目向下为正）
        line.put("judgeLineMoveEvents", JSONArray().put(pgrMove(0.0, 64.0, 0.5, 0.5, 1.0, 0.5)))
        val doc2 = JSONObject().put("formatVersion", 3).put("offset", 0.0)
            .put("judgeLineList", JSONArray().put(line))
        val chart2 = parse(zipOf(listOf("chart.json" to doc2.toString().toByteArray())))
        val l2 = chart2.lines.single()
        assertEquals(0, l2.posPct)
        // 1000ms：y=0.5 ⇒ y0(上为正)=+1，此刻=0 ⇒ 向下残量 +1
        assertEquals(1f, l2.keyframes.last().moveY, 1e-4f)
    }
}
