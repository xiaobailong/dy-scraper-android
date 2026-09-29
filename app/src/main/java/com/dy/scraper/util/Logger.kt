package com.dy.scraper.util

import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {

    const val PREFS_NAME = "dy_scraper_settings"
    const val KEY_LOG_ENABLED = "log_enabled"

    private const val TAG = "DyScraper"
    private const val LOG_DIR_NAME = "dy-scraper"
    private const val LOG_FILE_PREFIX = "dy_scraper_log"
    private const val LOG_RETENTION_DAYS = 7L
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private var logFile: File? = null
    private var logFilePath: String = "N/A"
    private var initialized = false
    private var enabled = true
    private val listeners = mutableListOf<(String) -> Unit>()

    fun addListener(listener: (String) -> Unit) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: (String) -> Unit) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    @Synchronized
    fun init(context: android.content.Context) {
        if (initialized) return
        initialized = true

        val appCtx = context.applicationContext
        val sb = StringBuilder()

        enabled = readEnabledFromPrefs(appCtx)

        val sdk = android.os.Build.VERSION.SDK_INT
        val model = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

        sb.append("=== 斗虫 v${getVersionName(appCtx)} ===\n")
        sb.append("=== SDK: $sdk | Device: $model ===\n")

        if (enabled) {
            val result = trySetupLog()
            sb.append("=== Log: $result ===\n")
        } else {
            logFile = null
            logFilePath = expectedLogPath()
            sb.append("=== Log: disabled, not created ($logFilePath) ===\n")
        }

        sb.append("=== Log started ===\n")
        sb.append("=== Log enabled: $enabled ===\n")
        writeLine(sb.toString())
        if (enabled) {
            Log.d(TAG, sb.toString())
        }
    }

    private fun readEnabledFromPrefs(context: android.content.Context): Boolean {
        return try {
            context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_LOG_ENABLED, true)
        } catch (e: Exception) {
            true
        }
    }

    private fun trySetupLog(): String {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val logDir = File(downloadsDir, LOG_DIR_NAME)
            if (!logDir.exists()) logDir.mkdirs()

            cleanOldLogs(logDir)

            val file = File(logDir, todayLogFileName())
            logFile = file
            logFilePath = file.absolutePath
            "OK: $logFilePath"
        } catch (e: Exception) {
            "FAILED: ${e.message}"
        }
    }

    private fun todayLogFileName(): String = "${LOG_FILE_PREFIX}_${dateFormat.format(Date())}.txt"

    private fun expectedLogPath(): String {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(File(downloadsDir, LOG_DIR_NAME), todayLogFileName()).absolutePath
        } catch (e: Exception) {
            "N/A"
        }
    }

    private fun cleanOldLogs(dir: File) {
        try {
            val cutoffTime = System.currentTimeMillis() - LOG_RETENTION_DAYS * 24 * 60 * 60 * 1000L
            val logFiles = dir.listFiles { f ->
                f.isFile && f.name.startsWith(LOG_FILE_PREFIX) && f.name.endsWith(".txt")
            }
            if (logFiles != null) {
                for (file in logFiles) {
                    if (file.lastModified() < cutoffTime) {
                        val deleted = file.delete()
                        if (enabled) {
                            Log.d(TAG, "cleanOldLogs: ${file.name} lastModified=${dateFormat.format(Date(file.lastModified()))}, deleted=$deleted")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (enabled) {
                Log.w(TAG, "cleanOldLogs failed: ${e.message}")
            }
        }
    }

    @Synchronized
    fun setEnabled(context: android.content.Context, enabled: Boolean) {
        this.enabled = enabled
        try {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LOG_ENABLED, enabled)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Log setting save failed: ${e.message}")
        }
        if (enabled && logFile == null) {
            val result = trySetupLog()
            Log.d(TAG, "Log re-enabled, file setup: $result")
        }
    }

    fun isEnabled(): Boolean = enabled

    fun d(message: String) {
        if (!enabled) return
        val ts = timestampFormat.format(Date())
        val thread = Thread.currentThread().name
        val line = "[$ts][$thread] $message"
        Log.d(TAG, message)
        writeLine(line)
    }

    fun w(message: String) {
        if (!enabled) return
        val ts = timestampFormat.format(Date())
        val thread = Thread.currentThread().name
        val line = "[$ts][$thread] WARN: $message"
        Log.w(TAG, message)
        writeLine(line)
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (!enabled) return
        val ts = timestampFormat.format(Date())
        val thread = Thread.currentThread().name
        val sb = StringBuilder()
        sb.append("[$ts][$thread] ERROR: $message")
        if (throwable != null) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            sb.append("\n").append(sw.toString())
        }
        Log.e(TAG, message, throwable)
        writeLine(sb.toString())
    }

    fun log(message: String, level: String = "info") {
        if (!enabled) return
        val ts = timestampFormat.format(Date())
        val thread = Thread.currentThread().name
        val formatted = "[$ts][$thread] $message"

        when (level) {
            "error" -> Log.e(TAG, formatted)
            "warn" -> Log.w(TAG, formatted)
            "debug" -> Log.d(TAG, formatted)
            else -> Log.i(TAG, formatted)
        }

        writeLine(formatted)
        synchronized(listeners) {
            listeners.forEach { it(message) }
        }
    }

    fun logSection(title: String) {
        if (!enabled) return
        val sep = "=".repeat(60)
        log(sep)
        log("  $title")
        log(sep)
    }

    @Synchronized
    private fun writeLine(line: String) {
        if (!enabled) return
        val text = line + "\n"
        try {
            logFile?.let { file ->
                file.parentFile?.mkdirs()
                FileWriter(file, true).use { writer ->
                    writer.append(text)
                    writer.flush()
                }
            }
        } catch (ex: Exception) {
            if (enabled) {
                Log.e(TAG, "Log write failed: ${ex.message}")
            }
        }
    }

    fun getLogPath(): String = logFilePath

    fun isLogFileActive(): Boolean = logFile != null

    private fun getVersionName(context: android.content.Context): String {
        return try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pkgInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
}