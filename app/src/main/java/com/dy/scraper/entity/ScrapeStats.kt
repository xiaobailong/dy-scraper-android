package com.dy.scraper.entity

import com.dy.scraper.util.Logger

data class MediaStats(
    var success: Int = 0,
    var failed: Int = 0,
    var skippedSmall: Int = 0,
    var skippedLarge: Int = 0,
    var skippedDup: Int = 0,
    var skippedPhashDup: Int = 0
) {
    val total: Int
        get() = success + failed + skippedSmall + skippedLarge + skippedDup + skippedPhashDup

    fun accumulateResults(results: List<DownloadResult>) {
        for (r in results) {
            val key = when (r.status) {
                "downloaded", "skipped_duplicate" -> "success"
                else -> r.status
            }
            when (key) {
                "success" -> success++
                "failed" -> failed++
                "skipped_small" -> skippedSmall++
                "skipped_large" -> skippedLarge++
                "skipped_duplicate" -> skippedDup++
                // 表情包过滤与 pHash 重复同属「内容过滤」，合并计数保证 总数 = 成功+失败+跳过
                "skipped_video_phash_dup", "skipped_phash_dup", "skipped_emoji" -> skippedPhashDup++
            }
        }
    }
}

class ScrapeStats(
    val urlTotal: Int = 0
) {
    val video = MediaStats()
    val image = MediaStats()

    var urlsWithDownloads: Int = 0
    var skippedUrlCount: Int = 0

    val successCount: Int
        get() = urlsWithDownloads
    val skippedPageCount: Int
        get() = skippedUrlCount
    val failedCount: Int
        get() = video.failed + image.failed

    fun accumulatePage(ctx: PageContext) {
        video.accumulateResults(ctx.videoResults)
        image.accumulateResults(ctx.imageResults)
        if (ctx.hasDownloads) urlsWithDownloads++
    }

    fun printPageResult(ctx: PageContext) {
        val dupInfo = buildString {
            if (ctx.videoDupCount > 0 || ctx.imageDupCount > 0) {
                append("  去重: 视频${ctx.videoDupCount}个 图片${ctx.imageDupCount}个")
            }
        }
        Logger.log("  视频: ${ctx.videoSuccessCount}/${ctx.videoUrls.size}" +
                "  图片: ${ctx.imageSuccessCount}/${ctx.imageUrls.size}$dupInfo")
    }

    fun printFinalSummary() {
        val totalSuccess = video.success + image.success
        val totalFailed = video.failed + image.failed
        val videoSkipped = video.skippedSmall + video.skippedLarge + video.skippedDup + video.skippedPhashDup
        // 注意：图片的 pHash 重复也必须计入跳过，否则「文件总数 ≠ 成功+失败+跳过」
        val imageSkipped = image.skippedSmall + image.skippedLarge + image.skippedDup + image.skippedPhashDup
        val totalSkipped = videoSkipped + imageSkipped
        val totalUrls = urlTotal + skippedUrlCount
        val accounted = totalSuccess + totalFailed + totalSkipped

        Logger.log("")
        Logger.log("=".repeat(60))
        Logger.log("  全部完成! 共处理 $urlTotal 个 URL")
        Logger.log("=".repeat(60))

        Logger.log("")
        Logger.log("  ┌─ 汇总 ────────────────────────────────────")
        Logger.log("  │  URL 总数: $totalUrls 个")
        Logger.log("  │  本次处理: $urlTotal 个")
        if (skippedUrlCount > 0) {
            Logger.log("  │  跳过(已处理URL): $skippedUrlCount 个")
        }
        Logger.log("  │  有成功下载的 URL: $urlsWithDownloads 个")
        Logger.log("  │  文件总数: ${video.total + image.total} 个 (视频 ${video.total}, 图片 ${image.total})")
        Logger.log("  │  下载成功: $totalSuccess 个 (视频 ${video.success}, 图片 ${image.success})")
        Logger.log("  │  下载失败: $totalFailed 个 (视频 ${video.failed}, 图片 ${image.failed})")
        Logger.log("  │  跳过(文件): $totalSkipped 个 (视频 $videoSkipped, 图片 $imageSkipped)")
        Logger.log("  │  合计核对: ${video.total + image.total} = $accounted (成功+失败+跳过)")
        Logger.log("  └──────────────────────────────────────────")
        Logger.log("=".repeat(60))
    }

    val allUrlsSuccessful: Boolean
        get() = urlsWithDownloads == urlTotal && video.failed == 0 && image.failed == 0
}