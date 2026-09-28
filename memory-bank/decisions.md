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