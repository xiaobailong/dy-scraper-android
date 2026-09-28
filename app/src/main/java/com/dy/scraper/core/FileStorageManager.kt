package com.dy.scraper.core

import com.dy.scraper.util.AppConfig
import java.io.File

class FileStorageManager {

    val downloadVideoDir: File get() = AppConfig.downloadVideoDir
    val downloadImageDir: File get() = AppConfig.downloadImageDir
    val resultDir: File get() = AppConfig.resultDir

    fun setup() {
        AppConfig.ensureDirs()
    }
}