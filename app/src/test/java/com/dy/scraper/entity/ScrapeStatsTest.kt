package com.dy.scraper.entity

import com.dy.scraper.util.Logger
import org.junit.Assert.*
import org.junit.Test

/**
 * 统计汇总单测（复现日志里「文件总数 29 / 成功 26 / 跳过 1」对不上的问题）
 */
class ScrapeStatsTest {

    @Test
    fun `media stats total covers all statuses including emoji filter`() {
        val stats = MediaStats()
        stats.accumulateResults(
            listOf(
                DownloadResult(name = "a", status = "downloaded"),
                DownloadResult(name = "b", status = "skipped_duplicate"),
                DownloadResult(name = "c", status = "skipped_small"),
                DownloadResult(name = "d", status = "skipped_emoji"),
                DownloadResult(name = "e", status = "skipped_phash_dup"),
                DownloadResult(name = "f", status = "failed"),
            )
        )
        assertEquals(6, stats.total)
        assertEquals(2, stats.success)
        assertEquals(1, stats.failed)
        assertEquals(1, stats.skippedSmall)
        assertEquals(2, stats.skippedPhashDup)
    }

    @Test
    fun `final summary counts image phash duplicates`() {
        val stats = ScrapeStats(urlTotal = 3)
        // 复现日志场景：图片 29 个 = 26 成功(含MD5重复) + 1 MD5跳过 + 2 pHash跳过
        stats.image.success = 26
        stats.image.skippedDup = 1
        stats.image.skippedPhashDup = 2

        val lines = captureLog { stats.printFinalSummary() }

        assertTrue(lines.any { it.contains("文件总数: 29 个") })
        assertTrue(lines.any { it.contains("跳过(文件): 3 个") })
        assertTrue(lines.any { it.contains("合计核对: 29 = 29") })
    }

    @Test
    fun `final summary reports video like the real log`() {
        val stats = ScrapeStats(urlTotal = 1)
        stats.video.success = 1
        stats.video.skippedPhashDup = 1

        val lines = captureLog { stats.printFinalSummary() }
        assertTrue(lines.any { it.contains("文件总数: 2 个") })
        assertTrue(lines.any { it.contains("视频 2, 图片 0") })
    }

    private fun captureLog(block: () -> Unit): List<String> {
        val lines = mutableListOf<String>()
        val listener: (String) -> Unit = { lines.add(it) }
        Logger.addListener(listener)
        try {
            block()
        } finally {
            Logger.removeListener(listener)
        }
        return lines
    }
}
