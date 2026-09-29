package com.dy.scraper.core

import android.content.Context
import com.dy.scraper.api.DouyinApiCollector
import com.dy.scraper.data.AppDatabase
import com.dy.scraper.data.entity.ScrapeRecord
import com.dy.scraper.data.entity.SkippedRecord
import com.dy.scraper.data.entity.UrlMapping
import com.dy.scraper.entity.PageContext
import com.dy.scraper.entity.ScrapeStats
import com.dy.scraper.util.AppConfig
import com.dy.scraper.util.ImageDedupChecker
import com.dy.scraper.util.Logger
import com.dy.scraper.util.Utils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UrlProcessor(
    private val context: Context,
    private val db: AppDatabase,
    private val md5Registry: MutableSet<String>,
    private val videoHashRegistry: MutableMap<String, List<String>>,
    private val resultDir: File,
) {
    private val scrapeDao = db.scrapeDao()

    suspend fun process(
        wvm: WebViewManager,
        targetUrl: String,
        urlIdx: Int,
        urlTotal: Int,
        stats: ScrapeStats,
        lastFinalUrl: String?,
    ): Pair<PageContext?, String> {
        val normalizedUrl = Utils.normalizeUrl(targetUrl)

        Logger.log("")
        Logger.log("=".repeat(60))
        Logger.log("  [$urlIdx/$urlTotal] ${targetUrl.take(80)}")
        Logger.log("=".repeat(60))

        // ── 1. goto ──
        Logger.log("[2/6] 访问目标页面...")
        val loaded = wvm.loadUrl(targetUrl)
        val finalUrl = wvm.getFinalUrl()
        Logger.log("  最终跳转地址: $finalUrl")

        if (!loaded) {
            Logger.log("  ⚠️ 页面加载失败，跳过当前 URL")
            scrapeDao.insertSkipped(
                SkippedRecord(
                    shortUrl = normalizedUrl,
                    skipReason = "页面加载失败",
                    createTime = now()
                )
            )
            return Pair(null, lastFinalUrl ?: "")
        }

        // ── 2. 校验 ──
        val (valid, skipReason) = validateUrl(finalUrl, lastFinalUrl)
        if (!valid) {
            scrapeDao.insertSkipped(
                SkippedRecord(
                    shortUrl = normalizedUrl,
                    skipReason = skipReason,
                    createTime = now()
                )
            )
            return Pair(null, lastFinalUrl ?: "")
        }

        // 记录请求收集的切片起点
        val reqStart = wvm.collectedRequests.size
        val detailStart = wvm.detailResponses.size

        // ── 3. 等待渲染 ──
        Logger.log("[3/6] 等待页面内容渲染...")
        wvm.waitForRender()

        // ── 4. 创建上下文 ──
        val ctx = PageContext(shortUrl = targetUrl, finalUrl = finalUrl)
        ctx.pushStage("ctx_create")

        // ── 5. 提取元数据 ──
        Logger.log("[4/6] 提取页面数据...")
        MetadataExtractor.extractFromWebView(wvm, ctx)
        ctx.pushStage("extract_meta")

        // 从网络请求提取 URL
        val newRequests = wvm.collectedRequests.drop(reqStart).toList()
        val (netVideos, netImages) = Downloader.extractUrlsFromNetwork(newRequests)
        ctx.networkVideoUrls = netVideos
        ctx.networkImageUrls = netImages
        ctx.pushStage("extract_network")

        // ── 详情 API 响应（主路径：document-start 钩子旁听页面自己发出的请求体） ──
        val newDetailUrls = wvm.detailResponses.drop(detailStart).toList()
        Logger.log("  拦截到 ${newDetailUrls.size} 个详情 API URL")

        // 延长等待时间：抖音页面可能很晚才发出详情 API 请求（实测 ~17s）
        val bodies = wvm.waitForDetailBodies(AppConfig.getDetailApiWaitMs(context))
        Logger.log("  [主路径] Hook 旁听到 ${bodies.size} 个详情 API 响应体")

        // 第二轮网络请求提取：详情 API 响应到达后，WebView 可能才开始加载视频/图片
        // （douyinvod.com 等 CDN URL 会作为新的网络请求出现）
        val secondPassRequests = wvm.collectedRequests.drop(reqStart + newRequests.size).toList()
        val (netVideos2, netImages2) = Downloader.extractUrlsFromNetwork(secondPassRequests)
        if (netVideos2.isNotEmpty() || netImages2.isNotEmpty()) {
            Logger.log(
                "  第二轮网络提取: ${netVideos2.size} 视频 + ${netImages2.size} 图片 (新增 ${secondPassRequests.size} 个请求)",
                "info"
            )
            ctx.networkVideoUrls = (ctx.networkVideoUrls + netVideos2).distinct()
            ctx.networkImageUrls = (ctx.networkImageUrls + netImages2).distinct()
        }
        if (bodies.isEmpty() && !PageHook.ENABLE_REPLAY_FALLBACK) {
            Logger.log(
                "  ⚠ Hook 未捕获到详情响应体（重放兜底已关闭：重放缺 a_bogus 拿不到 body），" +
                        "只能靠 SSR/DOM/网络请求兜底",
                "warn"
            )
        }
        if (bodies.isNotEmpty()) {
            val apiData = DouyinApiCollector.parseAllBodies(bodies, finalUrl)
            if (apiData.hasMedia) {
                if (apiData.videoUrls.isNotEmpty()) ctx.apiVideoUrls = apiData.videoUrls
                if (apiData.imageUrls.isNotEmpty()) {
                    ctx.apiImageUrls = (ctx.apiImageUrls + apiData.imageUrls).distinct()
                }
                if (apiData.author.isNotEmpty() && ctx.author.isEmpty()) {
                    ctx.author = apiData.author
                    ctx.authorCode = apiData.authorCode
                }
                if (ctx.title.isEmpty() && apiData.title.isNotEmpty()) ctx.title = apiData.title
                if (ctx.coverUrl.isEmpty() && apiData.coverUrl.isNotEmpty()) ctx.coverUrl = apiData.coverUrl
            }
        }

        // 兜底：重放请求（默认关闭 —— 缺 a_bogus 签名，body 恒为空或整页 HTML，还会二次触发风控）
        if (PageHook.ENABLE_REPLAY_FALLBACK && ctx.apiVideoUrls.isEmpty() && ctx.apiImageUrls.isEmpty()) {
            val distinctApiUrls = newDetailUrls.distinct().take(6)
            for (apiUrl in distinctApiUrls) {
                val apiData = DouyinApiCollector.fetchAndParseApiResponseViaJs(wvm, apiUrl, finalUrl)
                if (apiData.hasMedia) {
                    if (apiData.videoUrls.isNotEmpty()) ctx.apiVideoUrls = apiData.videoUrls
                    if (apiData.imageUrls.isNotEmpty()) {
                        ctx.apiImageUrls = (ctx.apiImageUrls + apiData.imageUrls).distinct()
                    }
                    if (apiData.author.isNotEmpty() && ctx.author.isEmpty()) {
                        ctx.author = apiData.author
                        ctx.authorCode = apiData.authorCode
                    }
                    if (ctx.title.isEmpty() && apiData.title.isNotEmpty()) ctx.title = apiData.title
                    if (apiData.videoUrls.isNotEmpty()) break
                }
            }
        }
        if (PageHook.ENABLE_REPLAY_FALLBACK && ctx.apiVideoUrls.isEmpty() && newDetailUrls.isNotEmpty()) {
            val okData = DouyinApiCollector.fetchAndParseApiResponse(
                newDetailUrls.first(), wvm.getCookies(), finalUrl
            )
            if (okData.videoUrls.isNotEmpty()) ctx.apiVideoUrls = okData.videoUrls
            if (okData.imageUrls.isNotEmpty() && ctx.apiImageUrls.isEmpty()) {
                ctx.apiImageUrls = okData.imageUrls
            }
        }

        // ── 6. 合并 URL ──
        Logger.log("[6a] 合并URL...")
        mergeUrls(ctx)
        ctx.pushStage("merge_urls")

        val authorInfo = buildString {
            append(ctx.author.takeIf { it.isNotEmpty() } ?: "(未获取到)")
            if (ctx.authorCode.isNotEmpty()) append("  (@${ctx.authorCode})")
        }
        Logger.log("")
        Logger.log("【页面标题】${ctx.title.takeIf { it.isNotEmpty() } ?: "(未获取到)"}")
        Logger.log("【作者】$authorInfo")
        Logger.log("【视频】${ctx.videoUrls.size} 个  【图片】${ctx.imageUrls.size} 个")

        // ── 7. 下载 ──
        Logger.log("")
        Logger.log("[5/6] 下载文件...")
        Logger.log("  视频目录: ${AppConfig.downloadVideoDir.absolutePath}")

        ctx.videoResults = Downloader.downloadFiles(
            ctx, AppConfig.downloadVideoDir, "video",
            maxWorkers = AppConfig.getMaxVideoWorkers(context),
            md5Registry = md5Registry,
            videoHashRegistry = videoHashRegistry,
            cookies = wvm.getCookies(),
        )
        val videoDone = ctx.videoResults.count { it.status == "downloaded" }
        val videoSkip = ctx.videoResults.size - videoDone
        Logger.log("  视频下载完成: $videoDone 成功, $videoSkip 跳过/失败")
        ctx.pushStage("download_video")

        Logger.log("  图片目录: ${AppConfig.downloadImageDir.absolutePath}")
        ctx.imageResults = Downloader.downloadFiles(
            ctx, AppConfig.downloadImageDir, "image",
            maxWorkers = AppConfig.getMaxImageWorkers(context),
            md5Registry = md5Registry,
            cookies = wvm.getCookies(),
        )
        val imgDone = ctx.imageResults.count { it.status == "downloaded" }
        val imgSkip = ctx.imageResults.size - imgDone
        Logger.log("  图片下载完成: $imgDone 成功, $imgSkip 跳过/失败")
        ctx.pushStage("download_image")

        // ── 8. 保存结果 ──
        Logger.log("")
        Logger.log("[6/6] 保存结果...")
        stats.accumulatePage(ctx)
        stats.printPageResult(ctx)

        recordToDb(ctx, targetUrl, finalUrl)
        ctx.pushStage("db_record")

        val resultPath = ctx.saveResultJson(resultDir)
        ctx.pushStage("save_result")
        Logger.log("  结果已保存: ${resultPath.absolutePath}")

        return Pair(ctx, finalUrl)
    }

    // ── 校验 ──
    private suspend fun validateUrl(finalUrl: String, lastFinalUrl: String?): Pair<Boolean, String> {
        if (AppConfig.isUrlDedupEnabled(context)) {
            val existing = scrapeDao.getByFinalUrl(finalUrl)
            if (existing != null) {
                Logger.log("  ⚠️ 长链接重复（最终地址已被处理过），跳过")
                Logger.log("     首次处理短链接: ${existing.shortUrl}")
                Logger.log("     首次处理时间: ${existing.createTime}")
                return Pair(false, "长链接重复（最终地址已被处理过）")
            }
        }

        if (lastFinalUrl != null && finalUrl == lastFinalUrl) {
            Logger.log("  ⚠️ 跳转前后地址相同，可能未成功进入新页面，跳过")
            return Pair(false, "跳转地址相同（未成功进入新页面）")
        }

        val invalidPaths = listOf("/notfound", "/404", "/about:blank", "/error")
        if (invalidPaths.any { it in finalUrl }) {
            Logger.log("  ⚠️ 目标页面不存在（$finalUrl），跳过")
            return Pair(false, "目标页面不存在")
        }

        return Pair(true, "")
    }

    // ── URL 合并（优先级：API > SSR > DOM+网络请求，对应 Python _merge_urls） ──
    private fun mergeUrls(ctx: PageContext) {
        val rawVideoUrls = when {
            ctx.apiVideoUrls.isNotEmpty() -> {
                Logger.log("  API获取到 ${ctx.apiVideoUrls.size} 个视频链接（高清），优先使用")
                ctx.apiVideoUrls
            }
            ctx.ssrVideoUrls.isNotEmpty() -> {
                Logger.log("  API未获取到视频链接，使用 SSR/页面内嵌数据 ${ctx.ssrVideoUrls.size} 个")
                ctx.ssrVideoUrls
            }
            else -> {
                Logger.log("  API/SSR均未获取到视频链接，退到DOM+网络请求")
                ctx.domVideoUrls + ctx.networkVideoUrls
            }
        }

        val videoDeduped = Downloader.deduplicateVideos(rawVideoUrls.distinct())
        val videoFiltered = videoDeduped
            .filter {
                if (it.startsWith("blob:")) { Logger.log("    [filter:blob] $it", "debug"); false }
                else if (Utils.isUiAsset(it)) { Logger.log("    [filter:uiAsset] $it", "debug"); false }
                else if (Utils.isAudioUrl(it)) { Logger.log("    [filter:audio] $it", "debug"); false }
                else true
            }
        ctx.videoUrls = videoFiltered

        val videoSource = if (ctx.apiVideoUrls.isNotEmpty()) "API" else "DOM+网络"
        val videoFilteredCount = videoDeduped.size - videoFiltered.size
        Logger.log("  去重后视频URL: ${videoDeduped.size} 个 → 过滤掉 $videoFilteredCount 个 → 最终 ${ctx.videoUrls.size} 个 (来源: $videoSource)")

        // 图片 URL 合并
        val rawImageUrls = if (ctx.apiImageUrls.isNotEmpty() || ctx.ssrImageUrls.isNotEmpty()) {
            val merged = (ctx.apiImageUrls + ctx.ssrImageUrls).distinct()
            Logger.log("  API/SSR获取到 ${merged.size} 个图片链接（API ${ctx.apiImageUrls.size} + SSR ${ctx.ssrImageUrls.size}），优先使用")
            merged
        } else {
            Logger.log("  API/SSR均未获取到图片链接，退到DOM+网络请求")
            ctx.domImageUrls + ctx.networkImageUrls
        }

        val imgQualitySorted = Downloader.sortImagesByQuality(rawImageUrls.distinct())
        Logger.log("  图片质量排序完成: ${imgQualitySorted.size} 个")
        val imgFiltered = imgQualitySorted
            .filter {
                if (it.startsWith("blob:")) { Logger.log("    [filter:blob] ${it.take(100)}", "debug"); false }
                else if (Utils.isUiAsset(it)) { Logger.log("    [filter:uiAsset] ${it.take(100)}", "debug"); false }
                else if (ImageDedupChecker.isCoverUrl(it)) { Logger.log("    [filter:isCover] ${it.take(100)}", "debug"); false }
                else if (ImageDedupChecker.isEmojiStickerUrl(it)) { Logger.log("    [filter:isEmoji] ${it.take(100)}", "debug"); false }
                else true
            }
        ctx.imageUrls = imgFiltered

        val imgSource = if (ctx.apiImageUrls.isNotEmpty()) "API/SSR" else "DOM+网络"
        val imgFilteredCount = imgQualitySorted.size - imgFiltered.size
        Logger.log("  合并后图片URL: $imgFilteredCount 个被过滤 → 最终 ${ctx.imageUrls.size} 个 (来源: $imgSource)")
    }

    // ── 数据库记录 ──
    private suspend fun recordToDb(ctx: PageContext, targetUrl: String, finalUrl: String) {
        val normalizedUrl = Utils.normalizeUrl(targetUrl)

        if (ctx.hasDownloads) {
            scrapeDao.insertRecord(
                ScrapeRecord(
                    shortUrl = normalizedUrl,
                    finalUrl = finalUrl,
                    albumName = ctx.author,
                    albumCode = ctx.authorCode,
                    remark = ctx.title,
                    createTime = now()
                )
            )
            scrapeDao.insertUrlMapping(
                UrlMapping(finalUrl = finalUrl, shortUrl = normalizedUrl, createTime = now())
            )
            Logger.log("  URL已记录到数据库, 作者=${ctx.author}, 标题=${ctx.title.take(30)}")
        } else if (ctx.hasMediaUrls) {
            scrapeDao.insertSkipped(
                SkippedRecord(
                    shortUrl = normalizedUrl,
                    albumName = ctx.author,
                    albumCode = ctx.authorCode,
                    remark = ctx.title,
                    skipReason = "所有媒体文件均因大小/去重被跳过",
                    createTime = now()
                )
            )
            Logger.log("  URL记录到跳过表 (所有文件被跳过)")
        } else {
            // 未提取到任何媒体 URL（页面结构变化/风控/非作品页）——记录便于排查
            scrapeDao.insertSkipped(
                SkippedRecord(
                    shortUrl = normalizedUrl,
                    albumName = ctx.author,
                    albumCode = ctx.authorCode,
                    remark = ctx.title,
                    skipReason = "无媒体URL",
                    createTime = now()
                )
            )
            Logger.log("  ⚠️ 未提取到任何媒体URL (视频/图片均为 0)，已记录到跳过表", "warn")
        }
    }

    private fun now(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    }
}