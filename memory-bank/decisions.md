# 技术决策（decisions / ADR）

> 上限 12KB（超了照 `WRITING.md` §3 归档；已废弃的移入 `archive/decisions-archive.md`）。
> 每条：背景 / 决策 / 理由 / 备选与为何不选 / 影响。编号只增不改。模板见 `WRITING.md` §1。

## ADR-001 技术路线：Native Kotlin + WebView — 已归档（2026-10-15）：见 archive/decisions-archive.md；要点：WebView 顶 Playwright 做渲染/拦截、OkHttp 顶 urllib、Room 顶文件记录、Coroutines 顶 asyncio。

## ADR-002 目录体系：直接下载到最终目录 — 已归档（2026-10-15）：见 archive/decisions-archive.md；要点：不做 temp 中转，边下边去重（MD5/pHash），失败残留靠 MD5 扫描过滤。

## ADR-003 详情 API 响应体：影子请求重放 — 已废弃（被 ADR-006 取代）／已归档（2026-10-15）：见 archive/decisions-archive.md；要点：重放无 a_bogus → body 恒空，改走 document-start 旁听。

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

## ADR-008 加密页：刷新改为「列表顶部下拉」，全选移到「列表首行」
- 日期: 2026-10-15 | 状态: 已采纳
- 背景: 加密页原来用一整行放「刷新 + 全选」两个按钮，挤占列表高度。用户要求去掉按钮：刷新改成"列表顶部继续下拉"触发，
  全选另换一种不常驻按钮的实现（先给方案、确认后执行）。
- 决策:
  ① 刷新 → `androidx.swiperefreshlayout:swiperefreshlayout:1.1.0` 的 `SwipeRefreshLayout`：`RecyclerView` 包在 `FrameLayout` 里、
     空态提示 `llEmptyHint` 做覆盖层（列表常驻可见，空列表也能下拉）；`setOnChildScrollUpCallback { _, _ -> rvMediaFiles.canScrollVertically(-1) }`
     保证只在列表滚到顶部时才允许下拉；`refreshFileList()` 的 `finally` 里 `isRefreshing = false`，原「正在刷新…」Toast 删除；
  ② 全选 → RecyclerView 首行：新增 `res/layout/item_select_all_row.xml`（复选框 + 文案），`EncryptAdapter` 加 `showSelectAllRow`
     与 `headerOffset`，首行 `viewType = TYPE_SELECT_ALL_ROW`，点击走 `toggleSelectAll()`（全选 ↔ 取消全选），**列表为空时不显示首行**；
  ③ 首行禁止左滑删除：`ItemTouchHelper.getMovementFlags` 遇到 `EncryptAdapter.SelectAllViewHolder` 返回 `makeMovementFlags(0, 0)`。
- 备选与为何不选: ① 长按进入多选模式（相册式上下文工具条）——交互更顺，但必须长按一次才能全选，且改动面更大；
  ② 收进工具栏「⋮」溢出菜单——多两步点击，还要与 `MainActivity` 现有 menu/popup 协调（切 tab 需 `invalidateOptionsMenu`）；
  ③ 长按「加密」按钮=全选并加密——语义隐蔽、易误操作。**用户选定首行方案**。
- 影响与约束: 新增一个官方小依赖（本机 Gradle 缓存已有 1.1.0，离线可构建）；
  `EncryptAdapter.getItem/removeItem` 的参数语义改为 **adapter position（含首行）**，内部用 `headerOffset` 换算，调用方 `onSwiped` 不用改；
  删除 3 个已无用字符串（`encrypt_btn_refresh` / `encrypt_btn_select_all` / `encrypt_btn_unselect_all`），计数行文案加"· 列表顶部下拉刷新"；
  已真机实测：首行点击全选/取消全选 ✓、首行左滑不删 ✓、顶部下拉触发刷新 ✓、滚离顶部小幅下拉不触发 ✓、点列表项进查看器序号正确（2 / 8）✓；
  空列表下拉未实测（结构上由"列表常驻可见 + 覆盖层空态提示"保证）。


## ADR-009 加密页长文件名显示：中间省略 + 信息行带「尾部标识」
- 日期: 2026-10-15 | 状态: 已采纳
- 背景: 文件名 = `<作者>_<标题≤50字>_<下载时间戳>_<序号>.<ext>`，最长 80+ 字符；列表行 `tvFileName` 只有 2 行（14sp、宽约 684px≈19 字/行）
  + `ellipsize="end"` → 被砍掉的恰好是尾部 `_yyyyMMdd_HHmmss_序号.ext`（区分同一帖子第几张的唯一信息），而信息行只有 `248 KB 图片`。
- 决策: ① 新增 `Utils.ellipsizeMiddle(name, headChars=10, minTailChars=22)`：长名显示成 `头部…尾部`，尾部从最近的 `_` 边界开始
  （实测 `我的_860连体裤穿…_20261015_122204_4.webp`）；② 新增 `Utils.nameTailKey(name)` 提取 `yyyyMMdd_HHmmss_序号.ext`（不匹配则退化为扩展名）；
  ③ `MediaFileItem` 新增 `shortName` / `infoText` 派生属性供适配器绑定，信息行改为 `20261015_122204_4.webp · 242 KB · 图片`。
- 备选与为何不选: ① 单行 + 原生 `ellipsize="middle"` —— 一行放不下 80 字符，省略后只剩首尾几个字；② 3 行 + `app:autoSizeTextType`
  自动缩放字号 —— 名字能全显示，但行高变大（每屏少 1~2 行）、字号不统一；③ 改下载命名规则把标题截短（`take(50)`→`take(20)`）——
  只对新文件生效、历史文件仍是长名，且削弱可读性（可作为后续叠加项）。
- 影响与约束: 纯显示层，不动真实文件名；`tvFileName` 保留 `maxLines=2 + ellipsize="end"` 兜底；`item_select_all_row` / 查看器不受影响；
  单测覆盖（`UtilsTest`：短名不变、尾部完整且对齐 `_` 边界、无下划线退化、阈值非正不处理、`nameTailKey` 提取与退化），
  共 131 个单测全绿；真机实测显示 `我的_860连体裤穿…_20261015_122204_4.webp` + `20261015_122204_4.webp · 242 KB · 图片`。


## ADR-010 预览页去掉「×」，改用系统返回
- 日期: 2026-10-15 | 状态: 已采纳
- 背景: 预览页（`MediaViewerActivity`）右上角常驻「×」关闭按钮，与系统返回重复且遮挡画面。
- 决策: 删除 `btnClose`（布局 + 监听 + 无用字符串 `media_viewer_close`）；退出只走系统返回键/手势；
  同时把资源清理收到 `onDestroy()`：适配器用 `boundHolders` 记录已绑定页，统一 `releasePlayer()`（释放 ExoPlayer + 删临时解密文件），不依赖 RecyclerView 回收时机。
- 备选与为何不选: 保留「×」改半透明/几秒后自动隐藏 —— 仍与系统返回重复，且要多一套显隐逻辑。
- 影响与约束: `onDestroy` 里用 `::viewPager.isInitialized` 防护（无文件早退路径不会崩）；真机实测：预览页 dump 无 `btnClose`、序号 `2 / 8` 正常，手势返回后停在本页列表（`mResumedActivity=MainActivity`）且日志出现 `releasePlayer 被调用`。
  提醒：用 `adb shell input keyevent 4` 验证时，合成事件会被二次投递把 MainActivity 也 finish（App 退到后台的假象），真实手势/按键无此现象。

## ADR-011 加密页计数并入底部状态栏（白底略带透明）
- 日期: 2026-10-15 | 状态: 已采纳
- 背景: 「共 N 个文件（已选 M 个）」原来独占加密页列表上方一行；Activity 底部本就有状态栏（原紫底白字）。
- 决策: 删掉 `fragment_encrypt.xml` 的 `tvFileCount`；`activity_main.xml` 底部改 `bottomBar`（左 `statusBar` + 右 `tvFileCountBar`，计数只在加密页显示）；
  底色 `@color/bottom_bar_background`(#E6FFFFFF，白色 90% 不透明)、文字改 `text_secondary`；`EncryptFragment.onCountUpdate` → `MainActivity.renderFileCount()` 统一渲染与显隐。
- 备选与为何不选: 计数常驻 → 采集页显示陈旧数据；两段拼一行 → 互相挤占、易被 ellipsize 吃掉。
- 影响与约束: `statusBar` id 与抓取进度逻辑不变；`MainPagerAdapter` 新增 `encryptFrag`；下拉提示缩短为「· 下拉刷新」；真机截图核对过白底深字。


