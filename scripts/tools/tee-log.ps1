# build.bat 的日志包装器：逐行"先写日志文件（自动 flush）→ 再回显到控制台"。
# 用法（build.bat 调）：powershell -NoProfile -ExecutionPolicy Bypass -File tools\tee-log.ps1 -Log build\logs\build_<ts>.log
# 子脚本/参数从环境变量取：_DS_SELF（被包装的 .bat 全路径）、_DS_ARGS（传给它的参数）
param(
    [Parameter(Mandatory = $true)][string]$Log,
    [string]$Script = $env:_DS_SELF,
    [string]$ScriptArgs = $env:_DS_ARGS
)

$ErrorActionPreference = 'Stop'

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$utf8Bom = New-Object System.Text.UTF8Encoding($true)

# 控制台按 UTF-8 输出（父/子都 chcp 65001）
[Console]::OutputEncoding = $utf8NoBom

# 日志文件仍是 UTF-8 带 BOM（与历史格式一致）
$writer = New-Object System.IO.StreamWriter($Log, $false, $utf8Bom)
$writer.AutoFlush = $true
$writer.WriteLine('[' + (Get-Date -Format 'yyyyMMdd_HHmmss') + '] dy-scraper-android Build Start')

if ([string]::IsNullOrWhiteSpace($Script)) {
    $writer.WriteLine('[error] _DS_SELF is empty, nothing to run')
    $writer.Dispose()
    exit 1
}

# 子进程：cmd /d /s /c ""<bat>" <args> 2>&1" —— stderr 合并进 stdout，只读一个流，避免交错乱序
$argsPart = ''
if (-not [string]::IsNullOrWhiteSpace($ScriptArgs)) { $argsPart = ' ' + $ScriptArgs.Trim() }

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $env:ComSpec
$psi.Arguments = '/d /s /c ""' + $Script + '"' + $argsPart + ' 2>&1"'
$psi.UseShellExecute = $false
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $false
$psi.StandardOutputEncoding = $utf8NoBom
$psi.CreateNoWindow = $false

$proc = New-Object System.Diagnostics.Process
$proc.StartInfo = $psi
$null = $proc.Start()

while ($true) {
    $line = $proc.StandardOutput.ReadLine()
    if ($null -eq $line) { break }
    $writer.WriteLine($line)                  # 1) 先落盘（AutoFlush）
    [Console]::Out.WriteLine($line)           # 2) 再同步展示
}

$proc.WaitForExit()
$exitCode = $proc.ExitCode
$writer.WriteLine('===== build exit code: ' + $exitCode + ' =====')
$writer.Dispose()
exit $exitCode