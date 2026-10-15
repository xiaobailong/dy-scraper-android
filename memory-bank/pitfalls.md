# 踩坑记录（pitfalls）

> 上限 12KB（超了照 `WRITING.md` §3 归档）。每条必填：正确做法 / 反例 / 自检（+ 触发条件与现象）。
> 通用坑来自同机同工具链的源仓库 `ClipboardMerger`；本项目事故另注 commit。编号只增不改。
> 标「已归档」的条目：主文件只留一行要点，原文在 `archive/pitfalls-archive.md`。

## PIT-001 Android evaluateJavascript 不会 await Promise
- 触发条件: 用 `evaluateJavascript("(async () => { return await fetch(...).then(r => r.text()) })()")` 取异步结果。
- 现象: 回调值恒为 `{}`（长度 2），解析出空数据；日志「[JS fetch API] 响应体长度: 2」。
- 正确做法: 异步结果走 `@JavascriptInterface` 桥回传（`DyBridge.onApiResponse(id, body)` + Kotlin `CompletableDeferred`），见 `WebViewManager.fetchApiViaJs`；
  文本类返回值统一用 `evaluateJavascriptText`（JS 侧 base64 传 UTF-8）。
- 反例: `(async function(){...})()` 直接当返回值；或 `.trim('"').replace("\\\"","\"")` 手撕 JSON 转义（会改坏正文里的 `\"`、`\n`）。
- 自检: 凡 JS 里有 await/Promise 的返回值都必须走桥；日志出现「长度: 2」即复发。

## PIT-002 抖音视频流地址没有 .mp4 后缀
- 触发条件: 用扩展名判断网络请求里的视频 URL。
- 现象: 视频页 154 个请求 → 0 视频；真实地址形如
  `https://v3-web.douyinvod.com/.../video/tos/cn/tos-cn-ve-15/oXXXX/?a=6383&br=2500&mime_type=video_mp4`。
- 正确做法: `Downloader.isVideoUrl` = 路径后缀 ∪ `mime_type=video_mp4`/mime ∪ 域名（douyinvod.com / ixigua.com / snssdk.com / bytecdn / zjcdn.com）。
- 反例: `.mp4?` / `.jpg?` 这类「扩展名+问号」匹配（只在恰好带 query 时命中，纯属巧合）。
- 自检: 单测 `DownloaderUrlTest`。

## PIT-003 同一视频有多个地址（download_addr / play_addr / play_addr_h264 / 多档 bit_rate）
- 触发条件: 把详情 JSON 里所有 url_list 都当独立文件下载。
- 现象: 同一作品下 4~5 份不同码率视频；MD5 不同 → 去重失效。
- 正确做法: 每个详情只取 1 条，分层优先级 `download_addr > 最高码率 bit_rate > play_addr > play_addr_h264`（`MediaExtractor.videoCandidates`）；
  再用 `Downloader.deduplicateVideos` 按 file_id+br 兜底。
- 自检: 单测断言候选顺序、且 `parseApiBody` 只返回 1 条视频。

## PIT-004 本机跑 Gradle 自测：JAVA_HOME 必须显式指定
- 触发条件: 直接跑 `gradle.bat`：环境 `JAVA_HOME` 指向 IDE 的 java25（无效路径）→ `ERROR: JAVA_HOME is set to an invalid directory`。
- 正确做法: 先 `set "JAVA_HOME=D:\Tools\DevTools\Java\JDK\jdk-17.0.10-oracle"` 再调 gradle；
  且用 `start "" /min <tmp脚本>` 起独立窗口、输出重定向到文件（工具的前台命令会被下一条命令杀掉）。
- 反例: 前台跑长构建后再敲别的命令 → 构建被中断，日志只剩 2 行；`build.bat` 禁用（会 push + 发 Release）。
- 自检: 日志末尾出现 `BUILD SUCCESSFUL` 与 `EXITCODE=0`。

## PIT-005 给 .bat / memory-bank 改内容要守 CRLF / 无 BOM
- 触发条件: 用文本工具批量替换 `.bat`、`memory-bank/*.md` 内容。
- 现象: 写成 LF 违反仓库约定；临时脚本里写 `\r\n` 字面量会把整段变一行（`@echo off\r\ncd ...` 被 cmd 当命令处理）。
- 正确做法: `[IO.File]::ReadAllText/WriteAllText` + `New-Object System.Text.UTF8Encoding($false)`，只做 ASCII 子串替换，改完自检 `crlf=True bom=False`。
- 反例: `Set-Content -Encoding UTF8`（PS 5.1 会写 BOM）；在无 BOM 的 .ps1 里写中文按 ANSI 解析会乱码。
- 自检: PowerShell 读回 `Contains([char]13)` 与首字节是否为 239。

## PIT-006 抖音详情 API 响应体只能「旁听」，不能「重放」
- 触发条件: 想用 OkHttp / JS fetch 重新请求 `shouldInterceptRequest` 里看到的详情 API URL 来拿 body（旧 ADR-003 的路子）。
- 现象: `[API影子] HTTP 200, 长度 0`；JS fetch 重放 `响应体长度: 817400` 且 `body前100字符: <!DOCTYPE html>`；OkHttp 直连 `[API] 响应体长度: 0`。
  三者都是风控结果：URL 里虽有 a_bogus，但它是页面 JS 按当时环境/时间算的，重放即失效。
- 正确做法: **document-start** 注入 XHR/fetch 钩子旁听页面自己那次请求，响应体经 `@JavascriptInterface onApiBody` 回传
  （`core/PageHook.kt` + `WebViewCompat.addDocumentStartJavaScript`）；重放只保留在 `PageHook.ENABLE_REPLAY_FALLBACK=true` 下供排障。
- 反例: 把重放当主路径 —— v1.0.19 / v1.0.20 两次真机都是 `【视频】0 个`。
- 自检: 日志出现 `[Hook] 捕获详情响应体 N 字符 → 视频 1 个`；否则 `[Hook] 丢弃无效响应体(...)`、`Hook 未捕获到详情响应体`。
  本机自检: `node tools\hook-smoke-test.js`（13 项断言，假 WebView + 假 XHR/fetch）。

## PIT-007 抖音 SSR 变量会在 hydration 后被删（必须赋值即快照）
- 触发条件: 等页面加载完再去读 `window._ROUTER_DATA` / `RENDER_DATA` 做兜底。
- 现象: `[SSR] 扫描 0 个内嵌数据源 (命中: 无)`（视频页，日志 554 行）；图集页 `扫描 2 个内嵌数据源 (命中: 无)`（日志 471 行）。
- 正确做法: document-start 用 `Object.defineProperty(window, k, {configurable, get, set})` 拦截赋值，把 JSON 存进 `window.__dySsr`；
  `MetadataExtractor.SSR_DUMP_SCRIPT` 优先读 `window.__dySsr`（回路 key 形如 `snap:_ROUTER_DATA`）。
- 反例: onPageFinished 之后 `JSON.stringify(window._ROUTER_DATA)` —— 变量已被删。
- 自检: 日志 `[SSR] 扫描 N 个内嵌数据源 (命中: snap:_ROUTER_DATA)`。

## PIT-008 详情 API 去重键不能只用路径
- 触发条件: 用「URL 去掉 query」当「同一响应只解析一次」的键。
- 现象: 所有作品的详情接口路径都相同（`/aweme/v1/web/aweme/detail/`）→ 第 2 个作品被当重复丢掉，只有第一个能被解析。
- 正确做法: 键 = path + `aweme_id`/`note_id`（`PageHook.apiDedupeKey`），并且每次 `WebViewManager.loadUrl` 清空该集合。
- 反例: `url.substringBefore("?")`。
- 自检: 单测 `PageHookTest.dedupe key keeps different aweme ids apart`。

## PIT-009 aHash 不能当 pHash 用（会误删图片）
- 触发条件: 图片去重用 8x8 灰度均值哈希（aHash）。
- 现象: 同一个图集 6 张图只留下 2 张，4 张被 `[图片去重] pHash匹配，删除 ... (较小)` 删掉（真机日志 500-508 行）；
  aHash 只反映整体亮度分布，同场景不同照片距离常 ≤4 —— 等于随机删用户照片。
- 正确做法: DCT pHash（32x32 灰度 → 8x8 低频系数 → 与中位数比较），阈值 5（对齐 Python `imagehash.phash` / `hamming_threshold=5`）；
  实现 `util/PHash.kt`，`ImageDedupChecker.computePHash` 调它。
- 反例: `val avg = grayPixels.average(); if (gray[i] > avg) ...`。
- 自检: 单测 `PHashTest`（同图亮度偏移 ≤5；竖条纹/横条纹/中心块两两 >5）。

## PIT-010 大文件 MD5 不能用 readBytes()
- 触发条件: 启动时扫已有文件算 MD5（`Utils.scanExistingMd5s` → `Utils.md5(file)`）。
- 现象: 视频目录几百 MB 时 `file.readBytes()` 一次性进堆（Android 通常 128~256MB）→ OOM 崩溃风险（`AppConfig.MAX_FILE_SIZE` 允许单文件 500MB）。
- 正确做法: 流式 `file.inputStream()` + 256KB buffer 增量 update digest（结果与 readBytes 版完全一致）。
- 反例: `digest.update(file.readBytes())`。
- 自检: 单测 `UtilsTest.md5 file equals md5 bytes and handles large file`（3MB 文件，与 readBytes 结果比对）。

## PIT-012 本机执行 adb / 长构建：stderr 会中断同一行命令、`tmp\` 内容可能消失
- 触发条件: 在同一 PowerShell 命令行里把 `adb`（pull/dump/install）与后续解析语句串在一起；或依赖上一条命令写进 `tmp\` 的中间文件跨命令读取。
- 现象: ① `adb pull` 正常成功（"1 file pulled"）但 PowerShell 报 `NativeCommandError`，后面所有语句都不执行（`2>$null` 也拦不住，命令整体 exit 1）；
  ② 本次 `tmp\compile.log`/`test.log`/`apk.log`/`ui.xml` 在后续命令里消失（同目录的 `probe.txt`、`ui2~4.xml` 仍在），`adb pull tmp\x` 报 `cannot create file/directory`。
- 正确做法: ① `adb` 调用单独成句、或包成 `cmd /c "\"...\adb.exe\" pull <src> <abs-dst> 2>nul"`（已验证：16485 bytes 正常落地且不中断后续语句），
  目标路径写**绝对路径**；② `tmp\` 当纯垃圾桶：拿到结论就写进回复/知识库，需要重读就重新生成（先 `New-Item -ItemType Directory -Force tmp` 再拉/再跑）。
- 反例: `adb pull x tmp\y 2>$null | Out-Null; <解析>` —— 解析永远跑不到；把关键中间文件当成「下一条命令一定能读到」。
- 自检: 同一条命令里 `cmd /c "... pull ..."` 之后紧跟的语句有输出（如 `if (Test-Path ...){...}` 打印尺寸）。

## PIT-013 华为 EMUI「回收站」拦 App 删除：`File.delete()` 有时完全无效（加密后明文与密文共存 → 列表翻倍）
- 触发条件: EMUI（LIO-AN00m / SDK 31）对 `Download/dy-scraper/{images,videos}/` 里的文件调 `File.delete()`（加密后删明文、或解密后删 `.enc`）。
- 现象: 同一目录、同一批文件里两种结果混着出现 —— ① 文件被改名成隐藏的 `.<原名>.hwbk`（原路径消失，无害，只是占空间）；
  ② **完全不生效**：文件仍留在原路径（真机 20:22：8 个明文 delete 后全在，明文 + 新 `enc_*` 共存 → 加密页列表 8 → 16；同一时刻的 8 个 `.enc` 却删掉了）。
  `delete()` 返回值以前没人检查，两种结果都静默。
- 清理现场（2026-10-15 实测）: **可见的明文残留 adb shell `rm` 能删掉**（同一个文件 App 删不掉、adb 能删 —— 拦截发生在 App 的 unlink 路径上）；
  而回收站里的 `.<名>.hwbk` 连 adb 都动不了：`rm -f` / `mv` 都返回 `No such file or directory`（被系统回收服务守着），
  只能走系统「文件管理 → 回收站 → 彻底删除」，或等它自己过期。
- 正确做法: 别把 `delete()` 当可靠删除 —— 先 `file.renameTo(File(dir, ".${file.name}.dyresidue"))`（rename 只改目录项，不触发回收站）把文件移出可见路径，
  再尽力 `delete()` 隐藏残留；随后**必须核对** `file.exists()`，还在就调用方登记隐藏（本项目 `crypto/CryptoManager.removeSourceFile()` + `crypto/SupersededSourceRegistry`）；
  扫描时再按「反解加密文件名 + 密文 = 明文 + 28B」识别历史残留（明文与密文共存）只列加密版，这样老残留也不会翻倍。
- 反例: 只 try/catch 不看返回值就当"源文件已删"；把翻倍当成"多复制了一份 / Adapter 插了两遍 / 扫描重复计数"。
- 自检: 加密后 `cmd /c "\"D:\Tools\DevTools\Android\Sdk\platform-tools\adb.exe\" shell ls -la /storage/emulated/0/Download/dy-scraper/images/"` 里不应再有明文原名文件；
  日志出现 `CryptoManager: 源文件已移出可见路径` 或 `源文件已删除`，且 `加密结束…源文件未移走 0 个`。

## PIT-011 Windows 上 adb devices 恒为空（华为 ADB 接口被 winusb.inf 抢装、缺 DeviceInterfaceGUIDs） — 已归档（2026-10-15）：见 archive/pitfalls-archive.md；要点 + 自检：给该接口的 Device Parameters 补 DeviceInterfaceGUIDs={F72FE0D4-CBCB-407d-8814-9ED673D0DD6B} 后 pnputil /restart-device（或设备管理器改选 ADB Device），adb devices 非空即好。
