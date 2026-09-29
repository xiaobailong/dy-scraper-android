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
                Logger.log("  [API] 请求失败 HTTP ${response.code}: ${apiUrl.take(100)}...", "warn")
                return ApiData()
            }

            val body = response.body?.string() ?: return ApiData()
            Logger.log("  [API] 响应体长度: ${body.length}", "debug")

            val json = try {
                JsonParser.parseString(body).asJsonObject
            } catch (e: Exception) {
                Logger.log("  [API] JSON 解析失败: ${e.message}, body前100字符: ${body.take(100)}", "warn")
                return ApiData()
            }

            val videoUrls = mutableListOf<String>()
            val imageUrls = mutableListOf<String>()
            var author = ""
            var authorCode = ""
            var title = ""

            // 支持 data 包裹层: {"data": {"aweme_detail": {...}}}
            val root = json.getAsJsonObject("data") ?: json

            // 1) 尝试 aweme_detail 直接路径
            var awemeDetail = root.getAsJsonObject("aweme_detail")
                ?: root.getAsJsonObject("aweme")?.getAsJsonObject("detail")

            // 2) 尝试 aweme_list 数组（取第一条）
            if (awemeDetail == null) {
                val awemeList = root.getAsJsonArray("aweme_list")
                if (awemeList != null && awemeList.size() > 0) {
                    awemeDetail = awemeList.get(0).asJsonObject.getAsJsonObject("aweme_info")
                        ?: awemeList.get(0).asJsonObject
                }
            }

            if (awemeDetail != null) {
                Logger.log("  [API解析] 找到 aweme_detail", "debug")
                val video = awemeDetail.getAsJsonObject("video")
                if (video != null) {
                    // play_addr (无水印)
                    extractUrlList(video, "play_addr", videoUrls)
                    // play_addr_h264 (备用)
                    extractUrlList(video, "play_addr_h264", videoUrls)
                    // bit_rate 数组（多码率，取最高清）
                    val bitRate = video.getAsJsonArray("bit_rate")
                    if (bitRate != null && bitRate.size() > 0) {
                        for (br in bitRate) {
                            extractUrlList(br.asJsonObject, "play_addr", videoUrls)
                        }
                    }
                }

                // 图片 URL（图集）
                val images = awemeDetail.getAsJsonArray("images")
                if (images != null) {
                    for (img in images) {
                        val urlList = img.asJsonObject.getAsJsonArray("url_list")
                        if (urlList != null && urlList.size() > 0) {
                            imageUrls.add(urlList.last().asString)
                        }
                    }
                }

                // 封面
                extractUrlList(video?.getAsJsonObject("cover"), null, imageUrls)
                val coverObj = awemeDetail.getAsJsonObject("video")?.getAsJsonObject("cover")
                if (coverObj != null) {
                    val coverUrlList = coverObj.getAsJsonArray("url_list")
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

            // 3) 尝试 note_detail 路径（笔记/图集）
            if (awemeDetail == null || imageUrls.isEmpty()) {
                val noteDetail = root.getAsJsonObject("note_detail")
                    ?: root.getAsJsonObject("note")
                if (noteDetail != null) {
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

                    if (title.isEmpty()) {
                        title = noteDetail.get("desc")?.asString ?: ""
                    }
                }
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

    private fun extractUrlList(parent: com.google.gson.JsonObject?, field: String?, urls: MutableList<String>) {
        if (parent == null) return
        val obj = if (field != null) parent.getAsJsonObject(field) ?: return else parent
        val urlList = obj.getAsJsonArray("url_list")
        if (urlList != null && urlList.size() > 0) {
            for (url in urlList) {
                val s = url.asString
                if (s.isNotBlank() && s.startsWith("http")) {
                    urls.add(s)
                }
            }
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