package com.dy.scraper.util

import android.content.Context
import com.dy.scraper.R

enum class RunMode(val key: String, val labelResId: Int) {
    LOCAL("local", R.string.mode_local),
    YOUDAO("youdao", R.string.mode_youdao);

    companion object {
        const val PREFS_KEY = "run_mode"
        const val PREFS_KEY_DRAFT_URLS = "draft_urls"

        fun fromPrefs(context: Context): RunMode {
            val prefs = context.getSharedPreferences(
                Logger.PREFS_NAME, Context.MODE_PRIVATE
            )
            val key = prefs.getString(PREFS_KEY, LOCAL.key) ?: LOCAL.key
            return entries.find { it.key == key } ?: LOCAL
        }

        fun saveToPrefs(context: Context, mode: RunMode) {
            context.getSharedPreferences(
                Logger.PREFS_NAME, Context.MODE_PRIVATE
            ).edit().putString(PREFS_KEY, mode.key).apply()
        }
    }
}