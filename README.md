# dy-scraper-android

抖音网页内容抓取工具 Android 版 —— 基于 Kotlin + WebView 实现，将 [dy-scraper](https://github.com)（Python / Playwright）迁移到 Android 平台。

## 功能

| 模块 | 说明 |
|---|---|
| 页面抓取 | WebView 加载抖音页面，`PageHook` 在 `document-start` 注入 JS 钩子（拦截 `XMLHttpRequest` / `fetch`），旁听页面原生请求获取详情 JSON（避免风控签名问题） |
| 媒体提取 | `MediaExtractor` 纯 Kotlin 解析抖音详情 JSON / SSR 数据，提取视频最优地址 & 图片原始分辨率，与 Python 版提取优先级完全对齐 |
| 抓取引擎 | `ScraperEngine` 共用抓取管道，Activity（UI WebView）和 Worker（后台 WebView）复用同一流程 |
| 内容下载 | OkHttp 多线程下载视频 / 图片，自动去重（MD5 + pHash） |
| 本地模式 | 手动粘贴抖音链接（每行一个），暂存草稿，开始 / 停止抓取 |
| 有道模式 | 从有道云笔记 API 获取 URL 列表，用户确认后开始抓取 |
| 全局日志 | 写入 `Download/dy-scraper/` 目录，按天滚动，7 天自动清理；Logcat 同步输出 |
| 日志开关 | 右上角菜单一键关闭全部日志输出，SharedPreferences 持久化，重启生效 |
| 运行模式 | 本地 / 有道两模式可切换，SharedPreferences 持久化，默认本地模式 |
| 底部状态栏 | 实时显示抓取进度（百分比 / 当前/总数）和完成状态 |
| 文件管理 | 直接下载到最终目录（无临时目录），目录：`videos/`、`images/`、`results/` |
| 进度通知 | 前台 Service + 通知栏显示抓取进度，防系统回收 |

## 技术栈

| 层次 | 技术 | 对应 Python 原版 |
|---|---|---|
| 语言 | Kotlin 1.9 | Python 3.x |
| 浏览器引擎 | Android WebView (Chromium) | Playwright (Chromium) |
| HTTP 客户端 | OkHttp 4.12 | urllib / requests |
| 异步调度 | Kotlin Coroutines | asyncio |
| 持久化 | Room (SQLite) | 文件存储 |
| 后台任务 | WorkManager | 手动循环 |
| UI | AppCompat + XML Layout | — |

## 目录结构

```
dy-scraper-android/
├── app/
│   ├── build.gradle.kts          # SDK 26+, Room KSP, OkHttp, WorkManager
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/dy/scraper/
│       │   ├── MainActivity.kt           # 主界面：Toolbar + 模式切换 + 日志区 + 底部状态栏
│       │   ├── ScraperApp.kt             # Application：通知渠道
│       │   ├── api/
│       │   │   └── DouyinApiCollector.kt  # 抖音 API 响应收集（OkHttp 直连）
│       │   ├── core/
│       │   │   ├── ScraperEngine.kt       # 共用抓取管道（Activity/Worker 复用）
│       │   │   ├── PageHook.kt            # document-start JS 钩子（拦截 XHR/fetch，旁听页面原生请求）
│       │   │   ├── MediaExtractor.kt      # 纯 Kotlin 媒体地址提取（详情 JSON / SSR）
│       │   │   ├── WebViewManager.kt      # WebView 生命周期 & JS 注入
│       │   │   ├── UrlProcessor.kt        # 单 URL 处理流程
│       │   │   ├── Downloader.kt          # 多线程文件下载
│       │   │   ├── MetadataExtractor.kt   # 标题 / 作者元数据提取
│       │   │   └── FileStorageManager.kt  # 目录创建 & 文件操作
│       │   ├── data/                      # Room 数据库
│       │   │   ├── AppDatabase.kt         # 数据库实例
│       │   │   ├── ConfigDao.kt / ScrapeDao.kt
│       │   │   └── entity/                # ScrapeRecord / SkippedRecord / UrlMapping / AppConfigEntity
│       │   ├── entity/                    # PageContext / ScrapeStats
│       │   ├── util/
│       │   │   ├── Logger.kt              # 全局日志（文件 + Logcat + 开关）
│       │   │   ├── RunMode.kt             # 运行模式枚举 & 持久化
│       │   │   ├── YoudaoFetcher.kt       # 有道云笔记 API URL 提取
│       │   │   ├── AppConfig.kt           # 路径 / 大小 / 超时配置
│       │   │   ├── Utils.kt               # 格式化 / URL 标准化
│       │   │   ├── PHash.kt               # 感知哈希算法实现
│       │   │   ├── VideoDedupChecker.kt   # pHash 视频去重
│       │   │   └── ImageDedupChecker.kt   # MD5 图片去重
│       │   └── worker/
│       │       ├── ScraperWorker.kt       # WorkManager 后台抓取 Worker
│       │       └── ScraperForegroundService.kt
│       └── res/                           # 布局 / 菜单 / 字符串 / 主题
├── tools/
│   ├── tee-log.ps1               # 构建日志 tee 脚本（实时双写文件 + 控制台）
│   ├── bump-version.ps1          # 版本号递增脚本
│   ├── sync-gradle-version.ps1   # version.properties → build.gradle.kts 版本同步
│   └── hook-smoke-test.js        # PageHook JS 钩子冒烟测试
├── memory-bank/                  # 知识库：技术决策 / 踩坑记录 / 问题解决
├── build.bat                     # 构建脚本（日志双写 + 版本号递增 + APK 编译）
├── clean.bat                     # 清理构建产物
├── gh-release.bat                # GitHub Release 发布
├── version.properties            # versionCode / versionName 唯一源
├── build.gradle.kts              # 根构建（AGP 8.2 / Kotlin 1.9.20 / KSP）
└── settings.gradle.kts
```

## 快速开始

### 环境要求
- Android Studio Hedgehog (2023.1) 或更高
- JDK 17
- Android SDK 34
- Kotlin 1.9.20

### 构建

```bash
# 构建 debug APK
gradlew assembleDebug

# 或使用构建脚本（自动版本号递增 + 打 tag）
build.bat
```

### 运行

1. 用 Android Studio 打开项目根目录
2. 连接 Android 设备（SDK ≥ 26）或启动模拟器
3. Run → Run 'app'

### 基本使用

1. 首次启动默认**本地模式**，在输入框粘贴抖音链接（每行一个），点击「暂存」保存草稿，点击「开始抓取」启动任务
2. 右上角 `⋮` → 运行模式 → 切换为**有道模式**，点击「获取有道URL」从有道云笔记拉取链接列表，确认后点击「开始抓取」
3. 右上角 `⋮` → 日志 → 可随时开关全局日志输出
4. 抓取过程中可点击「停止抓取」取消任务

## 配置

### version.properties

```properties
versionCode=27
versionName=1.0.27
```

`build.bat` 每次构建自动 `versionCode++` 并写回。versionName 需手动修改。

### AppConfig

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `MAX_VIDEO_WORKERS` | 5 | 视频下载并发数 |
| `MAX_IMAGE_WORKERS` | 8 | 图片下载并发数 |
| `DOWNLOAD_TIMEOUT_SECONDS` | 120 | 单文件下载超时 |
| `PAGE_LOAD_TIMEOUT_MS` | 30_000 | 页面加载超时 |
| `RENDER_WAIT_MS` | 5_000 | 渲染等待时间 |

## 与 Python 原版对照

| Python | Android |
|---|---|
| `main/scraper.py` 主循环 | `ScraperEngine.kt` + `ScraperWorker.kt` |
| `core/browser_manager.py` | `WebViewManager.kt` |
| `core/page_hook.py` (add_init_script) | `PageHook.kt` (document-start JS 注入) |
| `core/metadata.py` (媒体提取) | `MediaExtractor.kt` (详情 JSON / SSR) |
| `core/url_processor.py` | `UrlProcessor.kt` |
| `core/file_storage.py` | `FileStorageManager.kt` |
| `core/metadata.py` (标题/作者) | `MetadataExtractor.kt` |
| `api/youdao.py` | `YoudaoFetcher.kt` |
| `common/logger.py` | `Logger.kt` |
| `entity/scrape_stats.py` | `ScrapeStats.kt` |
| `config/config.py`（数据库） | `AppConfig.kt`（常量） |

## License

MIT