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

    /** 用户点名的两个面板：音符列表（单音符属性）与判定线缩放，知识库必须写清且能检索到 */
    @Test
    fun `知识库覆盖音符列表与判定线缩放`() {
        val kb = AiPrompts.knowledgeText()
        for (w in listOf(
            "音符列表", "单音符属性面板", "judgeArea", "tintHitEffects", "hitsound",
            "缩放", "scaleX", "scaleY", "绿色箭头", "双押贴图"
        )) {
            assertTrue("知识库缺少「$w」", kb.contains(w))
        }
        val hit = AiKnowledge.search("音符列表怎么改单个音符的判定范围")
        assertNotNull("检索不到音符列表相关原文", hit)
        assertTrue(hit!!.contains("音符列表"))
        val scale = AiKnowledge.search("判定线的缩放怎么调")
        assertNotNull("检索不到判定线缩放相关原文", scale)
        assertTrue(scale!!.contains("缩放"))
    }

    /** 视频背景页签和联机玩家聊天也要进知识库，别让 AI 回答"没这个功能" */
    @Test
    fun `知识库覆盖视频背景与玩家聊天`() {
        val kb = AiPrompts.knowledgeText()
        for (w in listOf(
            "视频背景", "视频」页签", "开始（秒）", "裁剪", "完整", "拉伸", "昏暗",
            "extra.json", "玩家聊天", "没有「撤回」按钮", "不影响其他玩家", "60 条", "458dp"
        )) {
            assertTrue("知识库缺少「$w」", kb.contains(w))
        }
        val chat = AiPrompts.systemAgentChat()
        assertTrue("精简说明也要有视频背景", chat.contains("视频背景"))
        assertTrue("精简说明也要有玩家聊天", chat.contains("玩家聊天"))
        val video = AiKnowledge.search("视频背景怎么加")
        assertNotNull("检索不到视频背景相关原文", video)
        assertTrue(video!!.contains("视频"))
        val playerChat = AiKnowledge.search("联机的玩家聊天在哪打开")
        assertNotNull("检索不到玩家聊天相关原文", playerChat)
        assertTrue(playerChat!!.contains("玩家聊天"))
    }

    /** 噪域已经可编辑（拖角/关键帧/显示/深浅/新建/复制/删除/图层），知识库不能再说它只读 */
    @Test
    fun `知识库覆盖噪域编辑与撤销`() {
        val kb = AiPrompts.knowledgeText()
        assertTrue(kb.contains("噪域"))
        assertTrue(kb.contains("长按页脚「＋新建噪域」"))
        assertTrue(kb.contains("所有噪域改动都能撤销/重做"))
        assertTrue("噪域要支持图层", kb.contains("噪域都支持**图层**"))
        assertFalse("知识库不能再把噪域写成只读", kb.contains("噪域数据只读") || kb.contains("噪域（只读）"))
        val hit = AiKnowledge.search("噪域怎么编辑，能撤销吗")
        assertNotNull(hit)
        assertTrue(hit!!.contains("噪域"))
    }

    /**
     * 「玩家能做、AI 也能做」要对齐：AI 协议里有的动作，提示词/知识库必须写明；
     * 少写一条，AI 就会回答"我改不了噪域/图片线"（这是用户点名过的现象）。
     */
    @Test
    fun `知识库与新协议覆盖全部编辑动作`() {
        val kb = AiPrompts.knowledgeText()
        for (w in listOf(
            "噪域", "图层", "图片线", "文字线", "撤销", "重做",
            "混合", "判定线", "关键帧", "噪域都支持**图层**"
        )) {
            assertTrue("知识库缺少「$w」", kb.contains(w))
        }
        // AI 能用的谱面协议（编辑页）
        val proto = AiPrompts.systemEditorChat(true)
        for (w in listOf("\"na\"", "\"area\"", "\"tex\"", "\"text\"", "\"layer\"",
            "\"kf\"", "\"kfdel\"", "\"fix\"", "\"del\"", "\"fake\"", "\"rev\"", "\"redo\"",
            "\"speed\"", "\"yOffset\"", "\"visibleTime\"")) {
            assertTrue("编辑页协议缺少「$w」", proto.contains(w))
        }
        // 闲聊轮不该带谱面输出协议（省 token）；但能力清单（EDITOR_DOC）照样带着，能回答"能不能改噪域"
        val chat = AiPrompts.systemEditorChat(false)
        assertFalse(chat.contains("只输出 JSON Lines"))
        assertTrue("闲聊轮也要知道能改噪域", chat.contains("噪域"))
    }

    /**
     * 用户点名：AI 抽屉的「撤回」按钮**和编辑页那个撤销按钮是同一个功能**（同一个撤销栈），
     * 长按 = 重做。知识库不能再说成"AI 撤回只保留 5 步"那种独立的一套。
     */
    @Test
    fun `知识库说明抽屉撤回就是编辑页撤销`() {
        val kb = AiPrompts.knowledgeText()
        assertTrue("要说明抽屉撤回 = 编辑页撤销按钮", kb.contains("就是编辑页那个撤销按钮"))
        assertTrue("要说长按撤回 = 重做", kb.contains("长按「撤回」= 重做"))
        assertFalse("旧的「AI 撤回只保留 5 步」已废弃", kb.contains("保留 5 步"))
        val chat = AiPrompts.systemEditorChat(true)
        assertTrue("编辑页协议里也要写", chat.contains("长按「撤回」"))
    }

    /** 精简版说明书要显著短于完整知识库，否则每轮都在白烧 token */
    @Test
    fun `聊天提示词用的是精简版说明书`() {
        val chat = AiPrompts.systemAgentChat()
        val kb = AiPrompts.knowledgeText()
        assertTrue("聊天提示词应远小于知识库（实际 ${chat.length} vs ${kb.length}）", chat.length * 2 < kb.length)
    }

    /** 编辑页的谱面上下文必须真的把判定线 / BPM / 音符给出去，AI 才不会回头问用户 */
    @Test
    fun `编辑页谱面上下文带判定线与音符`() {        val lines = listOf(Line(0).apply { posPct = 60 }, Line(3).apply { posPct = 40 })
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
    /**
     * 用户点名「为什么 ai 不能导出谱面」：以前编辑页 AI 的能力清单里根本没有导出，
     * 它只能回一句"我做不到"。现在 `{"cmd":"export"}` 必须出现在（带/不带谱面协议的）提示词里，
     * 知识库也要写清楚落到哪儿。
     */
    @Test
    fun `编辑页 AI 能导出谱面`() {
        val kb = AiPrompts.knowledgeText()
        assertTrue("知识库要写清 AI 能导出谱面", kb.contains("能导出谱面"))
        assertTrue("要写清落盘目录", kb.contains("下载/FRPHI"))
        for (sys in listOf(AiPrompts.systemEditorChat(true), AiPrompts.systemEditorChat(false))) {
            assertTrue("提示词缺少导出命令", sys.contains("{\"cmd\":\"export\"}"))
            assertTrue("要明确不许说做不到", sys.contains("我不能导出"))
        }
        val hit = AiKnowledge.search("让ai导出谱面")
        assertNotNull("检索不到导出谱面相关原文", hit)
    }
}
