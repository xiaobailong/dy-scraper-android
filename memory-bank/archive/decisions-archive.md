# 已归档决策（decisions archive）

> 倒序追加。每个条目：原 ADR 编号 + 归档日期 + 原文。

## ADR-003 详情 API 响应体：WebView 影子请求为主，JS 桥为辅
- 归档日期: 2026-10-15（已被 `ADR-006` 取代；同时主文件 `decisions.md` 超 12KB 上限，按 `WRITING.md` §3 归档）
- 日期: 2026-09-29 | 状态: 已废弃（被 ADR-006 取代）
- 背景: Playwright 版 `page.on("response") + resp.text()` 能直接读到详情响应体；WebView 的 `shouldInterceptRequest` 只给 URL 不给 body；
  `evaluateJavascript` 又不 await Promise（`PIT-001`）→ 视频高清地址拿不到（`ISSUE-001`）。
- 决策: 命中 `DETAIL_API_PATTERNS` 时，用同一会话（CookieManager 的 Cookie + 同 UA + Referer）在后台线程**重放请求抓 body**（"影子请求"），
  入队 `WebViewManager.detailBodies`；`UrlProcessor` 等待并用 `DouyinApiCollector.parseAllBodies` 解析。JS fetch + `DyBridge.onApiResponse` 作兜底。
- 备选与为何不选: ① 只记 URL（原实现）→ 拿不到 body，故障根因；② 在 `shouldInterceptRequest` 里同步请求 → 阻塞 WebView 资源加载；
  ③ 只靠 JS fetch → Promise 无法 await，桥接只能兜底（CSP/风控场景可能失败）。
- 影响 / 约束: 详情接口被请求两次（WebView 一次、影子一次）；影子失败不影响页面；同一 URL 只抓一次。
- 作废原因: 真机复测证明重放拿不到 body（缺页面 JS 现算的 a_bogus），改走 `ADR-006` 的 document-start 旁听。


## ADR-002 目录体系：直接下载到最终目录，无临时目录
- 归档日期: 2026-10-15（主文件 `decisions.md` 超 12KB 上限，按 `WRITING.md` §3 归档）
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


## ADR-001 技术路线：Native Kotlin + WebView（替代 Python Playwright）
- 归档日期: 2026-10-15（主文件 `decisions.md` 超 12KB 上限，按 `WRITING.md` §3 归档）
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
