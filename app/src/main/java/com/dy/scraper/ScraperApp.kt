package com.dy.scraper

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.dy.scraper.util.Logger

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
        Logger.init(this)
        Logger.d("========== App onCreate ==========")
        Logger.d("SDK_INT=${Build.VERSION.SDK_INT}, MANUFACTURER=${Build.MANUFACTURER}, MODEL=${Build.MODEL}")
        createNotificationChannel()
        Logger.d("========== App init complete ==========")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "斗虫",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "斗虫运行状态"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            Logger.d("Notification channel created: $CHANNEL_ID")
        } else {
            Logger.d("Notification channel not needed (SDK < O)")
        }
    }
}