package com.dy.scraper.core

import com.dy.scraper.entity.DownloadResult
import com.dy.scraper.entity.PageContext
import com.dy.scraper.util.AppConfig
import com.dy.scraper.util.ImageDedupChecker
import com.dy.scraper.util.Logger
import com.dy.scraper.util.Utils
import com.dy.scraper.util.VideoDedupChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Downloader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(AppConfig.DOWNLOAD_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
        .readTimeout(AppConfig.DOWNLOAD_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ── 同步下载单个文件 ──
    private fun downloadFileSync(
        url: String,
        savePath: File,
        referer: String = ""
    ): Triple<Boolean, String, String> {
        try {
            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", AppConfig.USER_AGENT)
                .header("Accept", "*/*")

            if (referer.isNotEmpty()) {
                requestBuilder.header("Referer", referer)
            }

            val response = client.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                return Triple(false, "HTTP ${response.code}: ${response.message}", "")
            }

            val body = response.body ?: return Triple(false, "响应体为空", "")

            // 流式读取，边读边写，同时检查文件大小
            val data: ByteArray
            body.source().use { source ->
                val buffer = okio.Buffer()
                var totalRead: Long = 0
                while (!source.exhausted()) {
                    val read = source.read(buffer, 8192)
                    totalRead += read
                    if (totalRead > AppConfig.MAX_FILE_SIZE) {
                        response.close()
                        if (savePath.exists()) savePath.delete()
                        return Triple(false, "超过${Utils.formatBytes(AppConfig.MAX_FILE_SIZE)}限制(${Utils.formatBytes(totalRead)})", "")
                    }
                }
                data = buffer.readByteArray()
            }

            val md5 = Utils.md5(data)

            // 写入文件
            savePath.parentFile?.mkdirs()
            savePath.outputStream().use { it.write(data) }

            if (!savePath.exists() || savePath.length() == 0L) {
                return Triple(false, "文件写入后消失（可能被安全软件拦截）", "")
            }

            return Triple(true, Utils.formatBytes(savePath.length()), md5)
        } catch (e: Exception) {
            if (savePath.exists()) Utils.safeDelete(savePath)
            return Triple(false, e.message ?: "未知错误", "")
        }
    }

    // ── 并发下载（对应 download_files） ──
    suspend fun downloadFiles(
        ctx: PageContext,
        saveDir: File,
        fileType: String,
        maxWorkers: Int = 5,
        md5Registry: MutableSet<String>? = null,
        videoHashRegistry: MutableMap<String, List<String>>? = null,
    ): List<DownloadResult> = withContext(Dispatchers.IO) {

        val urls = if (fileType == "video") ctx.videoUrls else ctx.imageUrls
        if (urls.isEmpty()) return@withContext emptyList()

        val registry = md5Registry ?: mutableSetOf()
        val vReg = videoHashRegistry ?: mutableMapOf()

        saveDir.mkdirs()
        val results = mutableListOf<DownloadResult>()
        val safeTitle = Utils.cleanTitle(ctx.title)
        val safeAuthor = Utils.cleanTitle(ctx.author).takeIf { it.isNotEmpty() } ?: ""
        val downloadTime = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val referer = ctx.finalUrl

        val tasks = urls.mapIndexed { i, url ->
            val ext = Utils.extractExtFromUrl(url, if (fileType == "video") ".mp4" else ".jpg")
            val prefix = if (safeAuthor.isNotEmpty()) "${safeAuthor}_" else ""
            val fileName = "${prefix}${safeTitle}_${downloadTime}_${i}${ext}"
            val savePath = File(saveDir, fileName)

            Logger.log("  [${i + 1}/${urls.size}] 下载中: ${url.take(80)}...")

            // 预检查：跳过已知的重复 MD5（通过 URL 快速判断）
            // 注意：这里无法在下载前判断 MD5，只能下载后判断

            async {
                val (success, info, md5) = downloadFileSync(url, savePath, referer)

                if (!success) {
                    Logger.log("  [${i + 1}/${urls.size}] 失败: $info")
                    return@async DownloadResult(
                        name = "", url = url, path = "", size = "",
                        md5 = "", status = "failed", error = info
                    )
                }

                // 检查文件大小
                val actualSize = savePath.length()
                if (actualSize < AppConfig.MIN_FILE_SIZE) {
                    Utils.safeDelete(savePath)
                    Logger.log("  [${i + 1}/${urls.size}] 删除 (小于${Utils.formatBytes(AppConfig.MIN_FILE_SIZE)}): $fileName")
                    return@async DownloadResult(
                        name = fileName, url = url, path = "", size = Utils.formatBytes(actualSize),
                        md5 = md5, status = "skipped_small"
                    )
                }

                // MD5 去重
                if (md5 in registry) {
                    Utils.safeDelete(savePath)
                    Logger.log("  [${i + 1}/${urls.size}] 跳过 (MD5重复): $fileName")
                    return@async DownloadResult(
                        name = fileName, url = url, path = "", size = info,
                        md5 = md5, status = "skipped_duplicate"
                    )
                }
                registry.add(md5)

                // 视频去重（pHash 指纹）
                if (fileType == "video") {
                    if (VideoDedupChecker.checkAndDedup(savePath, vReg)) {
                        if (!savePath.exists()) {
                            Logger.log("  [${i + 1}/${urls.size}] 删除 (视频指纹重复-较小): $fileName")
                            return@async DownloadResult(
                                name = fileName, url = url, path = "", size = info,
                                md5 = md5, status = "skipped_video_phash_dup"
                            )
                        }
                    }
                }

                // 图片表情包过滤
                if (fileType == "image" && ImageDedupChecker.isEmojiByDimensions(savePath)) {
                    Utils.safeDelete(savePath)
                    Logger.log("  [${i + 1}/${urls.size}] 删除 (表情包): $fileName")
                    return@async DownloadResult(
                        name = fileName, url = url, path = "", size = info,
                        md5 = md5, status = "skipped_emoji"
                    )
                }

                // 图片 pHash 去重
                if (fileType == "image" && ImageDedupChecker.checkAndDedup(savePath, saveDir)) {
                    if (!savePath.exists()) {
                        Logger.log("  [${i + 1}/${urls.size}] 删除 (pHash重复-较小): $fileName")
                        return@async DownloadResult(
                            name = fileName, url = url, path = "", size = info,
                            md5 = md5, status = "skipped_phash_dup"
                        )
                    }
                }

                Logger.log("  [${i + 1}/${urls.size}] 完成: $fileName ($info)")
                DownloadResult(
                    name = fileName, url = url, path = savePath.absolutePath,
                    size = info, md5 = md5, status = "downloaded"
                )
            }
        }

        // 限制并发数
        val chunked = tasks.chunked(maxWorkers)
        for (chunk in chunked) {
            val batchResults = awaitAll(*chunk.toTypedArray())
            results.addAll(batchResults)
        }

        return@withContext results
    }

    /**
     * 从网络请求列表提取 URL（对应 extract_urls_from_network）
     */
    fun extractUrlsFromNetwork(collectedRequests: List<Map<String, String>>): Pair<List<String>, List<String>> {
        val videoPatterns = listOf(".mp4?", ".webm?", ".mov?", ".m3u8?", "video/mp4", "video/webm")
        val videoDomains = listOf("douyinvod.com", "ixigua.com", "snssdk.com")
        val imagePatterns = listOf(".jpg?", ".jpeg?", ".png?", ".webp?", ".gif?", "image/")

        val videoUrls = mutableListOf<String>()
        val imageUrls = mutableListOf<String>()

        for (req in collectedRequests) {
            val url = req["url"] ?: continue
            val contentType = req["contentType"] ?: ""

            if (url.startsWith("blob:")) continue
            if (Utils.isUiAsset(url)) continue

            val lower = url.lowercase()
            when {
                videoPatterns.any { it in lower || it in contentType } -> videoUrls.add(url)
                videoDomains.any { it in lower } -> videoUrls.add(url)
                imagePatterns.any { it in lower || it in contentType } -> imageUrls.add(url)
            }
        }

        val result = Pair(videoUrls.distinct(), imageUrls.distinct())
        Logger.log("  网络请求提取: ${result.first.size} 视频 + ${result.second.size} 图片 (从 ${collectedRequests.size} 个请求中)", "debug")
        return result
    }

    /**
     * 视频 URL 去重（按 URL 模式去重，因为同一视频可能有多个清晰度版本）
     */
    fun deduplicateVideos(urls: List<String>): List<String> {
        if (urls.size <= 1) return urls

        // 去掉 query string 去重，保留最后一个（通常是最清晰的）
        val map = linkedMapOf<String, String>()
        for (url in urls) {
            val key = url.substringBefore("?")
            map[key] = url
        }
        val result = map.values.toList()
        Logger.log("  视频URL去重: ${urls.size} → ${result.size}", "debug")
        return result
    }

    /**
     * 图片按质量排序（优先大尺寸/高清晰度）
     */
    fun sortImagesByQuality(urls: List<String>): List<String> {
        // 简单策略：webp/png 优先于 jpg
        val priority = mapOf("webp" to 3, "png" to 2, "jpeg" to 1, "jpg" to 0)
        val result = urls.sortedByDescending { url ->
            val ext = url.substringBefore("?").substringAfterLast(".").lowercase()
            priority[ext] ?: 0
        }
        Logger.log("  图片质量排序完成: ${result.size} 个", "debug")
        return result
    }
}