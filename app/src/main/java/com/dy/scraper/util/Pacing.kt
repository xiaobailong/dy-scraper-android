package com.dy.scraper.util

import kotlin.random.Random

/**
 * 反风控「节流」工具：全部是纯函数（可单测），只负责算等待时长，不发起任何请求。
 *
 * 用法：`delay(Pacing.nextDelayMs(baseMs, ratio))` —— `baseMs` 来自设置，`ratio` 是抖动比例
 * （0 = 固定间隔；0.5 = 实际间隔落在 `[base, base×1.5)` 之间，避免机械节奏）。
 */
object Pacing {

    /** 重试退避表（毫秒）：第 1 次重试前 2s、第 2 次 5s、之后 10s */
    private val BACKOFF_TABLE = longArrayOf(2_000L, 5_000L, 10_000L)

    /** 批次内单个请求的随机错峰范围（毫秒），避免同一毫秒齐发 */
    private const val STAGGER_MIN_MS = 150L
    private const val STAGGER_MAX_MS = 300L

    /**
     * 基础间隔 + 抖动：结果落在 `[baseMs, baseMs × (1 + jitterRatio)]`。
     * `baseMs <= 0` → 0（不等待）；`jitterRatio <= 0` → 精确等于 `baseMs`。
     */
    fun nextDelayMs(baseMs: Long, jitterRatio: Double, random: Random = Random.Default): Long {
        if (baseMs <= 0L) return 0L
        val ratio = jitterRatio.coerceIn(0.0, 2.0)
        if (ratio <= 0.0) return baseMs
        return baseMs + (baseMs * ratio * random.nextDouble()).toLong()
    }

    /** 重试退避：`attempt` 从 0 开始（首次失败后调用），越界夹到表尾 */
    fun backoffDelayMs(attempt: Int, jitterRatio: Double = 0.3, random: Random = Random.Default): Long {
        val idx = attempt.coerceIn(0, BACKOFF_TABLE.size - 1)
        return nextDelayMs(BACKOFF_TABLE[idx], jitterRatio, random)
    }

    /** 批次内单请求错峰：`[150, 300)` 毫秒随机 */
    fun requestStaggerMs(random: Random = Random.Default): Long {
        return STAGGER_MIN_MS + random.nextLong(STAGGER_MAX_MS - STAGGER_MIN_MS)
    }

    /** HTTP 状态码是否属于风控/限流信号 */
    fun isRiskHttpCode(code: Int): Boolean = code == 403 || code == 412 || code == 429

    /** 日志用的短时长文本（`800ms` / `2.5s`） */
    fun formatMs(ms: Long): String {
        if (ms < 1000L) return "${ms}ms"
        val seconds = ms / 1000.0
        return String.format(java.util.Locale.US, "%.1fs", seconds)
    }
}
