package com.dy.scraper.core

import com.dy.scraper.util.AppConfig
import com.dy.scraper.util.Logger
import java.io.File

class FileStorageManager {

    val downloadVideoDir: File get() = AppConfig.downloadVideoDir
    val downloadImageDir: File get() = AppConfig.downloadImageDir
    val resultDir: File get() = AppConfig.resultDir

    fun setup() {
        AppConfig.ensureDirs()
        Logger.log("  存储目录已就绪")
        Logger.log("    视频目录: ${downloadVideoDir.absolutePath}")
        Logger.log("    图片目录: ${downloadImageDir.absolutePath}")
        Logger.log("    结果目录: ${resultDir.absolutePath}")
    }
}