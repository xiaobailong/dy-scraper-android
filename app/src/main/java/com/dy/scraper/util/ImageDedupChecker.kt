package com.dy.scraper.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

object ImageDedupChecker {

    private const val EMOJI_MIN_SIZE = 300
    private const val PHASH_HAMMING_THRESHOLD = 10

    private val COVER_KEYWORDS = listOf(
        "cover", "thumb", "poster", "bg", "background", "banner"
    )
    private val EMOJI_KEYWORDS = listOf(
        "emoji", "sticker", "表情", "贴纸"
    )

    fun isCoverUrl(url: String): Boolean {
        val lower = url.lowercase()
        return COVER_KEYWORDS.any { it in lower }
    }

    fun isEmojiStickerUrl(url: String): Boolean {
        val lower = url.lowercase()
        return EMOJI_KEYWORDS.any { it in lower }
    }

    fun isEmojiByDimensions(file: File): Boolean {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.outWidth > 0 && options.outHeight > 0 &&
                    (options.outWidth < EMOJI_MIN_SIZE || options.outHeight < EMOJI_MIN_SIZE)
        } catch (_: Exception) {
            false
        }
    }

    fun computePHash(file: File): String? {
        return try {
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(
                    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inJustDecodeBounds = true })
                )
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                ?: return null
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

    fun checkAndDedup(currentFile: File, existingDir: File): Boolean {
        val currentHash = computePHash(currentFile) ?: return false
        val existingFiles = existingDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif") }
            ?: return false

        var removed = false
        for (existingFile in existingFiles) {
            if (existingFile.absolutePath == currentFile.absolutePath) continue
            val existingHash = computePHash(existingFile) ?: continue
            if (hammingDistance(currentHash, existingHash) <= PHASH_HAMMING_THRESHOLD) {
                // 保留较大的文件
                if (currentFile.length() >= existingFile.length()) {
                    Utils.safeDelete(existingFile)
                } else {
                    Utils.safeDelete(currentFile)
                    removed = true
                }
                break
            }
        }
        return removed
    }
}