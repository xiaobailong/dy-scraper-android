package com.dy.scraper.core

import com.dy.scraper.util.AppConfig
import com.dy.scraper.util.Logger
import com.dy.scraper.util.Utils
import java.io.File

class FileStorageManager {

    val downloadVideoDir: File get() = AppConfig.downloadVideoDir
    val downloadImageDir: File get() = AppConfig.downloadImageDir
    val tempVideoDir: File get() = AppConfig.tempVideoDir
    val tempImageDir: File get() = AppConfig.tempImageDir
    val resultDir: File get() = AppConfig.resultDir

    fun setup() {
        AppConfig.ensureDirs()

        // 检查临时目录残留文件
        val hasTempVideo = tempVideoDir.exists() && tempVideoDir.listFiles()?.any { it.isFile } == true
        val hasTempImage = tempImageDir.exists() && tempImageDir.listFiles()?.any { it.isFile } == true

        if (hasTempVideo || hasTempImage) {
            Logger.log("")
            Logger.log("检测到临时目录有残留文件（上次采集未完成），检查是否与目标目录重复...")
            dedupTempWithFinal(tempVideoDir, downloadVideoDir, "视频")
            dedupTempWithFinal(tempImageDir, downloadImageDir, "图片")
            Logger.log("临时目录去重校验完成")
            Logger.log("")
        }
    }

    private fun dedupTempWithFinal(tempDir: File, finalDir: File, label: String) {
        if (!tempDir.exists()) {
            Logger.log("  [$label] 临时目录不存在，跳过")
            return
        }

        val tempFiles = tempDir.listFiles()?.filter { it.isFile } ?: return
        if (tempFiles.isEmpty()) {
            Logger.log("  [$label] 临时目录为空")
            return
        }

        val finalFiles = mutableMapOf<String, File>()
        if (finalDir.exists()) {
            finalDir.listFiles()?.filter { it.isFile }?.forEach {
                finalFiles[it.name] = it
            }
        }

        var removedByMd5 = 0
        var kept = 0

        for (tempFile in tempFiles) {
            val finalFile = finalFiles[tempFile.name]
            if (finalFile == null) {
                kept++
                continue
            }

            try {
                val tempMd5 = Utils.md5(tempFile)
                val finalMd5 = Utils.md5(finalFile)
                if (tempMd5 != null && finalMd5 != null && tempMd5 == finalMd5) {
                    Utils.safeDelete(tempFile)
                    removedByMd5++
                } else {
                    Logger.log("  [$label] 同名但内容不同，保留: ${tempFile.name}")
                    kept++
                }
            } catch (_: Exception) {
                kept++
            }
        }

        if (removedByMd5 > 0) {
            Logger.log("  [$label] 删除 $removedByMd5 个重复文件，保留 $kept 个文件")
        } else if (kept > 0) {
            Logger.log("  [$label] 未发现重复文件，保留 $kept 个已有文件")
        }
    }

    fun moveAllToFinal(): Map<String, Map<String, Int>> {
        Logger.log("")
        Logger.log("=".repeat(60))
        Logger.log("  移动文件到最终目录...")
        Logger.log("=".repeat(60))

        val videoResult = moveDir(tempVideoDir, downloadVideoDir, "视频")
        val imageResult = moveDir(tempImageDir, downloadImageDir, "图片")
        return mapOf("video" to videoResult, "image" to imageResult)
    }

    private fun moveDir(srcDir: File, dstDir: File, label: String): Map<String, Int> {
        if (!srcDir.exists()) {
            Logger.log("  $label: 临时目录不存在，跳过")
            return mapOf("moved" to 0, "skipped" to 0)
        }

        dstDir.mkdirs()
        Logger.log("  ${label}最终目录: ${dstDir.absolutePath}")

        var moved = 0
        var skipped = 0

        srcDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val dst = File(dstDir, file.name)
            if (dst.exists()) {
                Logger.log("    跳过 (目标已存在): ${file.name}")
                Utils.safeDelete(file)
                skipped++
            } else {
                if (Utils.safeRename(file, dst)) {
                    moved++
                } else {
                    Logger.log("    移动失败: ${file.name}")
                    skipped++
                }
            }
        }

        try { srcDir.delete() } catch (_: Exception) { }

        Logger.log("  $label: 移动 $moved 个, 跳过 $skipped 个")
        return mapOf("moved" to moved, "skipped" to skipped)
    }
}