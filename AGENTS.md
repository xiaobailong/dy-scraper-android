# dy-scraper-android – Architecture Guide

## Overview

Android 抖音内容刮削器，支持本地输入 URL 和有道笔记获取 URL 两种模式，自动抓取抖音视频/图集资源。

---

## Entry Points

**Main Activity** (`app/src/main/java/com/dy/scraper/MainActivity.kt`):
主界面，模式切换（本地输入 / 有道笔记）+ 按钮控制（开始/停止）。

**ScraperWorker** (`app/src/main/java/com/dy/scraper/worker/ScraperWorker.kt`):
WorkManager Worker，后台执行抓取任务，支持 URL 去重和状态管理。

---

## Architecture

```
dy-scraper-android/
├── app/src/main/java/com/dy/scraper/
│   ├── MainActivity.kt          # 主界面，模式切换 + 按钮控制
│   ├── api/                     # API 收集器
│   ├── core/                    # 核心逻辑 (UrlProcessor, Downloader, WebViewManager)
│   ├── crypto/                  # 加密模块 (CryptoManager)
│   ├── data/                    # Room 数据库 (entity, dao, AppDatabase)
│   ├── entity/                  # 数据模型 (PageContext, ScrapeStats)
│   ├── ui/                      # 界面组件 (Fragment, Adapter, Activity)
│   ├── util/                    # 工具类 (Utils, YoudaoFetcher, Logger, AppConfig)
│   └── worker/                  # WorkManager Worker (ScraperWorker)
├── app/src/test/java/com/dy/scraper/util/  # 单元测试
├── scripts/
│   ├── build.bat                # 一键构建 + 版本递增 + GitHub Release
│   ├── clean.bat                # 清理构建产物
│   ├── gh-release.bat           # 独立 GitHub Release 发布
│   ├── install.bat              # ADB 安装 APK 到设备
│   └── tools/                   # 辅助 PowerShell 脚本
└── memory-bank/                 # 知识库
```

---

## Data Flow

```
1. User inputs URL / Youdao fetches URLs → extractUrls() → .distinct()
2. URLs enqueued to Room DB via ScraperDao
3. WorkManager schedules ScraperWorker
4. Worker reads URLs → filterProcessedUrls() → process each URL:
   - WebView fetches page content
   - Downloader saves media resources
   - CryptoManager handles encryption if needed
5. Results stored in DB, UI updates via LiveData/Flow
```

---

## URL Deduplication

- 输入层: `Utils.extractUrls()` 末尾 `.distinct()`
- Worker 层: `ScraperWorker.readUrlList()` 末尾 `.distinct()`
- 有道模式: `YoudaoFetcher.extractUrls()` 末尾 `.distinct()`
- 跨运行: `ScraperWorker.filterProcessedUrls()` 按归一化 URL + DB 检查

---

## Build

- **构建**: Gradle 8.5 / AGP 8.5 / JDK 17
- **版本号**: `version.properties` 是唯一真源
- **最低 SDK**: 26, **目标 SDK**: 34
- **完整构建**: `scripts\build.bat`（一键构建 + 自动递增版本 + GitHub Release）
- **自测编译**: 写 `.bat` 到 `tmp\`，通过 `Start-Process` 异步启动

```bat
set JAVA_HOME=D:\Tools\DevTools\Java\JDK\jdk-17.0.10-oracle
set ANDROID_HOME=D:\Tools\DevTools\Android\Sdk
set ANDROID_SDK_ROOT=D:\Tools\DevTools\Android\Sdk
set "PATH=D:\Tools\DevTools\Java\JDK\jdk-17.0.10-oracle\bin;D:\Tools\DevTools\gradle\gradle-8.5\bin;%PATH%"
cd /d "d:\WorkSpace\test\dy-scraper-android"
call gradle assembleDebug testDebugUnitTest --rerun-tasks --console=plain >> tmp\build_out.txt 2>&1
echo EXIT=%ERRORLEVEL% >> tmp\build_out.txt
```

---

## Testing

- **框架**: JUnit4
- **测试目录**: `app/src/test/java/com/dy/scraper/util/`
- **测试类**: `UtilsTest`, `ImageDedupCheckerTest`, `YoudaoFetcherTest`

---

## Key Dependencies

| Dependency | Purpose |
|-----------|---------|
| WorkManager | Background task scheduling |
| Room | Local database |
| OkHttp | HTTP client |
| Jsoup | HTML parsing |
| KSP | Annotation processing |