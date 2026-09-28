package com.dy.scraper.util

import org.junit.Assert.*
import org.junit.Test

class UtilsTest {

    // ==================== extractUrls ====================

    @Test
    fun `extractUrls single URL`() {
        val result = Utils.extractUrls("https://v.douyin.com/abc123/")
        assertEquals(1, result.size)
        assertTrue(result[0].startsWith("https://v.douyin.com/abc123"))
    }

    @Test
    fun `extractUrls multiple URLs one per line`() {
        val input = """
            https://v.douyin.com/abc123/
            https://www.douyin.com/video/123456
            https://v.douyin.com/def456/
        """.trimIndent()
        val result = Utils.extractUrls(input)
        assertEquals(3, result.size)
    }

    @Test
    fun `extractUrls multiple URLs same line no space`() {
        val result = Utils.extractUrls("https://v.douyin.com/abc123/https://v.douyin.com/def456/")
        assertEquals(2, result.size)
        assertTrue(result[0].contains("abc123"))
        assertTrue(result[1].contains("def456"))
    }

    @Test
    fun `extractUrls multiple URLs same line with spaces`() {
        val result = Utils.extractUrls("https://v.douyin.com/abc123/ https://v.douyin.com/def456/ https://v.douyin.com/ghi789/")
        assertEquals(3, result.size)
    }

    @Test
    fun `extractUrls with Chinese text mixed`() {
        val result = Utils.extractUrls("这是第一个链接https://v.douyin.com/abc123/，这是第二个链接https://v.douyin.com/def456/。结束")
        assertEquals(2, result.size)
    }

    @Test
    fun `extractUrls trailing Chinese removed`() {
        val result = Utils.extractUrls("https://v.douyin.com/abc123/这是一段中文")
        assertEquals(1, result.size)
        assertEquals("https://v.douyin.com/abc123/", result[0])
    }

    @Test
    fun `extractUrls trailing punctuation removed`() {
        val result = Utils.extractUrls("https://v.douyin.com/abc123/。，；")
        assertEquals(1, result.size)
        assertEquals("https://v.douyin.com/abc123/", result[0])
    }

    @Test
    fun `extractUrls empty input`() {
        assertTrue(Utils.extractUrls("").isEmpty())
    }

    @Test
    fun `extractUrls no URL in text`() {
        assertTrue(Utils.extractUrls("这是一段没有链接的普通文本").isEmpty())
    }

    @Test
    fun `extractUrls deduplicates identical URLs`() {
        val result = Utils.extractUrls("https://v.douyin.com/abc123/ https://v.douyin.com/abc123/")
        assertEquals(1, result.size)
    }

    @Test
    fun `extractUrls http and https`() {
        val result = Utils.extractUrls("http://example.com/page https://example.com/other")
        assertEquals(2, result.size)
    }

    // ===== Real URLs from dy_scraper_log_2026-09-28.txt =====

    @Test
    fun `extractUrls real log douyin share text`() {
        // 日志中的真实输入: "https://v.douyin.com/pg49NbO3_RY/ :4pm Y@m.dN cNJ:/ 05/26"
        val result = Utils.extractUrls("https://v.douyin.com/pg49NbO3_RY/ :4pm Y@m.dN cNJ:/ 05/26")
        assertEquals(1, result.size)
        assertTrue(result[0].contains("pg49NbO3_RY"))
    }

    @Test
    fun `extractUrls real log second share text`() {
        // 日志中的真实输入: "https://v.douyin.com/OpEOqm8nVpM/ 04/16 I@V.LJ sEH:/ :2pm"
        val result = Utils.extractUrls("https://v.douyin.com/OpEOqm8nVpM/ 04/16 I@V.LJ sEH:/ :2pm")
        assertEquals(1, result.size)
        assertTrue(result[0].contains("OpEOqm8nVpM"))
    }

    @Test
    fun `extractUrls real log two URLs in one line`() {
        // 模拟用户粘贴两个分享文本到同一行（没有换行）
        val input = "https://v.douyin.com/pg49NbO3_RY/ :4pm Y@m.dN cNJ:/ 05/26 https://v.douyin.com/OpEOqm8nVpM/ 04/16 I@V.LJ sEH:/ :2pm"
        val result = Utils.extractUrls(input)
        assertEquals(2, result.size)
        assertTrue(result.any { "pg49NbO3_RY" in it })
        assertTrue(result.any { "OpEOqm8nVpM" in it })
    }

    @Test
    fun `extractUrls real log douyin pic URLs preserved`() {
        // 日志中真实出现的图片URL模式
        val input = "https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/oUP5EN1LOBAJPSA1ARi7Ae1ACA0gLClXI"
        val result = Utils.extractUrls(input)
        assertEquals(1, result.size)
        assertTrue(result[0].contains("douyinpic.com"))
    }

    @Test
    fun `extractUrls douyin note URL`() {
        // 日志中真实跳转后的URL
        val result = Utils.extractUrls("https://www.douyin.com/note/7690526203930018010?previous_page=app_code_link")
        assertEquals(1, result.size)
        assertTrue(result[0].contains("7690526203930018010"))
    }

    @Test
    fun `extractUrls douyin video URL`() {
        val result = Utils.extractUrls("https://www.douyin.com/video/7690515078848542890?previous_page=app_code_link")
        assertEquals(1, result.size)
        assertTrue(result[0].contains("7690515078848542890"))
    }

    @Test
    fun `extractUrls notes and video URLs mixed`() {
        val input = """
            https://www.douyin.com/note/7690526203930018010
            https://www.douyin.com/video/7690515078848542890
        """.trimIndent()
        val result = Utils.extractUrls(input)
        assertEquals(2, result.size)
    }

    // ==================== normalizeUrl ====================

    @Test
    fun `normalizeUrl strips https prefix`() {
        assertEquals("v.douyin.com/abc123/", Utils.normalizeUrl("https://v.douyin.com/abc123/"))
    }

    @Test
    fun `normalizeUrl handles trailing dot-com`() {
        assertEquals("example.com/", Utils.normalizeUrl("https://example.com"))
    }

    @Test
    fun `normalizeUrl empty string`() {
        assertEquals("", Utils.normalizeUrl(""))
    }

    // ==================== cleanTitle ====================

    @Test
    fun `cleanTitle removes special chars`() {
        val result = Utils.cleanTitle("test/file:name*?<>|")
        assertFalse(result.contains("/"))
        assertFalse(result.contains(":"))
        assertFalse(result.contains("*"))
    }

    @Test
    fun `cleanTitle preserves Chinese and alphanumeric`() {
        assertEquals("测试Test2024", Utils.cleanTitle("测试Test2024"))
    }

    @Test
    fun `cleanTitle real title from log`() {
        // 日志中的真实标题
        val title = "把夏日清新感装进镜头的秘诀。● 利用白色纱幔做前 - 抖音"
        val result = Utils.cleanTitle(title)
        assertFalse(result.contains("抖音"))
        assertFalse(result.contains("。"))
        assertFalse(result.contains("●"))
        assertTrue(result.length <= 50)
    }

    // ==================== formatBytes ====================

    @Test
    fun `formatBytes KB from log`() {
        val result = Utils.formatBytes(541720) // log shows 541.72 KB
        assertTrue(result.contains("KB"))
    }

    @Test
    fun `formatBytes MB`() {
        val result = Utils.formatBytes(1050480) // log shows ~1.00 MB
        assertTrue(result.contains("MB"))
    }

    @Test
    fun `formatBytes zero`() {
        assertTrue(Utils.formatBytes(0).isNotEmpty())
    }

    @Test
    fun `formatBytes negative`() {
        assertTrue(Utils.formatBytes(-1).isNotEmpty())
    }

    // ==================== md5 ====================

    @Test
    fun `md5 bytes deterministic`() {
        val data = "hello world".toByteArray()
        assertEquals(Utils.md5(data), Utils.md5(data))
    }

    @Test
    fun `md5 bytes different data different hash`() {
        assertNotEquals(
            Utils.md5("hello".toByteArray()),
            Utils.md5("world".toByteArray())
        )
    }

    @Test
    fun `md5 bytes known value`() {
        assertEquals("5eb63bbbe01eeed093cb22bb8f5acdc3", Utils.md5("hello world".toByteArray()))
    }

    // ==================== isUiAsset ====================

    @Test
    fun `isUiAsset detects douyinstatic mp3 as UI asset`() {
        assertTrue(Utils.isUiAsset("https://sf6-cdn-tos.douyinstatic.com/obj/ies-music/7676479935044668166.mp3?is_ssr=1"))
    }

    @Test
    fun `isUiAsset real douyin image is NOT UI asset`() {
        assertFalse(Utils.isUiAsset("https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/oUP5EN1LOBAJPSA1ARi7Ae1ACA0gLClXI"))
    }

    @Test
    fun `isUiAsset emoji URL is UI asset`() {
        assertTrue(Utils.isUiAsset("https://p3-sign.douyinpic.com/obj/ies.fe.effect/dbd9c8682cb1ed3748cbccd22d59391f?lk3s=7b078dd2"))
    }

    // ==================== extractIdFromUrl ====================

    @Test
    fun `extractIdFromUrl from note URL`() {
        assertEquals("7690526203930018010", Utils.extractIdFromUrl("https://www.douyin.com/note/7690526203930018010?previous_page=app_code_link"))
    }

    @Test
    fun `extractIdFromUrl null for no ID`() {
        assertNull(Utils.extractIdFromUrl("https://v.douyin.com/pg49NbO3_RY/"))
    }

    // ==================== extractExtFromUrl ====================

    @Test
    fun `extractExtFromUrl webp`() {
        assertEquals(".webp", Utils.extractExtFromUrl("https://example.com/photo.webp", ".jpg"))
    }

    @Test
    fun `extractExtFromUrl fallback`() {
        assertEquals(".jpg", Utils.extractExtFromUrl("https://example.com/photo", ".jpg"))
    }

    // ==================== isAudioUrl ====================

    @Test
    fun `isAudioUrl mp3`() {
        assertTrue(Utils.isAudioUrl("https://sf6-cdn-tos.douyinstatic.com/obj/ies-music/7676479935044668166.mp3"))
    }

    @Test
    fun `isAudioUrl webp is NOT audio`() {
        assertFalse(Utils.isAudioUrl("https://example.com/photo.webp"))
    }
}