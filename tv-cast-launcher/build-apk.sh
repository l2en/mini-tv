#!/bin/bash
set -e

IMAGE_NAME="tv-cast-builder"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== 电视投屏桌面 APK 构建工具 ==="
echo ""

# 检查 Docker
if ! command -v docker &> /dev/null; then
    echo "错误: 请先安装 Docker"
    echo "  macOS: brew install --cask docker"
    echo "  Linux: https://docs.docker.com/engine/install/"
    exit 1
fi

# 构建 Docker 镜像（首次约 5 分钟，之后秒级）
echo "[1/3] 准备构建环境..."
docker build -t $IMAGE_NAME "$SCRIPT_DIR" -q

# 在容器中构建 APK
echo "[2/3] 构建 APK..."
docker run --rm -v "$SCRIPT_DIR":/project $IMAGE_NAME \
    bash -c "chmod +x gradlew && ./gradlew assembleDebug --no-daemon -q"

# 输出结果
APK_PATH="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK_PATH" ]; then
    SIZE=$(ls -lh "$APK_PATH" | awk '{print $5}')
    echo "[3/3] 构建成功!"
    echo ""
    echo "  APK 文件: app/build/outputs/apk/debug/app-debug.apk"
    echo "  文件大小: $SIZE"
    echo ""
    echo "  安装到电视:"
    echo "    adb connect <电视IP>:5555"
    echo "    adb install -r app/build/outputs/apk/debug/app-debug.apk"
else
    echo "构建失败，请检查错误信息"
    exit 1
fi

