package com.dy.scraper.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object YoudaoFetcher {

    const val DEFAULT_YOUDAO_API =
        "https://note.youdao.com/yws/api/note/70f23df9766d959e890f02747415d2f4?sev=j1&editorType=1&unloginId=c6e838d2-5c51-045a-6067-49fa4b84b37f&editorVersion=new-json-editor&sec=v1"
    private const val PREFS_KEY_YOUDAO_URL = "youdao_api_url"

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
        val url = context.getSharedPreferences(Logger.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREFS_KEY_YOUDAO_URL, "") ?: ""
        return url.ifBlank { DEFAULT_YOUDAO_API }
    }

    fun saveApiUrl(context: Context, url: String) {
        context.getSharedPreferences(Logger.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFS_KEY_YOUDAO_URL, url)
            .apply()
        Logger.d("YoudaoFetcher: saved API URL = $url")
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
        return result
    }
}