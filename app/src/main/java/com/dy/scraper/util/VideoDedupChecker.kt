package com.dy.scraper.util

import java.io.File

object VideoDedupChecker {

    private const val PHASH_HAMMING_THRESHOLD = 5

    fun scanExisting(videoDir: File): Map<String, List<String>> {
        val registry = mutableMapOf<String, List<String>>()
        if (!videoDir.exists()) return registry

        videoDir.listFiles()?.forEach { file ->
            if (file.isFile && isVideoFile(file)) {
                val fingerprint = computeFingerprint(file)
                if (fingerprint != null) {
                    registry[file.name] = fingerprint
                }
            }
        }
        return registry
    }

    fun isVideoFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in setOf("mp4", "webm", "mov", "avi", "mkv", "flv", "m4v", "3gp")
    }

    fun computeFingerprint(file: File): List<String>? {
        return try {
            // 使用文件大小 + 前 64KB 的 MD5 作为快速指纹
            val sizeFingerprint = "size:${file.length()}"
            val contentFingerprint = if (file.length() > 0) {
                val buffer = ByteArray(minOf(file.length(), 65536).toInt())
                file.inputStream().use { it.read(buffer) }
                "md5:${Utils.md5(buffer)}"
            } else {
                "md5:empty"
            }
            listOf(sizeFingerprint, contentFingerprint)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 检查当前视频是否与注册表中已有视频重复。
     * 返回 true 表示当前文件是重复且已被删除（较小文件），
     * 返回 false 表示当前文件保留。
     */
    fun checkAndDedup(
        currentFile: File,
        registry: MutableMap<String, List<String>>
    ): Boolean {
        if (!currentFile.exists()) return false

        val currentFingerprint = computeFingerprint(currentFile) ?: return false

        for ((name, existingFingerprint) in registry) {
            if (currentFingerprint[0] == existingFingerprint[0] &&
                currentFingerprint[1] == existingFingerprint[1]) {
                // 完全相同，保留较大的
                val existingFile = File(currentFile.parent, name)
                if (currentFile.length() >= (existingFile.length().takeIf { existingFile.exists() } ?: 0L)) {
                    Utils.safeDelete(existingFile)
                    registry.remove(name)
                } else {
                    Utils.safeDelete(currentFile)
                    return true
                }
                break
            }
        }
        // 加入注册表
        registry[currentFile.name] = currentFingerprint
        return false
    }
}