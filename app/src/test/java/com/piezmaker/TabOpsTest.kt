package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Test

/** 标签删除后的选中下标：覆盖"删当前 / 删前面 / 删后面"三种情况 */
class TabOpsTest {

    @Test
    fun `删除当前标签_选中顶上来的那个`() {
        // [A,B,C] 当前在 B(1)，删 B → 剩下 [A,C]，应选中 C（下标 1）
        assertEquals(1, TabOps.afterDelete(3, 1, 1))
    }

    @Test
    fun `删除最后一个且它就是当前_往前挪一个`() {
        assertEquals(1, TabOps.afterDelete(3, 2, 2))
        assertEquals(0, TabOps.afterDelete(2, 1, 1))
    }

    @Test
    fun `删除当前之前的标签_当前下标前移`() {
        // [A,B,C] 当前 C(2)，删 A(0) → [B,C]，C 变成下标 1
        assertEquals(1, TabOps.afterDelete(3, 0, 2))
        // 当前 B(1)，删 A(0) → [B,C]，B 变成下标 0
        assertEquals(0, TabOps.afterDelete(3, 0, 1))
        // 当前 C(2)，删 B(1) → [A,C]，C 变成下标 1
        assertEquals(1, TabOps.afterDelete(3, 1, 2))
    }

    @Test
    fun `删除当前之后的标签_当前不动`() {
        assertEquals(0, TabOps.afterDelete(3, 2, 0))
        assertEquals(0, TabOps.afterDelete(3, 1, 0))
        assertEquals(1, TabOps.afterDelete(4, 3, 1))
    }

    @Test
    fun `穷举_结果永远落在合法范围内`() {
        for (size in 2..6) {
            for (index in 0 until size) {
                for (current in 0 until size) {
                    val next = TabOps.afterDelete(size, index, current)
                    assertTrueMsg(next in 0 until (size - 1), "size=$size index=$index current=$current -> $next")
                    // 除了被删的那个，其余标签的相对顺序不变：新下标指向的应该是原来相邻的标签
                    if (index != current) {
                        val expect = if (index < current) current - 1 else current
                        assertEquals("size=$size index=$index current=$current", expect, next)
                    }
                }
            }
        }
    }

    private fun assertTrueMsg(cond: Boolean, msg: String) {
        if (!cond) throw AssertionError(msg)
    }

    @Test
    fun `标签id白名单_防止误删别的标签的素材`() {
        // 正常 UUID 放行
        assertTrue(TabOps.isSafeId("3f2b8c1e-9d4a-4b6e-8c2f-1a2b3c4d5e6f"))
        // 空 id 最危险：File(filesDir, "imported/") 就是整个素材目录
        assertFalse(TabOps.isSafeId(""))
        assertFalse(TabOps.isSafeId("   "))
        // 路径穿越
        assertFalse(TabOps.isSafeId("../drafts"))
        assertFalse(TabOps.isSafeId("a/b"))
        assertFalse(TabOps.isSafeId(".."))
        // 太短
        assertFalse(TabOps.isSafeId("abc"))
    }

    private fun assertFalse(cond: Boolean) = assertTrueMsg(!cond, "应该被判为不安全")

    private fun assertTrue(cond: Boolean) = assertTrueMsg(cond, "应该被判为安全")
}
