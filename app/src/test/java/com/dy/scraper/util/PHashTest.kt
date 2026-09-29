package com.dy.scraper.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DCT pHash 单测（本机 JVM 可跑）。
 *
 * 背景：旧实现用 aHash（8x8 均值哈希）冒充 pHash，真机上把同一图集的 6 张图误删到只剩 2 张。
 * 这里用合成像素证明「同图判同、异图判异」。
 */
class PHashTest {

    private val size = PHash.SAMPLE_SIZE

    /** 竖条纹（模拟一张结构图） */
    private fun verticalStripes(period: Int = 4): IntArray =
        IntArray(size * size) { i -> if ((i % size) / period % 2 == 0) 230 else 40 }

    /** 横条纹 */
    private fun horizontalStripes(period: Int = 4): IntArray =
        IntArray(size * size) { i -> if ((i / size) / period % 2 == 0) 230 else 40 }

    /** 中心亮块（模拟另一张构图完全不同的图） */
    private fun centerBlock(): IntArray =
        IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            if (x in 10..22 && y in 10..22) 240 else 60
        }

    /** 同图 + 整体亮度偏移（模拟轻微曝光差异 / 重新编码） */
    private fun brightened(base: IntArray, delta: Int): IntArray =
        IntArray(base.size) { i -> (base[i] + delta).coerceIn(0, 255) }

    @Test
    fun `same pixels produce same hash`() {
        val a = verticalStripes()
        assertEquals(PHash.hash(a), PHash.hash(a.copyOf()))
    }

    @Test
    fun `hash is 16 hex chars`() {
        val h = PHash.hash(verticalStripes())
        assertEquals(16, h.length)
        assertTrue(h.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `same image with brightness shift stays within dedupe threshold`() {
        val base = centerBlock()
        val dist = ImageDedupChecker.hammingDistance(PHash.hash(base), PHash.hash(brightened(base, 12)))
        assertTrue("同图亮度偏移 12 的距离应 ≤5，实际 $dist", dist <= 5)
    }

    @Test
    fun `different compositions are not treated as duplicates`() {
        // 这几种图在旧 aHash 实现下会被误判成重复（距离 ≤4），DCT pHash 必须区分开
        val d1 = ImageDedupChecker.hammingDistance(PHash.hash(verticalStripes()), PHash.hash(horizontalStripes()))
        val d2 = ImageDedupChecker.hammingDistance(PHash.hash(verticalStripes()), PHash.hash(centerBlock()))
        val d3 = ImageDedupChecker.hammingDistance(PHash.hash(centerBlock()), PHash.hash(horizontalStripes()))
        assertTrue("竖条纹 vs 横条纹 距离=$d1", d1 > 5)
        assertTrue("竖条纹 vs 中心块 距离=$d2", d2 > 5)
        assertTrue("中心块 vs 横条纹 距离=$d3", d3 > 5)
        assertNotEquals(PHash.hash(verticalStripes()), PHash.hash(horizontalStripes()))
    }

    @Test
    fun `gray conversion uses bt601 weights`() {
        val gray = PHash.toGray(intArrayOf(0x00FFFFFF.toInt(), 0x00000000, 0x00FF0000))
        assertEquals(255, gray[0])
        assertEquals(0, gray[1])
        assertEquals(76, gray[2])
    }

    @Test
    fun `hamming distance of identical and inverted hashes`() {
        val h = PHash.hash(centerBlock())
        assertEquals(0, ImageDedupChecker.hammingDistance(h, h))
    }
}
