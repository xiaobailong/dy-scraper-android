package com.dy.scraper.api

import com.dy.scraper.core.MediaExtractor
import com.dy.scraper.core.WebViewManager
import com.dy.scraper.util.AppConfig
import com.dy.scraper.util.Logger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 抖音详情 API（aweme/detail、note）响应处理
 *
 * 解析逻辑统一在 [MediaExtractor]（纯 Kotlin，可单元测试），本类只负责「拿到响应体」。
 * 拿到响应体有两条腿：
 *   1. WebViewManager 的「影子请求」——拦截到 API 时用同一会话重新请求并抓 body（主路径）
 *   2. JS fetch + JS 桥回调（兜底，evaluateJavascript 不会 await Promise，必须走桥）
 */
object DouyinApiCollector {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class ApiData(
        val videoUrls: List<String> = emptyList(),
        val imageUrls: List<String> = emptyList(),
        val author: String = "",
        val authorCode: String = "",
        val title: String = "",
        val coverUrl: String = "",
        /** 命中的提取方式，如 "bit_rate=1280000" / "download_addr" / "play_addr" */
        val videoSource: String = ""
    ) {
        val hasMedia: Boolean
            get() = videoUrls.isNotEmpty() || imageUrls.isNotEmpty()
    }

    fun isDetailApiResponse(url: String): Boolean =
        AppConfig.DETAIL_API_PATTERNS.any { it in url }

    /**
     * 通过 WebView JS fetch 请求 API 并解析（共享 Cookie 会话，兜底路径）
     */
    suspend fun fetchAndParseApiResponseViaJs(
        wvm: WebViewManager,
        apiUrl: String,
        pageUrl: String = ""
    ): ApiData {
        val body = wvm.fetchApiViaJs(apiUrl)
        if (body.isNullOrEmpty() || body == "{}") {
            Logger.log("  [API-JS] 响应体为空: ${apiUrl.take(100)}...", "debug")
            return ApiData()
        }
        return parseApiBody(body, pageUrl)
    }

    /**
     * 通过 OkHttp 请求 API（兼容/兜底方案，可能因风控返回空 body）
     */
    fun fetchAndParseApiResponse(apiUrl: String, cookies: String = "", pageUrl: String = ""): ApiData {
        try {
            val builder = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", AppConfig.USER_AGENT)
                .header("Referer", "https://www.douyin.com/")
                .header("Accept", "application/json, text/plain, */*")
            if (cookies.isNotEmpty()) builder.header("Cookie", cookies)

            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Logger.log("  [API] 请求失败 HTTP ${response.code}: ${apiUrl.take(100)}...", "warn")
                    return ApiData()
                }
                val body = response.body?.string() ?: return ApiData()
                Logger.log("  [API] 响应体长度: ${body.length}", "debug")
                return parseApiBody(body, pageUrl)
            }
        } catch (e: Exception) {
            Logger.log("  [API] 请求异常: ${e.message}", "warn")
            return ApiData()
        }
    }

    /**
     * 解析单条详情 API 响应体（纯函数，可单元测试）
     */
    fun parseApiBody(body: String, pageUrl: String = ""): ApiData {
        val media = MediaExtractor.parseApiBody(body, pageUrl)
        if (!media.hasMedia && media.author.isEmpty() && media.authorCode.isEmpty()) {
            Logger.log("  [API解析] 未解析出媒体, body前100字符: ${body.take(100)}", "warn")
            return ApiData()
        }
        val source = videoSourceOf(media)
        Logger.log(
            "  [API解析] 视频: ${media.videoUrls.size} ($source), " +
                    "图片: ${media.imageUrls.size}, 作者: ${media.author} (${media.authorCode})"
        )
        return ApiData(
            videoUrls = media.videoUrls,
            imageUrls = media.imageUrls,
            author = media.author,
            authorCode = media.authorCode,
            title = media.title,
            coverUrl = media.coverUrl,
            videoSource = source
        )
    }

    /**
     * 合并解析多条详情响应体（影子请求可能同时命中 detail 与 note 两种接口）
     */
    fun parseAllBodies(bodies: List<Map<String, String>>, pageUrl: String = ""): ApiData {
        if (bodies.isEmpty()) return ApiData()

        val videos = LinkedHashSet<String>()
        val images = LinkedHashSet<String>()
        var author = ""
        var authorCode = ""
        var title = ""
        var cover = ""
        var source = ""

        for (b in bodies) {
            val body = b["body"] ?: continue
            if (body.isBlank()) continue
            val one = parseApiBody(body, pageUrl)
            if (!one.hasMedia && one.author.isEmpty()) continue
            videos.addAll(one.videoUrls)
            images.addAll(one.imageUrls)
            if (author.isEmpty()) author = one.author
            if (authorCode.isEmpty()) authorCode = one.authorCode
            if (title.isEmpty()) title = one.title
            if (cover.isEmpty()) cover = one.coverUrl
            if (source.isEmpty()) source = one.videoSource
        }

        return ApiData(
            videoUrls = videos.toList(),
            imageUrls = images.toList(),
            author = author,
            authorCode = authorCode,
            title = title,
            coverUrl = cover,
            videoSource = source
        )
    }

    /** 判定视频地址来源（用于日志排查） */
    private fun videoSourceOf(media: MediaExtractor.Media): String {
        if (media.videoUrls.isEmpty()) return "无"
        val url = media.videoUrls.first()
        Regex("[?&]br=(\\d+)").find(url)?.groupValues?.get(1)?.let { return "bit_rate=$it" }
        if (url.contains("watermark=0")) return "download_addr"
        if (url.contains("play_addr_h264") || url.contains("/h264/")) return "play_addr_h264"
        return "play_addr"
    }
}
