package com.dy.scraper.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

object YoudaoFetcher {

    private const val ENCRYPTED_DEFAULT_API = "LA0nEwFbX0ocXURXGlgvViAYPE0RDh1KC0VDHVVRKQwqFicGXVZAA0ABVFQNFnYVIEBmWhdZSVUUAgIFABZ0EnEdYQV" +
            "GXgMABA9aAxJEJEowFiE3CxEVWEMURVxYTidKKjA3XhFXFV1BClQAGRQjFnVUY1dHAF1TQgQHHwAYJkJwG2tXEFJHA1RXVFtATjJ1IQsgCh0PTQ" +
            "sXRR1YR04uDiEdOhcdE1YWF1ENRAU="
    private const val XOR_KEY = "DyScraper2024!@#"
    private const val PREFS_KEY_YOUDAO_URL = "youdao_api_url"

    internal fun getDefaultApiUrl(): String {
        val encrypted = Base64.getDecoder().decode(ENCRYPTED_DEFAULT_API)
        val keyBytes = XOR_KEY.toByteArray(Charsets.UTF_8)
        for (i in encrypted.indices) {
            encrypted[i] = (encrypted[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        return String(encrypted, Charsets.UTF_8)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class FetchResult(
        val urls: List<String>,
        val error: String? = null,
    )

    fun getApiUrl(context: Context): String {
        val savedUrl = context.getSharedPreferences(Logger.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREFS_KEY_YOUDAO_URL, "") ?: ""
        if (savedUrl.isBlank()) return getDefaultApiUrl()

        if (!isValidNoteApiUrl(savedUrl)) {
            Logger.d("YoudaoFetcher: saved URL is not a valid note API, falling back to default")
            return getDefaultApiUrl()
        }

        return savedUrl
    }

    private fun isValidNoteApiUrl(url: String): Boolean {
        return url.contains("/yws/api/note/") && Regex("note/([a-f0-9]{32})").containsMatchIn(url)
    }

    fun saveApiUrl(context: Context, url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isBlank()) {
            Logger.d("YoudaoFetcher: save rejected, URL is blank")
            return false
        }
        if (!isValidNoteApiUrl(trimmed)) {
            Logger.d("YoudaoFetcher: save rejected, invalid note API URL: $trimmed")
            return false
        }
        context.getSharedPreferences(Logger.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFS_KEY_YOUDAO_URL, trimmed)
            .apply()
        Logger.d("YoudaoFetcher: saved API URL = $trimmed")
        return true
    }

    fun isNoteApiUrlValid(url: String): Boolean = isValidNoteApiUrl(url.trim())

    fun getDisplayApiUrl(context: Context): String {
        val savedUrl = context.getSharedPreferences(Logger.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREFS_KEY_YOUDAO_URL, "") ?: ""
        return if (savedUrl.isBlank() || !isValidNoteApiUrl(savedUrl)) getDefaultApiUrl()
        else savedUrl
    }

    suspend fun fetchUrls(context: Context): FetchResult =
        fetchUrlsInternal(getApiUrl(context))

    private suspend fun fetchUrlsInternal(apiUrl: String): FetchResult =
        withContext(Dispatchers.IO) {
            try {
                Logger.d("YoudaoFetcher: fetching from $apiUrl")
                val request = Request.Builder()
                    .url(apiUrl)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/126.0.0.0 Safari/537.36"
                    )
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    val msg = "HTTP ${response.code}: ${response.message}"
                    Logger.e("YoudaoFetcher: $msg")
                    return@withContext FetchResult(emptyList(), msg)
                }

                val body = response.body?.string()
                response.close()
                if (body.isNullOrBlank()) {
                    Logger.d("YoudaoFetcher: empty response body")
                    return@withContext FetchResult(emptyList(), "有道云返回内容为空")
                }

                val parsed = parseContent(body)
                Logger.d(
                    "YoudaoFetcher: parsed ${parsed.length} chars, " +
                    "preview: ${parsed.take(200)}"
                )

                val urls = extractUrls(parsed)
                Logger.d("YoudaoFetcher: extracted ${urls.size} URLs")
                urls.forEach { Logger.d("  $it") }

                FetchResult(urls)
            } catch (e: Exception) {
                Logger.e("YoudaoFetcher: fetch failed", e)
                FetchResult(emptyList(), "获取有道云内容失败: ${e.message}")
            }
        }

    private fun parseContent(raw: String): String {
        return try {
            val data = JSONObject(raw)
            var content = data.optString("content", "")
            if (content.isBlank()) return ""

            if (content.startsWith("{")) {
                val jsonContent = JSONObject(content)
                val texts = mutableListOf<String>()
                extractText8(jsonContent, texts)
                if (texts.isNotEmpty()) {
                    content = texts.joinToString("\n")
                }
            }

            content.replace("\\n", "\n").replace("\\r\\n", "\n")
        } catch (e: Exception) {
            Logger.d("YoudaoFetcher: JSON parse failed, using raw content")
            raw.replace("\\n", "\n").replace("\\r\\n", "\n")
        }
    }

    private fun extractText8(obj: Any, texts: MutableList<String>) {
        when (obj) {
            is JSONObject -> {
                if (obj.has("8")) {
                    val v = obj.optString("8", "")
                    if (v.isNotBlank()) texts.add(v)
                }
                val keys = obj.keys()
                while (keys.hasNext()) {
                    extractText8(obj.get(keys.next()), texts)
                }
            }
            is org.json.JSONArray -> {
                for (i in 0 until obj.length()) {
                    extractText8(obj.get(i), texts)
                }
            }
        }
    }

    private fun extractUrls(content: String): List<String> {
        val rawUrls = Utils.extractUrls(content)
        val result = mutableListOf<String>()
        var skippedYd = 0
        var skippedNoCom = 0
        for (u in rawUrls) {
            if ("youdao.com" in u) {
                skippedYd++
                Logger.d("YoudaoFetcher: skip youdao domain: $u")
                continue
            }
            if (".com" !in u) {
                skippedNoCom++
                Logger.d("YoudaoFetcher: skip non-.com: $u")
                continue
            }
            result.add(Utils.normalizeUrl(u))
        }

        Logger.d(
            "YoudaoFetcher: skipped $skippedYd youdao domains, " +
            "$skippedNoCom non-.com domains"
        )
        return result.distinct()
    }
}