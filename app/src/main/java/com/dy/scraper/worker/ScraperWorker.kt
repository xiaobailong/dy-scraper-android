package com.dy.scraper.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dy.scraper.MainActivity
import com.dy.scraper.ScraperApp
import com.dy.scraper.core.WebViewManager
import com.dy.scraper.core.UrlProcessor
import com.dy.scraper.core.FileStorageManager
import com.dy.scraper.data.AppDatabase
import com.dy.scraper.entity.ScrapeStats
import com.dy.scraper.util.AppConfig
import com.dy.scraper.util.Logger
import com.dy.scraper.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ScraperWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_URLS = "urls"
    }

    private val db = AppDatabase.getInstance(context)
    private val scrapeDao = db.scrapeDao()

    override suspend fun doWork(): Result {
        Logger.log("=".repeat(60))
        Logger.log("  抖音网页内容抓取工具 (Android WebView)")
        Logger.log("=".repeat(60))

        // ── 前台服务通知 ──
        setForeground(createForegroundInfo())

        try {
            // ── 1. 准备环境 ──
            AppConfig.initDirs(applicationContext)
            Logger.log("下载目录: ${AppConfig.downloadVideoDir.absolutePath}")
            Logger.log("结果目录: ${AppConfig.resultDir.absolutePath}")

            // ── 2. 获取 URL 列表 ──
            // TODO: 从配置或输入获取 URL 列表
            // 目前从 raw 资源文件读取
            val urlList = readUrlList()
            if (urlList.isEmpty()) {
                Logger.log("没有待处理的 URL，任务结束")
                return Result.success()
            }

            val (newUrls, skippedCount) = filterProcessedUrls(urlList)
            if (newUrls.isEmpty()) {
                Logger.log("所有 URL 均已处理，跳过 $skippedCount 个")
                return Result.success()
            }

            Logger.log("")
            Logger.log("共 ${newUrls.size} 个 URL 待处理 ($skippedCount 个已跳过)")
            Logger.log("")

            updateProgress(0, newUrls.size, 0, "初始化完成")

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

            // ── 4. 初始化浏览器和处理器 ──
            val wvm = withContext(Dispatchers.Main) {
                WebViewManager(applicationContext)
            }

            val stats = ScrapeStats(urlTotal = newUrls.size)
            val processor = UrlProcessor(
                db = db,
                md5Registry = md5Registry,
                videoHashRegistry = videoHashRegistry,
                resultDir = storage.resultDir,
            )

            // ── 5. 逐个处理 URL ──
            var lastFinalUrl: String? = null
            for ((idx, targetUrl) in newUrls.withIndex()) {
                val urlIdx = idx + 1
                val (ctx, newFinalUrl) = processor.process(
                    wvm = wvm,
                    targetUrl = targetUrl,
                    urlIdx = urlIdx,
                    urlTotal = newUrls.size,
                    stats = stats,
                    lastFinalUrl = lastFinalUrl,
                )
                lastFinalUrl = newFinalUrl

                val pct = (urlIdx * 100) / newUrls.size
                updateProgress(pct, newUrls.size, urlIdx, "处理中: ${urlIdx}/${newUrls.size}")
            }

            // ── 6. 收尾 ──
            stats.skippedUrlCount = skippedCount
            stats.printFinalSummary()

            // ── 7. 清理 ──
            withContext(Dispatchers.Main) {
                wvm.destroy()
            }

            updateProgress(100, newUrls.size, newUrls.size, "完成!")
            return Result.success()

        } catch (e: Exception) {
            Logger.log("抓取过程异常: ${e.message}", "error")
            e.printStackTrace()
            return Result.failure()
        }
    }

    private suspend fun filterProcessedUrls(urlList: List<String>): Pair<List<String>, Int> {
        val processed = scrapeDao.getAllProcessedUrls().toSet()
        val skipped = scrapeDao.getAllSkippedUrls().toSet()
        val excluded = processed + skipped

        val newUrls = mutableListOf<String>()
        for (url in urlList) {
            val normalized = Utils.normalizeUrl(url)
            if (normalized in excluded) {
                Logger.log("  跳过(已处理): $url", "debug")
            } else {
                newUrls.add(url)
            }
        }
        return Pair(newUrls, urlList.size - newUrls.size)
    }

    private fun readUrlList(): List<String> {
        val urlsStr = inputData.getString(KEY_URLS) ?: ""
        if (urlsStr.isNotBlank()) {
            return urlsStr.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        }

        return try {
            val urlsFile = File(applicationContext.filesDir, "urls.txt")
            if (urlsFile.exists()) {
                urlsFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            } else {
                // 首次运行时创建示例文件
                urlsFile.parentFile?.mkdirs()
                urlsFile.writeText(
                    "# 抖音 URL 列表，每行一个\n" +
                    "# 示例:\n" +
                    "# https://www.douyin.com/video/xxx\n" +
                    "# https://www.douyin.com/note/xxx\n"
                )
                Logger.log("已在 ${urlsFile.absolutePath} 创建 URL 列表文件，请填入要抓取的抖音链接")
                emptyList()
            }
        } catch (e: Exception) {
            Logger.log("读取 URL 列表失败: ${e.message}", "error")
            emptyList()
        }
    }

    private fun updateProgress(pct: Int, total: Int, current: Int, status: String) {
        setProgress(
            workDataOf(
                "progress_pct" to pct,
                "url_total" to total,
                "url_current" to current,
                "status" to status,
            )
        )
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val cancelIntent = Intent(applicationContext, MainActivity::class.java)
        val cancelPendingIntent = PendingIntent.getActivity(
            applicationContext, 0, cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(
            applicationContext,
            ScraperApp.CHANNEL_ID
        )
            .setContentTitle("抖音抓取运行中")
            .setContentText("正在抓取抖音内容...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "取消", cancelPendingIntent)
            .build()

        return ForegroundInfo(ScraperApp.NOTIFICATION_ID, notification)
    }
}