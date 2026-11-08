package com.piezmaker

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义人设：长按接口列表里的一项可以改，改完要拼进系统提示词，
 * 而且主界面和编辑页必须同时生效（同一个 AI）。
 */
class AiPersonaTest {

    private val cat = "你是一只慵懒的猫，说话简短，句尾带「喵」。"

    @Test
    fun `人设能存进JSON再读回来`() {
        val ep = AiEndpoint("自定义1", "https://x/v1/chat/completions", "k", "m", false, cat)
        val back = AiEndpoint.fromJson(JSONObject(ep.toJson().toString()))
        assertEquals(cat, back.persona)
        // 老数据（没有 persona 字段）读出来是空，不崩
        val old = AiEndpoint.fromJson(JSONObject("""{"name":"旧","url":"","key":"","model":""}"""))
        assertEquals("", old.persona)
    }

    @Test
    fun `自定义人设会替换默认人设`() {
        val main = AiPrompts.systemAgentChat(cat)
        val editor = AiPrompts.systemEditorChat(true, cat)
        for (p in listOf(main, editor)) {
            assertTrue("要带上用户写的人设", p.contains(cat))
            assertTrue("要标明是自定义", p.contains("用户自定义"))
            assertFalse("不该再叠一份默认人设", p.contains("你首先是 FRPHI 里的 AI 助手，其次才是"))
        }
    }

    @Test
    fun `留空就用默认人设`() {
        for (p in listOf(
            AiPrompts.systemAgentChat(""),
            AiPrompts.systemAgentChat(null),
            AiPrompts.systemEditorChat(false, "   ")
        )) {
            assertTrue(p.contains("你首先是 FRPHI 里的 AI 助手"))
        }
    }
}
