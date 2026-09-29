package com.dy.scraper.core

import org.junit.Assert.*
import org.junit.Test

/**
 * 网络请求 URL 分类 / 去重单测（本机 JVM，可跑）
 *
 * 背景：日志里视频页 154 个请求 → 0 视频，原因是视频地址形如
 *   https://v3-web.douyinvod.com/xxx/video/tos/cn/tos-cn-ve-15/oXXXX/?a=6383&br=2500&mime_type=video_mp4
 * 没有 .mp4 后缀，旧的 `.mp4?` 模式匹配不到。
 */
class DownloaderUrlTest {

    private val realVideoUrl =
        "https://v3-web.douyinvod.com/aaa/bbb/video/tos/cn/tos-cn-ve-15/oABC123/?a=6383&ch=26&br=2500&bt=2500&mime_type=video_mp4&qs=1"
    private val realImageUrl =
        "https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/oUP5EN1LOBAJPSA1ARi7Ae1ACA0gLClXI~tplv-dy-aweme-images:q75.webp?biz_tag=aweme_images"
    private val musicUrl =
        "https://sf6-cdn-tos.douyinstatic.com/obj/ies-music/7676479935044668166.mp3?is_ssr=1"

    // ==================== 视频 / 图片判定 ====================

    @Test
    fun `douyinvod url without mp4 extension is detected as video`() {
        assertTrue(Downloader.isVideoUrl(realVideoUrl))
    }

    @Test
    fun `url with mp4 extension is detected as video`() {
        assertTrue(Downloader.isVideoUrl("https://example.com/a/b.mp4"))
        assertTrue(Downloader.isVideoUrl("https://example.com/a/b.mp4?x=1"))
    }

    @Test
    fun `content type video is detected as video`() {
        assertTrue(Downloader.isVideoUrl("https://example.com/stream/12345", "video/mp4"))
        assertTrue(Downloader.isVideoUrl("https://example.com/stream/12345", "application/x-mpegURL"))
    }

    @Test
    fun `mp3 and ui assets are not video`() {
        assertFalse(Downloader.isVideoUrl(musicUrl))
        assertFalse(Downloader.isVideoUrl("https://sf3-cdn-tos.douyinstatic.com/obj/ies-music/1.mp3"))
        assertFalse(Downloader.isVideoUrl("https://lf-douyin-pc-web.douyinstatic.com/obj/douyin-pc-web/x.js"))
    }

    @Test
    fun `douyinpic image detected as image`() {
        assertTrue(Downloader.isImageUrl(realImageUrl))
        assertTrue(Downloader.isImageUrl("https://example.com/a.png"))
        assertFalse(Downloader.isImageUrl(realVideoUrl))
    }

    // ==================== 网络请求提取 ====================

    @Test
    fun `extract urls from network classifies video image and filters music`() {
        val requests = listOf(
            mapOf("url" to realVideoUrl, "contentType" to ""),
            mapOf("url" to realImageUrl, "contentType" to ""),
            mapOf("url" to musicUrl, "contentType" to ""),
            mapOf("url" to "https://lf-douyin-pc-web.douyinstatic.com/obj/douyin-pc-web/x.js", "contentType" to ""),
            mapOf("url" to "blob:https://www.douyin.com/uuid", "contentType" to ""),
            mapOf("url" to "https://p-pc-weboff.byteimg.com/tos-cn-i-9r5gewecjs/1x1.png", "contentType" to ""),
        )
        val (videos, images) = Downloader.extractUrlsFromNetwork(requests)
        assertEquals(1, videos.size)
        assertEquals(realVideoUrl, videos[0])
        assertEquals(1, images.size)
        assertEquals(realImageUrl, images[0])
    }

    // ==================== 视频 URL 去重（同一视频多码率只留一份） ====================

    @Test
    fun `deduplicate videos keeps highest bitrate per file id`() {
        val br1000 = "https://v3-web.douyinvod.com/a/video/tos/cn/tos-cn-ve-15/oSAME/?a=6383&br=1000"
        val br2500 = "https://v3-web.douyinvod.com/a/video/tos/cn/tos-cn-ve-15/oSAME/?a=6383&br=2500"
        val other = "https://v3-web.douyinvod.com/a/video/tos/cn/tos-cn-ve-15/oOTHER/?a=6383&br=800"

        val result = Downloader.deduplicateVideos(listOf(br1000, br2500, other))
        assertEquals(2, result.size)
        assertTrue(result[0].contains("br=2500"))
        assertTrue(result[1].contains("oOTHER"))
    }

    @Test
    fun `single video url is returned as is`() {
        assertEquals(listOf(realVideoUrl), Downloader.deduplicateVideos(listOf(realVideoUrl)))
    }

    @Test
    fun `video file id extracted from path`() {
        assertEquals("oSAME", Downloader.videoFileId("https://x.com/a/video/tos/cn/oSAME/?a=6383&br=1000"))
        assertEquals("ABC", Downloader.videoFileId("https://x.com/a/video/ABC?br=1"))
    }

    @Test
    fun `video quality score from br param`() {
        assertEquals(2500, Downloader.videoQualityScore(realVideoUrl))
        assertEquals(0, Downloader.videoQualityScore("https://x.com/a/video/ABC"))
    }

    // ==================== 图片去重/排序 ====================

    @Test
    fun `same image different resolutions keeps largest`() {
        val small = "https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/IMG~tplv-dy-aweme-images:q75_720x1280.webp?biz_tag=aweme_images"
        val large = "https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/IMG~tplv-dy-aweme-images:q75_1080x1920.webp?biz_tag=aweme_images"
        val result = Downloader.sortImagesByQuality(listOf(small, large))
        assertEquals(1, result.size)
        assertEquals(large, result[0])
    }

    @Test
    fun `tiny thumbnail is dropped`() {
        val thumb = "https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/TINY~tplv-dy-aweme-images:q75_100x100.webp"
        val normal = "https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/NORMAL~tplv-dy-aweme-images:q75.webp"
        val result = Downloader.sortImagesByQuality(listOf(thumb, normal))
        assertEquals(1, result.size)
        assertEquals(normal, result[0])
    }

    @Test
    fun `different images are kept`() {
        val a = "https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/AAA~tplv-dy-aweme-images:q75.webp"
        val b = "https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/BBB~tplv-dy-aweme-images:q75.webp"
        assertEquals(2, Downloader.sortImagesByQuality(listOf(a, b)).size)
    }

    @Test
    fun `image identity ignores query and extension`() {
        val webp = "https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/IMG~tplv-dy-aweme-images:q75.webp?biz_tag=aweme_images"
        val jpeg = "https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/IMG~tplv-dy-aweme-images:q75.jpeg?biz_tag=aweme_images"
        assertEquals(Downloader.imageIdentity(webp), Downloader.imageIdentity(jpeg))
    }
}
