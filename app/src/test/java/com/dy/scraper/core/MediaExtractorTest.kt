package com.dy.scraper.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

/**
 * 媒体提取单测（用真实抖音结构的数据，本机 JVM 可跑，无需设备/网络）
 *
 * 覆盖视频下载失败的两条关键链路：
 *   1. 详情 API 响应体 → 最优视频地址（download_addr > bit_rate 最高码率 > play_addr > play_addr_h264）
 *   2. 页面 SSR（_ROUTER_DATA / RENDER_DATA）→ 视频 / 图集图片
 */
class MediaExtractorTest {

    private val videoPageUrl = "https://www.douyin.com/video/7690515078848542890"
    private val notePageUrl = "https://www.douyin.com/note/7690497070327342821"

    /** 真实结构的视频详情（aweme detail API 响应） */
    private val videoDetailJson = """
    {
      "aweme_detail": {
        "aweme_id": "7690515078848542890",
        "desc": "测试视频标题",
        "author": {"nickname": "我的", "unique_id": "", "short_id": "4439190739908841", "sec_uid": "MS4wLjABAAAA"},
        "video": {
          "play_addr": {"url_list": ["https://v3-web.douyinvod.com/aaa/video/tos/cn/tos-cn-ve-15/oPLAY/?a=6383&br=1500&mime_type=video_mp4"]},
          "play_addr_h264": {"url_list": ["https://v3-web.douyinvod.com/aaa/video/tos/cn/tos-cn-ve-15/oH264/?a=6383&br=900&mime_type=video_mp4"]},
          "download_addr": {"url_list": ["https://v3-web.douyinvod.com/aaa/video/tos/cn/tos-cn-ve-15/oDL/?a=6383&br=2000&watermark=0"]},
          "bit_rate": [
            {"bit_rate": 1000000, "play_addr": {"url_list": ["https://v3-web.douyinvod.com/aaa/video/tos/cn/tos-cn-ve-15/oBR1/?a=6383&br=1000"]}},
            {"bit_rate": 2500000, "play_addr": {"url_list": ["https://v3-web.douyinvod.com/aaa/video/tos/cn/tos-cn-ve-15/oBR2/?a=6383&br=2500"]}}
          ],
          "cover": {"url_list": ["https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/oCOVER~tplv-dy-aweme-images:q75.webp?biz_tag=cover"]}
        }
      }
    }
    """.trimIndent()

    /** 真实结构的图集详情（note API 响应） */
    private val noteDetailJson = """
    {
      "note_detail": {
        "note_id": "7690497070327342821",
        "desc": "图集标题",
        "author": {"nickname": "我的", "unique_id": "dy123", "short_id": "4439190739908841"},
        "images": [
          {
            "url_list": ["https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/aaa~tplv-dy-aweme-images:q75.webp?biz_tag=aweme_images"],
            "download_url_list": ["https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/aaa~tplv-dy-aweme-images:q75.jpeg?biz_tag=aweme_images"]
          },
          {
            "url_list": ["https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/bbb~tplv-dy-aweme-images:q75.webp?biz_tag=aweme_images"]
          }
        ]
      }
    }
    """.trimIndent()

    // ==================== 视频地址优先级 ====================

    @Test
    fun `download_addr wins over bit_rate and play_addr`() {
        val media = MediaExtractor.parseApiBody(videoDetailJson, videoPageUrl)
        assertEquals(1, media.videoUrls.size)
        assertTrue(media.videoUrls[0].contains("/oDL/"))
    }

    @Test
    fun `video candidates score order is download_addr then highest bit_rate`() {
        val root = JsonParser.parseString(videoDetailJson).asJsonObject
        val candidates = MediaExtractor.videoCandidates(MediaExtractor.findDetail(root, videoPageUrl))
        assertEquals(5, candidates.size)
        assertTrue(candidates[0].url.contains("/oDL/"))
        assertEquals(MediaExtractor.SCORE_DOWNLOAD_ADDR, candidates[0].score)
        assertTrue(candidates[1].url.contains("/oBR2/"))
        assertEquals(MediaExtractor.bitRateScore(2500000), candidates[1].score)
        assertTrue(candidates[2].url.contains("/oBR1/"))
        assertEquals(MediaExtractor.bitRateScore(1000000), candidates[2].score)
        assertTrue(candidates[3].url.contains("/oPLAY/"))
        assertEquals(MediaExtractor.SCORE_PLAY_ADDR, candidates[3].score)
        assertTrue(candidates[4].url.contains("/oH264/"))
        assertEquals(MediaExtractor.SCORE_PLAY_ADDR_H264, candidates[4].score)
    }

    @Test
    fun `play_addr used when no download_addr and no bit_rate`() {
        val json = """
        {"aweme_detail": {"aweme_id": "7690515078848542890", "desc": "x",
          "video": {"play_addr": {"url_list": ["https://v3-web.douyinvod.com/a/video/tos/cn/oP/?a=6383&br=1200"]}}}}
        """.trimIndent()
        val media = MediaExtractor.parseApiBody(json, videoPageUrl)
        assertEquals(1, media.videoUrls.size)
        assertTrue(media.videoUrls[0].contains("/oP/"))
    }

    @Test
    fun `other pages detail is rejected to avoid cross page data`() {
        val json = """
        {"aweme_detail": {"aweme_id": "1111111111111111111", "desc": "别的视频",
          "video": {"play_addr": {"url_list": ["https://v3-web.douyinvod.com/a/video/tos/cn/oOTHER/?a=6383&br=1200"]}}}}
        """.trimIndent()
        val media = MediaExtractor.parseApiBody(json, videoPageUrl)
        assertTrue(media.videoUrls.isEmpty())
    }

    @Test
    fun `bitrate play addr outranks plain play_addr`() {
        val json = """
        {"aweme_detail": {"aweme_id":"7690515078848542890","desc":"x","video":{
          "play_addr":{"url_list":["https://v3-web.douyinvod.com/a/video/tos/cn/oPLAIN/?a=6383"]},
          "bit_rate":[{"bit_rate":2000000,"play_addr":{"url_list":["https://v3-web.douyinvod.com/a/video/tos/cn/oBR/?a=6383&br=2000"]}}]}}}
        """.trimIndent()
        val media = MediaExtractor.parseApiBody(json, videoPageUrl)
        assertEquals(1, media.videoUrls.size)
        assertTrue(media.videoUrls[0].contains("/oBR/"))
    }

    // ==================== 图集图片 ====================
    @Test
    fun `note images prefer download_url_list and keep one url per image`() {
        val media = MediaExtractor.parseApiBody(noteDetailJson, notePageUrl)
        assertTrue(media.videoUrls.isEmpty())
        assertEquals(2, media.imageUrls.size)
        assertTrue(media.imageUrls[0].contains("/aaa~tplv-dy-aweme-images:q75.jpeg"))
        assertTrue(media.imageUrls[1].contains("/bbb~tplv-dy-aweme-images:q75.webp"))
    }

    @Test
    fun `author title and cover parsed from detail`() {
        val media = MediaExtractor.parseApiBody(videoDetailJson, videoPageUrl)
        assertEquals("我的", media.author)
        assertEquals("4439190739908841", media.authorCode)
        assertEquals("测试视频标题", media.title)
        assertTrue(media.coverUrl.contains("/oCOVER~tplv-dy-aweme-images"))
    }

    // ==================== SSR / _ROUTER_DATA / RENDER_DATA ====================

    @Test
    fun `extract video from router data with recommendation decoy`() {
        val routerData = """
        {"loaderData": {"video_(id)/page": {"videoInfoRes": {"item_list": [
            {"aweme_id": "1111111111111111111", "desc": "推荐流里的别的视频",
             "video": {"play_addr": {"url_list": ["https://v3-web.douyinvod.com/a/video/tos/cn/oREC/?a=6383&br=9999"]}}},
            {"aweme_id": "7690515078848542890", "desc": "目标视频",
             "author": {"nickname": "我的", "short_id": "4439190739908841"},
             "video": {"play_addr": {"url_list": ["https://v3-web.douyinvod.com/a/video/tos/cn/oTARGET/?a=6383&br=1200&mime_type=video_mp4"]}}}
        ]}}}, "errors": {}}
        """.trimIndent()

        val media = MediaExtractor.parseSsTexts(mapOf("_ROUTER_DATA" to routerData), videoPageUrl)
        assertEquals(1, media.videoUrls.size)
        assertTrue(media.videoUrls[0].contains("/oTARGET/"))
        assertEquals("我的", media.author)
        assertTrue(media.detailKeys.contains("_ROUTER_DATA"))
    }

    @Test
    fun `extract images from render data`() {
        val renderData = """
        {"app":{"videoInfo":{"aweme":{"note_id":"7690497070327342821","desc":"图集",
          "images":[{"url_list":["https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/ccc~tplv-dy-aweme-images:q75.webp?biz_tag=aweme_images"]}]}}}}
        """.trimIndent()
        val media = MediaExtractor.parseSsTexts(mapOf("RENDER_DATA" to renderData), notePageUrl)
        assertEquals(1, media.imageUrls.size)
        assertTrue(media.imageUrls[0].contains("/ccc~tplv-dy-aweme-images"))
    }

    @Test
    fun `ss texts ignore garbage and empty variables`() {
        val media = MediaExtractor.parseSsTexts(
            mapOf("A" to "", "B" to "not-json", "C" to "{}", "_ROUTER_DATA" to "{\"a\":1}"),
            videoPageUrl
        )
        assertFalse(media.hasMedia)
        assertTrue(media.detailKeys.isEmpty())
    }

    @Test
    fun `inline ssr dump details are located by findDetail`() {
        // 对应 SSR_DUMP_SCRIPT 返回的 {details:[...]}（JS 已深搜出的详情候选）
        val dump = """
        {"url":"https://www.douyin.com/video/7690515078848542890",
         "details":[
           {"aweme_id":"1111111111111111111","desc":"推荐",
            "video":{"play_addr":{"url_list":["https://v3-web.douyinvod.com/a/video/tos/cn/oREC/?a=6383&br=9999"]}}},
           {"aweme_id":"7690515078848542890","desc":"目标视频",
            "video":{"play_addr":{"url_list":["https://v3-web.douyinvod.com/a/video/tos/cn/oINLINE/?a=6383&br=1200"]}}}
         ],
         "texts":{}}
        """.trimIndent()
        val root = JsonParser.parseString(dump).asJsonObject
        val candidates = com.google.gson.JsonObject()
        candidates.add("candidates", root.getAsJsonArray("details"))
        val detail = MediaExtractor.findDetail(candidates, videoPageUrl)
        val media = MediaExtractor.mediaFromDetail(detail)
        assertEquals(1, media.videoUrls.size)
        assertTrue(media.videoUrls[0].contains("/oINLINE/"))
        assertEquals("目标视频", media.title)
    }

    @Test
    fun `hook ssr snapshot text parses like router data`() {
        // PageHook 在 _ROUTER_DATA 赋值瞬间抓的快照，SSR_DUMP_SCRIPT 以 'snap:_ROUTER_DATA' 为 key 回传
        val snapshot = """
        {"loaderData":{"video_(id)/page":{"videoInfoRes":{"item_list":[
           {"aweme_id":"7690515078848542890","desc":"目标视频",
            "author":{"nickname":"我的","short_id":"4439190739908841"},
            "video":{"play_addr":{"url_list":["https://v3-web.douyinvod.com/a/video/tos/cn/oSNAP/?a=6383&br=1800&mime_type=video_mp4"]}}}
        ]}}}}
        """.trimIndent()
        val media = MediaExtractor.parseSsTexts(mapOf("snap:_ROUTER_DATA" to snapshot), videoPageUrl)
        assertEquals(1, media.videoUrls.size)
        assertTrue(media.videoUrls[0].contains("/oSNAP/"))
        assertEquals("我的", media.author)
        assertTrue(media.detailKeys.contains("snap:_ROUTER_DATA"))
    }

    // ==================== 工具方法 ====================

    @Test
    fun `page ids extracted from url`() {
        assertTrue(MediaExtractor.pageIds(videoPageUrl).contains("7690515078848542890"))
        assertTrue(
            MediaExtractor.pageIds("https://www.douyin.com/note/7675380407248344677?previous_page=app_code_link")
                .contains("7675380407248344677")
        )
    }

    @Test
    fun `image quality score from size marker`() {
        assertEquals(720 * 1280, MediaExtractor.imageQualityScore("https://x.com/a~tplv_720x1280.webp"))
        assertEquals(0, MediaExtractor.imageQualityScore("https://x.com/a.webp"))
    }
}
