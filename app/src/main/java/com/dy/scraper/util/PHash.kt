package com.dy.scraper.util

/**
 * 真正的 pHash（DCT 感知哈希），对齐 Python 版 `imagehash.phash`。
 *
 * 原实现是 **aHash**（8x8 灰度均值哈希）配阈值 4：对「同一场景/同一亮度的不同照片」几乎没有区分度。
 * 真机日志（`dy_scraper_log_2026-09-29.txt`）里一个图集作品 6 张图只留下 2 张，
 * 4 张被 `[图片去重] pHash匹配，删除 ... (较小)` 删掉 —— 就是 aHash 误判（Python 侧用 imagehash.phash）。
 *
 * 本对象是**纯 Kotlin**（不依赖 Android），可用合成像素在 JVM 单测里验证。
 * 算法：
 *   1. 32x32 灰度；
 *   2. 二维 DCT（先按行、再按列，只算 8 个低频分量）；
 *   3. 取左上 8x8 系数，与全体系数中位数比较，大于中位数记 1 → 64bit；
 *   4. 输出 16 位十六进制字符串（与 [ImageDedupChecker.hammingDistance] 兼容）。
 */
object PHash {

    /** 采样边长（imagehash 默认 hash_size=8、highfreq_factor=4 → 32） */
    const val SAMPLE_SIZE = 32

    /** 低频分量个数（8x8） */
    private const val FREQ = 8

    private val cosTable: Array<DoubleArray> by lazy {
        Array(FREQ) { u ->
            DoubleArray(SAMPLE_SIZE) { x ->
                Math.cos((2.0 * x + 1.0) * u * Math.PI / (2.0 * SAMPLE_SIZE))
            }
        }
    }

    /**
     * 计算 pHash。
     * @param gray 灰度像素（按行展开，长度必须是 size*size，取值 0..255）
     * @param size 采样边长，默认 [SAMPLE_SIZE]
     */
    fun hash(gray: IntArray, size: Int = SAMPLE_SIZE): String {
        require(size > 0 && size * size == gray.size) {
            "gray size ${gray.size} != ${size}x$size"
        }
        val cos = if (size == SAMPLE_SIZE) cosTable else Array(FREQ) { u ->
            DoubleArray(size) { x -> Math.cos((2.0 * x + 1.0) * u * Math.PI / (2.0 * size)) }
        }

        // 行方向 DCT（只保留前 FREQ 个 u）
        val rows = Array(FREQ) { DoubleArray(size) }
        for (u in 0 until FREQ) {
            val cu = cos[u]
            for (y in 0 until size) {
                val base = y * size
                var sum = 0.0
                for (x in 0 until size) sum += gray[base + x] * cu[x]
                rows[u][y] = sum
            }
        }

        // 列方向 DCT → 8x8 低频块
        val coef = Array(FREQ) { DoubleArray(FREQ) }
        val values = DoubleArray(FREQ * FREQ)
        var k = 0
        for (u in 0 until FREQ) {
            for (v in 0 until FREQ) {
                val cv = cos[v]
                var sum = 0.0
                for (y in 0 until size) sum += rows[u][y] * cv[y]
                coef[u][v] = sum
                values[k++] = sum
            }
        }

        // 中位数（与 numpy.median 一致：偶数个取中间两个的平均）
        values.sort()
        val median = if (values.size % 2 == 0) {
            (values[values.size / 2 - 1] + values[values.size / 2]) / 2.0
        } else {
            values[values.size / 2]
        }

        var bits = 0L
        var idx = 0
        for (u in 0 until FREQ) {
            for (v in 0 until FREQ) {
                if (coef[u][v] > median) bits = bits or (1L shl (63 - idx))
                idx++
            }
        }
        return String.format("%016x", bits)
    }

    /** 从 ARGB 像素数组取灰度（BT.601，与旧实现一致） */
    fun toGray(pixels: IntArray): IntArray {
        return IntArray(pixels.size) { i ->
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            (0.299 * r + 0.587 * g + 0.114 * b).toInt()
        }
    }
}
