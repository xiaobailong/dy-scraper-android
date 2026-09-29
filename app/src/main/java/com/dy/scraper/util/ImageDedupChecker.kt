package com.dy.scraper.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

object ImageDedupChecker {

    private const val EMOJI_MAX_SIZE = 400

    /** Hamming 距离阈值（对齐 Python 版 `image_dedup.check_and_dedup(hamming_threshold=5)`） */
    private const val PHASH_HAMMING_THRESHOLD = 5

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
            if (decodeOptions.outWidth <= 0 || decodeOptions.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(decodeOptions)
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                ?: return null.also { Logger.d("ImageDedup: computePHash failed to decode ${file.name}") }

            val scaled = Bitmap.createScaledBitmap(bitmap, PHash.SAMPLE_SIZE, PHash.SAMPLE_SIZE, true)
            bitmap.recycle()

            val pixels = IntArray(PHash.SAMPLE_SIZE * PHash.SAMPLE_SIZE)
            scaled.getPixels(pixels, 0, PHash.SAMPLE_SIZE, 0, 0, PHash.SAMPLE_SIZE, PHash.SAMPLE_SIZE)
            scaled.recycle()

            // 真正的 DCT pHash（对齐 Python imagehash.phash）——
            // 旧实现是 aHash，对同场景不同照片区分度太低，会误删（见 PHash 注释）
            PHash.hash(PHash.toGray(pixels))
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