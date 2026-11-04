package com.piezmaker

/**
 * 内置节奏型模板库。
 *
 * 一首谱并不是每个音符都独立摆位置，而是由「节奏型」成组拼起来的：
 * 纵连、交互、楼梯、散点、扫键、长条段、红键段……本文件把这些型写成数据，
 * 生成/后处理时按难度挑一组模板，逐个音符套进去（时间保持音频重音点不变）。
 *
 * 模板里的 [Step] 只描述「位置 + 键型（+ 长条时长）」，一个模板约 8 个音符，
 * 循环使用时会错开起点，避免整段完全一样。
 */
object PatternLibrary {

    /** 一个音符在模板里的写法 */
    data class Step(val x: Float, val type: Int = 1, val holdMs: Int = 0)

    class Template(val name: String, val steps: List<Step>)

    // ---------------------------------------------------------------- 模板定义
    private val jack = Template("纵连", List(8) { Step(0.5f) })

    private val interaction = Template(
        "交互",
        listOf(
            Step(-0.6f), Step(0.6f), Step(-0.6f), Step(0.6f),
            Step(-0.6f), Step(0.6f), Step(-0.6f), Step(0.6f)
        )
    )

    private val stairUp = Template(
        "楼梯上行",
        listOf(
            Step(-0.75f), Step(-0.5f), Step(-0.25f), Step(0f),
            Step(0.25f), Step(0.5f), Step(0.75f), Step(0.25f)
        )
    )

    private val stairDown = Template(
        "楼梯下行",
        listOf(
            Step(0.75f), Step(0.5f), Step(0.25f), Step(0f),
            Step(-0.25f), Step(-0.5f), Step(-0.75f), Step(-0.25f)
        )
    )

    private val scatter = Template(
        "散点",
        listOf(
            Step(0.75f), Step(-0.25f), Step(0.25f), Step(-0.75f),
            Step(0f), Step(0.5f), Step(-0.5f), Step(0.25f)
        )
    )

    private val dragRun = Template(
        "扫键",
        listOf(
            Step(-0.4f, 4), Step(-0.2f, 4), Step(0f, 4), Step(0.2f, 4),
            Step(0.4f, 4), Step(0.2f, 4), Step(0f, 4), Step(-0.2f, 4)
        )
    )

    private val flickRun = Template(
        "红键段",
        listOf(
            Step(0.6f, 3), Step(-0.6f, 3), Step(0.3f, 1), Step(-0.3f, 3),
            Step(0.6f, 3), Step(-0.3f, 1), Step(0.3f, 3), Step(-0.6f, 1)
        )
    )

    private val holdRun = Template(
        "长条段",
        listOf(
            Step(-0.5f, 2, 380), Step(-0.5f, 1), Step(0.5f, 2, 380), Step(0.5f, 1),
            Step(-0.25f, 2, 300), Step(-0.25f, 1), Step(0.25f, 1), Step(0.25f, 1)
        )
    )

    private val jumpMix = Template(
        "跳跃",
        listOf(
            Step(0.75f), Step(-0.75f), Step(0.5f), Step(-0.5f),
            Step(0f), Step(0.6f), Step(-0.6f), Step(0.25f)
        )
    )

    /**
     * 按难度给一段模板序列：难度低多纵连/长条，难度高多楼梯/散点/红键。
     * 序列会循环使用，避免整首只有一种型。
     */
    fun sequenceFor(difficulty: Double): List<Template> = when {
        difficulty <= 6.0 -> listOf(jack, holdRun, interaction, stairUp, dragRun, scatter)
        difficulty <= 12.0 -> listOf(jack, interaction, stairUp, scatter, dragRun, stairDown, flickRun)
        else -> listOf(stairUp, scatter, interaction, flickRun, stairDown, jumpMix, jack)
    }

    /** 供提示词展示的抽象示例（只有相对位置和键型，不含任何原作音符） */
    val abstractExamples: String
        get() = """
纵连：[0.5, 0.5, 0.5, 0.5]
交互：[-0.6, 0.6, -0.6, 0.6, -0.6, 0.6]
楼梯：[-0.75, -0.5, -0.25, 0, 0.25, 0.5]
散点：[0.75, -0.25, 0.25, -0.75, 0, 0.5]
扫键（黄）：[-0.4, -0.2, 0, 0.2, 0.4]（连续 Drag）
红键段：[0.6, -0.6, 0.3, -0.3]（交替 Flick / Tap）
""".trimIndent()

    /**
     * 把一整份音符按模板重排（时间不变，只改位置/键型）。
     * 每 8 个音符换一个模板，并在模板内错开起点，听起来不会重复。
     */
    fun rewrite(notes: List<RecordedNote>, difficulty: Double, durationMs: Long): List<RecordedNote> {
        if (notes.isEmpty()) return notes
        val seq = sequenceFor(difficulty.coerceIn(0.0, 20.0))
        val out = ArrayList<RecordedNote>(notes.size)
        var i = 0
        var chunk = 0
        while (i < notes.size) {
            val tpl = seq[chunk % seq.size]
            val shift = (chunk * 3) % tpl.steps.size
            var k = 0
            while (i < notes.size && k < tpl.steps.size) {
                val n = notes[i]
                val s = tpl.steps[(k + shift) % tpl.steps.size]
                out.add(applyStep(n, s, durationMs))
                i++; k++
            }
            chunk++
        }
        return out
    }

    /** 用一个模板步套到一个音符上（时间保留，键型/位置/长条时长替换） */
    fun applyStep(n: RecordedNote, s: Step, durationMs: Long): RecordedNote {
        val type = s.type
        val end = when {
            type == 2 && s.holdMs > 0 -> (n.startMs + s.holdMs).coerceAtMost(durationMs)
            type == 2 -> (n.startMs + 300).coerceAtMost(durationMs)
            else -> n.startMs
        }
        return n.copy(
            type = type,
            pass = when (type) {
                1 -> 1
                4 -> 2
                3 -> 3
                else -> 4
            },
            posX = s.x.coerceIn(-1f, 1f),
            endMs = end,
            above = true,
            fake = false
        )
    }
}
