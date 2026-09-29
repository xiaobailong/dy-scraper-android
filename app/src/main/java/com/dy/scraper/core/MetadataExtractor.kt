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

    suspend fun extractFromWebView(wvm: WebViewManager, ctx: PageContext): PageContext {
        Logger.log("[元数据提取] 执行 JS 提取脚本...")

        val rawResult = wvm.evaluateJavascript(EXTRACT_SCRIPT)
        Logger.log("[元数据提取] JS 执行完成", "debug")

        // 解析 JSON 结果（WebView.evaluateJavascript 返回的是带引号的 JSON 字符串）
        val jsonStr = rawResult.trim('"').replace("\\\"", "\"").replace("\\\\", "\\")

        return try {
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
            Logger.log("  原始返回: ${rawResult.take(500)}", "debug")
            ctx
        }
    }

    /**
     * 从 API 响应中提取视频/图片 URL（对应 Python 的 api 模块解析逻辑）
     */
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