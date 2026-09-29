package com.dy.scraper.util

import android.content.Context
import java.io.File

object AppConfig {

    const val PREFS_NAME = "dy_scraper_settings"
    const val KEY_URL_DEDUP_ENABLED = "url_dedup_enabled"
    const val KEY_KEEP_SCREEN_ON_ENABLED = "keep_screen_on_enabled"

    // ── 爬虫参数 SharedPreferences key ──
    const val KEY_PAGE_LOAD_TIMEOUT = "page_load_timeout"
    const val KEY_RENDER_WAIT = "render_wait"
    const val KEY_DETAIL_API_WAIT = "detail_api_wait"
    const val KEY_DOWNLOAD_TIMEOUT = "download_timeout"
    const val KEY_MAX_VIDEO_WORKERS = "max_video_workers"
    const val KEY_MAX_IMAGE_WORKERS = "max_image_workers"
    const val KEY_DOWNLOAD_ROOT_PATH = "download_root_path"
    const val KEY_IMAGE_SIZE_FILTER_KB = "image_size_filter_kb"
    const val KEY_VIDEO_SIZE_FILTER_MB = "video_size_filter_mb"

    // ── 爬虫参数默认值 ──
    const val DEFAULT_PAGE_LOAD_TIMEOUT_MS = 30_000L
    const val DEFAULT_RENDER_WAIT_MS = 5_000L
    const val DEFAULT_DETAIL_API_WAIT_MS = 20_000L
    const val DEFAULT_DOWNLOAD_TIMEOUT_SECONDS = 120
    const val DEFAULT_MAX_VIDEO_WORKERS = 5
    const val DEFAULT_MAX_IMAGE_WORKERS = 8
    const val DEFAULT_IMAGE_SIZE_FILTER_KB = 50L
    const val DEFAULT_VIDEO_SIZE_FILTER_MB = 20L

    // ── 网络日志（调试用：记录 WebView 所有请求/响应到日志文件） ──
    const val VERBOSE_NETWORK_LOG = true
    const val NETWORK_LOG_MAX_BODY_LENGTH = 2000

    // ── 存储路径 ──
    var downloadVideoDir: File = File("/storage/emulated/0/Download/dy-scraper/videos")
    var downloadImageDir: File = File("/storage/emulated/0/Download/dy-scraper/images")
    var resultDir: File = File("/storage/emulated/0/Download/dy-scraper/results")

    // ── 文件大小上限 ──
    const val MAX_FILE_SIZE: Long = 500L * 1024 * 1024 // 500MB

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
        val root = getDownloadRootDir(context)
        downloadVideoDir = File(root, "videos").also { it.mkdirs() }
        downloadImageDir = File(root, "images").also { it.mkdirs() }
        resultDir = File(root, "results").also { it.mkdirs() }
    }

    private fun getDefaultDownloadRoot(): File {
        return File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS), "dy-scraper")
    }

    fun getDownloadRootDir(context: Context): File {
        val path = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DOWNLOAD_ROOT_PATH, null)
        return if (path.isNullOrBlank()) {
            getDefaultDownloadRoot()
        } else {
            File(path)
        }
    }

    fun getDownloadRootPath(context: Context): String {
        return getDownloadRootDir(context).absolutePath
    }

    fun setDownloadRootPath(context: Context, path: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_DOWNLOAD_ROOT_PATH, path).apply()
    }

    fun getDefaultDownloadRootPath(): String {
        return getDefaultDownloadRoot().absolutePath
    }

    fun getImageSizeFilterBytes(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_IMAGE_SIZE_FILTER_KB, DEFAULT_IMAGE_SIZE_FILTER_KB) * 1024L
    }

    fun getImageSizeFilterKb(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_IMAGE_SIZE_FILTER_KB, DEFAULT_IMAGE_SIZE_FILTER_KB)
    }

    fun setImageSizeFilterKb(context: Context, kb: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_IMAGE_SIZE_FILTER_KB, kb).apply()
    }

    fun getVideoSizeFilterBytes(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_VIDEO_SIZE_FILTER_MB, DEFAULT_VIDEO_SIZE_FILTER_MB) * 1024L * 1024L
    }

    fun getVideoSizeFilterMb(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_VIDEO_SIZE_FILTER_MB, DEFAULT_VIDEO_SIZE_FILTER_MB)
    }

    fun setVideoSizeFilterMb(context: Context, mb: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_VIDEO_SIZE_FILTER_MB, mb).apply()
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

    fun isKeepScreenOnEnabled(context: Context): Boolean {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_KEEP_SCREEN_ON_ENABLED, true)
        } catch (_: Exception) {
            true
        }
    }

    fun setKeepScreenOnEnabled(context: Context, enabled: Boolean) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_KEEP_SCREEN_ON_ENABLED, enabled)
                .apply()
        } catch (_: Exception) {
        }
    }

    // ── 爬虫可配置参数（SharedPreferences 读写，带默认值回退） ──

    fun getPageLoadTimeoutMs(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_PAGE_LOAD_TIMEOUT, DEFAULT_PAGE_LOAD_TIMEOUT_MS)
    }

    fun setPageLoadTimeoutMs(context: Context, value: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_PAGE_LOAD_TIMEOUT, value).apply()
    }

    fun getRenderWaitMs(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_RENDER_WAIT, DEFAULT_RENDER_WAIT_MS)
    }

    fun setRenderWaitMs(context: Context, value: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_RENDER_WAIT, value).apply()
    }

    fun getDetailApiWaitMs(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_DETAIL_API_WAIT, DEFAULT_DETAIL_API_WAIT_MS)
    }

    fun setDetailApiWaitMs(context: Context, value: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_DETAIL_API_WAIT, value).apply()
    }

    fun getDownloadTimeoutSeconds(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_DOWNLOAD_TIMEOUT, DEFAULT_DOWNLOAD_TIMEOUT_SECONDS)
    }

    fun setDownloadTimeoutSeconds(context: Context, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_DOWNLOAD_TIMEOUT, value).apply()
    }

    fun getMaxVideoWorkers(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_MAX_VIDEO_WORKERS, DEFAULT_MAX_VIDEO_WORKERS)
    }

    fun setMaxVideoWorkers(context: Context, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MAX_VIDEO_WORKERS, value).apply()
    }

    fun getMaxImageWorkers(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_MAX_IMAGE_WORKERS, DEFAULT_MAX_IMAGE_WORKERS)
    }

    fun setMaxImageWorkers(context: Context, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MAX_IMAGE_WORKERS, value).apply()
    }
}