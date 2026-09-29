package com.dy.scraper.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 抖音媒体 URL 提取（纯 Kotlin，无 Android 依赖，便于本机单元测试）
 *
 * 对应 Python 版 dy-scraper 的：
 *   - metadata.py: _extract_video_from_detail / _extract_images_from_detail / _detail_belongs_to_current_page
 *   - metadata.py: extract_media_from_api_responses / extract_images_from_ssr
 *
 * 提取优先级（与 Python 完全一致）：
 *   视频: download_addr > bit_rate(最高码率) > play_addr > play_addr_h264，每个详情只取「最优的 1 条」
 *   图片: download_url_list(原始分辨率) > url_list(按尺寸估分)，每张图只取最优的 1 条
 */
object MediaExtractor {

    // ── 视频质量分（分层：download_addr > 最高码率 bit_rate > play_addr > play_addr_h264） ──
    // 用「层」而不是裸分值，保证 bit_rate（可能只有几 Mbps）一定排在 play_addr 之前
    const val SCORE_DOWNLOAD_ADDR = 2_000_000_000
    const val SCORE_BITRATE_BASE = 1_000_000_000
    const val SCORE_PLAY_ADDR = 100_000_000
    const val SCORE_PLAY_ADDR_H264 = 0

    /** bit_rate 分值（层内按码率排序，上限防止溢出） */
    fun bitRateScore(br: Int): Int = SCORE_BITRATE_BASE + br.coerceIn(0, 800_000_000)

    // 图片 download_url_list 的加权（比 url_list 高 1000 万，与 Python 一致）
    private const val SCORE_IMAGE_DOWNLOAD_BONUS = 10_000_000

    private val DETAIL_KEYS = listOf(
        "aweme_detail", "note_detail", "aweme", "note", "itemDetail", "item_detail"
    )
    private val ID_KEYS = listOf("aweme_id", "note_id", "item_id", "id", "awemeId", "noteId")
    private val CODE_KEYS = listOf("unique_id", "short_id", "uid", "author_uid", "user_id", "douyin_id", "account_id")

    data class ScoredUrl(val url: String, val score: Int)

    data class Media(
        val videoUrls: List<String> = emptyList(),
        val imageUrls: List<String> = emptyList(),
        val author: String = "",
        val authorCode: String = "",
        val title: String = "",
        val coverUrl: String = "",
        val detailKeys: List<String> = emptyList()
    ) {
        val hasMedia: Boolean
            get() = videoUrls.isNotEmpty() || imageUrls.isNotEmpty()
    }

    // ── JSON 安全取值 ──

    fun obj(parent: JsonObject?, key: String): JsonObject? {
        if (parent == null) return null
        val e = parent.get(key) ?: return null
        if (e is JsonNull || !e.isJsonObject) return null
        return try {
            e.asJsonObject
        } catch (_: Exception) {
            null
        }
    }

    fun arr(parent: JsonObject?, key: String): JsonArray? {
        if (parent == null) return null
        val e = parent.get(key) ?: return null
        if (e is JsonNull || !e.isJsonArray) return null
        return try {
            e.asJsonArray
        } catch (_: Exception) {
            null
        }
    }

    fun str(parent: JsonObject?, key: String): String {
        if (parent == null) return ""
        val e = parent.get(key) ?: return ""
        if (e is JsonNull || !e.isJsonPrimitive) return ""
        return try {
            e.asString
        } catch (_: Exception) {
            ""
        }
    }

    /** 读取对象里的 url_list（http 开头） */
    fun urlList(parent: JsonObject?): List<String> {
        val list = arr(parent, "url_list") ?: return emptyList()
        val out = ArrayList<String>(list.size())
        for (e in list) {
            val s = asHttpUrl(e)
            if (s != null) out.add(s)
        }
        return out
    }

    private fun asHttpUrl(e: JsonElement?): String? {
        if (e == null || e is JsonNull || !e.isJsonPrimitive) return null
        val s = try {
            e.asString
        } catch (_: Exception) {
            return null
        }
        return if (s.startsWith("http")) s else null
    }

    // ── 页面 id / 详情归属校验（防止 SPA 软跳转时拿到旧页面数据） ──

    fun pageIds(pageUrl: String): Set<String> {
        val ids = linkedSetOf<String>()
        if (pageUrl.isEmpty()) return ids
        Regex("[/?#.=-](\\d{15,22})[/?#.=&-]").findAll(pageUrl).forEach { ids.add(it.groupValues[1]) }
        Regex("(\\d{15,22})(?:[?#]|$)").find(pageUrl)?.let { ids.add(it.groupValues[1]) }
        return ids
    }

    fun detailId(detail: JsonObject?): String {
        if (detail == null) return ""
        for (k in ID_KEYS) {
            val e = detail.get(k) ?: continue
            if (e is JsonNull || !e.isJsonPrimitive) continue
            val s = try {
                e.asString
            } catch (_: Exception) {
                continue
            }
            if (s.isNotEmpty() && s != "0") return s
        }
        return ""
    }

    fun belongsToPage(detail: JsonObject?, pageUrl: String): Boolean {
        val ids = pageIds(pageUrl)
        if (ids.isEmpty()) return true
        val did = detailId(detail)
        if (did.isEmpty()) return true
        return did in ids
    }

    // ── 详情对象定位 ──

    /**
     * 在响应/SSR 根对象中定位详情对象
     * （aweme_detail / note_detail / aweme.detail / aweme_list[0] / 深度搜索兜底）
     */
    fun findDetail(root: JsonObject?, pageUrl: String = ""): JsonObject? {
        if (root == null) return null

        val direct = ArrayList<JsonObject>()
        for (k in DETAIL_KEYS) {
            val d = obj(root, k) ?: continue
            // aweme.detail / note.detail
            direct.add(obj(d, "detail") ?: d)
        }
        // aweme_list[0].aweme_info
        val list = arr(root, "aweme_list")
        if (list != null && list.size() > 0) {
            val first = list.get(0)
            if (first.isJsonObject) {
                val f = first.asJsonObject
                direct.add(obj(f, "aweme_info") ?: f)
            }
        }
        pickBest(direct, pageUrl)?.let { return it }

        // 深度搜索（_ROUTER_DATA 等深层嵌套结构）
        val deep = ArrayList<JsonObject>()
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<JsonElement, Boolean>())
        deepCollect(root, 0, 12, visited, deep)
        return pickBest(deep, pageUrl)
    }

    /** 从候选详情里挑最合适的：只接受归属当前页面的详情，再按媒体丰富度取最优 */
    private fun pickBest(candidates: List<JsonObject>, pageUrl: String): JsonObject? {
        if (candidates.isEmpty()) return null
        val matching = candidates.filter { belongsToPage(it, pageUrl) }
        if (matching.isEmpty()) return null
        return matching.maxByOrNull { richness(it) }
    }

    private fun richness(detail: JsonObject): Int {
        var score = 0
        val video = obj(detail, "video")
        if (video != null) {
            if (obj(video, "download_addr") != null) score += 8
            if ((arr(video, "bit_rate")?.size() ?: 0) > 0) score += 6
            if (obj(video, "play_addr") != null) score += 4
            score += 2
        }
        val images = arr(detail, "images")
        if (images != null && images.size() > 0) score += 3 + images.size()
        if (str(detail, "desc").isNotEmpty()) score += 1
        if (obj(detail, "author") != null) score += 1
        return score
    }

    private fun deepCollect(
        element: JsonElement?,
        depth: Int,
        maxDepth: Int,
        visited: MutableSet<JsonElement>,
        out: MutableList<JsonObject>
    ) {
        if (element == null || depth > maxDepth) return
        if (!element.isJsonObject && !element.isJsonArray) return
        if (!visited.add(element)) return

        if (element.isJsonObject) {
            val o = element.asJsonObject
            if (looksLikeDetail(o)) out.add(o)
            for ((_, v) in o.entrySet()) deepCollect(v, depth + 1, maxDepth, visited, out)
        } else {
            for (v in element.asJsonArray) deepCollect(v, depth + 1, maxDepth, visited, out)
        }
    }

    /** 判断一个对象是否「像一个作品详情」 */
    private fun looksLikeDetail(o: JsonObject): Boolean {
        val hasId = o.has("aweme_id") || o.has("note_id") || o.has("item_id")
        val hasDesc = o.has("desc")
        val video = obj(o, "video")
        val hasVideoMedia = video != null &&
                (obj(video, "play_addr") != null || obj(video, "download_addr") != null || arr(video, "bit_rate") != null)
        val images = arr(o, "images")
        val hasImages = images != null && images.size() > 0
        if ((hasVideoMedia || hasImages) && (hasId || hasDesc)) return true
        return false
    }

    // ── 视频提取 ──

    /**
     * 从详情对象里取出最优视频 URL（每个详情只返回 1 条，避免同一视频多码率重复下载）
     */
    fun bestVideoUrl(detail: JsonObject?): String? = videoCandidates(detail).firstOrNull()?.url

    /** 视频候选（按质量分降序），便于排查与兜底 */
    fun videoCandidates(detail: JsonObject?): List<ScoredUrl> {
        val video = obj(detail, "video") ?: return emptyList()
        val candidates = ArrayList<ScoredUrl>()

        // 方案1: download_addr（无水印原画，最高优先级）
        urlList(obj(video, "download_addr")).forEach { candidates.add(ScoredUrl(it, SCORE_DOWNLOAD_ADDR)) }

        // 方案2: bit_rate 列表（多码率，层内按 bit_rate 值排序）
        arr(video, "bit_rate")?.forEach { br ->
            if (br != null && br.isJsonObject) {
                val b = br.asJsonObject
                val rate = runCatching { b.get("bit_rate")?.asInt ?: 0 }.getOrDefault(0)
                urlList(obj(b, "play_addr")).forEach { candidates.add(ScoredUrl(it, bitRateScore(rate))) }
            }
        }

        // 方案3: play_addr（兜底）
        urlList(obj(video, "play_addr")).forEach { candidates.add(ScoredUrl(it, SCORE_PLAY_ADDR)) }

        // 方案4: play_addr_h264（最后兜底）
        urlList(obj(video, "play_addr_h264")).forEach { candidates.add(ScoredUrl(it, SCORE_PLAY_ADDR_H264)) }

        return candidates
            .filter { it.url.isNotEmpty() }
            .distinctBy { it.url }
            .sortedWith(compareByDescending<ScoredUrl> { it.score }.thenBy { it.url })
    }

    // ── 图片提取 ──

    /** 从详情对象里提取图片（每张图只取最优的 1 条） */
    fun imageUrls(detail: JsonObject?): List<String> {
        val images = arr(detail, "images") ?: return emptyList()
        val out = ArrayList<String>()
        for (e in images) {
            if (e == null || !e.isJsonObject) continue
            val best = bestImageUrl(e.asJsonObject)
            if (best != null && best !in out) out.add(best)
        }
        return out
    }

    private fun bestImageUrl(img: JsonObject): String? {
        var bestUrl: String? = null
        var bestScore = -1

        // download_url_list 是原始分辨率，优先
        arr(img, "download_url_list")?.forEach { e ->
            val s = asHttpUrl(e) ?: return@forEach
            val score = imageQualityScore(s) + SCORE_IMAGE_DOWNLOAD_BONUS
            if (score > bestScore) {
                bestScore = score
                bestUrl = s
            }
        }
        arr(img, "url_list")?.forEach { e ->
            val s = asHttpUrl(e) ?: return@forEach
            val score = imageQualityScore(s)
            if (score > bestScore) {
                bestScore = score
                bestUrl = s
            }
        }
        return bestUrl
    }

    /** 从 URL 尺寸标记估算图片质量（对应 Python 的 _image_quality_score） */
    fun imageQualityScore(url: String): Int {
        val m = Regex("[~_](\\d{2,4})x(\\d{2,4})").find(url) ?: return 0
        val w = m.groupValues[1].toIntOrNull() ?: 0
        val h = m.groupValues[2].toIntOrNull() ?: 0
        return w * h
    }

    // ── 详情 → Media ──

    fun mediaFromDetail(detail: JsonObject?): Media {
        if (detail == null) return Media()
        val video = obj(detail, "video")

        val authorObj = obj(detail, "author") ?: obj(detail, "author_info")
        val author = str(authorObj, "nickname").ifEmpty { str(authorObj, "name") }
        var authorCode = str(authorObj, "unique_id")
        if (authorCode.isEmpty()) authorCode = str(authorObj, "short_id")
        if (authorCode.isEmpty()) {
            for (k in CODE_KEYS) {
                val v = str(authorObj, k)
                if (v.isNotEmpty() && v != "0") {
                    authorCode = v
                    break
                }
            }
        }

        val cover = urlList(obj(video, "cover")).firstOrNull()
            ?: urlList(obj(video, "origin_cover")).firstOrNull()
            ?: urlList(obj(detail, "cover")).firstOrNull()
            ?: ""

        return Media(
            videoUrls = listOfNotNull(bestVideoUrl(detail)),
            imageUrls = imageUrls(detail),
            author = author,
            authorCode = authorCode,
            title = str(detail, "desc"),
            coverUrl = cover,
            detailKeys = listOf("id=" + detailId(detail))
        )
    }

    // ── 从 JSON 文本（详情 API 响应 / SSR 变量）提取 ──

    /**
     * 解析详情 API 响应体（对应 Python extract_media_from_api_responses）
     */
    fun parseApiBody(body: String, pageUrl: String = ""): Media {
        val json = try {
            JsonParser.parseString(body)
        } catch (_: Exception) {
            return Media()
        }
        if (json == null || !json.isJsonObject) return Media()
        val root = json.asJsonObject
        val roots = if (obj(root, "data") != null) listOf(root, obj(root, "data")!!) else listOf(root)
        for (r in roots) {
            val detail = findDetail(r, pageUrl) ?: continue
            val media = mediaFromDetail(detail)
            if (media.hasMedia || media.author.isNotEmpty()) return media
        }
        return Media()
    }

    /**
     * 从 SSR 变量文本（_ROUTER_DATA / RENDER_DATA / __INITIAL_STATE__ ...）提取媒体。
     * 多个变量合并：视频取第一个命中的最优项，图片合并去重（对应 Python extract_images_from_ssr）
     */
    fun parseSsTexts(texts: Map<String, String>, pageUrl: String = ""): Media {
        var video: String? = null
        val images = ArrayList<String>()
        var author = ""
        var authorCode = ""
        var title = ""
        var cover = ""
        val hits = ArrayList<String>()

        for ((name, text) in texts) {
            if (text.isBlank()) continue
            val media = parseSsText(text, pageUrl)
            if (!media.hasMedia && media.author.isEmpty() && media.authorCode.isEmpty()) continue
            hits.add(name)
            if (video.isNullOrEmpty()) video = media.videoUrls.firstOrNull()
            for (u in media.imageUrls) if (u !in images) images.add(u)
            if (author.isEmpty()) author = media.author
            if (authorCode.isEmpty()) authorCode = media.authorCode
            if (title.isEmpty()) title = media.title
            if (cover.isEmpty()) cover = media.coverUrl
        }

        return Media(
            videoUrls = listOfNotNull(video),
            imageUrls = images,
            author = author,
            authorCode = authorCode,
            title = title,
            coverUrl = cover,
            detailKeys = hits
        )
    }

    /** 单个 SSR 变量文本 → Media */
    fun parseSsText(text: String, pageUrl: String = ""): Media {
        val json = try {
            JsonParser.parseString(text)
        } catch (_: Exception) {
            return Media()
        }
        if (json != null && json.isJsonObject) {
            val detail = findDetail(json.asJsonObject, pageUrl)
            if (detail != null) {
                val m = mediaFromDetail(detail)
                if (m.hasMedia || m.author.isNotEmpty()) return m
            }
        }
        return Media()
    }
}
