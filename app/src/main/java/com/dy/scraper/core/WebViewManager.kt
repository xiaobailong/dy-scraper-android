package com.dy.scraper.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.dy.scraper.util.AppConfig
import com.dy.scraper.util.Logger
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WebViewManager(private val context: Context) {

    private val gson = Gson()

    companion object {
        /** 网络日志中记录的头字段白名单（小写） */
        private val NETWORK_LOG_HEADER_WHITELIST = setOf(
            "content-type", "content-length", "referer", "origin",
            "x-requested-with", "x-requested-from", "x-csrftoken",
            "x-tt-request-tag", "x-tt-trace-id", "x-tt-logid",
            "x-argus", "x-ladon", "x-khronos", "x-gorgon",
            "cookie", "user-agent", "accept", "accept-encoding",
        )
    }

    // ── 页面内钩子（document-start 注入 XHR/fetch 旁听 + SSR 快照） ──
    /** document-start 脚本是否已由 androidx.webkit 安装（false = 只能 onPageStarted 兜底注入） */
    var documentStartHookInstalled = false
        private set

    /** 已回传过的 API URL（去掉 query），避免重复解析与重复日志 */
    private val hookedApiUrls = ConcurrentHashMap.newKeySet<String>()

    // ── 影子请求（抓取详情 API 响应体，对应 Playwright 的 page.on("response") + resp.text()） ──
    private val shadowExecutor = Executors.newFixedThreadPool(2)
    private val shadowClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()


    @SuppressLint("SetJavaScriptEnabled")
    private lateinit var webView: WebView

    init {
        val wv = WebView(context)
        initWebView(wv)
    }

    constructor(context: Context, externalWebView: WebView) : this(context) {
        // 销毁 init 块创建的临时 WebView（已被 externalWebView 替换，避免内存泄漏）
        try {
            webView.destroy()
        } catch (_: Exception) {
        }
        initWebView(externalWebView)
    }

    private fun initWebView(wv: WebView) {
        webView = wv
        wv.settings.apply {
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

        wv.addJavascriptInterface(JsBridge(), PageHook.BRIDGE_NAME)

        // WebViewClient 必须在 initWebView 中绑定（而非独立 init 块），
        // 确保次级构造函数替换 WebView 后 still 拥有正确的 Client（含 shouldOverrideUrlLoading）
        setupWebViewClient(wv)

        // 安装 document-start 钩子
        // 内部创建的 WebView 尚未 attached → 通过 listener 延迟安装
        // 外部 WebView 已在布局中 → 直接安装；若 provider 未就绪则 post 重试
        if (wv.isAttachedToWindow) {
            installDocumentStartHook()
        } else {
            wv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    installDocumentStartHook()
                    wv.removeOnAttachStateChangeListener(this)
                }
                override fun onViewDetachedFromWindow(v: View) {}
            })
        }
    }

    /**
     * 绑定 WebViewClient：负责页面生命周期回调、URL 跳转拦截（防拉起外部 App）、
     * 网络请求收集、反检测脚本注入、钩子兜底注入。
     */
    private fun setupWebViewClient(wv: WebView) {
        wv.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                currentUrl = url
                Logger.log("  WebView 开始加载: ${url.take(80)}...", "debug")
                // 兜底：WebView 不支持 document-start 脚本时尽早注入（脚本自身幂等）。
                if (!documentStartHookInstalled) {
                    try {
                        view.evaluateJavascript(PageHook.SCRIPT, null)
                    } catch (_: Exception) {
                    }
                }
            }

            /**
             * 拦截外部 App 跳转（douyin://, intent://, snssdk://, aweme://）。
             * 不拦截会导致抖音 App 被拉起，WebView 显示空白。
             */
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                if (url.startsWith("douyin://") ||
                    url.startsWith("intent://") ||
                    url.startsWith("snssdk://") ||
                    url.startsWith("aweme://")
                ) {
                    Logger.log("  [拦截] 阻止外部App跳转: ${url.take(100)}", "warn")
                    return true
                }
                return super.shouldOverrideUrlLoading(view, request)
            }

            // 反检测脚本注入：隐藏 WebView 特征，防止被抖音识别为自动化工具
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
                val method = request.method

                collectedRequests.add(mapOf(
                    "url" to url,
                    "contentType" to contentType,
                ))

                if (AppConfig.isNetworkLogEnabled(context) && !url.contains("douyinstatic.com")) {
                    val headers = request.requestHeaders.entries
                        .filter { (k, _) -> k.lowercase() in NETWORK_LOG_HEADER_WHITELIST }
                        .joinToString(", ") { (k, v) -> "$k: ${v.take(120)}" }
                    val urlShort = url.take(200)
                    Logger.log("  [NET:${method}] $urlShort", "network")
                    if (headers.isNotEmpty()) {
                        Logger.log("    Headers: $headers", "network")
                    }
                }

                if (PageHook.isDetailApiUrl(url)) {
                    Logger.log("  [API拦截] ${url.take(100)}...", "debug")
                    detailResponses.add(url)
                    if (PageHook.ENABLE_REPLAY_FALLBACK) shadowFetchDetail(url)
                }

                return super.shouldInterceptRequest(view, request)
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onLoadResource(view: WebView, url: String) {
                super.onLoadResource(view, url)
                if (AppConfig.isNetworkLogEnabled(context) && !url.contains("douyinstatic.com")) {
                    Logger.log("  [NET:LOADED] ${url.take(200)}", "network")
                }
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String,
                failingUrl: String
            ) {
                Logger.log("  WebView 请求错误(已废弃API): [$errorCode] $description url=${failingUrl.take(80)}", "error")
                pageLoadDeferred?.complete(false)
            }
        }
    }

    /**
     * 安装 document-start 钩子（对应 Playwright 的 `add_init_script`）。
     * 钩子在页面脚本之前挂到 XHR/fetch 上并快照 SSR 变量 —— 这是唯一能拿到抖音详情响应体的位置
     * （事后重放缺 a_bogus：`[API影子] HTTP 200, 长度 0`）。
     */
    private fun installDocumentStartHook() {
        // 某些设备上 WebViewProvider 可能在 attached 后仍未就绪，用 post 兜底
        val doInstall = {
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    WebViewCompat.addDocumentStartJavaScript(webView, PageHook.SCRIPT, setOf("*"))
                    documentStartHookInstalled = true
                    Logger.log("  [Hook] document-start 钩子已注入（XHR/fetch 旁听 + SSR 快照）")
                } else {
                    Logger.log("  [Hook] 当前 WebView 不支持 document-start 脚本，回退 onPageStarted 注入", "warn")
                }
            } catch (e: Exception) {
                Logger.log("  [Hook] document-start 注入失败: ${e.message}，回退 onPageStarted 注入", "warn")
            }
        }

        try {
            doInstall()
        } catch (_: Exception) {
            // 如果首次调用因 provider 未就绪而失败，post 到消息队列尾部重试
            webView.post {
                doInstall()
            }
        }
    }

    // ── 网络请求收集 ──
    val collectedRequests = ConcurrentLinkedQueue<Map<String, String>>()
    val detailResponses = ConcurrentLinkedQueue<String>()

    /** 详情 API 响应体（{"url":.., "body":..}），由影子请求填充 —— 这是拿到高清视频地址的关键 */
    val detailBodies = ConcurrentLinkedQueue<Map<String, String>>()

    // ── 页面加载状态 ──
    private var pageLoadDeferred: CompletableDeferred<Boolean>? = null

    /** JS fetch 回调（requestId → 结果），由 JsBridge.onApiResponse 填充 */
    private val jsCallbacks = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private var finalUrl: String = ""
    var currentUrl: String = ""
        private set

    // ── 页面加载 ──
    suspend fun loadUrl(url: String): Boolean {
        Logger.log("[WebView] 加载页面: ${url.take(80)}...")
        pageLoadDeferred = CompletableDeferred()
        collectedRequests.clear()
        detailResponses.clear()
        detailBodies.clear()
        hookedApiUrls.clear()

        val pageLoadTimeout = AppConfig.getPageLoadTimeoutMs(context)
        val result = withContext(Dispatchers.Main) {
            webView.loadUrl(url)
            try {
                withTimeout(pageLoadTimeout) {
                    pageLoadDeferred!!.await()
                }
            } catch (_: Exception) {
                Logger.log("  页面加载超时 (${pageLoadTimeout}ms)", "warn")
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

    // ── 影子请求：用同一会话（Cookie/UA/Referer）重新请求详情 API，抓取响应体 ──
    private fun shadowFetchDetail(url: String) {
        // 重放请求缺页面 JS 现算的 a_bogus → 抖音返回空 body 或整页 HTML（真机日志已证），默认关闭
        if (!PageHook.ENABLE_REPLAY_FALLBACK) {
            Logger.log("  [API影子] 已禁用（重放无签名，body 恒为空）: ${url.take(80)}...", "debug")
            return
        }
        // 同一个 API URL 只抓一次
        if (detailBodies.any { it["url"] == url }) return
        shadowExecutor.execute {
            try {
                val cookie = getCookies()
                val builder = Request.Builder()
                    .url(url)
                    .header("User-Agent", AppConfig.USER_AGENT)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Referer", "https://www.douyin.com/")
                if (cookie.isNotEmpty()) builder.header("Cookie", cookie)
                shadowClient.newCall(builder.build()).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    Logger.log(
                        "  [API影子] HTTP ${resp.code}, 长度 ${body.length}, ${url.take(80)}...",
                        "debug"
                    )
                    if (resp.isSuccessful && body.isNotEmpty()) {
                        detailBodies.add(mapOf("url" to url, "body" to body))
                    }
                }
            } catch (e: Exception) {
                Logger.log("  [API影子] 请求失败: ${e.message}", "warn")
            }
        }
    }

    /**
     * 等待拦截到的详情 API 响应体（钩子回传；优先返回属于 [pageUrl] 的响应）
     */
    suspend fun waitForDetailBodies(timeoutMs: Long = 8000): List<Map<String, String>> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val all = detailBodies.toList()
            if (all.isNotEmpty()) {
                // 再多等 300ms，让同页面的其它响应（note/detail 两种）都进来
                delay(300)
                return detailBodies.toList()
            }
            delay(200)
        }
        Logger.log("  [API] 等待 ${timeoutMs}ms 仍未捕获到详情响应体（检查 Hook 是否注入成功）", "warn")
        return detailBodies.toList()
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

    // ── JS 执行（返回 base64 编码的 UTF-8 文本，避免 JSON 转义导致内容损坏） ──
    suspend fun evaluateJavascriptText(script: String): String {
        val wrapped = """
            (function() {
                try {
                    var v = (function() { $script })();
                    if (typeof v !== 'string') v = JSON.stringify(v);
                    return btoa(unescape(encodeURIComponent(v)));
                } catch (e) {
                    return '';
                }
            })();
        """.trimIndent()
        val raw = evaluateJavascript(wrapped)
        if (raw.isEmpty() || raw == "null") return ""
        return try {
            val encoded = gson.fromJson(raw, String::class.java) ?: return ""
            if (encoded.isEmpty()) "" else String(
                android.util.Base64.decode(encoded, android.util.Base64.DEFAULT),
                Charsets.UTF_8
            )
        } catch (e: Exception) {
            Logger.log("  [JS] base64 解码失败: ${e.message}", "warn")
            ""
        }
    }

    // ── 通过 JS fetch 从 WebView 内部请求 API（共享 Cookie 会话，结果经 JS 桥异步回传） ──
    suspend fun fetchApiViaJs(apiUrl: String): String? {
        val escapedUrl = apiUrl.replace("\\", "\\\\").replace("'", "\\'")
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        jsCallbacks[requestId] = deferred

        val script = """
            (function() {
                var rid = '$requestId';
                try {
                    fetch('$escapedUrl', {credentials: 'include', headers: {'Accept': 'application/json, text/plain, */*'}})
                        .then(function(r) {
                            if (!r.ok) { DyBridge.onApiResponse(rid, 'HTTP_' + r.status); return null; }
                            return r.text();
                        })
                        .then(function(t) {
                            if (t !== null && t !== undefined) DyBridge.onApiResponse(rid, String(t));
                        })
                        .catch(function(e) { DyBridge.onApiResponse(rid, 'ERROR:' + (e && e.message ? e.message : String(e))); });
                } catch (e) {
                    DyBridge.onApiResponse(rid, 'ERROR:' + (e && e.message ? e.message : String(e)));
                }
                return 'started';
            })();
        """.trimIndent()

        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(script) { }
        }

        val raw = try {
            withTimeout(8000) { deferred.await() }
        } catch (_: Exception) {
            Logger.log("  [JS fetch API] 超时未返回", "warn")
            null
        } finally {
            jsCallbacks.remove(requestId)
        }

        if (raw == null) return null
        if (raw.startsWith("HTTP_") || raw.startsWith("ERROR:")) {
            Logger.log("  [JS fetch API] 失败: ${raw.take(120)}", "warn")
            return null
        }
        Logger.log("  [JS fetch API] 响应体长度: ${raw.length}", "debug")
        return raw
    }

    // ── 等待页面渲染 ──
    suspend fun waitForRender() {
        val renderWaitMs = AppConfig.getRenderWaitMs(context)
        Logger.log("  等待页面渲染 (最多${renderWaitMs}ms)...")
        val probe = """
            (function() {
                try {
                    if (window._ROUTER_DATA && JSON.stringify(window._ROUTER_DATA).indexOf('play_addr') >= 0) return true;
                } catch (e) {}
                try {
                    // document-start 钩子的 SSR 快照（抖音 hydration 后会把 _ROUTER_DATA 删掉，只能读快照）
                    var snap = window.__dySsr || {};
                    for (var k in snap) {
                        if (typeof snap[k] === 'string' && snap[k].indexOf('play_addr') >= 0) return true;
                    }
                } catch (e) {}
                try {
                    if (window.__INITIAL_STATE__ && JSON.stringify(window.__INITIAL_STATE__).indexOf('play_addr') >= 0) return true;
                } catch (e) {}
                if (document.querySelectorAll('img').length > 5) return true;
                if (document.querySelector('video')) return true;
                return false;
            })()
        """.trimIndent()

        try {
            withTimeout(renderWaitMs) {
                val startTime = System.currentTimeMillis()
                var renderedAt = -1L
                while (System.currentTimeMillis() - startTime < renderWaitMs) {
                    val rendered = evaluateJavascript(probe)
                    if (rendered == "true") {
                        renderedAt = System.currentTimeMillis()
                        Logger.log("  页面主要内容已渲染", "debug")
                        break
                    }
                    delay(400)
                }
                if (renderedAt < 0) Logger.log("  未检测到主要内容，继续尝试...", "debug")
            }
        } catch (_: Exception) {
            Logger.log("  渲染等待超时，继续处理...", "debug")
        }
        // 额外等待，确保异步接口/图片列表补齐
        delay(1500)
    }

    // ── 轻量清理（抓取任务间复用，不销毁 WebView 原生层） ──
    fun cleanup() {
        Logger.log("[WebView] 清理 WebView（保留可复用状态）")
        webView.apply {
            onPause()
            loadUrl("about:blank")
            stopLoading()
            try { removeJavascriptInterface(PageHook.BRIDGE_NAME) } catch (_: Exception) {}
            clearHistory()
            clearCache(true)
        }
    }

    // ── 完整销毁（Activity 生命周期结束时调用，不可逆） ──
    fun destroy() {
        Logger.log("[WebView] 销毁 WebView 实例")
        try {
            shadowExecutor.shutdownNow()
        } catch (_: Exception) {
        }
        cleanup()
        webView.destroy()
    }

    // ── JS 桥接（用于页面与 Native 双向通信） ──
    inner class JsBridge {
        @JavascriptInterface
        fun onDataExtracted(data: String) {
            Logger.log("  [JS Bridge] 收到数据: ${data.take(100)}...", "debug")
        }

        /**
         * document-start 钩子回传的详情 API 响应体（页面自己发的请求，带 a_bogus 签名）。
         * 对应 Playwright 的 `page.on("response")` 回调，是拿到高清视频地址的主路径。
         */
        @JavascriptInterface
        fun onApiBody(url: String, body: String) {
            try {
                if (!PageHook.isUsableBody(body)) {
                    Logger.log("  [Hook] 丢弃无效响应体(${body.length} 字符): ${url.take(80)}...", "debug")
                    return
                }
                if (!PageHook.isDetailApiUrl(url)) return
                if (!hookedApiUrls.add(PageHook.apiDedupeKey(url))) return
                detailBodies.add(mapOf("url" to url, "body" to body, "source" to "hook"))

                val media = MediaExtractor.parseApiBody(body, currentUrl)
                val videoInfo = if (media.videoUrls.isEmpty()) "" else "，最优: ${media.videoUrls[0].take(90)}"
                Logger.log(
                    "  [Hook] 捕获详情响应体 ${body.length} 字符 → 视频 ${media.videoUrls.size} 个$videoInfo" +
                            "，图片 ${media.imageUrls.size} 个",
                    if (media.hasMedia) "info" else "warn"
                )
            } catch (e: Exception) {
                Logger.log("  [Hook] 响应体处理异常: ${e.message}", "warn")
            }
        }

        /** JS fetch 异步回传响应体（evaluateJavascript 不会 await Promise，必须走桥接） */
        @JavascriptInterface
        fun onApiResponse(requestId: String, body: String) {
            jsCallbacks[requestId]?.complete(body)
        }

        /**
         * 全量网络日志：页面所有 XHR/fetch 请求的响应都会回传到这里。
         * 用于分析哪些 API 返回了视频 URL、请求参数等。
         */
        @JavascriptInterface
        fun onNetworkLog(jsonStr: String) {
            if (!AppConfig.isNetworkLogEnabled(context)) return
            try {
                val entry = gson.fromJson(jsonStr, Map::class.java) ?: return
                val url = (entry["url"] as? String) ?: return
                val status = (entry["status"] as? Double)?.toInt() ?: 0
                val body = (entry["body"] as? String) ?: ""
                val ct = (entry["contentType"] as? String) ?: ""
                val method = (entry["method"] as? String) ?: ""

                val urlShort = url.take(200)
                val bodyLimit = AppConfig.NETWORK_LOG_MAX_BODY_LENGTH
                val bodySnippet = if (body.length <= bodyLimit) body else body.take(bodyLimit) + "...(${body.length} 字符)"

                Logger.log("  [NET:RESP] $method $urlShort → $status ($ct)", "network")
                if (bodySnippet.isNotEmpty() && PageHook.isUsableBody(body)) {
                    Logger.log("    Body(${body.length} 字符): $bodySnippet", "network")
                }
            } catch (_: Exception) {
                // 网络日志解析失败不影响主流程
            }
        }
    }
}