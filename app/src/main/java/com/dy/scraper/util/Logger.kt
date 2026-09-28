package com.dy.scraper.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {
    private const val TAG = "DyScraper"
    private val listeners = mutableListOf<(String) -> Unit>()

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    fun log(message: String, level: String = "info") {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val formatted = "[$time] $message"

        when (level) {
            "error" -> Log.e(TAG, formatted)
            "warn" -> Log.w(TAG, formatted)
            "debug" -> Log.d(TAG, formatted)
            else -> Log.i(TAG, formatted)
        }

        listeners.forEach { it(formatted) }
    }

    fun logSection(title: String) {
        log("=".repeat(60))
        log("  $title")
        log("=".repeat(60))
    }
}