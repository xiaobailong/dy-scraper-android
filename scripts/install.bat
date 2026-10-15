@echo off
chcp 65001 > nul 2>&1
setlocal enabledelayedexpansion
cd /d "%~dp0.."

REM ---- 0. 自动检测 Android SDK ----
if defined ANDROID_HOME (
    set "PATH=%ANDROID_HOME%\platform-tools;%PATH%"
) else if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
    set "PATH=%LOCALAPPDATA%\Android\Sdk\platform-tools;%PATH%"
) else if exist "D:\Tools\DevTools\Android\Sdk\platform-tools\adb.exe" (
    set "PATH=D:\Tools\DevTools\Android\Sdk\platform-tools;%PATH%"
)

echo ========================================
echo   dy-scraper-android - ADB 安装脚本
echo ========================================
echo.

REM ---- 1. 检查 adb ----
where adb >nul 2>&1
if %ERRORLEVEL% neq 0 goto :no_adb_found

for /f "tokens=*" %%a in ('adb --version 2^>nul ^| findstr /i "version"') do (
    echo [adb] %%a
)
echo.

REM ---- 2. 检查设备连接 ----
for /f "skip=1 tokens=1,2" %%a in ('adb devices 2^>nul') do (
    if not "%%a"=="" (
        set "SERIAL=%%a"
        set "STATE=%%b"
        goto :check_device_state
    )
)
goto :no_device_found

:check_device_state
echo [设备] !SERIAL! ^(!STATE!^)
if /i "!STATE!" neq "device" goto :device_not_ready
echo.

REM ---- 3. 查找 APK ----
set "APK_PATH="

REM 优先用项目根目录下的 dy-scraper-*.apk
for /f "delims=" %%f in ('dir /b /o-d "dy-scraper-*.apk" 2^>nul') do (
    set "APK_PATH=%%f"
    goto :found_apk
)

REM 其次用 app/build 下的 debug APK
for /f "delims=" %%f in ('dir /s /b "app\build\outputs\apk\debug\*.apk" 2^>nul') do (
    set "APK_PATH=%%f"
    goto :found_apk
)

echo [错误] 未找到 APK 文件
echo        请先在项目根目录运行 scripts\build.bat 构建 APK
echo        或确保 dy-scraper-*.apk 存在于当前目录
exit /b 1

:found_apk

for %%f in ("!APK_PATH!") do (
    set "APK_SIZE=%%~zf"
    set "APK_NAME=%%~nxf"
)

set /a APK_SIZE_MB=!APK_SIZE! / 1048576
echo [APK]  !APK_NAME!  (%APK_SIZE_MB% MB)
echo.

REM ---- 4. 安装 ----
echo [安装] 正在安装到设备...
echo.
adb install -r -d "!APK_PATH!"
set "INSTALL_EXIT=%ERRORLEVEL%"

echo.
if !INSTALL_EXIT! equ 0 goto :install_success
goto :install_failed

REM ============================================================
REM  安装结果标签
REM ============================================================

:install_success
echo [成功] 安装完成！
echo.
echo        提示：如果桌面没有图标，请到系统设置的「应用」列表中查找「dy-scraper」
timeout /t 10 > nul
exit /b 0

:install_failed
echo [失败] 安装失败 ^(adb 退出码: !INSTALL_EXIT!^)
echo.
echo        常见原因：
echo        - INSTALL_FAILED_UPDATE_INCOMPATIBLE: 签名与已安装版本不一致
echo          ^(debug 签名 vs release 签名^)，请先卸载再安装
echo        - INSTALL_FAILED_INSUFFICIENT_STORAGE: 设备存储空间不足
echo        - 设备未解锁 / USB 调试未开启
echo.
echo        尝试卸载旧版本后重试：
echo          adb uninstall com.dy.scraper
echo.
timeout /t 30 > nul
exit /b 1

REM ============================================================
REM  错误处理标签
REM ============================================================

:no_adb_found
echo [错误] 未找到 adb 命令
echo       请安装 Android SDK Platform-Tools
echo       路径: %ANDROID_HOME%\platform-tools
timeout /t 30 > nul
exit /b 1

:no_device_found
echo [错误] 未检测到已连接的设备
echo       请确保：
echo       1. 手机已通过 USB 连接到电脑
echo       2. 手机已开启「USB 调试」模式
echo       3. 手机已授权本电脑的调试请求
echo       4. 部分手机需要选择「传输文件」/「PTP」模式
timeout /t 30 > nul
exit /b 1

:device_not_ready
echo [错误] 设备状态异常 ^(!STATE!^)，无法安装
echo       常见原因：
echo       - unauthorized: 手机弹出授权框，请点击「允许」
echo       - offline:    USB 连接断开，请重新连接
echo       - recovery:   设备处于 Recovery 模式
timeout /t 30 > nul
exit /b 1