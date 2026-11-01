package com.piezmaker

/**
 * 判断"这句话到底是不是在说谱面"（纯函数，可在 JVM 上单测）。
 *
 * 只用来决定**提示词怎么组装**（要不要带谱面上下文字、要不要带 JSON 协议、没音乐时要不要拦），
 * **不用来执行 agent 操作** —— 要不要动手始终由 AI 自己决定。
 *
 * 编辑页的 AI 是通用助手：打招呼、聊新闻、写小说、扒网页都不该触发任何谱面流程。
 * 所以这里的判定必须收得很紧（"靠谱""离谱""写一首诗"都不能误判）。
 */
object AiIntent {

    /** 谱面相关的词；"谱"排除 靠谱/离谱/摆谱 这类日常词 */
    private val CHART = Regex("(?<![靠离摆])谱|音符|判定线|关键帧|蓝键|黄键|红键|长条|键型|双押|特效|(?i)bpm")

    /** 只会在改谱时说的手感反馈（"这里太密了""空一点"）——在编辑页里默认就是在说谱面 */
    private val CHART_HINT = Regex("太密|密了|太疏|疏了|太空|空一点|密一点|疏一点|塞满|铺满")

    /** 明确要求"写整首"的说法 */
    private val WRITE_HARD = Regex("写谱|编谱|做谱|打谱|制谱|生成谱|谱面")

    /** 动词 / 修改类动词（用来区分"写"和"改"） */
    private val VERB = Regex("写|做|来|生成|编")
    private val MODIFY = Regex("改|修|删|挪|移|调整|去掉|太多|太少|再加")

    /** 提到谱面（含 BPM） */
    fun mentionsChart(text: String): Boolean =
        CHART.containsMatchIn(text) || CHART_HINT.containsMatchIn(text)

    /** 明确要写整首谱（写一份 12 级谱 / 写谱 / 谱面重做…） */
    fun isWritingRequest(text: String): Boolean {
        if (WRITE_HARD.containsMatchIn(text)) return true
        return VERB.containsMatchIn(text) && mentionsChart(text) && !MODIFY.containsMatchIn(text)
    }

    /** 这一轮需不需要谱面上下文与 JSON 协议 */
    fun chartIntent(text: String): Boolean = mentionsChart(text) || isWritingRequest(text)

    /** 问 BPM（没接 AI 时用它触发本地检测） */
    fun bpmKeyword(text: String): Boolean =
        Regex("(?i)(bpm|beats\\s*per|节拍|曲速|速度多少)").containsMatchIn(text)
}
