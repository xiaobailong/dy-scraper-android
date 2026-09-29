# dy-scraper-android 工作区避坑规则

> 执行命令或操作过程中遇到的坑，自动记录在此。每条包含：现象、原因、解决方案。

## 已记录坑点

### 1. RunCommand 无法复用已有终端 + 终端清理时机
- **现象**：
  1. 执行命令时指定 `target_terminal` 为上一次返回的终端 ID，系统仍然会新开一个终端，导致终端数量不断增加。
  2. 终端清理不及时，需要用户提醒才清理，导致终端大量堆积。
- **原因**：
  1. 当前环境中 `target_terminal` 参数指定已有 ID 时未生效，每次调用都会分配新终端。
  2. 清理时机规则不明确，"对话结束前"容易被理解为整个会话结束而非每个用户请求完成后。
- **解决方案/规避**：
  - 无法在工具层面强制复用，只能尽量减少不必要的命令调用。
  - **终端命名（必须启用）**：每个新终端打开后首条命令必须是标识头：
    ```cmd
    echo === task-N-用途 ===
    ```
    N 为递增序号，用途为简短英文描述（如 build、clean、git）。
  - **终端清理强制时机**：每完成一个用户请求（用户的最后一条消息处理完毕后），必须立即清理本次打开的所有终端，不要等待用户提醒。
  - **关闭终端的有效方法**：
    - 方法一（推荐，按 PID 精确关闭）：
      - 获取当前终端 PID：`powershell -NoProfile -Command "(Get-WmiObject Win32_Process -Filter ProcessId=$pid).ParentProcessId"`
      - 记住每个终端的 PID，清理时用 `taskkill /f /pid <PID>` 精确关闭，不会误伤用户自己的 cmd。
      - **批量关闭非当前终端**：`Get-Process cmd | Where-Object { $_.Id -ne $currentParent } | Stop-Process -Force`
      - **自杀式一键关闭**（最后一个终端用）：`for /f "delims=" %a in ('powershell -NoProfile -Command "(Get-WmiObject Win32_Process -Filter ProcessId=$pid).ParentProcessId"') do taskkill /f /pid %a`
    - 方法二（慎用）：用 `taskkill /f /im cmd.exe` 杀掉所有 cmd 进程，可一次性清理大量堆积的终端。**注意：会杀掉所有 cmd（包括用户自己开的），仅在堆积严重且确认无误伤时使用。**
    - 方法三：命令末尾加 `& exit`（cmd）或 `; exit`（PowerShell），命令执行完后终端自动关闭。
  - `StopCommand` 只能停止正在运行的命令，不会关闭终端会话。
  - 用户也可手动关闭 VSCode 终端面板中多余的终端标签。

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