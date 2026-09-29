set JAVA_HOME=D:\Tools\DevTools\Java\JDK\jdk-17.0.10-oracle
set ANDROID_HOME=D:\Tools\DevTools\Android\Sdk
set ANDROID_SDK_ROOT=D:\Tools\DevTools\Android\Sdk
set "PATH=D:\Tools\DevTools\Java\JDK\jdk-17.0.10-oracle\bin;D:\Tools\DevTools\gradle\gradle-8.5\bin;%PATH%"
cd /d "d:\WorkSpace\test\dy-scraper-android"
echo === %date% %time% === > d:\WorkSpace\test\dy-scraper-android\build_out.txt
call gradle assembleDebug testDebugUnitTest --rerun-tasks --console=plain >> d:\WorkSpace\test\dy-scraper-android\build_out.txt 2>&1
echo EXIT=%ERRORLEVEL% >> d:\WorkSpace\test\dy-scraper-android\build_out.txt
echo === %date% %time% === >> d:\WorkSpace\test\dy-scraper-android\build_out.txt