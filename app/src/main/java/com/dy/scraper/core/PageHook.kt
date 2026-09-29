package com.dy.scraper.core

/**
 * 页面内 **document-start 钩子**（对应 Playwright 的 `page.add_init_script()` + `page.on("response")`）。
 *
 * 为什么必须这样拿响应体（真机日志证据 `dy_scraper_log_2026-09-29.txt`）：
 * - 影子请求（OkHttp 重放）：`[API影子] HTTP 200, 长度 0` —— 缺少页面 JS 现算的 a_bogus 签名，body 为空；
 * - JS fetch 重放：`[JS fetch API] 响应体长度: 817410` 且 `body前100字符: <!DOCTYPE html>` —— 被风控当导航，返回整页；
 * - OkHttp 直连（DouyinApiCollector）：`[API] 响应体长度: 0` —— 同上。
 * 结论：**只有旁听页面自己发的那次请求**才能拿到详情 JSON（Playwright 能做到，WebView 需要本钩子）。
 *
 * 钩子做三件事：
 *   1. 挂 `XMLHttpRequest` / `window.fetch` 钩子，命中详情 API 时把响应体经 `DyBridge.onApiBody` 回传；
 *   2. 用 setter 快照 `window._ROUTER_DATA` 等 SSR 变量 —— 抖音 hydration 后会把它们删掉，
 *      事后 dump（`SSR_DUMP_SCRIPT`）什么都拿不到，所以必须「赋值即抓」；
 *   3. 顺手做反检测（`navigator.webdriver` 等）—— 原来在 onPageCommitVisible 注入，时机太晚。
 *
 * 本对象只放纯 Kotlin（无 Android 依赖），逻辑可在 JVM 单测里断言。
 */
object PageHook {

    /**
     * 需要旁听的详情 API 路径特征。
     * 必须与 [com.dy.scraper.util.AppConfig.DETAIL_API_PATTERNS] 完全一致（`PageHookTest` 有断言）。
     */
    val API_PATTERNS = listOf(
        "/aweme/v1/web/aweme/detail/",
        "/aweme/v1/web/note/detail/",
        "/aweme/v1/web/note/",
        "/aweme/v1/aweme/detail/",
    )

    /** 页面内嵌数据变量名（hydration 后会被页面删除，必须在赋值时快照） */
    val SSR_KEYS = listOf(
        "_ROUTER_DATA", "__INITIAL_STATE__", "__UNIVERSAL_DATA__", "__NEXT_DATA__", "__NUXT__", "__DATA__",
    )

    /**
     * 「事后重放请求」兜底开关。
     * 真机日志已证明重放拿不到 body（`HTTP 200, 长度 0` / 返回整页 HTML），
     * 而且 JS fetch 重放会再次触发 `shouldInterceptRequest` → 影子请求，形成自我放大的风控请求，
     * 因此默认 **关闭**；排障时改成 true 才会走 `DouyinApiCollector` 的重放路径。
     */
    const val ENABLE_REPLAY_FALLBACK = false

    /** JS 桥对象名（`addJavascriptInterface` 注册名） */
    const val BRIDGE_NAME = "DyBridge"

    /** 回传响应体的桥方法名 */
    const val BRIDGE_METHOD = "onApiBody"

    /** SSR 快照在页面上的挂载点（`MetadataExtractor.SSR_DUMP_SCRIPT` 会读它） */
    const val SSR_SNAPSHOT_VAR = "__dySsr"

    val SCRIPT: String = buildScript()

    /** 生成注入脚本（参数化便于单测用自定义特征串校验） */
    fun buildScript(
        patterns: List<String> = API_PATTERNS,
        ssrKeys: List<String> = SSR_KEYS
    ): String {
        val patternArray = patterns.joinToString(",") { "\"" + it + "\"" }
        val ssrArray = ssrKeys.joinToString(",") { "'" + it + "'" }
        return """
(function() {
    if (window.__dyHookV1) return 'already';
    window.__dyHookV1 = true;

    // ── 反检测（对应 Playwright add_init_script，必须在页面脚本之前） ──
    try {
        Object.defineProperty(navigator, 'webdriver', { get: function() { return false; } });
        Object.defineProperty(navigator, 'plugins', { get: function() { return [1, 2, 3, 4, 5]; } });
        Object.defineProperty(navigator, 'languages', { get: function() { return ['zh-CN', 'zh', 'en']; } });
    } catch (e) {}

    var PATTERNS = [$patternArray];
    var SSR_KEYS = [$ssrArray];

    function hit(u) {
        try {
            u = String(u || '');
            for (var i = 0; i < PATTERNS.length; i++) {
                if (u.indexOf(PATTERNS[i]) >= 0) return true;
            }
        } catch (e) {}
        return false;
    }

    function report(u, body) {
        try {
            if (!body) return;
            var s = String(body);
            if (s.length < 16) return;                  // '{}' / 空壳
            if (s.charAt(0) === '<') return;            // 风控返回的整页 HTML
            if (window.DyBridge && window.DyBridge.onApiBody) {
                window.DyBridge.onApiBody(String(u || ''), s);
            }
        } catch (e) {}
    }

    // ── 1. XHR 旁听 ──
    try {
        var XHR = window.XMLHttpRequest;
        if (XHR && XHR.prototype) {
            var _open = XHR.prototype.open;
            var _send = XHR.prototype.send;
            XHR.prototype.open = function(m, u) {
                try { this.__dyUrl = u; } catch (e) {}
                return _open.apply(this, arguments);
            };
            XHR.prototype.send = function() {
                try {
                    var xhr = this;
                    if (hit(xhr.__dyUrl)) {
                        xhr.addEventListener('loadend', function() {
                            try {
                                var t = '';
                                var rt = xhr.responseType;
                                if (!rt || rt === 'text') t = xhr.responseText;
                                else if (rt === 'json') t = JSON.stringify(xhr.response);
                                report(xhr.__dyUrl, t);
                            } catch (e) {}
                        });
                    }
                } catch (e) {}
                return _send.apply(this, arguments);
            };
        }
    } catch (e) {}

    // ── 2. fetch 旁听 ──
    try {
        var _fetch = window.fetch;
        if (_fetch && !_fetch.__dyWrapped) {
            var wrapped = function(input, init) {
                var u = '';
                try { u = (typeof input === 'string') ? input : ((input && input.url) || ''); } catch (e) {}
                var p = _fetch.apply(this, arguments);
                if (!hit(u)) return p;
                try {
                    return p.then(function(resp) {
                        try {
                            if (resp && resp.clone) {
                                resp.clone().text().then(function(t) { report(u, t); }).catch(function() {});
                            }
                        } catch (e) {}
                        return resp;
                    });
                } catch (e) { return p; }
            };
            wrapped.__dyWrapped = true;
            window.fetch = wrapped;
        }
    } catch (e) {}

    // ── 3. SSR 变量快照（赋值即抓，避免 hydration 后被删除） ──
    try {
        window.__dySsr = window.__dySsr || {};
        var snap = function(k, v) {
            try {
                if (v === undefined || v === null) return;
                var s = (typeof v === 'string') ? v : JSON.stringify(v);
                if (s && s.length > 0) window.__dySsr[k] = s;
            } catch (e) {}
        };
        var installKey = function(k) {
            var cur = null;
            try { cur = window[k]; } catch (e) {}
            snap(k, cur);
            try {
                Object.defineProperty(window, k, {
                    configurable: true,
                    enumerable: true,
                    get: function() { return cur; },
                    set: function(v) { cur = v; snap(k, v); }
                });
            } catch (e) {}
        };
        for (var s = 0; s < SSR_KEYS.length; s++) installKey(SSR_KEYS[s]);
    } catch (e) {}

    // ── 4. 内嵌 script（RENDER_DATA 等）快照 ──
    try {
        var grabScripts = function() {
            try {
                var list = document.querySelectorAll('script');
                for (var j = 0; j < list.length; j++) {
                    var sc = list[j];
                    var txt = sc.textContent || '';
                    if (!txt || txt.length < 64) continue;
                    var ok = txt.indexOf('play_addr') >= 0 || txt.indexOf('aweme_id') >= 0 ||
                             txt.indexOf('note_id') >= 0 || txt.indexOf('_ROUTER_DATA') >= 0;
                    if (!ok) continue;
                    var key = 'script:' + (sc.id || sc.getAttribute('data-e2e') || ('#' + j));
                    if (!window.__dySsr[key]) {
                        window.__dySsr[key] = (txt.length > 200000) ? txt.substring(0, 200000) : txt;
                    }
                }
            } catch (e) {}
        };
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', grabScripts);
        } else {
            grabScripts();
        }
    } catch (e) {}

    return 'installed';
})();
        """.trimIndent()
    }

    /** URL 是否命中详情 API（纯 Kotlin，供 JS 桥回传时过滤） */
    fun isDetailApiUrl(url: String): Boolean {
        if (url.isEmpty()) return false
        return API_PATTERNS.any { it in url }
    }

    /**
     * 详情 API 去重键：同一作品只解析一份 body。
     * **必须带上 aweme_id / note_id** —— 所有详情请求的路径都一样（`/aweme/v1/web/aweme/detail/`），
     * 只用路径会把第二个作品的响应体误判成重复而丢掉（视频永远 0 个的另一种成因）。
     */
    fun apiDedupeKey(url: String): String {
        if (url.isEmpty()) return ""
        val path = url.substringBefore("?")
        val id = Regex("(?:aweme_id|note_id|item_id)=(\\d+)").find(url)?.groupValues?.get(1)
        return if (id.isNullOrEmpty()) url else "$path#$id"
    }

    /**
     * 回传的响应体是否可用（HTML 壳 / 空对象一律丢弃）。
     * 对应 JS 侧 `report()` 的过滤条件，两侧保持一致。
     */
    fun isUsableBody(body: String): Boolean {
        val t = body.trim()
        if (t.length < 16) return false
        if (t.startsWith("<")) return false
        return t.startsWith("{") || t.startsWith("[")
    }
}
