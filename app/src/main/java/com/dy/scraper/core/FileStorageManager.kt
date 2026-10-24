package com.dy.scraper.core

import com.dy.scraper.util.AppConfig
import com.dy.scraper.util.Logger
import java.io.File

class FileStorageManager {

    val downloadVideoDir: File get() = AppConfig.downloadVideoDir
    val downloadImageDir: File get() = AppConfig.downloadImageDir
    val tmpDir: File get() = AppConfig.tmpDir

    fun setup() {
        AppConfig.ensureDirs()
        Logger.log("  存储目录已就绪")
        Logger.log("    视频目录: ${downloadVideoDir.absolutePath}")
        Logger.log("    图片目录: ${downloadImageDir.absolutePath}")
        Logger.log("    临时目录: ${tmpDir.absolutePath}")
    }

    fun cleanTmp() {
        if (!tmpDir.exists()) return
        tmpDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                if (!file.delete()) {
                    Logger.log("  无法删除临时文件: ${file.name}", "warn")
                }
            } else if (file.isDirectory) {
                file.deleteRecursively()
            }
        }
        Logger.log("  临时目录已清理: ${tmpDir.absolutePath}")
    }
}