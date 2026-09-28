package com.dy.scraper.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.dy.scraper.util.AppConfig
import com.dy.scraper.util.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue

class WebViewManager(private val context: Context) {

    @SuppressLint("SetJavaScriptEnabled")
    private val webView: WebView = WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = AppConfig.USER_AGENT
            useWideViewPort = true
            loadWithOverviewMode = true
            blockNetworkImage = false
            blockNetworkLoads = false
        }

        addJavascriptInterface(JsBridge(), "DyBridge")
    }

    // ── 网络请求收集 ──
    val collectedRequests = ConcurrentLinkedQueue<Map<String, String>>()
    val detailResponses = ConcurrentLinkedQueue<String>()

    // ── 页面加载状态 ──
    private var pageLoadDeferred: CompletableDeferred<Boolean>? = null
    private var finalUrl: String = ""
    var currentUrl: String = ""
        private set

    init {
        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                currentUrl = url
                Logger.log("  WebView 开始加载: ${url.take(80)}...", "debug")
            }

            // 反检测脚本注入：隐藏 WebView 特征，防止被抖音识别为自动化工具
            // 对应 Python Playwright 的 page.add_init_script()
            override fun onPageCommitVisible(view: WebView, url: String) {
                super.onPageCommitVisible(view, url)
                view.evaluateJavascript("""
                    (function() {
                        Object.defineProperty(navigator, 'webdriver', { get: function() { return false; } });
                        Object.defineProperty(navigator, 'plugins', { get: function() { return [1, 2, 3, 4, 5]; } });
                        Object.defineProperty(navigator, 'languages', { get: function() { return ['zh-CN', 'zh', 'en']; } });
                    })();
                """.trimIndent()) {
                    Logger.log("  反检测脚本注入完成", "debug")
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                finalUrl = url
                currentUrl = url
                Logger.log("  WebView 加载完成: ${url.take(80)}...", "debug")

                if ("douyin.com" in url || "iesdouyin.com" in url) {
                    pageLoadDeferred?.complete(true)
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()
                val contentType = request.requestHeaders["Content-Type"] ?: ""

                // 收集所有网络请求（对应 Playwright 的 page.on("response")）
                collectedRequests.add(mapOf(
                    "url" to url,
                    "contentType" to contentType,
                ))

                // 拦截抖音详情 API 响应（对应 douyin_detail.py）
                if (AppConfig.DETAIL_API_PATTERNS.any { it in url }) {
                    Logger.log("  [API拦截] ${url.take(100)}...", "debug")
                    detailResponses.add(url)
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String,
                failingUrl: String
            ) {
                Logger.log("  WebView 加载错误: [$errorCode] $description", "error")
                pageLoadDeferred?.complete(false)
            }
        }
    }

    // ── 页面加载 ──
    suspend fun loadUrl(url: String): Boolean {
        Logger.log("[WebView] 加载页面: ${url.take(80)}...")
        pageLoadDeferred = CompletableDeferred()
        collectedRequests.clear()
        detailResponses.clear()

        val result = withContext(Dispatchers.Main) {
            webView.loadUrl(url)
            try {
                withTimeout(AppConfig.PAGE_LOAD_TIMEOUT_MS) {
                    pageLoadDeferred!!.await()
                }
            } catch (_: Exception) {
                Logger.log("  页面加载超时 (${AppConfig.PAGE_LOAD_TIMEOUT_MS}ms)", "warn")
                false
            }
        }

        Logger.log("  收集到 ${collectedRequests.size} 个网络请求", "debug")
        Logger.log("  拦截到 ${detailResponses.size} 个详情API响应", "debug")
        return result
    }

    fun getFinalUrl(): String = finalUrl

    fun getCookies(): String {
        val cookieManager = android.webkit.CookieManager.getInstance()
        return cookieManager.getCookie("https://www.douyin.com") ?: ""
    }

    // ── JS 执行 ──
    suspend fun evaluateJavascript(script: String): String {
        val startTime = System.currentTimeMillis()
        val result = withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<String>()
            webView.evaluateJavascript(script) { result ->
                deferred.complete(result ?: "null")
            }
            deferred.await()
        }
        Logger.log("  JS执行耗时: ${System.currentTimeMillis() - startTime}ms, 结果长度: ${result.length}", "debug")
        return result
    }

    // ── 等待页面渲染 ──
    suspend fun waitForRender() {
        Logger.log("  等待页面渲染 (最多${AppConfig.RENDER_WAIT_MS}ms)...")
        try {
            withTimeout(AppConfig.RENDER_WAIT_MS) {
                // 等待 video 元素出现或超时
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < AppConfig.RENDER_WAIT_MS) {
                    val hasVideo = evaluateJavascript(
                        "document.querySelector('video') !== null"
                    )
                    if (hasVideo == "true") break
                    kotlinx.coroutines.delay(500)
                }
            }
        } catch (_: Exception) {
            Logger.log("  渲染等待超时，继续处理...", "debug")
        }
        // 额外等待确保异步内容加载完成
        kotlinx.coroutines.delay(3000)
    }

    // ── 资源清理 ──
    fun destroy() {
        Logger.log("[WebView] 销毁 WebView 实例")
        webView.apply {
            stopLoading()
            clearHistory()
            clearCache(true)
            destroy()
        }
    }

    // ── JS 桥接（用于页面与 Native 双向通信） ──
    inner class JsBridge {
        @JavascriptInterface
        fun onDataExtracted(data: String) {
            Logger.log("  [JS Bridge] 收到数据: ${data.take(100)}...", "debug")
        }
    }
}