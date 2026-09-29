# 技术决策（decisions / ADR）

> 上限 12KB（超了照 `WRITING.md` §3 归档；已废弃的移入 `archive/decisions-archive.md`）。
> 每条：背景 / 决策 / 理由 / 备选与为何不选 / 影响。编号只增不改。模板见 `WRITING.md` §1。

## ADR-001 技术路线：Native Kotlin + WebView（替代 Python Playwright）
- 日期: 2026-09-28 | 状态: 已采纳
- 背景: 原 Python 项目 `dy-scraper` 使用 Playwright + urllib 实现抖音内容抓取，需要迁移到 Android 平台。
- 决策: 采用 **Kotlin + AndroidX + WebView** 方案，WebView 替代 Playwright 做页面渲染和网络拦截，
  OkHttp 替代 urllib 做直接 HTTP 请求，Room 替代文件存储做持久化记录，Coroutines 替代 asyncio 做异步调度。
- 理由: WebView 内嵌 Chromium 内核，支持 `shouldInterceptRequest()` 拦截网络请求和 `evaluateJavascript()` DOM 操作，
  与 Playwright 能力对等；Kotlin Coroutines 是 Android 生态标准异步方案，比 Java Executor 更轻量。
- 备选与为何不选: ①React Native / Flutter（跨平台方案引入额外框架层，Chromium 引擎仍需原生集成，没有本质优势）；
  ②纯 OkHttp + HTML Parser（无法执行 JavaScript，抖音页面高度依赖前端渲染，抓不到内容）；
  ③GeckoView（Mozilla 引擎，Android 兼容性不及 Chromium，社区资源少）。
- 影响 / 约束: 需要 Android 5.0+ (API 21)；WebView 需要系统 WebView 组件支持（或内置 Chromium WebView）；
  长时间运行的抓取任务需前台 Service + WakeLock 防止被系统回收。

## ADR-002 目录体系：直接下载到最终目录，无临时目录
- 日期: 2026-09-28 | 状态: 已采纳
- 背景: 原 Python 项目有 `temp` / `download` 两阶段（先下载到 temp，完成后统一切换到 download），
  增加了文件移动开销和残留清理逻辑。
- 决策: **去掉临时目录**，文件直接下载到最终目标目录（`downloadVideoDir` / `downloadImageDir`），
  下载过程中即时去重（MD5 / pHash），下载完成即最终状态。
- 理由: Android 的文件系统比服务器更简单，不需要跨盘移动；直接下载减少了 IO 开销和目录管理复杂度；
  去重注册表（MD5 扫描已有文件）在下载前初始化，实时跳过重复文件。
- 备选与为何不选: 保留下载中途的临时切换逻辑（增加 FileStorageManager 复杂度，移动失败需额外处理）。
- 影响 / 约束: FileStorageManager 简化为只负责目录创建；ScraperWorker MD5 扫描范围缩减一半；
  下载失败的中间文件会留在最终目录（由后续的 MD5 扫描过滤）。

## ADR-003 详情 API 响应体：WebView 影子请求为主，JS 桥为辅
- 日期: 2026-09-29 | 状态: 已采纳
- 背景: Playwright 版 `page.on("response") + resp.text()` 能直接读到详情响应体；WebView 的 `shouldInterceptRequest` 只给 URL 不给 body；
  `evaluateJavascript` 又不 await Promise（`PIT-001`）→ 视频高清地址拿不到（`ISSUE-001`）。
- 决策: 命中 `DETAIL_API_PATTERNS` 时，用同一会话（CookieManager 的 Cookie + 同 UA + Referer）在后台线程**重放请求抓 body**（"影子请求"），
  入队 `WebViewManager.detailBodies`；`UrlProcessor` 等待并用 `DouyinApiCollector.parseAllBodies` 解析。JS fetch + `DyBridge.onApiResponse` 作兜底。
- 备选与为何不选: ① 只记 URL（原实现）→ 拿不到 body，故障根因；② 在 `shouldInterceptRequest` 里同步请求 → 阻塞 WebView 资源加载；
  ③ 只靠 JS fetch → Promise 无法 await，桥接只能兜底（CSP/风控场景可能失败）。
- 影响 / 约束: 详情接口被请求两次（WebView 一次、影子一次）；影子失败不影响页面；同一 URL 只抓一次。

## ADR-004 SSR/内嵌数据：JS 只做定位，解析放 Kotlin（可单测）
- 日期: 2026-09-29 | 状态: 已采纳
- 背景: 视频页数据在 `window._ROUTER_DATA`（图集页 `RENDER_DATA` / 内嵌 script）；全量 dump 会带来 MB 级 JS↔Native 传输，纯 JS 提取又无法本机单测。
- 决策: `MetadataExtractor.SSR_DUMP_SCRIPT` 只在内嵌数据里深搜「像作品详情」的对象（有 aweme_id/note_id + video.play_addr 或 images），
  回传 ≤6 个候选详情（另带受限 SSR 文本 200KB/源、总 600KB 兜底）；Kotlin 侧用 `MediaExtractor` 统一解析并做「归属校验」
  （详情 id 必须在当前页面 URL 里，防止推荐流串页）。
- 备选与为何不选: ① 全量 dump SSR → 传输过大、易被 WebView 截断；② 纯 JS 提取 → 逻辑散在字符串里、无法单测。
- 影响 / 约束: 页面结构大改时先看日志 `[SSR] 内嵌详情候选 N 个 → 命中 id=...`；归属校验不通过会记「无媒体URL」跳过，不会串页下载。

## ADR-005 版本号与 tag 一律 `v<versionName>`，不带 `-b<versionCode>`
- 日期: 2026-09-29 | 状态: 已采纳
- 背景: 原 tag / Release 名是 `v1.0.19-b19`（`build.bat` / `gh-release.bat` 里拼 `-b%V_CODE%`），用户要求任何地方都不要带该后缀。
- 决策: tag、Release 标题、Release 说明、commit message、tag message 一律只用 `v%V_NAME%`（如 `v1.0.19`），
  不再输出 `(build %V_CODE%)`；`versionCode` 仍保留在 `version.properties` / build.gradle（Android 必需），仅在控制台诊断行打印。
- 备选与为何不选: 保留 `-b<code>` 便于区分同版本多次构建 —— 用户明确不要，且 versionCode 在 APK/DB 内可见。
- 影响 / 约束: 历史远端 tag（如 `v1.0.19-b19`）仍在，清理需用户明确指示；`gh-release.bat:find_apk` 的 APK 名已同步为 `dy-scraper-v<versionName>.apk`。

## ADR-006 详情响应体：document-start 钩子旁听（取代「重放」）
- 日期: 2026-09-29 | 状态: 已采纳（取代 ADR-003 的主路径部分）
- 背景: ADR-003 定的「影子请求为主 + JS fetch 兜底」在 v1.0.19 / v1.0.20 两次真机都被证伪：重放请求缺 a_bogus，body 恒为空或整页 HTML（`PIT-006`）。
- 决策: 用 `WebViewCompat.addDocumentStartJavaScript(webView, PageHook.SCRIPT, setOf("*"))` 在 document-start 注入钩子：
  旁听 `XMLHttpRequest` / `window.fetch` 命中详情 API 的响应体 → `@JavascriptInterface DyBridge.onApiBody(url, body)` → `WebViewManager.detailBodies`；
  同时用 setter 快照 `window._ROUTER_DATA` 等 SSR 变量（`PIT-007`）。`PageHook.ENABLE_REPLAY_FALLBACK=false`（默认不重放）。
  WebView 不支持 `DOCUMENT_START_SCRIPT` 特性时退化到 `onPageStarted` 注入（脚本幂等：`window.__dyHookV1`）。
- 理由: 只有页面自己的请求带正确签名；钩子是 WebView 里唯一等价于 Playwright `page.on("response") + resp.text()` 的位置。
- 备选与为何不选: ① 继续重放（无签名，恒空/整页，还会二次触发风控）；② 自己实现 a_bogus（算法随版本变化，维护成本不可控）；
  ③ 只靠 SSR（变量被 hydration 删除）；④ 服务端代理解析（超出本 App 边界）。
- 影响与约束: 依赖 `androidx.webkit:webkit:1.9.0`（已在 `app/build.gradle.kts`）与设备 WebView M75+；
  详情 body 经 JS→Java 字符串传递（几十~几百 KB，可接受）；桥回传后按 path+aweme_id 去重（`PIT-008`）。

## ADR-007 图片去重改用 DCT pHash（阈值 5）
- 日期: 2026-09-29 | 状态: 已采纳
- 背景: 旧实现用 aHash（8x8 均值哈希）+ 阈值 4 冒充 pHash，真机上一个 6 图作品被删到只剩 2 张（`PIT-009`）。
- 决策: 新增纯 Kotlin `util/PHash.kt`（32x32 灰度 → 二维 DCT 取 8x8 低频 → 与中位数比较），`ImageDedupChecker` 改调它，Hamming 阈值 5（对齐 Python `imagehash.phash` 与 `hamming_threshold=5`）。
- 备选与为何不选: ① 保留 aHash 只是把阈值降到 0~2 —— 安全区间几乎失去去重能力；② 引入第三方 imagehash 库 —— Android 生态没有等价轻量库，且要新增离线依赖。
- 影响与约束: 哈希只做即时比较、不落库 → 无需数据迁移；采样从 8x8 提升到 32x32，单张图开销仍很小；
  `checkAndDedup` 仍会对目录内每张已有图重算 pHash（性能项，未改，见 ISSUE 待办）。
