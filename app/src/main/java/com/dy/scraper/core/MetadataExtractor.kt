package com.dy.scraper.core

import com.dy.scraper.entity.PageContext
import com.dy.scraper.util.Logger
import com.google.gson.Gson
import com.google.gson.JsonParser

object MetadataExtractor {

    private val gson = Gson()

    // ── JS 提取脚本（从 metadata.py 的 EXTRACT_SCRIPT 移植） ──
    val EXTRACT_SCRIPT = """
(function() {
    const result = {
        title: document.title || '',
        author: '',
        authorCode: '',
        secUid: '',
        description: '',
        videoUrls: [],
        imageUrls: [],
        coverUrl: '',
        pageUrl: window.location.href,
        extractSource: '',
        ssrAvailable: []
    };

    const ssrKeys = ['__INITIAL_STATE__', '__UNIVERSAL_DATA__', '__NEXT_DATA__', '__NUXT__', '__DATA__', '__META_DATA__', 'RENDER_DATA'];
    const CODE_KEYS = ['unique_id', 'short_id', 'uid', 'author_uid', 'user_id', 'douyin_id', 'account_id'];

    function _extractCode(obj) {
        for (const k of CODE_KEYS) {
            if (k in obj) {
                const v = obj[k];
                if (typeof v === 'string' && v && v !== '0' && v !== 'undefined') return v;
                if (typeof v === 'number' && v > 0) return String(v);
            }
        }
        return '';
    }

    function _extractIdsFromUrl(url) {
        const ids = new Set();
        (url || '').replace(/[\/\?#\.=-](\d{15,22})[\/\?#\.=&-]/g, function(_, id) { ids.add(id); return _; });
        const m = (url || '').match(/(\d{15,22})(?:\?|#|$$)/);
        if (m) ids.add(m[1]);
        return ids;
    }

    function _findDetailId(detail) {
        if (!detail || typeof detail !== 'object') return '';
        for (const k of ['aweme_id', 'note_id', 'item_id', 'id', 'awemeId', 'noteId']) {
            const v = detail[k];
            if (typeof v === 'string' && v) return v;
            if (typeof v === 'number' && v > 0) return String(v);
        }
        return '';
    }

    function _detailBelongsToCurrentPage(detail) {
        const pageIds = _extractIdsFromUrl(window.location.href);
        if (!pageIds.size) return true;
        const did = _findDetailId(detail);
        if (!did) return true;
        return pageIds.has(did);
    }

    function _findValidDetail(root) {
        if (!root || typeof root !== 'object') return null;
        for (const k of ['aweme_detail', 'note_detail', 'aweme', 'note', 'itemDetail', 'item_detail']) {
            const d = root[k];
            if (d && typeof d === 'object' && _detailBelongsToCurrentPage(d)) return d;
        }
        return null;
    }

    function deepFind(obj, maxDepth, visited) {
        if (maxDepth === undefined) maxDepth = 12;
        if (visited === undefined) visited = new WeakSet();
        if (maxDepth <= 0 || obj === null || typeof obj !== 'object') return null;
        if (visited.has(obj)) return null;
        visited.add(obj);

        const found = { nickname: '', unique_id: '', short_id: '', sec_uid: '' };

        if (Array.isArray(obj)) {
            for (let i = 0; i < obj.length; i++) {
                const child = deepFind(obj[i], maxDepth - 1, visited);
                if (child) {
                    if (!found.nickname && child.nickname) found.nickname = child.nickname;
                    if (!found.unique_id && child.unique_id) found.unique_id = child.unique_id;
                    if (!found.short_id && child.short_id) found.short_id = child.short_id;
                    if (!found.sec_uid && child.sec_uid) found.sec_uid = child.sec_uid;
                    if (found.nickname && (found.unique_id || found.short_id)) break;
                }
            }
        } else {
            const keys = Object.keys(obj);
            if ('nickname' in obj && typeof obj.nickname === 'string' && obj.nickname) {
                found.nickname = obj.nickname;
            }
            if ('unique_id' in obj && typeof obj.unique_id === 'string' && obj.unique_id) {
                found.unique_id = obj.unique_id;
            }
            if ('short_id' in obj) {
                const sid = typeof obj.short_id === 'string' ? obj.short_id : String(obj.short_id);
                if (sid && sid !== '0' && sid !== 'undefined') found.short_id = sid;
            }
            if (!found.unique_id && !found.short_id) {
                const code = _extractCode(obj);
                if (code) {
                    if (/^\d+$$/.test(code)) found.short_id = code;
                    else found.unique_id = code;
                }
            }
            if ('sec_uid' in obj && typeof obj.sec_uid === 'string' && obj.sec_uid) {
                found.sec_uid = obj.sec_uid;
            }
            if (found.nickname && (found.unique_id || found.short_id)) return found;

            for (let i = 0; i < keys.length; i++) {
                const v = obj[keys[i]];
                if (v && typeof v === 'object') {
                    const child = deepFind(v, maxDepth - 1, visited);
                    if (child) {
                        if (!found.nickname && child.nickname) found.nickname = child.nickname;
                        if (!found.unique_id && child.unique_id) found.unique_id = child.unique_id;
                        if (!found.short_id && child.short_id) found.short_id = child.short_id;
                        if (!found.sec_uid && child.sec_uid) found.sec_uid = child.sec_uid;
                        if (found.nickname && (found.unique_id || found.short_id)) break;
                    }
                }
            }
        }
        return (found.nickname || found.unique_id || found.short_id || found.sec_uid) ? found : null;
    }

    // ========================================
    // 策略1: 深度遍历 SSR 数据
    // ========================================
    for (const key of ssrKeys) {
        if (window[key] !== undefined) result.ssrAvailable.push(key);
        if (window[key]) {
            try {
                const data = typeof window[key] === 'string' ? JSON.parse(window[key]) : window[key];
                const validDetail = _findValidDetail(data);
                let hasAnyDetail = false;
                try {
                    (function _walk(o) {
                        if (!o || typeof o !== 'object') return;
                        if (Array.isArray(o)) { for (const x of o) _walk(x); return; }
                        for (const k of Object.keys(o)) {
                            if (['aweme_detail','note_detail','aweme','note'].includes(k) && o[k] && typeof o[k]==='object') { hasAnyDetail = true; return; }
                            _walk(o[k]); if (hasAnyDetail) return;
                        }
                    })(data);
                } catch(e) {}
                if (hasAnyDetail && !validDetail) continue;

                const found = deepFind(data);
                if (found) {
                    if (found.nickname) result.author = found.nickname;
                    if (found.unique_id) {
                        result.authorCode = found.unique_id;
                    } else if (found.short_id) {
                        result.authorCode = found.short_id;
                    }
                    if (found.sec_uid) result.secUid = found.sec_uid;
                    if (result.author) {
                        result.extractSource = 'ssr_deep:' + key;
                        break;
                    }
                }
            } catch(e) {}
        }
    }

    // ========================================
    // 策略1.5: RENDER_DATA 脚本标签（笔记页数据在 #RENDER_DATA 元素中）
    // ========================================
    if (!result.extractSource) {
        let renderData = null;
        const renderEl = document.getElementById('RENDER_DATA');
        if (renderEl && renderEl.textContent) {
            try {
                renderData = JSON.parse(renderEl.textContent);
            } catch(e) {
                try {
                    renderData = JSON.parse(decodeURIComponent(renderEl.textContent));
                } catch(e2) {}
            }
        }
        if (renderData) {
            const validDetail = _findValidDetail(renderData);
            let hasAnyDetail = false;
            try {
                (function _walk(o) {
                    if (!o || typeof o !== 'object') return;
                    if (Array.isArray(o)) { for (const x of o) _walk(x); return; }
                    for (const k of Object.keys(o)) {
                        if (['aweme_detail','note_detail','aweme','note'].includes(k) && o[k] && typeof o[k]==='object') { hasAnyDetail = true; return; }
                        _walk(o[k]); if (hasAnyDetail) return;
                    }
                })(renderData);
            } catch(e) {}
            if (!hasAnyDetail || validDetail) {
                const found = deepFind(renderData);
                if (found) {
                    if (found.nickname && !result.author) result.author = found.nickname;
                    if (found.unique_id && !result.authorCode) result.authorCode = found.unique_id;
                    else if (found.short_id && !result.authorCode) result.authorCode = found.short_id;
                    if (found.sec_uid && !result.secUid) result.secUid = found.sec_uid;
                    if (result.author) result.extractSource = 'render_data_deep';
                }
                // 深度遍历没找到，用正则兜底
                if (!result.authorCode) {
                    try {
                        const s = JSON.stringify(renderData);
                        const m = s.match(/"(?:unique_id|short_id|douyin_id|account_id)"\s*:\s*"([^"]+)"/);
                        if (m) {
                            result.authorCode = m[1];
                            result.extractSource = 'render_data_regex';
                        }
                    } catch(e) {}
                }
            }
        }
    }

    // ========================================
    // 策略2: DOM 提取视频/图片 URL
    // ========================================
    if (!result.extractSource) {
        // DOM 作者
        const authorLinks = document.querySelectorAll('a[href*="/user/"], [data-e2e="user-info"] span, .author-name, [class*="author"] span');
        for (const el of authorLinks) {
            const text = el.textContent.trim();
            if (text && text.length < 50 && !/^[@\d\s]+$$/.test(text)) {
                result.author = text;
                result.extractSource = 'dom:author';
                break;
            }
        }

        // meta 标签
        const metaDesc = document.querySelector('meta[name="description"]') || document.querySelector('meta[property="og:description"]');
        if (metaDesc) result.description = metaDesc.content || '';

        const metaTitle = document.querySelector('meta[property="og:title"]');
        if (metaTitle && metaTitle.content && !result.title) result.title = metaTitle.content;
    }

    // DOM 视频
    document.querySelectorAll('video source, video[src]').forEach(function(v) {
        const src = v.src || v.getAttribute('src');
        if (src && src.startsWith('http')) result.videoUrls.push(src);
    });

    // DOM 图片（排除 UI 元素）
    document.querySelectorAll('img[src]').forEach(function(img) {
        const src = img.src;
        if (src && src.startsWith('http') && img.naturalWidth > 100 && img.naturalHeight > 100) {
            if (!/avatar|logo|icon|emoji|emblem/i.test(src) && !/100x100/.test(src)) {
                result.imageUrls.push(src);
            }
        }
    });

    return JSON.stringify(result);
})();
    """.trimIndent()

    // ── 从网络请求中提取视频/图片 URL ──
    val EXTRACT_NETWORK_URLS_SCRIPT = """
(function() {
    const urls = [];
    const perfEntries = performance.getEntriesByType('resource');
    for (const entry of perfEntries) {
        const url = entry.name;
        if (url.startsWith('http')) {
            urls.push({url: url, type: entry.initiatorType});
        }
    }
    return JSON.stringify(urls);
})();
    """.trimIndent()

    /**
     * 页面内嵌数据 dump 脚本：把 SSR / 内嵌 JSON 原样取出，交给 Kotlin 侧解析
     * （抖音 PC 视频页数据在 window._ROUTER_DATA，图集页在 RENDER_DATA 等）
     */
    val SSR_DUMP_SCRIPT = """
(function() {
    const out = { url: location.href || '', details: [], texts: {} };
    const visited = new WeakSet();
    const MAX_DETAILS = 6;
    const MAX_TEXT = 200000;
    let budget = 600000;

    function isDetail(o) {
        if (!o || typeof o !== 'object' || Array.isArray(o)) return false;
        const hasId = ('aweme_id' in o) || ('note_id' in o) || ('item_id' in o);
        const hasDesc = ('desc' in o);
        const hasVideo = o.video && typeof o.video === 'object' &&
            (o.video.play_addr || o.video.download_addr || (Array.isArray(o.video.bit_rate) && o.video.bit_rate.length > 0));
        const hasImages = Array.isArray(o.images) && o.images.length > 0;
        return (hasVideo || hasImages) && (hasId || hasDesc);
    }

    function walk(o, depth) {
        if (!o || depth > 12 || typeof o !== 'object' || out.details.length >= MAX_DETAILS) return;
        if (visited.has(o)) return;
        visited.add(o);
        try {
            if (!Array.isArray(o) && isDetail(o)) out.details.push(o);
            if (Array.isArray(o)) {
                const n = Math.min(o.length, 200);
                for (let i = 0; i < n; i++) walk(o[i], depth + 1);
            } else {
                for (const k in o) walk(o[k], depth + 1);
            }
        } catch (e) {}
    }

    // document-start 钩子（PageHook）在赋值瞬间抓的快照：抖音 hydration 后会把 _ROUTER_DATA 删掉，
    // 事后再 dump 什么都拿不到（真机日志：`[SSR] 扫描 0 个内嵌数据源`），所以这里优先读快照
    try {
        const snap = window.__dySsr || {};
        for (const k in snap) {
            let raw = snap[k];
            let v = raw;
            if (typeof v === 'string') {
                try { v = JSON.parse(v); } catch (e) { v = null; }
            }
            if (v !== null && v !== undefined && typeof v === 'object') walk(v, 0);
            if (raw && typeof raw === 'string' && raw.length <= MAX_TEXT && budget > 0) {
                out.texts['snap:' + k] = raw;
                budget -= raw.length;
            }
        }
    } catch (e) {}

    const keys = ['_ROUTER_DATA', '__INITIAL_STATE__', '__UNIVERSAL_DATA__', '__NEXT_DATA__', '__NUXT__', '__DATA__', '__META_DATA__', 'RENDER_DATA'];
    for (const k of keys) {
        let v = null;
        try { v = window[k]; } catch (e) { continue; }
        if (v === undefined || v === null) continue;
        if (typeof v === 'string') {
            try { v = JSON.parse(v); } catch (e) { continue; }
        }
        walk(v, 0);
        try {
            const s = JSON.stringify(v);
            if (s && s.length <= MAX_TEXT && budget > 0) { out.texts[k] = s; budget -= s.length; }
        } catch (e) {}
    }

    // 内嵌 script（如 <script id="RENDER_DATA"> 里的 JSON）
    try {
        const scripts = document.querySelectorAll('script');
        for (let i = 0; i < scripts.length; i++) {
            const t = scripts[i].textContent || '';
            if (!t || t.length > MAX_TEXT || budget <= 0) continue;
            const id = (scripts[i].id || '');
            if (t.indexOf('play_addr') < 0 && t.indexOf('"aweme_id"') < 0
                && t.indexOf('url_list') < 0 && id.toLowerCase().indexOf('render_data') < 0) continue;
            out.texts['script:' + (id || i)] = t;
            budget -= t.length;
        }
    } catch (e) {}

    return JSON.stringify(out);
})();
    """.trimIndent()

    suspend fun extractFromWebView(wvm: WebViewManager, ctx: PageContext): PageContext {
        Logger.log("[元数据提取] 执行 JS 提取脚本...")

        val jsonStr = wvm.evaluateJavascriptText("return $EXTRACT_SCRIPT")
        Logger.log("[元数据提取] JS 执行完成", "debug")

        try {
            val json = JsonParser.parseString(jsonStr).asJsonObject

            ctx.title = json.get("title")?.asString ?: ""
            ctx.author = json.get("author")?.asString ?: ""
            ctx.authorCode = json.get("authorCode")?.asString ?: ""
            ctx.secUid = json.get("secUid")?.asString ?: ""
            ctx.description = json.get("description")?.asString ?: ""
            ctx.extractSource = json.get("extractSource")?.asString ?: ""
            ctx.ssrAvailable = json.getAsJsonArray("ssrAvailable")?.map { it.asString } ?: emptyList()

            ctx.domVideoUrls = json.getAsJsonArray("videoUrls")
                ?.map { it.asString }
                ?.filter { it.startsWith("http") && !it.startsWith("blob:") }
                ?.distinct() ?: emptyList()

            ctx.domImageUrls = json.getAsJsonArray("imageUrls")
                ?.map { it.asString }
                ?.filter { it.startsWith("http") && !it.startsWith("blob:") }
                ?.distinct() ?: emptyList()

            Logger.log("  标题: ${ctx.title.take(50)}")
            Logger.log("  作者: ${ctx.author}")
            Logger.log("  DOM视频: ${ctx.domVideoUrls.size}  DOM图片: ${ctx.domImageUrls.size}")
            Logger.log("  提取来源: ${ctx.extractSource}")

            ctx
        } catch (e: Exception) {
            Logger.log("[元数据提取] JSON 解析失败: ${e.message}", "error")
            ctx
        }

        // ── SSR / 页面内嵌 JSON 兜底（API 响应拿不到时的关键路径） ──
        extractFromSs(wvm, ctx)
        return ctx
    }

    /**
     * 从页面 SSR 变量（_ROUTER_DATA / RENDER_DATA / __INITIAL_STATE__ 等）与内嵌 script 中提取媒体。
     * 抖音 PC 视频页数据在 window._ROUTER_DATA，图集页在 RENDER_DATA / _ROUTER_DATA，
     * 这是拿不到详情 API 响应时唯一的可靠视频来源。
     */
    suspend fun extractFromSs(wvm: WebViewManager, ctx: PageContext) {
        val raw = try {
            wvm.evaluateJavascriptText("return $SSR_DUMP_SCRIPT")
        } catch (e: Exception) {
            Logger.log("  [SSR] dump 失败: ${e.message}", "warn")
            ""
        }
        if (raw.isEmpty()) {
            Logger.log("  [SSR] 未获取到页面内嵌数据", "debug")
            return
        }

        val texts = try {
            val json = JsonParser.parseString(raw).asJsonObject
            val map = LinkedHashMap<String, String>()
            json.getAsJsonObject("texts")?.entrySet()?.forEach { (k, v) ->
                if (v.isJsonPrimitive) map[k] = v.asString
            }
            map
        } catch (e: Exception) {
            Logger.log("  [SSR] 解析 dump 失败: ${e.message}", "warn")
            return
        }

        // 1) JS 已在内嵌数据里定位到的详情对象（体积小，优先）
        var inline = MediaExtractor.Media()
        try {
            val dump = JsonParser.parseString(raw).asJsonObject
            val details = dump.getAsJsonArray("details")
            if (details != null && details.size() > 0) {
                val root = com.google.gson.JsonObject()
                root.add("candidates", details)
                val detail = MediaExtractor.findDetail(root, ctx.finalUrl)
                inline = MediaExtractor.mediaFromDetail(detail)
                Logger.log(
                    "  [SSR] 内嵌详情候选 ${details.size()} 个 → 命中 id=${MediaExtractor.detailId(detail)}",
                    "debug"
                )
            }
        } catch (e: Exception) {
            Logger.log("  [SSR] 内嵌详情解析失败: ${e.message}", "warn")
        }

        // 2) SSR 变量文本兜底解析
        val fromTexts = MediaExtractor.parseSsTexts(texts, ctx.finalUrl)

        val media = MediaExtractor.Media(
            videoUrls = if (inline.videoUrls.isNotEmpty()) inline.videoUrls else fromTexts.videoUrls,
            imageUrls = (inline.imageUrls + fromTexts.imageUrls).distinct(),
            author = inline.author.ifEmpty { fromTexts.author },
            authorCode = inline.authorCode.ifEmpty { fromTexts.authorCode },
            title = inline.title.ifEmpty { fromTexts.title },
            coverUrl = inline.coverUrl.ifEmpty { fromTexts.coverUrl },
            detailKeys = inline.detailKeys + fromTexts.detailKeys
        )

        ctx.ssrAvailable = texts.keys.toList()
        if (media.videoUrls.isNotEmpty()) ctx.ssrVideoUrls = media.videoUrls
        if (media.imageUrls.isNotEmpty()) ctx.ssrImageUrls = media.imageUrls
        if (ctx.author.isEmpty() && media.author.isNotEmpty()) ctx.author = media.author
        if (ctx.authorCode.isEmpty() && media.authorCode.isNotEmpty()) ctx.authorCode = media.authorCode
        if (ctx.title.isEmpty() && media.title.isNotEmpty()) ctx.title = media.title
        if (ctx.coverUrl.isEmpty() && media.coverUrl.isNotEmpty()) ctx.coverUrl = media.coverUrl

        Logger.log(
            "  [SSR] 扫描 ${texts.size} 个内嵌数据源 " +
                    "(命中: ${media.detailKeys.joinToString(",").ifEmpty { "无" }}) " +
                    "→ 视频 ${ctx.ssrVideoUrls.size} 个, 图片 ${ctx.ssrImageUrls.size} 个"
        )
    }

    /**
     * 【已废弃】历史上只解析 URL 不解析响应体，现由 [MediaExtractor] + [com.dy.scraper.api.DouyinApiCollector] 取代。
     * 保留仅为兼容调用方，请勿在新代码中使用。
     */
    @Suppress("UNUSED_VARIABLE", "unused")
    fun extractFromApiResponses(detailResponses: List<String>): Pair<List<String>, List<String>> {
        val videoUrls = mutableListOf<String>()
        val imageUrls = mutableListOf<String>()

        for (apiUrl in detailResponses) {
            try {
                // 从 API URL 路径判断是 note 还是 detail
                val videoPattern = Regex("video[._](?:no_)?codec.*?url_list.*?\"(https?://[^\"]+)\"", RegexOption.IGNORE_CASE)
                val imagePattern = Regex("url_list.*?\"(https?://[^\"]+)\"", RegexOption.IGNORE_CASE)

                // 注意：这里 API 响应已经在 WebView 内部，我们只能拿到 URL。
                // 完整实现需要 OkHttp 同步请求这些 API 并解析 JSON。
                // 目前只标记收集到的 API URL。
                Logger.log("  [API] 收集到详情API: ${apiUrl.take(100)}...", "debug")

                if ("note" in apiUrl) {
                    // 笔记类型 - 图片为主
                    Logger.log("  [API] 识别为笔记类型", "debug")
                } else if ("detail" in apiUrl) {
                    // 视频类型
                    Logger.log("  [API] 识别为视频类型", "debug")
                }
            } catch (e: Exception) {
                Logger.log("  [API] 解析异常: ${e.message}", "warn")
            }
        }

        return Pair(videoUrls, imageUrls)
    }
}