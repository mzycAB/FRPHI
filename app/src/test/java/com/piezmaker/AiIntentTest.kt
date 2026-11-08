package com.piezmaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑页 AI 是"通用助手"，不是"专家系统"：随便聊天、问新闻、写小说都不该被当成改谱请求，
 * 否则一开口就会去分析音频、还只肯聊谱面。
 */
class AiIntentTest {

    @Test
    fun `闲聊不会被当成写谱`() {
        for (s in listOf(
            "你好", "在吗", "今天有什么新闻", "讲个笑话",
            "帮我写一首诗", "写个小说开头", "帮我扒一下这个网页 https://example.com",
            "这价格靠谱吗", "太离谱了", "你叫什么名字"
        )) {
            assertFalse("「$s」不该算写谱", AiIntent.isWritingRequest(s))
            assertFalse("「$s」不该带谱面上下文", AiIntent.chartIntent(s))
        }
    }

    @Test
    fun `明确写谱才算写谱`() {
        for (s in listOf(
            "写一份 12 级谱，蓝键多点", "帮我写谱", "谱面从头重做", "生成谱", "编谱"
        )) {
            assertTrue("「$s」应该算写谱", AiIntent.isWritingRequest(s))
            assertTrue(AiIntent.chartIntent(s))
        }
    }

    @Test
    fun `改谱算谱面意图但不算写整首`() {
        for (s in listOf("蓝键多点", "这里太密了", "删掉重复的音符", "把判定线关键帧第 2 秒改一下")) {
            assertFalse("「$s」不是整首重写", AiIntent.isWritingRequest(s))
            assertTrue("「$s」应该带谱面上下文", AiIntent.chartIntent(s))
        }
    }

    @Test
    fun `BPM 关键词`() {
        assertTrue(AiIntent.bpmKeyword("这首歌 bpm 多少"))
        assertTrue(AiIntent.bpmKeyword("BPM 是多少"))
        assertFalse(AiIntent.bpmKeyword("今天天气怎么样"))
    }
}
