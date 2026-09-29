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
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Downloader {

    private val client: OkHttpClient by lazy {
        val timeout = AppConfig.getDownloadTimeoutSeconds(com.dy.scraper.ScraperApp.instance)
        OkHttpClient.Builder()
            .connectTimeout(timeout.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeout.toLong(), TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    // ── 视频 / 图片 URL 特征（对应 Python downloader.py） ──
    private val VIDEO_EXTS = listOf(".mp4", ".webm", ".mov", ".m4v", ".flv", ".3gp", ".ts")
    private val VIDEO_MIME_MARKERS = listOf("mime_type=video_mp4", "video/mp4", "video/webm", "video/quicktime")
    private val VIDEO_DOMAINS = listOf("douyinvod.com", "ixigua.com", "snssdk.com", "bytecdn", "vod-", "zjcdn.com")
    private val IMAGE_EXTS = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif", ".heic", ".bmp")
    private val IMAGE_DOMAINS = listOf("douyinpic.com", "pstatp.com", "byteimg.com")

    private data class DownloadJob(val url: String, val fileName: String, val savePath: File)

    // ── 获取文件大小（HEAD 请求，对应 Python get_file_size） ──
    private fun getFileSize(url: String): Long? {
        return try {
            val headRequest = Request.Builder()
                .url(url)
                .method("HEAD", null)
                .header("User-Agent", AppConfig.USER_AGENT)
                .build()
            client.newCall(headRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val length = response.header("Content-Length")
                    length?.toLongOrNull()
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ── 单文件下载（流式写盘，避免大视频占满内存；带 Cookie/Referer；失败重试 1 次） ──
    internal fun downloadFileSync(
        url: String,
        savePath: File,
        referer: String = "",
        cookies: String = ""
    ): Triple<Boolean, String, String> {
        var lastError = "未知错误"
        val tmpFile = File(savePath.parentFile, savePath.name + ".part")

        for (attempt in 0..1) {
            try {
                val requestBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", AppConfig.USER_AGENT)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")

                if (referer.isNotEmpty()) requestBuilder.header("Referer", referer)
                if (cookies.isNotEmpty()) requestBuilder.header("Cookie", cookies)

                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = "HTTP ${response.code} ${response.message}"
                        return@use
                    }
                    val body = response.body
                    if (body == null) {
                        lastError = "响应体为空"
                        return@use
                    }

                    val digest = MessageDigest.getInstance("MD5")
                    var total = 0L
                    var overflow = false

                    tmpFile.parentFile?.mkdirs()
                    body.byteStream().use { input ->
                        tmpFile.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n <= 0) break
                                total += n
                                if (total > AppConfig.MAX_FILE_SIZE) {
                                    overflow = true
                                    break
                                }
                                digest.update(buffer, 0, n)
                                output.write(buffer, 0, n)
                            }
                            output.flush()
                        }
                    }

                    if (overflow) {
                        lastError = "超过 ${Utils.formatBytes(AppConfig.MAX_FILE_SIZE)} 限制"
                        Utils.safeDelete(tmpFile)
                        return@use
                    }
                    if (total <= 0L) {
                        lastError = "响应内容为空"
                        Utils.safeDelete(tmpFile)
                        return@use
                    }

                    val md5 = digest.digest().joinToString("") { "%02x".format(it) }
                    Utils.safeDelete(savePath)
                    if (!Utils.safeRename(tmpFile, savePath)) {
                        Utils.safeDelete(tmpFile)
                        lastError = "临时文件重命名失败"
                        return@use
                    }
                    if (!savePath.exists() || savePath.length() == 0L) {
                        lastError = "文件写入后消失（可能被安全软件拦截）"
                        return@use
                    }
                    return Triple(true, Utils.formatBytes(savePath.length()), md5)
                }
            } catch (e: Exception) {
                lastError = e.message ?: "未知错误"
            }

            Utils.safeDelete(tmpFile)
            if (attempt == 0) {
                Logger.log("  下载失败($lastError)，重试一次: ${url.take(60)}...", "warn")
            }
        }

        return Triple(false, lastError, "")
    }

    // ── 并发下载（对应 download_files，按批次限制并发） ──
    suspend fun downloadFiles(
        ctx: PageContext,
        saveDir: File,
        fileType: String,
        maxWorkers: Int = 5,
        md5Registry: MutableSet<String>? = null,
        videoHashRegistry: MutableMap<String, List<String>>? = null,
        cookies: String = "",
    ): List<DownloadResult> = withContext(Dispatchers.IO) {

        val rawUrls = if (fileType == "video") ctx.videoUrls else ctx.imageUrls
        if (rawUrls.isEmpty()) return@withContext emptyList()

        // 视频：同一文件只保留最高码率，避免同一视频下载多份
        val urls = if (fileType == "video") deduplicateVideos(rawUrls) else rawUrls

        val registry = md5Registry ?: mutableSetOf()
        val vReg = videoHashRegistry ?: mutableMapOf()

        saveDir.mkdirs()
        val safeTitle = Utils.cleanTitle(ctx.title)
        val safeAuthor = Utils.cleanTitle(ctx.author).takeIf { it.isNotEmpty() } ?: ""
        val downloadTime = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val referer = ctx.finalUrl

        val jobs = urls.mapIndexed { i, url ->
            val ext = Utils.extractExtFromUrl(url, if (fileType == "video") ".mp4" else ".jpg")
            val prefix = if (safeAuthor.isNotEmpty()) "${safeAuthor}_" else ""
            val fileName = "${prefix}${safeTitle}_${downloadTime}_${i}${ext}"
            DownloadJob(url, fileName, File(saveDir, fileName))
        }

        val results = mutableListOf<DownloadResult>()
        val batchSize = maxWorkers.coerceAtLeast(1)
        for (batch in jobs.indices.chunked(batchSize)) {
            val batchResults = coroutineScope {
                batch.map { i ->
                    async { downloadOne(jobs[i], i, jobs.size, fileType, saveDir, registry, vReg, referer, cookies) }
                }.awaitAll()
            }
            results.addAll(batchResults)
        }

        return@withContext results
    }

    private fun downloadOne(
        job: DownloadJob,
        index: Int,
        total: Int,
        fileType: String,
        saveDir: File,
        registry: MutableSet<String>,
        vReg: MutableMap<String, List<String>>,
        referer: String,
        cookies: String,
    ): DownloadResult {
        val url = job.url
        val savePath = job.savePath
        val fileName = job.fileName

        // 下载前 HEAD 请求检查 Content-Length（对齐 Python get_file_size 预检）
        val fileSize = getFileSize(url)
        val minSizeFilter = if (fileType == "video")
            AppConfig.getVideoSizeFilterBytes(com.dy.scraper.ScraperApp.instance)
        else
            AppConfig.getImageSizeFilterBytes(com.dy.scraper.ScraperApp.instance)
        if (fileSize != null && fileSize < minSizeFilter) {
            Logger.log("  [${index + 1}/$total] 跳过 (小于${Utils.formatBytes(minSizeFilter)}): ${url.take(80)}...")
            return DownloadResult(
                name = "", url = url, path = "", size = Utils.formatBytes(fileSize),
                md5 = "", status = "skipped_small"
            )
        }

        Logger.log("  [${index + 1}/$total] 下载中: ${url.take(80)}...")

        val (success, info, md5) = downloadFileSync(url, savePath, referer, cookies)
        if (!success) {
            Logger.log("  [${index + 1}/$total] 失败: $info")
            return DownloadResult(
                name = "", url = url, path = "", size = "", md5 = "",
                status = "failed", error = info
            )
        }

        val actualSize = savePath.length()
        if (actualSize < minSizeFilter) {
            Utils.safeDelete(savePath)
            Logger.log("  [${index + 1}/$total] 删除 (小于${Utils.formatBytes(minSizeFilter)}): $fileName")
            return DownloadResult(
                name = fileName, url = url, path = "", size = Utils.formatBytes(actualSize),
                md5 = md5, status = "skipped_small"
            )
        }

        // MD5 去重（跨页面、跨并发线程；加锁避免竞态导致重复下载）
        val duplicated = synchronized(registry) {
            if (md5 in registry) true else {
                registry.add(md5)
                false
            }
        }
        if (duplicated) {
            Utils.safeDelete(savePath)
            Logger.log("  [${index + 1}/$total] 跳过 (MD5重复): $fileName")
            return DownloadResult(
                name = fileName, url = url, path = "", size = info,
                md5 = md5, status = "skipped_duplicate"
            )
        }

        // 视频指纹去重
        if (fileType == "video") {
            val removed = synchronized(vReg) { VideoDedupChecker.checkAndDedup(savePath, vReg) }
            if (removed && !savePath.exists()) {
                Logger.log("  [${index + 1}/$total] 删除 (视频指纹重复-较小): $fileName")
                return DownloadResult(
                    name = fileName, url = url, path = "", size = info,
                    md5 = md5, status = "skipped_video_phash_dup"
                )
            }
        }

        // 图片表情包过滤
        if (fileType == "image" && ImageDedupChecker.isEmojiByDimensions(savePath)) {
            Utils.safeDelete(savePath)
            Logger.log("  [${index + 1}/$total] 删除 (表情包): $fileName")
            return DownloadResult(
                name = fileName, url = url, path = "", size = info,
                md5 = md5, status = "skipped_emoji"
            )
        }

        // 图片 pHash 去重
        if (fileType == "image") {
            val removed = ImageDedupChecker.checkAndDedup(savePath, saveDir)
            if (removed && !savePath.exists()) {
                Logger.log("  [${index + 1}/$total] 删除 (pHash重复-较小): $fileName")
                return DownloadResult(
                    name = fileName, url = url, path = "", size = info,
                    md5 = md5, status = "skipped_phash_dup"
                )
            }
        }

        Logger.log("  [${index + 1}/$total] 完成: $fileName ($info)")
        return DownloadResult(
            name = fileName, url = url, path = savePath.absolutePath,
            size = info, md5 = md5, status = "downloaded"
        )
    }

    // ── 从网络请求提取媒体 URL（对应 extract_urls_from_network） ──
    fun extractUrlsFromNetwork(collectedRequests: List<Map<String, String>>): Pair<List<String>, List<String>> {
        val videoUrls = mutableListOf<String>()
        val imageUrls = mutableListOf<String>()

        for (req in collectedRequests) {
            val url = req["url"] ?: continue
            val contentType = req["contentType"] ?: ""

            if (url.startsWith("blob:") || url.startsWith("data:")) continue
            if (url.contains("1x1")) continue
            if (Utils.isUiAsset(url)) continue
            if (Utils.isAudioUrl(url)) continue

            if (isVideoUrl(url, contentType)) {
                if (url !in videoUrls) videoUrls.add(url)
            } else if (isImageUrl(url, contentType)) {
                if (url !in imageUrls) imageUrls.add(url)
            }
        }

        Logger.log(
            "  网络请求提取: ${videoUrls.size} 视频 + ${imageUrls.size} 图片 (从 ${collectedRequests.size} 个请求中)",
            "debug"
        )
        return Pair(videoUrls, imageUrls)
    }

    /** 视频 URL 判定：抖音视频流地址通常没有 .mp4 后缀（形如 /xxx/video/tos/cn/...?a=6383&br=...） */
    fun isVideoUrl(url: String, contentType: String = ""): Boolean {
        val lower = url.lowercase()
        val path = lower.substringBefore("?").substringBefore("#")
        if (VIDEO_EXTS.any { path.endsWith(it) }) return true
        val ct = contentType.lowercase()
        if (ct.startsWith("video/") || ct.contains("mpegurl")) return true
        if (VIDEO_MIME_MARKERS.any { it in lower }) return true
        return VIDEO_DOMAINS.any { it in lower }
    }

    fun isImageUrl(url: String, contentType: String = ""): Boolean {
        val lower = url.lowercase()
        val path = lower.substringBefore("?").substringBefore("#")
        if (IMAGE_EXTS.any { path.endsWith(it) }) return true
        if (contentType.lowercase().startsWith("image/")) return true
        return IMAGE_DOMAINS.any { it in lower }
    }

    /**
     * 视频 URL 去重：同一 file_id 只保留最高码率（对应 Python deduplicate_videos），按码率降序
     */
    fun deduplicateVideos(urls: List<String>): List<String> {
        if (urls.size <= 1) return urls

        val groups = LinkedHashMap<String, Pair<String, Int>>()
        for (url in urls) {
            val key = videoFileId(url)
            val br = videoQualityScore(url)
            val exist = groups[key]
            if (exist == null || br > exist.second) groups[key] = url to br
        }
        val result = groups.values.sortedByDescending { it.second }.map { it.first }
        Logger.log("  视频URL去重: ${urls.size} → ${result.size} (按 file_id/br)", "debug")
        return result
    }

    /** 提取视频文件标识（URL 路径中 `?` 前的最后一段，兼容末尾斜杠） */
    fun videoFileId(url: String): String {
        val m = Regex("/([^/?]+)/?\\?").find(url)
        return m?.groupValues?.get(1) ?: url
    }

    fun videoQualityScore(url: String): Int {
        return Regex("[?&]br=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /**
     * 图片按质量排序 + 同图去重（对应 Python sort_images_by_quality）
     * 同一张图的不同分辨率/格式只保留最高分，并过滤垃圾缩略图
     */
    fun sortImagesByQuality(urls: List<String>): List<String> {
        val groups = LinkedHashMap<String, MutableList<Pair<String, Int>>>()
        for (u in urls) {
            groups.getOrPut(imageIdentity(u)) { mutableListOf() }.add(u to imageQualityScore(u))
        }

        val minArea = 200 * 200
        val result = mutableListOf<String>()
        for ((_, candidates) in groups) {
            val best = candidates.maxByOrNull { it.second } ?: continue
            if (best.second in 1 until minArea) continue
            result.add(best.first)
        }
        Logger.log("  图片质量排序完成: ${urls.size} → ${result.size} (同图去重/去缩略图)", "debug")
        return result
    }

    /** 图片身份标识：去掉尺寸标记、tplv 模板标记、扩展名与查询参数 */
    fun imageIdentity(url: String): String {
        var path = url.substringBefore("?").substringBefore("#")
        path = path.replace(Regex("[~_](\\d{2,4})x(\\d{2,4})"), "")
        path = path.replace(Regex("~tplv-[a-z0-9\\-]+"), "")
        path = path.replace(Regex("\\.[a-z0-9]+$", RegexOption.IGNORE_CASE), "")
        return path
    }

    /** 从 URL 尺寸标记估算图片质量（越高越好） */
    fun imageQualityScore(url: String): Int {
        val m = Regex("[~_](\\d{2,4})x(\\d{2,4})").find(url) ?: return 0
        val w = m.groupValues[1].toIntOrNull() ?: 0
        val h = m.groupValues[2].toIntOrNull() ?: 0
        return w * h
    }
}