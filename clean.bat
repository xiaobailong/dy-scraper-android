@echo off
chcp 65001 > nul
title dy-scraper-android Clean

REM ============================================
REM  dy-scraper-android 清理构建产物脚本
REM  用途: 清理 Gradle 构建输出、缓存和产物
REM ============================================

set "JAVA_HOME=D:\Tools\DevTools\Java\JDK\jdk-21.0.10-oracle"
set "ANDROID_HOME=D:\Tools\DevTools\Android\Sdk"
set "ANDROID_SDK_ROOT=D:\Tools\DevTools\Android\Sdk"
set "PATH=D:\Tools\DevTools\Java\JDK\jdk-21.0.10-oracle\bin;D:\Tools\DevTools\gradle\gradle-8.5\bin;%PATH%"

cd /d "%~dp0" 2>nul || (
    echo [错误] 项目目录不存在
    pause
    exit /b 1
)

echo.
echo ============================================
echo  dy-scraper-android 清理构建产物
echo ============================================
echo.

echo [1/5] Gradle clean...
call "D:\Tools\DevTools\gradle\gradle-8.5\bin\gradle.bat" clean --no-daemon --console=plain 2>nul
if %ERRORLEVEL% neq 0 (
    echo [警告] gradle clean 未完全成功，继续手动清理...
)
echo       完成。

echo [2/5] 清理 Gradle 缓存 (.gradle)...
if exist ".gradle" (
    rmdir /s /q ".gradle" 2>nul
    echo       已删除 .gradle 目录。
) else (
    echo       .gradle 目录不存在，跳过。
)

echo [3/5] 清理 build 目录...
if exist "build" (
    rmdir /s /q "build" 2>nul
    echo       已删除 build 目录。
) else (
    echo       build 目录不存在，跳过。
)
if exist "app\build" (
    rmdir /s /q "app\build" 2>nul
    echo       已删除 app\build 目录。
) else (
    echo       app\build 目录不存在，跳过。
)

echo [4/5] 清理输出产物...
if exist "*.apk" (
    del /q "*.apk" 2>nul
    echo       已删除 apk 文件。
) else (
    echo       无 apk 文件，跳过。
)
if exist "*.aab" (
    del /q "*.aab" 2>nul
    echo       已删除 aab 文件。
) else (
    echo       无 aab 文件，跳过。
)

echo [5/5] 清理临时目录...
if exist "tmp" (
    rmdir /s /q "tmp" 2>nul
    echo       已删除 tmp 目录。
) else (
    echo       tmp 目录不存在，跳过。
)

echo.
echo ============================================
echo  清理完成！
echo ============================================
echo.
call :countdown
exit /b 0

REM ============================================
REM  60秒倒计时关闭窗口
REM ============================================
:countdown
echo.
echo 所有步骤已完成，窗口将在 60 秒后自动关闭，按任意键立即关闭...
timeout /t 60
goto :eof