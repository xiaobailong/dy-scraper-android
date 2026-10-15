package com.dy.scraper.crypto

import android.content.Context
import com.dy.scraper.util.Logger
import java.io.File

/**
 * 「已被加密/解密取代、但系统不让 App 移走」的源文件登记表。
 *
 * 背景（真机 2026-10-15）：华为 EMUI 的「回收站」会拦截 App 的删除 —— 有时把文件改名成隐藏的
 * `.<原名>.hwbk`（无害），有时完全不生效，于是明文和密文同时留在目录里，加密页列表从 8 变 16。
 * `CryptoManager.removeSourceFile()` 用 rename 规避了绝大多数情况；剩下的（连 rename 都被拦）
 * 在这里按「路径 + 大小 + 修改时间」登记，扫描时跳过 → 列表口径永远等于用户实际看到的文件数。
 *
 * 用户在系统文件管理器里把这些文件删掉后，[prune] 会在下次扫描时把记录清掉。
 */
object SupersededSourceRegistry {

    private const val PREFS_NAME = "dy_crypto_superseded"
    private const val KEY_ENTRIES = "entries"
    private const val SEP = "|"

    /** 登记键：路径|大小|修改时间（三者全一致才算同一个文件） */
    fun keyOf(file: File): String =
        "${file.absolutePath}$SEP${file.length()}$SEP${file.lastModified()}"

    /** 扫描前读一次（避免每个文件都读一次 SharedPreferences） */
    fun snapshot(context: Context): Set<String> = readPrefs(context)

    fun isSuperseded(context: Context, file: File): Boolean =
        keyOf(file) in readPrefs(context)

    /** 记录一个「加/解密后系统不让 App 移走」的源文件（列表将隐藏它） */
    fun record(context: Context, file: File) {
        val entries = readPrefs(context).toMutableSet()
        if (entries.add(keyOf(file))) {
            writePrefs(context, entries)
            Logger.log(
                "SupersededSourceRegistry: 登记残留源文件（列表隐藏，可在系统文件管理器删除）: ${file.absolutePath}",
                "warn"
            )
        }
    }

    /** 清理已经不在磁盘上的记录（文件被删掉后自动出表） */
    fun prune(context: Context) {
        val entries = readPrefs(context)
        if (entries.isEmpty()) return
        val alive = entries.filter { pathOf(it).let { path -> File(path).exists() } }.toSet()
        if (alive.size != entries.size) {
            writePrefs(context, alive)
            Logger.d("SupersededSourceRegistry: 清理已不存在的残留记录 ${entries.size - alive.size} 条，剩余 ${alive.size} 条")
        }
    }

    /** 键里的路径部分（大小/修改时间在最后两段） */
    private fun pathOf(key: String): String =
        key.substringBeforeLast(SEP).substringBeforeLast(SEP)

    private fun readPrefs(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_ENTRIES, emptySet())?.toSet() ?: emptySet()

    private fun writePrefs(context: Context, entries: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_ENTRIES, entries).apply()
    }
}