package com.dy.scraper.util

import java.io.File
import java.security.MessageDigest
import java.util.regex.Pattern

object Utils {

    fun formatBytes(size: Long): String {
        if (size <= 0) return "未知"
        val units = listOf("B", "KB", "MB", "GB")
        var s = size.toDouble()
        for (unit in units) {
            if (s < 1024) return "%.2f %s".format(s, unit)
            s /= 1024
        }
        return "%.2f TB".format(s)
    }

    fun normalizeUrl(url: String): String {
        return url.trim('/').let {
            if (it.endsWith(".com")) "$it/" else it
        }
    }

    fun cleanTitle(title: String): String {
        var cleaned = title
            .replace(Regex("\\s*-\\s*抖音$"), "")
            .replace(Regex("\\d{8}"), "")
        cleaned = cleaned.replace(Regex("[^\\u4e00-\\u9fffA-Za-z0-9_\\-]"), "")
        cleaned = cleaned.replace(Regex("[_\\-]{2,}"), "_")
        cleaned = cleaned.trim('_', '-')
        return if (cleaned.isNotEmpty()) cleaned.take(50) else "douyin"
    }

    fun isUiAsset(url: String): Boolean {
        val lower = url.lowercase()
        val domain = try {
            java.net.URI(url).host?.lowercase() ?: ""
        } catch (_: Exception) {
            return false
        }

        if (AppConfig.UI_ASSET_DOMAINS.any { it in domain }) return true
        if ("twemoji" in lower || "emblem" in lower) return true
        if ("100x100" in lower || "aweme-avatar" in lower) return true
        if (lower.endsWith(".avif") && "douyin" !in domain) return true
        if ("tsj2vxp0zn" in lower && "obj/" in lower) return true
        return false
    }

    fun md5(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            digest.update(file.readBytes())
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun md5(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun scanExistingMd5s(directory: File): Set<String> {
        val md5Set = mutableSetOf<String>()
        if (!directory.exists()) return md5Set
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.extension.lowercase() != "json") {
                md5(file)?.let { md5Set.add(it) }
            }
        }
        return md5Set
    }

    fun safeDelete(file: File): Boolean {
        return try {
            if (file.exists()) {
                for (i in 0..2) {
                    try {
                        file.delete()
                        return true
                    } catch (_: Exception) {
                        Thread.sleep(300)
                    }
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    fun safeRename(src: File, dst: File): Boolean {
        for (attempt in 0..7) {
            try {
                if (src.renameTo(dst)) return true
            } catch (_: Exception) { }
            if (attempt < 7) Thread.sleep((100 * (1 shl attempt)).toLong())
        }
        return false
    }

    fun extractExtFromUrl(url: String, fallback: String): String {
        return try {
            val path = java.net.URI(url).path
            val name = File(path).name
            val dot = name.lastIndexOf('.')
            if (dot >= 0 && name.length - dot <= 10) name.substring(dot)
            else fallback
        } catch (_: Exception) {
            fallback
        }
    }

    fun isAudioUrl(url: String): Boolean {
        val audioExts = setOf(".mp3", ".wav", ".aac", ".ogg", ".m4a", ".flac", ".wma", ".opus")
        val lower = url.lowercase()
        return audioExts.any { lower.endsWith(it) || "$it?" in lower }
    }

    fun extractIdFromUrl(url: String): String? {
        // 匹配 15-22 位的数字 ID
        val pattern = Pattern.compile("(\\d{15,22})")
        val matcher = pattern.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }
}