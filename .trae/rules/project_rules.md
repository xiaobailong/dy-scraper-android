# dy-scraper-android 项目规则

## 概述
Android 抖音内容刮削器，支持本地输入 URL 和有道笔记获取 URL 两种模式，自动抓取抖音视频/图集资源。

## 技术栈
- **语言**: Kotlin
- **构建**: Gradle 8.5 / Android Gradle Plugin 8.5
- **JDK**: 17 (D:\Tools\DevTools\Java\JDK\jdk-17.0.10-oracle)
- **Android SDK**: D:\Tools\DevTools\Android\Sdk
- **关键依赖**: WorkManager, Room, OkHttp, Jsoup, KSP
- **最低 SDK**: 26, 目标 SDK: 34

## 目录结构
```
dy-scraper-android/
├── app/
│   ├── src/main/java/com/dy/scraper/
│   │   ├── MainActivity.kt          # 主界面，模式切换 + 按钮控制
│   │   ├── api/                     # API 收集器
│   │   ├── core/                    # 核心逻辑 (UrlProcessor, Downloader, WebViewManager)
│   │   ├── data/                    # Room 数据库 (entity, dao, AppDatabase)
│   │   ├── entity/                  # 数据模型 (PageContext, ScrapeStats)
│   │   ├── util/                    # 工具类 (Utils, YoudaoFetcher, Logger, AppConfig)
│   │   └── worker/                  # WorkManager Worker (ScraperWorker)
│   ├── src/test/java/com/dy/scraper/util/  # 单元测试
│   └── src/main/res/               # 布局、字符串资源
└── gradle/                          # Gradle wrapper 配置
```

## 关键约定

### 构建命令
- **完整构建 + 测试**: 写 `.bat` 批处理文件，通过 `Start-Process` 异步启动，输出重定向到 `build_out.txt`
  ```bat
  set JAVA_HOME=D:\Tools\DevTools\Java\JDK\jdk-17.0.10-oracle
  set ANDROID_HOME=D:\Tools\DevTools\Android\Sdk
  set ANDROID_SDK_ROOT=D:\Tools\DevTools\Android\Sdk
  set "PATH=D:\Tools\DevTools\Java\JDK\jdk-17.0.10-oracle\bin;D:\Tools\DevTools\gradle\gradle-8.5\bin;%PATH%"
  cd /d "d:\WorkSpace\test\dy-scraper-android"
  echo === %date% %time% === > %~dp0build_out.txt
  call gradle assembleDebug testDebugUnitTest --rerun-tasks --console=plain >> %~dp0build_out.txt 2>&1
  echo EXIT=%ERRORLEVEL% >> %~dp0build_out.txt
  echo === %date% %time% === >> %~dp0build_out.txt
  ```
- **启动方式**: `powershell -NoProfile -Command "Start-Process -FilePath 'd:\WorkSpace\test\dy-scraper-android\build_test.bat' -WorkingDirectory 'd:\WorkSpace\test\dy-scraper-android'; Write-Host 'Build launched'"`
- **查看结果**: 读取 `build_out.txt` 和 `app/build/test-results/testDebugUnitTest/*.xml`

### 测试
- 测试框架: JUnit4（`assertTrue`, `assertEquals` 等）
- 测试目录: `app/src/test/java/com/dy/scraper/util/`
- 测试类: `UtilsTest`, `ImageDedupCheckerTest`, `YoudaoFetcherTest`
- 测试结果: `app/build/test-results/testDebugUnitTest/TEST-*.xml`

### 代码规范
- 使用 Kotlin 标准库函数，如 `indexOfFirst { }` 而非 `indexOfAny(str)`
- URL 处理链路需保证去重：`extractUrls` → `.distinct()`，`readUrlList` → `.distinct()`
- 日志使用 `Logger` 工具类，通过 `AppConfig` 控制日志开关
- 按钮状态管理集中在 `updateStartStopButtons()` 方法中

### URL 去重规范
- 输入层: `Utils.extractUrls()` 末尾 `.distinct()`
- Worker 层: `ScraperWorker.readUrlList()` 末尾 `.distinct()`
- 有道模式: `YoudaoFetcher.extractUrls()` 末尾 `.distinct()`
- 跨运行: `ScraperWorker.filterProcessedUrls()` 按归一化 URL + DB 检查

### 终端管理（强制）
- **终端标签名**：`.vscode/settings.json` 配置 `"terminal.integrated.tabs.title": "dy-scraper"`，所有新终端标签统一显示为 `dy-scraper`。
- **终端内容标识（必须）**：每个新终端首条命令必须是标识头：
  ```cmd
  echo === task-N-用途 ===
  ```
  - `N` 为递增序号（从 1 开始，本次请求范围内唯一）
  - `用途` 为简短英文描述（如 build、git、test）
- **终端自关闭（强制）**：由于 `target_terminal` 复用不生效（每次必开新终端），唯一可靠的清理方式是让终端自己关闭自己 —— 每条命令末尾加 `& exit`：
  ```cmd
  echo === task-1-git === && cd /d "d:\..." && git status & exit
  ```
  构建命令用 `.bat` + `Start-Process` 异步启动（bat 末尾加 `exit`），启动命令也加 `& exit`。
- **禁止主动杀进程**：`taskkill /f /im cmd.exe` 会误伤用户自己的 cmd；基于 RunCommand 的杀进程方案（获取 PID → taskkill）同样会开新终端，越清越多。仅依赖 `& exit` 自关闭。

### 临时文件管理（强制）
- 所有构建输出、日志写到项目根目录下的临时文件（如 `build_out.txt`, `build_test.bat` 等）
- 每完成一个用户请求后，清理本次产生的临时批处理文件和日志

### 命令等待
- 在终端执行命令后必须等待命令执行完成，不要命令一发出就立刻查看输出并断定执行失败
- 对于 Gradle 构建类长时间命令，轮询 `build_out.txt` 直到出现 `BUILD SUCCESSFUL` 或 `BUILD FAILED`

## 常用操作
- **运行所有测试**: 通过 `build_test.bat` + `Start-Process` 方式执行
- **仅编译**: 同上，gradle 参数改为 `assembleDebug`
- **检查编译错误**: 查看 `build_out.txt` 中的 `FAILED` 和 `e: file:///...` 行
- **清理构建缓存**: 删除 `.gradle/8.5/` 目录和 `app/build/` 目录

## 项目记忆
- Git 仓库位于 `d:\WorkSpace\test\dy-scraper-android`，所有代码变更通过 git 管理
- 推送前务必运行完整测试套件确认通过