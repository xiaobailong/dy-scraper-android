package com.dy.scraper.util

import android.content.Context
import java.io.File

object AppConfig {

    const val PREFS_NAME = "dy_scraper_settings"
    const val KEY_URL_DEDUP_ENABLED = "url_dedup_enabled"

    // ── 存储路径 ──
    var downloadVideoDir: File = File("/storage/emulated/0/Download/dy-scraper/videos")
    var downloadImageDir: File = File("/storage/emulated/0/Download/dy-scraper/images")
    var resultDir: File = File("/storage/emulated/0/Download/dy-scraper/results")

    // ── 文件大小限制 ──
    const val MIN_FILE_SIZE: Long = 10 * 1024          // 10KB
    const val MAX_FILE_SIZE: Long = 500L * 1024 * 1024 // 500MB

    // ── 下载配置 ──
    const val MAX_VIDEO_WORKERS = 5
    const val MAX_IMAGE_WORKERS = 8
    const val DOWNLOAD_TIMEOUT_SECONDS = 120

    // ── 页面加载 ──
    const val PAGE_LOAD_TIMEOUT_MS = 30_000L
    const val RENDER_WAIT_MS = 5_000L

    // ── UI 素材域名（用于过滤非内容资源） ──
    val UI_ASSET_DOMAINS = setOf(
        "douyinstatic.com",
        "byteeffecttos.com",
        "byteimg.com",
        "bytescm.com",
        "baidu.com",
        "p-pc-weboff.byteimg.com",
    )

    // ── User-Agent（桌面 Chrome，匹配 Python 原版） ──
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // ── 抖音 API 路径特征 ──
    val DETAIL_API_PATTERNS = listOf(
        "/aweme/v1/web/aweme/detail/",
        "/aweme/v1/web/note/detail/",
        "/aweme/v1/web/note/",
        "/aweme/v1/aweme/detail/",
    )

    fun initDirs(context: Context) {
        val root = File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS), "dy-scraper")
        downloadVideoDir = File(root, "videos").also { it.mkdirs() }
        downloadImageDir = File(root, "images").also { it.mkdirs() }
        resultDir = File(root, "results").also { it.mkdirs() }
    }

    fun ensureDirs() {
        listOf(downloadVideoDir, downloadImageDir, resultDir)
            .forEach { it.mkdirs() }
    }

    fun isUrlDedupEnabled(context: Context): Boolean {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_URL_DEDUP_ENABLED, true)
        } catch (_: Exception) {
            true
        }
    }

    fun setUrlDedupEnabled(context: Context, enabled: Boolean) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_URL_DEDUP_ENABLED, enabled)
                .apply()
        } catch (_: Exception) {
        }
    }
}