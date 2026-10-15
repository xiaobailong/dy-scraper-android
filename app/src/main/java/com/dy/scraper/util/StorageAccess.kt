package com.dy.scraper.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * 存储访问权限（Android 11+ 的“所有文件访问”）。
 *
 * 背景：Android 11（API 30）起，`Download/` 属于“受管控目录”，FUSE 会按文件的
 * `owner_package_name` 过滤：App 只能看到自己创建的文件。别的 App、电脑 MTP、
 * 系统文件管理器恢复回来的文件，App 的 `listFiles()` / `ls` / MediaStore 查询都看不到，
 * 于是出现“手机文件管理器里有 7 张、App 列表里只有 6 张”（见 memory-bank ISSUE-004）。
 *
 * 授予 `MANAGE_EXTERNAL_STORAGE`（设置里的“所有文件访问”）后即可看到目录里全部文件。
 */
object StorageAccess {

    /** 是否已具备“所有文件访问”；API 30 以下恒为 true（旧版权限模型没有该隔离） */
    fun hasAllFilesAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return try {
            Environment.isExternalStorageManager()
        } catch (e: Exception) {
            Logger.d("StorageAccess: 检查所有文件访问失败: ${e.message}")
            false
        }
    }

    /** 打开“所有文件访问”授权页；机型不支持时退到应用详情页 */
    fun openAllFilesAccessSettings(context: Context) {
        val pkgUri = Uri.parse("package:${context.packageName}")
        val candidates = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            candidates.add(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri))
        }
        candidates.add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri))

        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Logger.d("StorageAccess: 已打开设置页 ${intent.action}")
                return
            } catch (e: Exception) {
                Logger.d("StorageAccess: 打开设置页失败(${intent.action}): ${e.message}")
            }
        }
        Logger.log("StorageAccess: 无法打开授权页，请手动到 设置 → 应用 → dy-scraper → 权限 里打开“所有文件访问”", "warn")
    }
}
