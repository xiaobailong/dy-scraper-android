package com.dy.scraper.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 详情响应体解析单测（模拟 document-start 钩子回传的 body，本机 JVM 可跑）。
 *
 * 这条链路是视频能否下载下来的关键：钩子 → `DyBridge.onApiBody` → `detailBodies` →
 * `DouyinApiCollector.parseAllBodies` → `PageContext.apiVideoUrls` → `Downloader`。
 */
class DouyinApiCollectorTest {

    private val videoPageUrl = "https://www.douyin.com/video/7690515078848542890"

    /** 钩子回传的真实结构（aweme detail 接口，含 download_addr / bit_rate / play_addr） */
    private val hookedVideoBody = """
    {
      "status_code": 0,
      "aweme_detail": {
        "aweme_id": "7690515078848542890",
        "desc": "把夏日清新感装进镜头",
        "author": {"nickname": "我的", "unique_id": "dy123", "short_id": "4439190739908841"},
        "video": {
          "play_addr": {"url_list": ["https://v3-web.douyinvod.com/a/video/tos/cn/oPLAY/?a=6383&br=1500&mime_type=video_mp4"]},
          "download_addr": {"url_list": ["https://v3-web.douyinvod.com/a/video/tos/cn/oDL/?a=6383&watermark=0"]},
          "bit_rate": [
            {"bit_rate": 2500000, "play_addr": {"url_list": ["https://v3-web.douyinvod.com/a/video/tos/cn/oBR2/?a=6383&br=2500"]}}
          ],
          "cover": {"url_list": ["https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/oCOVER~tplv-dy-aweme-images:q75.webp"]}
        }
      }
    }
    """.trimIndent()

    private val hookedNoteBody = """
    {
      "note_detail": {
        "note_id": "7690497070327342821",
        "desc": "图集标题",
        "author": {"nickname": "我的", "short_id": "4439190739908841"},
        "images": [
          {"url_list": ["https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/aaa~tplv-dy-aweme-images:q75.webp"]},
          {"url_list": ["https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/bbb~tplv-dy-aweme-images:q75.webp"]}
        ]
      }
    }
    """.trimIndent()

    @Test
    fun `hooked video body yields exactly one best url`() {
        val data = DouyinApiCollector.parseApiBody(hookedVideoBody, videoPageUrl)
        assertEquals(1, data.videoUrls.size)
        assertTrue(data.videoUrls[0].contains("/oDL/"))
        assertEquals("download_addr", data.videoSource)
        assertEquals("我的", data.author)
        assertEquals("把夏日清新感装进镜头", data.title)
        assertTrue(data.coverUrl.contains("oCOVER"))
    }

    @Test
    fun `hooked note body yields images`() {
        val data = DouyinApiCollector.parseApiBody(hookedNoteBody, "https://www.douyin.com/note/7690497070327342821")
        assertEquals(0, data.videoUrls.size)
        assertEquals(2, data.imageUrls.size)
        assertTrue(data.hasMedia)
    }

    @Test
    fun `html shell and empty json are ignored`() {
        // 风控把重放请求当导航时返回整页 HTML（真机日志: body前100字符: <!DOCTYPE html>）
        assertFalse(DouyinApiCollector.parseApiBody("<!DOCTYPE html><html><body></body></html>").hasMedia)
        assertFalse(DouyinApiCollector.parseApiBody("{}").hasMedia)
        assertFalse(DouyinApiCollector.parseApiBody("").hasMedia)
    }

    @Test
    fun `parse all bodies merges hook payloads and keeps one video`() {
        val bodies = listOf(
            mapOf("url" to "https://www.douyin.com/aweme/v1/web/aweme/detail/?aweme_id=7690515078848542890", "body" to hookedVideoBody, "source" to "hook"),
            mapOf("url" to "https://www.douyin.com/aweme/v1/web/aweme/detail/?aweme_id=7690515078848542890", "body" to "", "source" to "hook")
        )
        val data = DouyinApiCollector.parseAllBodies(bodies, videoPageUrl)
        assertEquals(1, data.videoUrls.size)
        assertTrue(data.videoUrls[0].contains("/oDL/"))
    }

    @Test
    fun `other page detail is rejected by belonging check`() {
        // 推荐流里别的作品的详情，不能算到当前页面头上
        val data = DouyinApiCollector.parseApiBody(hookedVideoBody, "https://www.douyin.com/video/7690156149479314361")
        assertFalse(data.hasMedia)
    }
}
