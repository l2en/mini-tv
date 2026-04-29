@echo off
setlocal

set IMAGE_NAME=tv-cast-builder

echo === 电视投屏桌面 APK 构建工具 ===
echo.

:: 检查 Docker
where docker >nul 2>nul
if %errorlevel% neq 0 (
    echo 错误: 请先安装 Docker Desktop
    echo   下载: https://www.docker.com/products/docker-desktop/
    exit /b 1
)

:: 构建 Docker 镜像
echo [1/3] 准备构建环境...
docker build -t %IMAGE_NAME% "%~dp0" -q
if %errorlevel% neq 0 (
    echo Docker 镜像构建失败
    exit /b 1
)

:: 在容器中构建 APK
echo [2/3] 构建 APK...
docker run --rm -v "%~dp0":/project %IMAGE_NAME% bash -c "chmod +x gradlew && ./gradlew assembleDebug --no-daemon -q"
if %errorlevel% neq 0 (
    echo APK 构建失败
    exit /b 1
)

:: 输出结果
if exist "%~dp0app\build\outputs\apk\debug\app-debug.apk" (
    echo [3/3] 构建成功!
    echo.
    echo   APK 文件: app\build\outputs\apk\debug\app-debug.apk
    echo.
    echo   安装到电视:
    echo     adb connect ^<电视IP^>:5555
    echo     adb install -r app\build\outputs\apk\debug\app-debug.apk
) else (
    echo 构建失败，请检查错误信息
    exit /b 1
)

endlocal
