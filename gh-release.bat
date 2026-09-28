@echo off
chcp 65001 > nul
title dy-scraper-android GitHub Release

REM --- relaunch once in a fresh cmd: with code page 65001 already active, the file is
REM     re-read as UTF-8 from byte 0, so CJK lines are not mis-parsed by the 936-start
REM     console
if defined _DS_GH_RELAUNCH goto :gh_relaunched
set "_DS_GH_RELAUNCH=1"
cmd /d /s /c ""%~f0" %*"
set "_DS_GH_RC=%ERRORLEVEL%"
exit /b %_DS_GH_RC%

:gh_relaunched
cd /d "%~dp0"

REM ============================================
REM  dy-scraper-android - standalone GitHub Release script (gh only)
REM  Target = the LAST COMMIT (HEAD): tag + push tag + create/update Release + upload APK.
REM  No version bump, no commit, no build. Tag name comes from version.properties
REM
REM  Usage:
REM    gh-release.bat                 publish; tag = v[versionName], points at HEAD
REM    gh-release.bat check           dry run: read-only checks, nothing is written
REM    gh-release.bat [tag]           explicit tag, still points at HEAD
REM    gh-release.bat [tag] [apk]     explicit tag + explicit APK path
REM  Env overrides: GH_EXE / GH_REPO (testing), GH_PROXY (optional HTTP(S) proxy).
REM  GH_PROXY unset: the script probes http://127.0.0.1:7897 once and uses it if it answers.
REM ============================================

REM ============================================
REM  log init (tee wrapper): write the line to the log file (AutoFlush),
REM  then echo it to the console
REM ============================================
if not defined _DS_LOG_ACTIVE goto :init_log
goto :skip_log

:init_log
set "_DS_LOG_ACTIVE=1"
if not exist "build\logs" mkdir "build\logs"
for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"') do set "_DS_LOG_TS=%%i"
set "_DS_LOGFILE=build\logs\gh-release_%_DS_LOG_TS%.log"
set "_DS_TEE=%~dp0tools\tee-log.ps1"
set "_DS_SELF=%~f0"
set "_DS_ARGS=%*"
echo 正在发布（日志实时双写：文件 + 控制台），日志文件: %_DS_LOGFILE%
if not exist "%_DS_TEE%" goto :log_legacy
powershell -NoProfile -ExecutionPolicy Bypass -File "%_DS_TEE%" -Log "%_DS_LOGFILE%"
set "_DS_REL_RESULT=%ERRORLEVEL%"
goto :log_done

:log_legacy
echo [警告] 缺少 tools\tee-log.ps1，退回"先写文件、结束再回显"模式
powershell -NoProfile -Command "[System.IO.File]::WriteAllText('%_DS_LOGFILE%', '[%_DS_LOG_TS%] dy-scraper-android Release Start', [System.Text.UTF8Encoding]::new($true))"
call "%~f0" %* 1>> "%_DS_LOGFILE%" 2>&1
set "_DS_REL_RESULT=%ERRORLEVEL%"
type "%_DS_LOGFILE%"

:log_done
echo ----------------------------------------
echo 日志已保存: %_DS_LOGFILE%
echo ----------------------------------------
exit /b %_DS_REL_RESULT%

:skip_log
if not defined GH_EXE  set "GH_EXE=C:\Program Files\GitHub CLI\gh.exe"
if not defined GH_REPO set "GH_REPO=xiaobailong/dy-scraper-android"
if not defined GH_PROXY for /f "usebackq delims=" %%a in (`curl -s -o nul --max-time 3 -w "%%{http_code}" -x http://127.0.0.1:7897 https://api.github.com`) do if "%%a"=="200" set "GH_PROXY=http://127.0.0.1:7897"
if not defined GH_PROXY goto :no_proxy
set "http_proxy=%GH_PROXY%"
set "https_proxy=%GH_PROXY%"
echo  [代理] 使用 GH_PROXY=%GH_PROXY%
:no_proxy


cd /d "%~dp0"
if errorlevel 1 (
    echo [错误] 无法进入脚本所在目录
    exit /b 1
)

set "GH_TAG=%~1"
set "GH_APK=%~2"
set "GH_DRY=0"
if /i "%GH_TAG%"=="check" (
    set "GH_DRY=1"
    set "GH_TAG=%~2"
    set "GH_APK=%~3"
)

echo ============================================
echo  dy-scraper-android GitHub Release - %date% %time%
echo ============================================
if "%GH_DRY%"=="1" echo  [预检 check] 只做只读检查，不会打标签 / 推送 / 发 Release
echo.

echo [1/6] 检查 gh CLI...
call :check_gh
if errorlevel 1 goto :fail

echo [2/6] 读取版本信息...
if not exist "version.properties" (
    echo [错误] 找不到 version.properties
    goto :fail
)
for /f "tokens=2 delims==" %%i in ('findstr "versionName=" version.properties') do set "V_NAME=%%i"
for /f "tokens=2 delims==" %%i in ('findstr "versionCode=" version.properties') do set "V_CODE=%%i"
if "%GH_TAG%"=="" set "GH_TAG=v%V_NAME%"
if "%GH_TAG%"=="v" (
    echo [错误] 读不到 versionName，无法推断标签，请显式传入标签
    goto :fail
)
echo       版本: %GH_TAG% （build %V_CODE%）

echo [3/6] 读取最后一个提交...
for /f "delims=" %%i in ('git log -1 --oneline') do set "GH_COMMIT=%%i"
if "%GH_COMMIT%"=="" (
    echo [错误] 读不到最后一个提交，当前目录不是 git 仓库？
    goto :fail
)
set "GH_COMMIT=%GH_COMMIT:"=%"
set "GH_COMMIT=%GH_COMMIT:&=/%"
set "GH_COMMIT=%GH_COMMIT:|=/%"
set "GH_COMMIT=%GH_COMMIT:(=%"
set "GH_COMMIT=%GH_COMMIT:)=%"
set "GH_COMMIT=%GH_COMMIT:<=/%"
set "GH_COMMIT=%GH_COMMIT:>=/%"
for /f "delims=" %%i in ('git rev-parse HEAD') do set "HEAD_SHA=%%i"
for /f "delims=" %%i in ('git rev-parse --abbrev-ref HEAD') do set "HEAD_BRANCH=%%i"
set "GH_NOTES=dy-scraper-android %GH_TAG% (build %V_CODE%) | %GH_COMMIT%"
echo       提交: %GH_COMMIT%
echo       分支: %HEAD_BRANCH%

echo [4/6] 定位 APK...
set "APK_PATH=%GH_APK%"
if "%APK_PATH%"=="" call :find_apk
if "%APK_PATH%"=="" (
    echo [错误] 未找到 APK：仓库根与 app\build\outputs\apk\debug\ 都没有
    echo        请先构建，或显式传入路径: gh-release.bat %GH_TAG% ＜apk路径＞
    goto :fail
)
if not exist "%APK_PATH%" (
    echo [错误] APK 不存在: %APK_PATH%
    goto :fail
)
for %%f in ("%APK_PATH%") do echo        APK: %%~nxf  -  %%~zf bytes

echo [5/6] 检查标签与推送状态...
set "TAG_SHA="
git rev-parse -q --verify "refs/tags/%GH_TAG%" >nul 2>&1
if not errorlevel 1 for /f "delims=" %%i in ('git rev-list -n 1 "%GH_TAG%"') do set "TAG_SHA=%%i"
if "%TAG_SHA%"=="" goto :tag_new
if /i "%TAG_SHA%"=="%HEAD_SHA%" goto :tag_same
echo       [警告] 标签 %GH_TAG% 已存在，指向 %TAG_SHA%
echo              最后一个提交是 %HEAD_SHA%，继续会把远端标签移动到后者
if "%GH_DRY%"=="1" goto :push_state
set "CONFIRM="
set /p "CONFIRM=      确认移动标签并继续？(y/N) "
if /i not "%CONFIRM%"=="y" (
    echo [取消] 未做任何修改
    call :countdown
    exit /b 1
)
goto :push_state

:tag_new
echo       标签 %GH_TAG% 不存在，将新建并指向最后一个提交
goto :push_state

:tag_same
echo       标签 %GH_TAG% 已存在且指向最后一个提交

:push_state
set "NEED_PUSH=0"
call git diff origin/main..HEAD --quiet >nul 2>&1
if errorlevel 1 set "NEED_PUSH=1"
if "%NEED_PUSH%"=="1" echo       有未推送的提交，将先 push main
if "%NEED_PUSH%"=="0" echo       main 已推送
git status --porcelain | findstr /r /c:"." >nul 2>&1
if not errorlevel 1 echo       [提示] 工作区有未提交改动，本脚本不会提交

set "REMOTE_TAG_SHA="
set "_RT_N=0"

:tag_check_try
set /a _RT_N+=1
for /f "usebackq delims=" %%a in (`call "%GH_EXE%" api "repos/%GH_REPO%/commits/%GH_TAG%" --jq .sha 2^>nul`) do set "REMOTE_TAG_SHA=%%a"
if not "%REMOTE_TAG_SHA%"=="" goto :tag_check_done
if %_RT_N% lss 3 (
    ping -n 3 127.0.0.1 > nul
    goto :tag_check_try
)

:tag_check_done
set "TAG_STATE=远端标签不等于 HEAD，将重新打标签并推送"
if /i "%REMOTE_TAG_SHA%"=="%HEAD_SHA%" set "TAG_STATE=远端标签已指向 HEAD，跳过打标签 / 推标签"
echo       远端标签: %TAG_STATE%

if "%GH_DRY%"=="1" goto :plan

echo [6/6] 发布 GitHub Release...
if "%NEED_PUSH%"=="1" (
    call :git_push main
    if errorlevel 1 goto :fail
)

if /i "%REMOTE_TAG_SHA%"=="%HEAD_SHA%" goto :tag_already

echo       打标签 %GH_TAG% 指向最后一个提交...
call git tag -f -a "%GH_TAG%" -m "Release %GH_TAG%"
if errorlevel 1 (
    echo [错误] git tag 失败
    goto :fail
)
call :git_push "%GH_TAG%"
if errorlevel 1 (
    echo       [警告] 普通推送标签失败，改用强制推送...
    call :git_push "%GH_TAG%" force
    if errorlevel 1 goto :fail
)

:tag_already
echo       创建/更新 GitHub Release 并上传 APK...
call :gh_release
if errorlevel 1 goto :fail

echo.
echo ============================================
echo  发布成功！
echo  版本:  %GH_TAG%
echo  提交:  %GH_COMMIT%
echo  APK:   %APK_PATH%
echo ============================================
call :countdown
exit /b 0

REM ============================================
REM  dry-run mode: print the commands, execute nothing
REM ============================================
:plan
echo [6/6] 预检结果——以下命令都还没有执行:
if "%NEED_PUSH%"=="1" echo         git push origin main
if /i not "%REMOTE_TAG_SHA%"=="%HEAD_SHA%" echo         git tag -f -a "%GH_TAG%" -m "Release %GH_TAG%"
if /i not "%REMOTE_TAG_SHA%"=="%HEAD_SHA%" echo         git push origin %GH_TAG%
"%GH_EXE%" release view "%GH_TAG%" --repo "%GH_REPO%" >nul 2>&1
if errorlevel 1 echo         "%GH_EXE%" release create "%GH_TAG%" "%APK_PATH%" --title "%GH_TAG%" --notes "%GH_NOTES%" --repo "%GH_REPO%"
if not errorlevel 1 echo         "%GH_EXE%" release edit "%GH_TAG%" --title "%GH_TAG%" --notes "%GH_NOTES%" --repo "%GH_REPO%"
if not errorlevel 1 echo         "%GH_EXE%" release upload "%GH_TAG%" "%APK_PATH%" --clobber --repo "%GH_REPO%"
echo.
echo [预检完成] 去掉 check 参数即可真正发布
call :countdown
exit /b 0

REM ============================================
REM  failure exit
REM ============================================
:fail
echo.
echo ============================================
echo  发布未完成，请按上面的错误信息处理后重试
echo ============================================
call :countdown
exit /b 1

REM ============================================
REM  check gh CLI (subroutine, exit /b 1 on failure)
REM ============================================
:check_gh
if not exist "%GH_EXE%" (
    echo [错误] GitHub CLI ^(gh^) 未安装！
    echo        路径: %GH_EXE%
    echo        安装: winget install --id GitHub.cli
    exit /b 1
)
"%GH_EXE%" --version
if errorlevel 1 (
    echo [错误] gh 命令无法执行，请检查安装！
    exit /b 1
)
"%GH_EXE%" auth status >nul 2>&1
if errorlevel 1 (
    echo       [警告] gh 登录状态自检未通过（网络瞬断也会导致），详情如下:
    echo       ----------------------------------------
    "%GH_EXE%" auth status
    echo       ----------------------------------------
    echo       若提示 token 无效，请运行: gh auth login
)
exit /b 0

REM ============================================
REM  git push (subroutine): retry 3x on transient errors
REM  usage: call :git_push main / :git_push TAG / :git_push TAG force
REM ============================================
:git_push
set "_DS_PUSH_REF=%~1"
set "_DS_PUSH_FORCE="
if /i "%~2"=="force" set "_DS_PUSH_FORCE=-f"
set "_DS_PUSH_N=0"

:git_push_try
set /a _DS_PUSH_N+=1
if %_DS_PUSH_N%==1 echo       推送 %_DS_PUSH_REF% ...
if %_DS_PUSH_N% gtr 1 echo       [重试 %_DS_PUSH_N%/3] 推送 %_DS_PUSH_REF% ...
call git push origin %_DS_PUSH_REF% %_DS_PUSH_FORCE%
if not errorlevel 1 exit /b 0
if %_DS_PUSH_N% lss 3 (
    echo       [警告] 推送失败，3 秒后重试...
    ping -n 4 127.0.0.1 > nul
    goto :git_push_try
)
echo [错误] git push %_DS_PUSH_REF% 连续 3 次失败！
echo        常见原因: 网络瞬断（Connection reset / timeout）、代理、22 端口被拦。
echo        手工重试:            git push origin %_DS_PUSH_REF%
echo        改走 HTTPS（一次性）: gh auth setup-git ^&^& git remote set-url origin https://github.com/%GH_REPO%.git
exit /b 1

REM ============================================
REM  create / update GitHub Release + upload APK (idempotent subroutine)
REM  needs: GH_EXE / GH_REPO / GH_TAG / GH_NOTES / APK_PATH (env vars)
REM ============================================
:gh_release
if "%GH_NOTES%"=="" set "GH_NOTES=dy-scraper-android %GH_TAG%"
if "%APK_PATH%"=="" (
    echo [错误] APK 路径为空，无法上传 Release！
    exit /b 1
)
if not exist "%APK_PATH%" (
    echo [错误] APK 不存在: %APK_PATH%
    exit /b 1
)
"%GH_EXE%" release view "%GH_TAG%" --repo "%GH_REPO%" >nul 2>&1
if not errorlevel 1 goto :gh_release_update

echo       创建 Release %GH_TAG% 并上传 APK...
"%GH_EXE%" release create "%GH_TAG%" "%APK_PATH%" --title "%GH_TAG%" --notes "%GH_NOTES%" --repo "%GH_REPO%"
if errorlevel 1 goto :gh_release_fail
goto :gh_release_done

:gh_release_update
echo       Release %GH_TAG% 已存在，更新说明并覆盖上传 APK...
set "REL_DRAFT="
for /f "usebackq delims=" %%a in (`call "%GH_EXE%" release view "%GH_TAG%" --repo "%GH_REPO%" --json isDraft --jq .isDraft 2^>nul`) do set "REL_DRAFT=%%a"
if /i "%REL_DRAFT%"=="true" echo       [提示] 该 Release 是草稿，将一并发布
if /i "%REL_DRAFT%"=="true" "%GH_EXE%" release edit "%GH_TAG%" --title "%GH_TAG%" --notes "%GH_NOTES%" --repo "%GH_REPO%" --draft=false >nul 2>&1
if /i not "%REL_DRAFT%"=="true" "%GH_EXE%" release edit "%GH_TAG%" --title "%GH_TAG%" --notes "%GH_NOTES%" --repo "%GH_REPO%" >nul 2>&1
for %%f in ("%APK_PATH%") do set "APK_NAME=%%~nxf"
set "REL_ID="
for /f "usebackq delims=" %%a in (`call "%GH_EXE%" api "repos/%GH_REPO%/releases/tags/%GH_TAG%" --jq .id 2^>nul`) do set "REL_ID=%%a"
if "%REL_ID%"=="" goto :gh_release_upload
for /f "usebackq delims=" %%i in (`call "%GH_EXE%" api "repos/%GH_REPO%/releases/%REL_ID%/assets?per_page=100" --jq ".[].id" 2^>nul`) do call :drop_same_asset %%i "%APK_NAME%"

:gh_release_upload
"%GH_EXE%" release upload "%GH_TAG%" "%APK_PATH%" --clobber --repo "%GH_REPO%"
if errorlevel 1 goto :gh_release_fail

:gh_release_done
echo       Release 链接:
"%GH_EXE%" release view "%GH_TAG%" --repo "%GH_REPO%" --json url --template "{{.url}}"
echo.
exit /b 0

:gh_release_fail
echo [错误] gh 返回失败，请检查: gh auth status / 网络 / 标签 %GH_TAG% 是否已存在
exit /b 1

REM --- drop one release asset when its name matches APK_NAME (REST, id based) ---
:drop_same_asset
set "_DA_ID=%~1"
set "_DA_NAME="
for /f "usebackq delims=" %%n in (`call "%GH_EXE%" api "repos/%GH_REPO%/releases/assets/%_DA_ID%" --jq .name 2^>nul`) do set "_DA_NAME=%%n"
if /i not "%_DA_NAME%"=="%~2" exit /b 0
echo       清理同名旧资产: %_DA_NAME%
"%GH_EXE%" api -X DELETE "repos/%GH_REPO%/releases/assets/%_DA_ID%" >nul 2>&1
exit /b 0

REM ============================================
REM  locate APK (subroutine): versioned APK in repo root, then any *.apk in repo root,
REM  then app\build\outputs\apk\debug\*.apk
REM ============================================
:find_apk
set "APK_PATH="
for %%f in ("dy-scraper-android-v%V_NAME%-%V_CODE%.apk") do if exist "%%~f" set "APK_PATH=%%~f"
if not "%APK_PATH%"=="" exit /b 0
for %%f in (*.apk) do if exist "%%~f" set "APK_PATH=%%~f"
if not "%APK_PATH%"=="" exit /b 0
for /f "delims=" %%f in ('dir /s /b "app\build\outputs\apk\debug\*.apk" 2^>nul') do set "APK_PATH=%%f"
exit /b 0

REM ============================================
REM  countdown before closing the window (for double-click runs)
REM ============================================
:countdown
echo.
echo 窗口将在 10 秒后自动关闭，按任意键立即关闭...
timeout /t 10
goto :eof