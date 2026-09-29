package com.dy.scraper.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

object ImageDedupChecker {

    private const val EMOJI_MAX_SIZE = 400
    private const val PHASH_HAMMING_THRESHOLD = 4

    private val COVER_PATTERNS = listOf(
        Regex("[?&]cover="),
        Regex("/cover/"),
        Regex("video_cover"),
        Regex("cover_image"),
    )
    private val EMOJI_STICKER_PATTERNS = listOf(
        Regex("emoticon"),
        Regex("sticker"),
        Regex("emoji"),
        Regex("/obj/tos-cn-i-tsj2vxp0zn/"),
        Regex("gif\\.douyinpic\\.com"),
        Regex("/obj/ies\\.fe\\.effect/"),
    )

    fun isCoverUrl(url: String): Boolean {
        val lower = url.lowercase()
        return COVER_PATTERNS.any { it.containsMatchIn(lower) }
    }

    fun isEmojiStickerUrl(url: String): Boolean {
        val lower = url.lowercase()
        return EMOJI_STICKER_PATTERNS.any { it.containsMatchIn(lower) }
    }

    fun isEmojiByDimensions(file: File): Boolean {
        if (file.extension.lowercase() != "gif") return false
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
            val w = options.outWidth
            val h = options.outHeight
            if (w <= 0 || h <= 0) return false
            val ratio = if (w < h) w.toDouble() / h else h.toDouble() / w
            val isEmoji = ratio > 0.6 && maxOf(w, h) <= EMOJI_MAX_SIZE
            if (isEmoji) {
                Logger.log("    [图片过滤] 表情包 ${file.name} (${w}x${h})", "debug")
            }
            isEmoji
        } catch (_: Exception) {
            false
        }
    }

    fun computePHash(file: File): String? {
        return try {
            val decodeOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(decodeOptions)
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                ?: return null.also { Logger.d("ImageDedup: computePHash failed to decode ${file.name}") }
            val scaled = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
            bitmap.recycle()

            val pixels = IntArray(64)
            scaled.getPixels(pixels, 0, 8, 0, 0, 8, 8)
            scaled.recycle()

            val grayPixels = pixels.map { pixel ->
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            }
            val avg = grayPixels.average()
            var hash = 0L
            for (i in grayPixels.indices) {
                if (grayPixels[i] > avg) {
                    hash = hash or (1L shl (63 - i))
                }
            }
            // 返回 16 进制字符串
            String.format("%016x", hash)
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options): Int {
        var sampleSize = 1
        val maxDim = maxOf(options.outWidth, options.outHeight)
        if (maxDim > 256) {
            sampleSize = maxDim / 256
        }
        return sampleSize
    }

    fun hammingDistance(hash1: String, hash2: String): Int {
        val len = minOf(hash1.length, hash2.length)
        var distance = 0
        for (i in 0 until len) {
            val v1 = hash1[i].digitToIntOrNull(16) ?: 0
            val v2 = hash2[i].digitToIntOrNull(16) ?: 0
            distance += Integer.bitCount(v1 xor v2)
        }
        return distance
    }

    @Synchronized
    fun checkAndDedup(currentFile: File, existingDir: File): Boolean {
        if (!currentFile.exists() || currentFile.length() == 0L) {
            Logger.d("ImageDedup: checkAndDedup skip, file already gone: ${currentFile.name}")
            return false
        }

        val currentSize = currentFile.length()
        val currentHash = computePHash(currentFile) ?: return false

        if (!currentFile.exists()) {
            Logger.d("ImageDedup: current file deleted during pHash computation: ${currentFile.name}")
            return false
        }

        val existingFiles = existingDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif") }
            ?: return false

        var removed = false
        for (existingFile in existingFiles) {
            if (existingFile.absolutePath == currentFile.absolutePath) continue
            if (!existingFile.exists()) continue

            val existingSize = existingFile.length()
            if (existingSize == 0L) continue

            val existingHash = computePHash(existingFile) ?: continue
            if (!existingFile.exists()) continue

            if (hammingDistance(currentHash, existingHash) <= PHASH_HAMMING_THRESHOLD) {
                if (currentSize >= existingSize) {
                    Utils.safeDelete(existingFile)
                    Logger.log("    [图片去重] pHash匹配，保留 ${currentFile.name} ($currentSize > $existingSize)", "debug")
                } else {
                    Utils.safeDelete(currentFile)
                    Logger.log("    [图片去重] pHash匹配，删除 ${currentFile.name} ($currentSize < $existingSize)", "debug")
                    removed = true
                }
                break
            }
        }
        return removed
    }
}