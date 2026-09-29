# dy-scraper-android 工作区避坑规则

> 执行命令或操作过程中遇到的坑，自动记录在此。每条包含：现象、原因、解决方案。

## 已记录坑点

### 1. RunCommand 无法复用已有终端 + 终端清理不可靠
- **现象**：
  1. 执行命令时指定 `target_terminal` 为上一次返回的终端 ID，系统仍然会新开一个终端。
  2. 每个 `RunCommand` 都会打开一个新终端，包括清理终端自身的命令，导致终端越清越多。
  3. 上上个会话结束时开了 20+ 个终端，清理命令（获取 PID、taskkill）全部都开了新终端。
- **原因**：
  1. `target_terminal` 参数指定已有 ID 时未生效，每次调用都分配新终端——这是工具层面的限制，无法通过规则规避。
  2. 任何基于 `RunCommand` 的终端清理方案（杀进程、自杀式关闭）都不可靠，因为清理命令本身也会开新终端。
  3. cmd 终端不能执行 PowerShell 语法（坑点 #2），但原来的清理方法推荐了 PowerShell 命令。
- **解决方案/规避**：
  - **接受现实**：无法在工具层面强制复用或清理终端。
  - **唯一可靠方案 —— 命令末尾 `& exit` 自关闭**：
    - 所有短命令（git、echo 等）末尾加 `& exit`，命令执行完终端自动关闭。
    - 构建命令：通过 `.bat` + `Start-Process` 异步启动（bat 末尾加 `exit`），当前终端发完 Start-Process 后也 `& exit`。
    - 格式：`command args & exit`（cmd 语法，注意是 `&` 不是 `;`）。
  - **终端命名（必须）**：每个新终端首条命令必须是标识头：
    ```cmd
    echo === task-N-用途 ===
    ```
  - **用户手动关闭**：VSCode 终端面板右键 → 关闭，或点击终端标签上的 ×。由于命令末尾 `& exit` 已自动关闭大部分终端，残留量会大幅减少。
  - **禁止使用 `taskkill /f /im cmd.exe`**，会误伤用户自己的 cmd。
  - `StopCommand` 只能停止正在运行的命令，不会关闭终端会话。

### 2. cmd 终端中使用 PowerShell 语法会报错
- **现象**：在 cmd 终端中执行 `#` 开头的注释或 `2>$null` 等 PowerShell 语法，会报 `'#' is not recognized as an internal or external command`。
- **原因**：cmd 和 PowerShell 语法不同，`#` 不是 cmd 的注释符，`2>$null` 也不是 cmd 的重定向语法。
- **解决方案/规避**：
  - 当前环境默认是 cmd 终端，不要在命令中使用 `#` 注释或 PowerShell 专属语法。
  - cmd 的注释用 `rem`，重定向用 `2>nul`（无 `$`）。
  - 命令末尾用 `& exit` 关闭终端，执行完可能弹出"退出代码 9009"提示，属正常现象（终端已关闭）。

### 3. VSCode 集成终端标签无法用 `title` 命令修改
- **现象**：在 cmd 中执行 `title xxx` 命令，VSCode 终端标签仍然显示 "cmd"，没有变成设置的名字。
- **原因**：VSCode 集成终端的标签由 VS Code API 控制（`Terminal.name`），`title` 命令改的是 Windows 控制台窗口标题，在 VSCode 中不生效。ANSI OSC 转义序列（`\e]0;...`）、PowerShell `$Host.UI.RawUI.WindowTitle` 同样无效。
- **解决方案/规避**：
  - **标签名（程序化）**：`.vscode/settings.json` 配置 `"terminal.integrated.tabs.title": "dy-scraper"`，所有新终端统一显示为 `dy-scraper`（而非 `cmd`）。
  - **内容标识（程序化）**：每个新终端第一行输出 `echo === task-N-用途 ===`，如 `echo === task-1-build ===`。

### 4. Gradle 构建输出无法被终端捕获（卡住无输出）
- **现象**：通过 `powershell -NoProfile -Command "& gradle.bat ..."` 执行 Gradle 命令，终端无任何输出且 exit code=0，但实际进程已静默退出。
- **原因**：
  1. Gradle Daemon 生成输出到子进程，终端工具的 stdout 捕获无法穿透多层进程。
  2. 僵尸 Daemon（BUSY/STOPPED 状态）残留会阻塞新 Daemon 启动。
- **解决方案/规避**：
  - **必须通过批处理文件 + `Start-Process` 异步启动**，将输出重定向到文件，然后读取文件：
    1. 写 `.bat` 文件，`call gradle.bat ... >> build_out.txt 2>&1`
    2. `powershell -NoProfile -Command "Start-Process -FilePath 'xxx.bat' -WorkingDirectory '...'; Write-Host 'Launched'"`
    3. 轮询 `Read build_out.txt` 直到出现 `BUILD SUCCESSFUL` 或 `BUILD FAILED`
  - 遇到构建无响应时，先用 `gradle --stop` 停止僵尸 Daemon，如果仍有残留则 `taskkill /f /im java.exe`
  - 不要使用 `--no-daemon` 参数，会导致类似问题
  - `gradle --version` 等快速命令可以用 `> file.txt 2>&1` 重定向后读取

### 5. Kotlin `indexOfAny` 不接受 String 参数
- **现象**：`url.indexOfAny(" \t\n\r")` 编译报错 `None of the following functions can be called with the arguments supplied`
- **原因**：Kotlin 的 `CharSequence.indexOfAny()` 接受 `CharArray` 或 `Collection<String>`，不接受 `String`
- **解决方案/规避**：使用 `indexOfFirst { it == ' ' || it == '\t' || ... }` 替代