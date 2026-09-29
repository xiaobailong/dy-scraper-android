package com.dy.scraper.core

import android.content.Context
import com.dy.scraper.data.AppDatabase
import com.dy.scraper.entity.ScrapeStats
import com.dy.scraper.util.AppConfig
import com.dy.scraper.util.Logger
import com.dy.scraper.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 共用抓取引擎：将核心管道从 Worker 中提取出来，供 Activity 和 Worker 复用。
 * Activity 通过 UI WebView（正确 attached）驱动，Worker 通过内部 WebView（隐身后台）驱动。
 */
object ScraperEngine {

    data class Progress(
        val pct: Int = 0,
        val current: Int = 0,
        val total: Int = 0,
        val status: String = ""
    )

    data class Input(
        val urls: List<String>,
        val skipProcessed: Boolean = true,
        val reportProgress: suspend (Progress) -> Unit = {},
    )

    data class Output(
        val stats: ScrapeStats = ScrapeStats(),
        val skippedUrlCount: Int = 0,
    )

    suspend fun run(
        context: Context,
        webViewManager: WebViewManager,
        input: Input,
    ): Output = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val scrapeDao = db.scrapeDao()

        // ── 1. 准备环境 ──
        AppConfig.initDirs(context)
        Logger.log("下载目录: ${AppConfig.downloadVideoDir.absolutePath}")
        Logger.log("结果目录: ${AppConfig.resultDir.absolutePath}")

        // ── 2. 过滤已处理的 URL ──
        val urlList = input.urls.ifEmpty {
            Logger.log("没有待处理的 URL，任务结束")
            return@withContext Output()
        }

        val newUrls: List<String>
        val skippedCount: Int
        if (input.skipProcessed && AppConfig.isUrlDedupEnabled(context)) {
            val processed = scrapeDao.getAllProcessedUrls().toSet()
            val skipped = scrapeDao.getAllSkippedUrls().toSet()
            val excluded = processed + skipped

            val filtered = mutableListOf<String>()
            for (url in urlList) {
                val normalized = Utils.normalizeUrl(url)
                if (normalized in excluded) {
                    Logger.log("  跳过(已处理): $url", "debug")
                } else {
                    filtered.add(url)
                }
            }
            newUrls = filtered
            skippedCount = urlList.size - filtered.size
        } else {
            newUrls = urlList
            skippedCount = 0
        }

        if (newUrls.isEmpty()) {
            Logger.log("所有 URL 均已处理，跳过 $skippedCount 个")
            return@withContext Output(skippedUrlCount = skippedCount)
        }

        Logger.log("")
        Logger.log("共 ${newUrls.size} 个 URL 待处理 ($skippedCount 个已跳过)")
        Logger.log("")

        input.reportProgress(Progress(0, 0, newUrls.size, "初始化完成"))

        // ── 3. 准备目录和去重注册表 ──
        val storage = FileStorageManager()
        storage.setup()

        val md5Registry = (
            Utils.scanExistingMd5s(storage.downloadVideoDir) +
            Utils.scanExistingMd5s(storage.downloadImageDir)
        ).toMutableSet()

        Logger.log("[0/6] 扫描已有文件 MD5...")
        Logger.log("  已有 ${md5Registry.size} 个文件，将跳过重复下载")

        val videoHashRegistry = com.dy.scraper.util.VideoDedupChecker
            .scanExisting(storage.downloadVideoDir).toMutableMap()

        // ── 4. 创建处理器 ──
        val stats = ScrapeStats(urlTotal = newUrls.size)
        val processor = UrlProcessor(
            context = context,
            db = db,
            md5Registry = md5Registry,
            videoHashRegistry = videoHashRegistry,
            resultDir = storage.resultDir,
        )

        // ── 5. 逐个处理 URL ──
        var lastFinalUrl: String? = null
        for ((idx, targetUrl) in newUrls.withIndex()) {
            val urlIdx = idx + 1

            input.reportProgress(Progress(
                pct = (urlIdx * 100) / newUrls.size,
                current = urlIdx,
                total = newUrls.size,
                status = "处理中: $urlIdx/${newUrls.size}"
            ))

            val (_, newFinalUrl) = processor.process(
                wvm = webViewManager,
                targetUrl = targetUrl,
                urlIdx = urlIdx,
                urlTotal = newUrls.size,
                stats = stats,
                lastFinalUrl = lastFinalUrl,
            )
            lastFinalUrl = newFinalUrl
        }

        // ── 6. 收尾 ──
        stats.skippedUrlCount = skippedCount
        stats.printFinalSummary()

        Logger.log("")
        Logger.log("  抓取任务完成!")
        Logger.log("    成功: ${stats.successCount} 页")
        Logger.log("    跳过: ${stats.skippedPageCount} 页")
        Logger.log("    失败: ${stats.failedCount} 页")
        Logger.log("    MD5注册表: ${md5Registry.size} 个文件")

        input.reportProgress(Progress(
            pct = 100,
            current = newUrls.size,
            total = newUrls.size,
            status = "完成!"
        ))

        Output(stats = stats, skippedUrlCount = skippedCount)
    }
}