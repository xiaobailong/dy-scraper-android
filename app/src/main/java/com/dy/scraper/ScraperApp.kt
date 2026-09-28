package com.dy.scraper

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class ScraperApp : Application() {

    companion object {
        const val CHANNEL_ID = "dy_scraper_channel"
        const val NOTIFICATION_ID = 1001

        lateinit var instance: ScraperApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "抖音抓取服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示抖音内容抓取进度"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}