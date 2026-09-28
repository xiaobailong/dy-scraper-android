package com.dy.scraper.api

import com.dy.scraper.util.AppConfig
import com.dy.scraper.util.Logger
import com.google.gson.Gson
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

    /**
     * 判断 URL 是否为抖音详情 API 响应
     */
    fun isDetailApiResponse(url: String): Boolean {
        return AppConfig.DETAIL_API_PATTERNS.any { it in url }
    }

    /**
     * 通过 OkHttp 同步请求 API 并解析 JSON，提取视频/图片 URL
     * （因为 WebView.shouldInterceptRequest 只能拿到 URL，拿不到响应体）
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
                Logger.log("  [API] 请求失败 HTTP ${response.code}: $apiUrl", "warn")
                return ApiData()
            }

            val body = response.body?.string() ?: return ApiData()
            val json = try {
                JsonParser.parseString(body).asJsonObject
            } catch (e: Exception) {
                Logger.log("  [API] JSON 解析失败: ${e.message}", "warn")
                return ApiData()
            }

            val videoUrls = mutableListOf<String>()
            val imageUrls = mutableListOf<String>()
            var author = ""
            var authorCode = ""
            var title = ""

            // 尝试 aweme_detail 路径
            val awemeDetail = json.getAsJsonObject("aweme_detail")
                ?: json.getAsJsonObject("aweme")?.getAsJsonObject("detail")

            if (awemeDetail != null) {
                // 视频 URL
                val video = awemeDetail.getAsJsonObject("video")
                if (video != null) {
                    val playAddr = video.getAsJsonObject("play_addr")
                    if (playAddr != null) {
                        val urlList = playAddr.getAsJsonArray("url_list")
                        if (urlList != null && urlList.size() > 0) {
                            videoUrls.addAll(urlList.map { it.asString })
                        }
                    }
                }

                // 图片 URL（图集）
                val images = awemeDetail.getAsJsonArray("images")
                if (images != null) {
                    for (img in images) {
                        val urlList = img.asJsonObject.getAsJsonArray("url_list")
                        if (urlList != null && urlList.size() > 0) {
                            imageUrls.add(urlList.last().asString) // 最大尺寸
                        }
                    }
                }

                // 封面
                val cover = awemeDetail.getAsJsonObject("video")?.getAsJsonObject("cover")
                if (cover != null) {
                    val coverUrlList = cover.getAsJsonArray("url_list")
                    if (coverUrlList != null && coverUrlList.size() > 0) {
                        imageUrls.add(coverUrlList.last().asString)
                    }
                }

                // 作者信息
                val authorObj = awemeDetail.getAsJsonObject("author")
                    ?: awemeDetail.getAsJsonObject("author_info")
                if (authorObj != null) {
                    author = authorObj.get("nickname")?.asString ?: ""
                    authorCode = authorObj.get("unique_id")?.asString
                        ?: authorObj.get("short_id")?.asString
                        ?: ""
                }

                title = awemeDetail.get("desc")?.asString ?: ""
            }

            // 尝试 note_detail 路径（笔记/图集）
            val noteDetail = json.getAsJsonObject("note_detail")
                ?: json.getAsJsonObject("note")
            if (noteDetail != null && imageUrls.isEmpty()) {
                val images = noteDetail.getAsJsonArray("images")
                    ?: noteDetail.getAsJsonObject("image_list")?.getAsJsonArray("images")
                if (images != null) {
                    for (img in images) {
                        val urlList = img.asJsonObject.getAsJsonArray("url_list")
                        if (urlList != null && urlList.size() > 0) {
                            imageUrls.add(urlList.last().asString)
                        }
                    }
                }

                val authorObj = noteDetail.getAsJsonObject("author")
                if (authorObj != null) {
                    author = authorObj.get("nickname")?.asString ?: author
                    authorCode = authorObj.get("unique_id")?.asString
                        ?: authorObj.get("short_id")?.asString
                        ?: authorCode
                }

                title = noteDetail.get("desc")?.asString ?: title
            }

            Logger.log("  [API解析] 视频: ${videoUrls.size}, 图片: ${imageUrls.size}, 作者: $author")
            return ApiData(
                videoUrls = videoUrls,
                imageUrls = imageUrls,
                author = author,
                authorCode = authorCode,
                title = title
            )
        } catch (e: Exception) {
            Logger.log("  [API] 请求异常: ${e.message}", "warn")
            return ApiData()
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