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
import com.dy.scraper.core.ScraperEngine
import com.dy.scraper.core.WebViewManager
import com.dy.scraper.util.AppConfig
import com.dy.scraper.util.Logger
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

    override suspend fun doWork(): Result {
        Logger.log("=".repeat(60))
        Logger.log("  斗虫 (Android WebView - Worker)")
        Logger.log("=".repeat(60))

        setForeground(createForegroundInfo())
        Logger.log("  前台通知已设置, notificationId=${ScraperApp.NOTIFICATION_ID}")

        try {
            val urlList = readUrlList()
            if (urlList.isEmpty()) {
                Logger.log("没有待处理的 URL，任务结束")
                return Result.success()
            }

            val wvm = withContext(Dispatchers.Main) {
                WebViewManager(applicationContext)
            }

            val output = ScraperEngine.run(
                context = applicationContext,
                webViewManager = wvm,
                input = ScraperEngine.Input(
                    urls = urlList,
                    reportProgress = { progress ->
                        setProgress(workDataOf(
                            "progress_pct" to progress.pct,
                            "url_total" to progress.total,
                            "url_current" to progress.current,
                            "status" to progress.status,
                        ))
                    },
                ),
            )
            Logger.log("抓取完成: 成功=${output.stats.successCount}, 失败=${output.stats.failedCount}")

            withContext(Dispatchers.Main) {
                wvm.destroy()
            }

            return Result.success()
        } catch (e: Exception) {
            Logger.log("抓取过程异常: ${e.message}", "error")
            e.printStackTrace()
            return Result.failure()
        }
    }

    private fun readUrlList(): List<String> {
        val urlsStr = inputData.getString(KEY_URLS) ?: ""
        if (urlsStr.isNotBlank()) {
            return urlsStr.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.distinct()
        }

        return try {
            val urlsFile = File(applicationContext.filesDir, "urls.txt")
            if (urlsFile.exists()) {
                urlsFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.distinct()
            } else {
                urlsFile.parentFile?.mkdirs()
                urlsFile.writeText(
                    "# URL 列表，每行一个\n" +
                    "# 示例:\n" +
                    "# https://www.douyin.com/video/xxx\n" +
                    "# https://www.douyin.com/note/xxx\n"
                )
                Logger.log("已在 ${urlsFile.absolutePath} 创建 URL 列表文件，请填入要抓取的链接")
                emptyList()
            }
        } catch (e: Exception) {
            Logger.log("读取 URL 列表失败: ${e.message}", "error")
            emptyList()
        }
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
            .setContentTitle("斗虫运行中")
            .setContentText("正在抓取内容...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "取消", cancelPendingIntent)
            .build()

        return ForegroundInfo(ScraperApp.NOTIFICATION_ID, notification)
    }
}