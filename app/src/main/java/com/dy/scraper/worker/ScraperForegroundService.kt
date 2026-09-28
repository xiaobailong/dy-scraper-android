package com.dy.scraper.worker

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.dy.scraper.util.Logger

/**
 * 前台服务 - 配合 WorkManager 使用，满足 Android 前台服务规范。
 * 真正的抓取逻辑在 ScraperWorker 中执行。
 */
class ScraperForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.log("ScraperForegroundService 启动")
        return START_STICKY
    }

    override fun onDestroy() {
        Logger.log("ScraperForegroundService 停止")
        super.onDestroy()
    }
}