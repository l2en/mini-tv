# 电视投屏启动桌面

一个轻量级 Android 电视启动桌面 APK，支持 iPhone 和安卓手机通过 B 站、优酷等 App 的投屏按钮将视频推送到电视播放。


## 功能

- 浅蓝色（#E6F4FF）全屏桌面，显示设备名称和 IP 地址
- 支持 DLNA 投屏协议（B 站、优酷、爱奇艺等 App 的投屏按钮）
- iPhone 和安卓手机均可投屏
- 开机自动启动
- APK 仅 51KB，运行内存占用极低

## 构建 APK

只需安装 Docker，无需 JDK、Android SDK：

```bash
cd tv-cast-launcher
./build-apk.sh
```

首次运行会自动下载构建环境（约 5 分钟），之后每次构建只需几秒。

### 产物位置

```
tv-cast-launcher/app/build/outputs/apk/debug/app-debug.apk
```

## 安装到电视

前提：电视和电脑在同一 Wi-Fi，电视已开启 ADB 调试（设置 → 关于 → 连续点击版本号开启开发者模式 → 开发者选项中开启网络调试）。

```bash
# 连接电视（替换为你电视的 IP 地址）
adb connect 192.168.x.x:5555

# 安装 APK
adb install tv-cast-launcher/app/build/outputs/apk/debug/app-debug.apk

# 覆盖安装（更新版本）
adb shell am force-stop com.home.tvlauncher
adb install -r tv-cast-launcher/app/build/outputs/apk/debug/app-debug.apk
```

安装后按电视遥控器 **Home 键**，选择 **投屏桌面** 并勾选 **始终使用**。

## 使用方法

1. 电视开机后自动进入浅蓝色桌面，显示设备名称和 IP 地址
2. 手机打开 B 站（或其他支持投屏的 App）
3. 播放视频，点击 **投屏** 按钮
4. 选择 **家庭电视**
5. 视频在电视上全屏播放
6. 播放结束后自动回到桌面

## 卸载

```bash
adb uninstall com.home.tvlauncher
```

## 故障排查

| 问题 | 解决方法 |
|------|----------|
| 手机找不到电视 | 确认同一 Wi-Fi；重启：`adb shell am force-stop com.home.tvlauncher` 后按 Home 键 |
| 投屏后没有画面 | 检查桌面上的错误提示；部分视频格式 Android 4 不支持 |
| ADB 连接不上 | 确认电视开启了网络调试；`adb disconnect` 后重新连接 |

## 项目结构

```
tv-cast-launcher/
├── Dockerfile                           # Docker 构建环境
├── build-apk.sh                         # 一键构建脚本
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
└── gradle/wrapper/                      # Gradle Wrapper
```
