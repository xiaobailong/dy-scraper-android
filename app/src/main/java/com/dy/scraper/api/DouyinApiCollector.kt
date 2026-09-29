package com.dy.scraper.api

import com.dy.scraper.core.WebViewManager
import com.dy.scraper.util.AppConfig
import com.dy.scraper.util.Logger
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object DouyinApiCollector {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun isDetailApiResponse(url: String): Boolean {
        return AppConfig.DETAIL_API_PATTERNS.any { it in url }
    }

    /**
     * 通过 WebView JS fetch 请求 API 并解析 JSON（共享 Cookie 会话）
     * 这是主要路径，对应 Python 版 await resp.text() 在浏览器上下文中的行为
     */
    suspend fun fetchAndParseApiResponseViaJs(
        wvm: WebViewManager,
        apiUrl: String
    ): ApiData {
        val body = wvm.fetchApiViaJs(apiUrl)
        if (body.isNullOrEmpty()) {
            Logger.log("  [API-JS] 响应体为空: ${apiUrl.take(100)}...", "debug")
            return ApiData()
        }
        Logger.log("  [API-JS] 响应体长度: ${body.length}", "debug")
        return parseApiBody(body, apiUrl)
    }

    /**
     * 通过 OkHttp 同步请求 API（兼容/兜底方案，可能因 Cookie 缺失而失败）
     */
    fun fetchAndParseApiResponse(apiUrl: String, cookies: String = ""): ApiData {
        try {
            val requestBuilder = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", AppConfig.USER_AGENT)
                .header("Referer", "https://www.douyin.com/")
                .header("Accept", "application/json")

            if (cookies.isNotEmpty()) {
                requestBuilder.header("Cookie", cookies)
            }

            val response = client.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                Logger.log("  [API] 请求失败 HTTP ${response.code}: ${apiUrl.take(100)}...", "warn")
                return ApiData()
            }

            val body = response.body?.string() ?: return ApiData()
            Logger.log("  [API] 响应体长度: ${body.length}", "debug")
            return parseApiBody(body, apiUrl)
        } catch (e: Exception) {
            Logger.log("  [API] 请求异常: ${e.message}", "warn")
            return ApiData()
        }
    }

    private fun parseApiBody(body: String, @Suppress("UNUSED_PARAMETER") apiUrl: String): ApiData {
        val videoUrls = mutableListOf<String>()
        val imageUrls = mutableListOf<String>()
        var author = ""
        var authorCode = ""
        var title = ""

        val json = try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            Logger.log("  [API] JSON 解析失败: ${e.message}, body前100字符: ${body.take(100)}", "warn")
            return ApiData()
        }

        val root = safeGetObject(json, "data") ?: json

        // 1) aweme_detail 直接路径
        var awemeDetail = safeGetObject(root, "aweme_detail")
        if (awemeDetail == null) {
            val awemeObj = safeGetObject(root, "aweme")
            awemeDetail = safeGetObject(awemeObj, "detail")
        }

        // 2) aweme_list 数组（取第一条）
        if (awemeDetail == null) {
            val awemeList = safeGetArray(root, "aweme_list")
            if (awemeList != null && awemeList.size() > 0) {
                val first = awemeList.get(0)
                if (first is JsonObject) {
                    awemeDetail = safeGetObject(first, "aweme_info") ?: first
                }
            }
        }

        if (awemeDetail != null) {
            Logger.log("  [API解析] 找到 aweme_detail", "debug")
            val video = safeGetObject(awemeDetail, "video")
            if (video != null) {
                // download_addr（无水印原画，最高优先级，对应 Python 版）
                extractUrlList(safeGetObject(video, "download_addr"), null, videoUrls)

                // play_addr（播放地址）
                extractUrlList(video, "play_addr", videoUrls)

                // play_addr_h264（备用）
                extractUrlList(video, "play_addr_h264", videoUrls)

                // bit_rate 数组（多码率，取最高清）
                val bitRate = safeGetArray(video, "bit_rate")
                if (bitRate != null && bitRate.size() > 0) {
                    for (br in bitRate) {
                        if (br is JsonObject) {
                            extractUrlList(br, "play_addr", videoUrls)
                        }
                    }
                }
            }

            // 图片 URL（图集）
            val images = safeGetArray(awemeDetail, "images")
            if (images != null) {
                for (img in images) {
                    if (img is JsonObject) {
                        val urlList = safeGetArray(img, "url_list")
                            ?: safeGetArray(img, "download_url_list")
                        if (urlList != null && urlList.size() > 0) {
                            for (u in urlList) {
                                if (u.asString.startsWith("http")) {
                                    imageUrls.add(u.asString)
                                }
                            }
                        }
                    }
                }
            }

            // 封面
            val coverObj = safeGetObject(video, "cover")
            if (coverObj != null) {
                extractUrlList(coverObj, null, imageUrls)
            }

            // 作者信息
            val authorObj = safeGetObject(awemeDetail, "author")
                ?: safeGetObject(awemeDetail, "author_info")
            if (authorObj != null) {
                author = authorObj.get("nickname")?.asString ?: ""
                authorCode = authorObj.get("unique_id")?.asString
                    ?: authorObj.get("short_id")?.asString
                    ?: ""
            }

            title = awemeDetail.get("desc")?.asString ?: ""
        }

        // 3) note_detail 路径（笔记/图集）
        val noteDetail = safeGetObject(root, "note_detail")
            ?: safeGetObject(root, "note")
        if (noteDetail != null) {
            val images = safeGetArray(noteDetail, "images")
                ?: safeGetObject(noteDetail, "image_list")?.let { safeGetArray(it, "images") }
            if (images != null) {
                for (img in images) {
                    if (img is JsonObject) {
                        val urlList = safeGetArray(img, "url_list")
                            ?: safeGetArray(img, "download_url_list")
                        if (urlList != null && urlList.size() > 0) {
                            for (u in urlList) {
                                if (u.asString.startsWith("http")) {
                                    imageUrls.add(u.asString)
                                }
                            }
                        }
                    }
                }
            }

            val authorObj = safeGetObject(noteDetail, "author")
            if (authorObj != null) {
                if (author.isEmpty()) {
                    author = authorObj.get("nickname")?.asString ?: ""
                }
                if (authorCode.isEmpty()) {
                    authorCode = authorObj.get("unique_id")?.asString
                        ?: authorObj.get("short_id")?.asString
                        ?: ""
                }
            }

            if (title.isEmpty()) {
                title = noteDetail.get("desc")?.asString ?: ""
            }
        }

        // 去重视频 URL
        val dedupedVideos = videoUrls.distinct()

        Logger.log("  [API解析] 视频: ${dedupedVideos.size}, 图片: ${imageUrls.size}, 作者: $author")
        return ApiData(
            videoUrls = dedupedVideos,
            imageUrls = imageUrls,
            author = author,
            authorCode = authorCode,
            title = title
        )
    }

    private fun extractUrlList(parent: JsonObject?, field: String?, urls: MutableList<String>) {
        if (parent == null) return
        val obj = if (field != null) safeGetObject(parent, field) ?: return else parent
        val urlList = safeGetArray(obj, "url_list")
        if (urlList != null && urlList.size() > 0) {
            for (url in urlList) {
                val s = url.asString
                if (s.isNotBlank() && s.startsWith("http")) {
                    urls.add(s)
                }
            }
        }
    }

    private fun safeGetObject(json: JsonObject?, key: String): JsonObject? {
        if (json == null) return null
        val elem = json.get(key) ?: return null
        if (elem is JsonNull) return null
        return try {
            elem.asJsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun safeGetArray(json: JsonObject?, key: String): JsonArray? {
        if (json == null) return null
        val elem = json.get(key) ?: return null
        if (elem is JsonNull) return null
        return try {
            elem.asJsonArray
        } catch (_: Exception) {
            null
        }
    }

    data class ApiData(
        val videoUrls: List<String> = emptyList(),
        val imageUrls: List<String> = emptyList(),
        val author: String = "",
        val authorCode: String = "",
        val title: String = ""
    )
}