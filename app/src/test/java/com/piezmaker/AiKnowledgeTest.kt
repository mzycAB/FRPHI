package com.piezmaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置知识库（没接 AI 时按关键词检索的那份）必须和实际功能同步：
 * 加了新按钮/新入口就要写进 [AiPrompts.FEATURE_DOC]，否则用户问「在哪检测 BPM」
 * 会被告知"没这个功能"。
 */
class AiKnowledgeTest {

    @Test
    fun `知识库覆盖 BPM 检测`() {
        val kb = AiPrompts.knowledgeText()
        assertTrue(kb.contains("检测 BPM"))
        assertTrue("要写清位置", kb.contains("判定线位置"))
        assertTrue("要写清本地、不联网", kb.contains("不联网"))
        val hit = AiKnowledge.search("怎么检测这首歌的 bpm")
        assertNotNull("检索不到 BPM 相关原文", hit)
        assertTrue(hit!!.contains("BPM"))
    }

    @Test
    fun `知识库覆盖编辑页 AI 抽屉`() {
        val kb = AiPrompts.knowledgeText()
        assertTrue(kb.contains("458dp"))
        assertTrue(kb.contains("任意方向滑"))
        val hit = AiKnowledge.search("编辑页的 AI 按钮在哪，怎么打开")
        assertNotNull(hit)
        assertTrue(hit!!.contains("AI"))
    }

    @Test
    fun `本地知识库也认识蓝键多点这类要求`() {
        val hit = AiKnowledge.search("蓝键多点是什么意思")
        assertNotNull(hit)
        assertTrue(hit!!.contains("蓝键"))
    }

    /** 项目信息 11 个字段 + 单个音符 / 判定线关键帧，都是要写进知识库的能力 */
    @Test
    fun `知识库覆盖项目信息全部字段`() {
        val kb = AiPrompts.knowledgeText()
        for (w in listOf("曲名", "曲师", "谱师", "难度", "BPM 偏移", "判定线位置", "流速", "延迟", "横线数", "竖线数")) {
            assertTrue("知识库缺少「$w」", kb.contains(w))
        }
        val hit = AiKnowledge.search("能不能帮我改曲师和谱师")
        assertNotNull(hit)
        assertTrue(hit!!.contains("曲师"))
    }

    @Test
    fun `知识库覆盖单个音符与判定线关键帧`() {
        val kb = AiPrompts.knowledgeText()
        assertTrue(kb.contains("单个音符"))
        assertTrue(kb.contains("关键帧"))
        assertTrue("要说明能删帧", kb.contains("kfdel"))
        val hit = AiKnowledge.search("怎么删掉一个判定线关键帧")
        assertNotNull(hit)
        assertTrue(hit!!.contains("关键帧"))
    }

    /** 编辑页 AI 不能一开口就带"只输出 JSON"，那是专家系统；闲聊时不该带谱面协议 */
    @Test
    fun `编辑页提示词按意图附带谱面协议`() {
        val chat = AiPrompts.systemEditorChat(false)
        assertTrue(chat.contains("闲聊"))
        assertTrue(chat.contains("写小说"))
        assertFalse("闲聊不该带 JSON 协议", chat.contains("只输出 JSON Lines"))
        val chart = AiPrompts.systemEditorChat(true)
        assertTrue("动谱面时要带 JSON 协议", chart.contains("只输出 JSON Lines"))
        assertTrue(chart.contains("蓝键多点"))
    }

    /** 主界面和编辑页必须共用同一套人设 / 联网说明，别这边有那边没有 */
    @Test
    fun `两个 AI 共用同一套人设与联网说明`() {
        val main = AiPrompts.systemAgentChat()
        val editor = AiPrompts.systemEditorChat(true)
        for (mark in listOf("【助手设定】", "【联网工具（随时可用）】", "无法联网", "fetch")) {
            assertTrue("主界面缺少「$mark」", main.contains(mark))
            assertTrue("编辑页缺少「$mark」", editor.contains(mark))
        }
    }

    /** 编辑页的谱面上下文必须真的把判定线 / BPM / 音符给出去，AI 才不会回头问用户 */
    @Test
    fun `编辑页谱面上下文带判定线与音符`() {
        val lines = listOf(Line(0).apply { posPct = 60 }, Line(3).apply { posPct = 40 })
        val notes = listOf(RecordedNote(1, 1000, 1000, 0.25f, 1, 0, true, false))
        val ctx = AiPrompts.editorContext(
            writing = false, durationMs = 60_000, bpm = 128.0, bpmOffsetMs = 0.0, level = "12",
            lines = lines, notes = notes, beats = emptyList(),
            userText = "你做了吗", style = ChartStyle.parse("")
        )
        assertTrue(ctx.contains("BPM 128"))
        assertTrue(ctx.contains("id=0"))
        assertTrue(ctx.contains("id=3"))
        assertTrue(ctx.contains("音符共 1 个"))
        assertTrue("要告诉 AI 这些是真实数据", ctx.contains("真实的"))
    }
}
