# 已排查问题（issues-solved）

> 上限 12KB（超了照 `WRITING.md` §3 归档）。每条必填：症状 / 复发判据 / 根因 / 证据 / 修法 / 反例。
> 编号只增不改，新条目取当前最大号 +1。模板见 `WRITING.md` §1。

## ISSUE-001 视频页「视频 0 个」，视频永远下载不到
- 状态: 已修复（代码已改，真机待复验）　- 复发判据: 视频页抓取日志出现 `[Hook] 捕获详情响应体 ... 视频 1 个` 即正常；
  仍见 `[主路径] Hook 旁听到 0 个详情 API 响应体` + `【视频】0 个`，或 `[API影子] HTTP 200, 长度 0`、`body前100字符: <!DOCTYPE html>` → 复发。
- 症状: 视频页（/video/<id>）处理完 `【视频】0 个`，只下到图片 → `dy_scraper_log_2026-09-29.txt:716`。
- 根因（v1.0.20 真机复测后重写；原记录只写到「四条腿断了」，没抓到唯一解）:
  ① **详情 API 响应体只有旁听页面自己那次请求才拿得到** —— 任何重放都失败（缺页面 JS 现算的 a_bogus）：
     `[API影子] HTTP 200, 长度 0`（日志 540 行）、`[JS fetch API] 响应体长度: 817400` + `body前100字符: <!DOCTYPE html>`（日志 695-700 行）、
     `[API] 响应体长度: 0`（日志 701 行）。高清视频地址只在这个 body 里 → 永远 0 个（PIT-006 / ADR-006）。
  ② SSR 兜底无效：`window._ROUTER_DATA` 被抖音 hydration 后删除，事后再 dump → `[SSR] 扫描 0 个内嵌数据源`（日志 554 行）（PIT-007）。
  ③ JS fetch 重放会被 `shouldInterceptRequest` 二次拦截 → 影子请求再次重放，自我放大风控请求（日志 691-694 行）。
  ④ 视频页在 WebView 里根本没渲染播放器（`DOM视频: 0`、渲染探针恒 false），网络请求里也没有视频流，所以「网络请求兜底」这条腿也不存在。
- 修法: 新增 `core/PageHook.kt` = document-start 注入（XHR/fetch 旁听 → `DyBridge.onApiBody`；SSR setter 快照 `window.__dySsr`）；
  `WebViewManager` 用 `WebViewCompat.addDocumentStartJavaScript`，不支持时退化 onPageStarted 注入；`SSR_DUMP_SCRIPT` 改读快照；
  详情 API 去重键带 aweme_id（PIT-008）；重放兜底默认关闭；渲染探针加 SSR 快照判定。
- 反例（曾误判成什么）: ① 以为「页面没渲染出 `<video>`」是根因 —— 那只是没渲染播放器，不是拿不到地址的原因；
  ② 以为「加个 SSR/dump 兜底」就够 —— 变量已被删，dump 时机不对等于没做；
  ③ 把重放（影子请求 / JS fetch）当主路径 —— 无签名，body 恒为空或整页 HTML。
- 相关条目: PIT-001 PIT-002 PIT-003 PIT-006 PIT-007 PIT-008 ADR-003 ADR-006
- 首次记录: 2026-09-29 ／ 最近复核: 2026-09-29（v1.0.20 真机日志仍 `视频 0 个` → 换方案；本机单测全绿，真机待复验）

## ISSUE-002 进度日志恒为 0/N
- 状态: 已修复　- 复发判据: 抓取时出现 `进度: 1/3 (33%)` 即正常；仍见 `进度: 0/3 (66%)` → 复发。
- 症状 / 根因 / 证据: 日志 33/315 行 `进度: 0/0 (0%)`、`进度: 0/3 (100%)`；
  `ScraperWorker.updateProgress` 写 `url_current`，`MainActivity` 读 `url_done`（键名不一致）。
- 修法: `MainActivity.kt` 改读 `url_current`；顺带 onDestroy 移除 Logger 监听器（防泄漏）。
- 反例: 误以为 WorkManager 进度不回调 —— 实际只有键名错。
- 相关条目: ISSUE-003　- 首次记录: 2026-09-29 ／ 最近复核: 2026-09-29

## ISSUE-003 汇总「文件总数 ≠ 成功+失败+跳过」
- 状态: 已修复　- 复发判据: 汇总应打印 `合计核对: N = N`；两侧不等 → 复发。
- 症状 / 根因 / 证据: 日志 323-326 行 文件总数 29 / 成功 26 / 跳过 1（差 2）；
  `ScrapeStats.printFinalSummary` 的 `imageSkipped` 漏加 `image.skippedPhashDup`；`skipped_emoji` 未进任何桶。
- 修法: imageSkipped 补 phash；`MediaStats.accumulateResults` 把 `skipped_emoji` 计入 skippedPhashDup；新增「合计核对」行。
- 相关条目: ISSUE-002　- 首次记录: 2026-09-29 ／ 最近复核: 2026-09-29