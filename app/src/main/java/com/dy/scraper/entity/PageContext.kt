package com.dy.scraper.entity

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Stage(
    val code: String,
    val timestamp: String = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
)

data class DownloadResult(
    val name: String = "",
    val url: String = "",
    val path: String = "",
    val size: String = "",
    val md5: String = "",
    val status: String = "",
    val error: String = ""
)

class PageContext(
    val shortUrl: String,
    var finalUrl: String = ""
) {
    // ── 作者/内容信息 ──
    var title: String = ""
    var author: String = ""
    var authorCode: String = ""
    var secUid: String = ""
    var description: String = ""
    var coverUrl: String = ""
    var extractSource: String = ""

    // ── 媒体 URL（按来源分层） ──
    var apiVideoUrls: List<String> = emptyList()
    var apiImageUrls: List<String> = emptyList()
    var ssrVideoUrls: List<String> = emptyList()
    var ssrImageUrls: List<String> = emptyList()
    var domVideoUrls: List<String> = emptyList()
    var domImageUrls: List<String> = emptyList()
    var networkVideoUrls: List<String> = emptyList()
    var networkImageUrls: List<String> = emptyList()

    // ── 合并后的最终 URL ──
    var videoUrls: List<String> = emptyList()
    var imageUrls: List<String> = emptyList()

    // ── 下载结果 ──
    var videoResults: List<DownloadResult> = emptyList()
    var imageResults: List<DownloadResult> = emptyList()

    // ── 调试信息 ──
    var apiResponseCount: Int = 0
    var ssrAvailable: List<String> = emptyList()
    var stages: List<Stage> = emptyList()

    // ── 调用追踪 ──
    private val stageList = mutableListOf<Stage>()

    fun pushStage(code: String) {
        stageList.add(Stage(code))
    }

    fun dumpStages(): List<Stage> = stageList.toList()

    // ── 统计属性 ──
    val videoSuccessCount: Int
        get() = videoResults.count { it.status in setOf("downloaded", "skipped_duplicate") }

    val videoDupCount: Int
        get() = videoResults.count { it.status == "skipped_duplicate" }

    val videoSizeSkippedCount: Int
        get() = videoResults.count { it.status in setOf("skipped_small", "skipped_large") }

    val imageSuccessCount: Int
        get() = imageResults.count { it.status in setOf("downloaded", "skipped_duplicate") }

    val imageDupCount: Int
        get() = imageResults.count { it.status == "skipped_duplicate" }

    val imageSizeSkippedCount: Int
        get() = imageResults.count { it.status in setOf("skipped_small", "skipped_large") }

    val hasDownloads: Boolean
        get() = videoSuccessCount > 0 || imageSuccessCount > 0

    val hasMediaUrls: Boolean
        get() = videoUrls.isNotEmpty() || imageUrls.isNotEmpty()

    val totalUrlCount: Int
        get() = videoUrls.size + imageUrls.size
}