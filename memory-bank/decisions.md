# 技术决策（decisions / ADR）

> 上限 12KB（超了照 `WRITING.md` §3 归档；已废弃的移入 `archive/decisions-archive.md`）。
> 每条：背景 / 决策 / 理由 / 备选与为何不选 / 影响。编号只增不改。模板见 `WRITING.md` §1。

## ADR-001 技术路线：Native Kotlin + WebView — 已归档（2026-10-15）：见 archive/decisions-archive.md；要点：WebView 顶 Playwright 做渲染/拦截、OkHttp 顶 urllib、Room 顶文件记录、Coroutines 顶 asyncio。

## ADR-002 目录体系：直接下载到最终目录 — 已归档（2026-10-15）：见 archive/decisions-archive.md；要点：不做 temp 中转，边下边去重（MD5/pHash），失败残留靠 MD5 扫描过滤。

## ADR-003 详情 API 响应体：影子请求重放 — 已废弃（被 ADR-006 取代）／已归档（2026-10-15）：见 archive/decisions-archive.md；要点：重放无 a_bogus → body 恒空，改走 document-start 旁听。

## ADR-004 SSR/内嵌数据：JS 只做定位，解析放 Kotlin（可单测） — 已归档（2026-10-15）：见 archive/decisions-archive.md；要点：JS 只回传 ≤6 个「像详情」候选（含受限 SSR 文本快照），解析与「归属校验」全在 Kotlin（`MediaExtractor`），归属不通过记「无媒体URL」跳过。

## ADR-005 版本号与 tag 一律 `v<versionName>`，不带 `-b<versionCode>` — 已归档（2026-10-15）：见 archive/decisions-archive.md；要点：tag/Release/commit 一律只用 `v%V_NAME%`（如 `v1.0.19`），不输出 `(build %V_CODE%)`；versionCode 仅留在 version.properties / build.gradle。

## ADR-006 详情响应体：document-start 钩子旁听（取代「重放」） — 已归档（2026-10-15）：见 archive/decisions-archive.md；要点：用 `WebViewCompat.addDocumentStartJavaScript` 注入钩子旁听 XHR/fetch 的详情响应体（+ setter 快照 SSR 变量），`ENABLE_REPLAY_FALLBACK=false`；不支持该特性时退化 `onPageStarted` 注入（`window.__dyHookV1` 幂等）。

## ADR-007 图片去重改用 DCT pHash（阈值 5） — 已归档（2026-10-15）：见 archive/decisions-archive.md；要点：新增纯 Kotlin `util/PHash.kt`（32x32 灰度→二维 DCT 取 8x8 低频→比中位数）替代 aHash，Hamming 阈值 5，对齐 Python `imagehash.phash`。

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


## ADR-010 预览页去掉「×」，改用系统返回 — 已归档（2026-10-15）：见 archive/decisions-archive.md；要点：删 `btnClose`，退出只走系统返回；ExoPlayer 释放与临时解密文件清理统一收到 `MediaViewerActivity.onDestroy()`。

## ADR-011 加密页计数并入底部状态栏（白底略带透明）
- 日期: 2026-10-15 | 状态: 已采纳
- 背景: 「共 N 个文件（已选 M 个）」原来独占加密页列表上方一行；Activity 底部本就有状态栏（原紫底白字）。
- 决策: 删掉 `fragment_encrypt.xml` 的 `tvFileCount`；`activity_main.xml` 底部改 `bottomBar`（左 `statusBar` + 右 `tvFileCountBar`，计数只在加密页显示）；
  底色 `@color/bottom_bar_background`(#E6FFFFFF，白色 90% 不透明)、文字改 `text_secondary`；`EncryptFragment.onCountUpdate` → `MainActivity.renderFileCount()` 统一渲染与显隐。
- 备选与为何不选: 计数常驻 → 采集页显示陈旧数据；两段拼一行 → 互相挤占、易被 ellipsize 吃掉。
- 影响与约束: `statusBar` id 与抓取进度逻辑不变；`MainPagerAdapter` 新增 `encryptFrag`；下拉提示缩短为「· 下拉刷新」；真机截图核对过白底深字。

## ADR-012 反风控：客户端节流 + 验证页「暂停等人工」，不做任何自动识别/绕过
- 日期: 2026-10-15 | 状态: 已采纳
- 背景: 抓取偶发落到抖音验证页（滑块）；触发率主要由请求节奏决定——原实现视频/图片并发 5/8、页与页/批次与批次之间零间隔，节奏固定且偏猛。
- 决策: ① **节流**：`AppConfig` 新增 `page_interval_ms`(2500) / `batch_interval_ms`(800) / `jitter_percent`(50)，并发默认 3/4；
  等待时长全部由纯函数 `util/Pacing.kt` 计算（`nextDelayMs` = 基础值 × [1, 1+抖动%]），落点：`ScraperEngine` 页间、
  `Downloader.downloadFiles` 批次间、`downloadOne` 批次内 150-300ms 随机错峰；下载失败重试改指数退避（2s→5s→10s + 30% 抖动），
  HTTP 403/412/429 视为风控码并在日志里单独提示（`Pacing.isRiskHttpCode`）。
  ② **验证页暂停**：`WebViewManager.isVerificationPage()`（URL 关键字 + DOM 容器/提示文案探测，走 `evaluateJavascriptText`）→
  `UrlProcessor.pauseForManualVerificationIfNeeded()` 暂停本页 → 回调 `onVerificationStateChange` → `ScrapeFragment` 隐藏日志区、
  让出整块区域给 WebView、底部状态栏提示「请手动完成安全验证」；每 2s 复检，通过即自动继续，5 分钟超时记 warn 后继续。
- 备选与为何不选: ① 写滑块识别/自动拖动 → 属绕过行为、会招更严风控，用户已明确不做；② 换 IP/换 UA/多端并行 → 单设备无意义，
  且轮换 UA 破坏现有「全场固定桌面 UA + 复用 WebView Cookie 会话」的一致性；③ 一遇验证就整体中止 → 丢进度，人工过一次更划算。
- 影响与约束: 抓取整体变慢（每页多 2.5-3.75s、每批次多 0.8-1.2s），要快就在设置里把间隔/抖动调小或设 0；
  已存过设置的用户保留旧值（并发仍是 5/8，需手动改）；验证检测是启发式，抖音改 DOM/文案后要更新 `RISK_URL_KEYWORDS` / `VERIFY_PROBE_JS`；
  人工等待上限 `AppConfig.DEFAULT_RISK_WAIT_TIMEOUT_MS`(5 分钟)。抓取逻辑不做真机自测（项目红线），计算部分由 `PacingTest`（9 例）覆盖。

## ADR-013 全局配色：纯白（顶栏 / Tab 栏 / 底部栏 / 窗口背景 / 系统状态栏）
- 日期: 2026-10-15 | 状态: 已采纳（取代同日的「白色略带灰」尝试）
- 背景: 先把顶栏/Tab 改成"白色略带灰"（#F2F2F2），真机一看被否（用户：白里带灰有点难看），要求全局纯白；
  同时顶部系统状态栏一直是 App 主色紫 —— 主题里 `colorPrimaryDark=#3700B3`，AppCompat 在 API 21+ 默认把它当 `android:statusBarColor`，不显式覆盖就永远是紫的。
- 决策: `colors.xml` 里 `background`(#F5F5F5→#FFFFFF)、`top_bar_background`(#F2F2F2→#FFFFFF)、`bottom_bar_background`(#E6FFFFFF→#FFFFFF) 全部纯白；
  `themes.xml` 显式加 `android:statusBarColor=@color/top_bar_background` + `android:windowLightStatusBar=true`（图标转深色；minSdk 26，直接放 `values/` 即可）。
- 理由: 用户要"全局白"；状态栏颜色由主题决定，改 `statusBarColor` 才治本，不必在 Activity 里写 `window.statusBarColor`。
- 备选与为何不选: ① 保留 #F2F2F2（已被用户否掉）；② 只改状态栏不动背景（白/灰不统一）；③ 代码里设状态栏颜色（多一份运行时逻辑，主题能表达就别写代码）。
- 影响与约束: 紫色退化为强调色（Tab 指示条 `tabIndicatorColor=@color/primary`、按钮）；底栏从 90% 半透明白变纯白（它在 LinearLayout 里不覆盖内容，无副作用）；
  顶栏/底栏靠 Toolbar 的 4dp elevation 与 1dp `divider` 区分，没有灰色底也看得清。




