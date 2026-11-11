package com.piezmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 图片线贴图路径解析回归（Reasons 那次的症状：FRPHI 导出的 pez 导回 FRPHI 只显示黄框）。
 *
 * 根因：草稿只存贴图**文件名**（texturePath 是本机绝对路径，不进草稿），
 * 载入草稿后没有任何地方按文件名重新找图 → lineBitmap 返回 null → 画占位框。
 * 修复：applyDraft 后按解析目录（lineimg + 项目导入目录）重建 texturePath。
 */
class LineTextureResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun imageLine(texture: String, path: String = ""): Line = Line(0).apply {
        this.texture = texture
        this.texturePath = path
    }

    @Test
    fun `按导入目录重建图片线路径`() {
        val dir = tmp.newFolder("imported")
        File(dir, "line_1793951725111.png").writeBytes(byteArrayOf(1, 2, 3))
        val l = imageLine("line_1793951725111.png")
        LineTextureResolver.resolve(listOf(l), listOf(dir))
        assertEquals(File(dir, "line_1793951725111.png").absolutePath, l.texturePath)
    }

    @Test
    fun `本机lineimg里的图也能找到`() {
        val lineimg = tmp.newFolder("lineimg")
        File(lineimg, "abc.png").writeBytes(byteArrayOf(9))
        val l = imageLine("abc.png")
        LineTextureResolver.resolve(listOf(l), listOf(lineimg))
        assertEquals(File(lineimg, "abc.png").absolutePath, l.texturePath)
    }

    @Test
    fun `已有的有效路径不动`() {
        val keep = tmp.newFolder("keep")
        val f = File(keep, "a.png"); f.writeBytes(byteArrayOf(1))
        val l = imageLine("a.png", f.absolutePath)
        val other = tmp.newFolder("other")
        LineTextureResolver.resolve(listOf(l), listOf(other))
        assertEquals(f.absolutePath, l.texturePath)
    }

    @Test
    fun `失效的旧路径会被重建`() {
        // 之前的机器路径（文件已不在）：应该被解析目录里的同名图替换
        val l = imageLine("a.png", "/data/old/project/a.png")
        val dir = tmp.newFolder("imported")
        File(dir, "a.png").writeBytes(byteArrayOf(1))
        LineTextureResolver.resolve(listOf(l), listOf(dir))
        assertEquals(File(dir, "a.png").absolutePath, l.texturePath)
    }

    @Test
    fun `找不到图时保持空路径_画占位框`() {
        val l = imageLine("missing.png")
        val dir = tmp.newFolder("imported")
        LineTextureResolver.resolve(listOf(l), listOf(dir))
        assertTrue(l.texturePath.isEmpty())
    }

    @Test
    fun `普通线和文字线不参与解析`() {
        val dir = tmp.newFolder("imported")
        val normal = Line(0).apply { texture = "" }               // line.png 是内建，texture 为空
        val text = Line(1).apply { texture = ""; lineText = "字" }
        LineTextureResolver.resolve(listOf(normal, text), listOf(dir))
        assertTrue(normal.texturePath.isEmpty())
        assertTrue(text.texturePath.isEmpty())
    }

    @Test
    fun `空文件不算找到`() {
        val dir = tmp.newFolder("imported")
        File(dir, "zero.png").createNewFile()                     // 0 字节
        val l = imageLine("zero.png")
        LineTextureResolver.resolve(listOf(l), listOf(dir))
        // 0 字节文件被跳过（lineBitmap 对 length<=0 一律返回 null）
        assertTrue(l.texturePath.isEmpty())
    }

    @Test
    fun `同名时项目导入目录压过全局lineimg`() {
        // 用户点名的场景：导入谱面的图必须用铺面 zip 解出来的那份（项目导入目录），
        // 绝不能被全局共享的 lineimg 里的同名图抢先（内容可能完全不同，导出也会打错图）
        val imported = tmp.newFolder("imported")
        val lineimg = tmp.newFolder("lineimg")
        File(imported, "deco.png").writeBytes(byteArrayOf(1))
        File(lineimg, "deco.png").writeBytes(byteArrayOf(2, 2))
        val l = imageLine("deco.png")
        // 顺序 = 优先级：导入目录在前
        LineTextureResolver.resolve(listOf(l), listOf(imported, lineimg))
        assertEquals(File(imported, "deco.png").absolutePath, l.texturePath)
    }

    @Test
    fun `导入目录没有时才落到lineimg`() {
        // 本机新建的图片线（line_时间戳.png）只存在于全局 lineimg
        val imported = tmp.newFolder("imported")
        val lineimg = tmp.newFolder("lineimg")
        File(lineimg, "line_1793951725111.png").writeBytes(byteArrayOf(9))
        val l = imageLine("line_1793951725111.png")
        LineTextureResolver.resolve(listOf(l), listOf(imported, lineimg))
        assertEquals(File(lineimg, "line_1793951725111.png").absolutePath, l.texturePath)
    }
}
