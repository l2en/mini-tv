# 电视投屏启动桌面

一个轻量级 Android 电视启动桌面 APK，支持 iPhone 和安卓手机通过 B 站、优酷等 App 的投屏按钮将视频推送到电视播放。

## 功能

- 浅蓝色（#E6F4FF）全屏桌面，显示设备名称和 IP 地址
- 支持 DLNA 投屏协议（B 站、优酷、爱奇艺等 App 的投屏按钮）
- iPhone 和安卓手机均可投屏
- 开机自动启动
- APK 仅 51KB，运行内存占用极低

## 开发环境要求

- macOS（Apple Silicon）
- JDK 17
- Android SDK（含 API 19 Platform 和 Build-Tools）

## 环境搭建

### 1. 安装 Android Studio

1. 访问 https://developer.android.com/studio
2. 下载 macOS (Apple Silicon) 版本
3. 安装后首次启动，选择 Standard 安装，等待 SDK 下载完成

### 2. 安装 SDK 组件

打开 Android Studio → Settings（Cmd + ,）→ Languages & Frameworks → Android SDK：

**SDK Platforms 标签页**，勾选：
- Android 4.4 (KitKat) API 19

**SDK Tools 标签页**，确认已勾选：
- Android SDK Build-Tools
- Android SDK Platform-Tools

点击 Apply，等待下载。

### 3. 配置环境变量

```bash
# 添加到 ~/.zshrc
echo 'export ANDROID_HOME=$HOME/Library/Android/sdk' >> ~/.zshrc
echo 'export PATH=$PATH:$ANDROID_HOME/platform-tools' >> ~/.zshrc
source ~/.zshrc
```

验证：

```bash
adb version
# 应输出: Android Debug Bridge version 1.0.xx
```

### 4. 安装 JDK 17（如果没有）

```bash
brew install --cask temurin@17
```

验证：

```bash
/usr/libexec/java_home -V
# 应能看到 17.x.x 版本
```

## 构建 APK

### 方式一：Docker 构建（推荐，新电脑零配置）

只需安装 Docker，无需 JDK、Android SDK：

```bash
cd tv-cast-launcher
./build-apk.sh
```

首次运行会自动下载构建环境（约 5 分钟），之后每次构建只需几秒。

### 方式二：本地构建

需要先完成上面的环境搭建步骤，然后：

```bash
cd tv-cast-launcher
ANDROID_HOME=$HOME/Library/Android/sdk JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew assembleDebug
```

构建成功后输出 `BUILD SUCCESSFUL`。

### 产物位置

```
tv-cast-launcher/app/build/outputs/apk/debug/app-debug.apk
```

## 安装到电视

### 前提条件

- 电视和电脑连接同一个 Wi-Fi 网络
- 电视已开启 ADB 调试（通常在 设置 → 关于 → 连续点击版本号 开启开发者模式，然后在开发者选项中开启 USB/网络调试）

### 安装步骤

```bash
# 1. 连接电视（替换为你电视的 IP 地址）
adb connect 192.168.x.x:5555

# 2. 确认连接成功
adb devices
# 应显示你的电视设备

# 3. 安装 APK
adb install tv-cast-launcher/app/build/outputs/apk/debug/app-debug.apk

# 4. 如果是覆盖安装（更新版本）
adb shell am force-stop com.home.tvlauncher
adb install -r tv-cast-launcher/app/build/outputs/apk/debug/app-debug.apk
```

### 设置为默认桌面

安装后按电视遥控器的 **Home 键**，系统会弹出选择默认桌面的对话框，选择 **投屏桌面** 并勾选 **始终使用**。

## 使用方法

1. 电视开机后自动进入浅蓝色桌面，显示设备名称和 IP 地址
2. 手机打开 B 站（或其他支持投屏的 App）
3. 播放一个视频，点击视频画面上的 **投屏** 按钮
4. 在设备列表中选择 **家庭电视**
5. 视频在电视上全屏播放
6. 播放结束后自动回到浅蓝色桌面

## 卸载

```bash
adb uninstall com.home.tvlauncher
```

## 故障排查

| 问题 | 解决方法 |
|------|----------|
| 手机找不到电视 | 确认手机和电视在同一 Wi-Fi；重启 APK：`adb shell am force-stop com.home.tvlauncher` 后按 Home 键 |
| 投屏后没有画面 | 检查电视桌面上的错误提示；部分视频格式 Android 4 不支持 |
| ADB 连接不上 | 确认电视开启了网络调试；尝试 `adb disconnect` 后重新 `adb connect` |
| 构建失败 | 确认 ANDROID_HOME 和 JAVA_HOME 设置正确；运行 `./gradlew clean` 后重试 |

## 项目结构

```
tv-cast-launcher/
├── app/src/main/
│   ├── AndroidManifest.xml              # 权限和组件注册
│   ├── java/com/home/tvlauncher/
│   │   ├── LauncherActivity.java        # 启动桌面主界面
│   │   ├── VideoPlayerActivity.java     # 视频播放器
│   │   ├── DLNAService.java             # DLNA 后台服务
│   │   ├── BootReceiver.java            # 开机自启动
│   │   └── utils/
│   │       ├── DLNARenderer.java        # DLNA 渲染器（SSDP + UPnP）
│   │       └── NetworkUtils.java        # 网络工具
│   └── res/values/strings.xml           # 字符串资源
├── build.gradle                         # 项目级构建配置
├── app/build.gradle                     # 应用级构建配置
├── settings.gradle                      # 项目设置
└── gradle/wrapper/                      # Gradle Wrapper
```

## 技术实现

- **DLNA 发现**：自实现 SSDP 多播协议（端口 1900），响应手机的 M-SEARCH 请求
- **UPnP 控制**：NanoHTTPD 处理 SOAP 请求，实现 AVTransport 和 RenderingControl 服务
- **视频播放**：MediaPlayer + SurfaceView，支持自定义 HTTP Header
- **最低兼容**：Android 4.0（API 14）
