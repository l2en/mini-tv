# 电视投屏启动桌面 APK 设计文档

## 项目概述

开发一个轻量级 Android 电视启动桌面 APK，集成 DLNA 媒体渲染器功能。用户开机后自动进入浅蓝色桌面，手机端（安卓/iPhone）通过 B 站、优酷等 App 的投屏按钮即可将视频推送到电视播放。

## 目标用户

个人家庭使用，不需要发布到应用商店。

## 运行环境

- 电视系统：Android 4.0+（API 14+）
- 开发环境：macOS 26，Apple M5 芯片
- 网络：家庭局域网（Wi-Fi）

## 核心需求

1. 轻量启动桌面，#E6F4FF 纯色背景
2. 显示设备名称和 IP 地址，方便手机发现
3. 显示投屏状态提示（等待投屏/正在播放）
4. 支持安卓手机和 iPhone 通过 DLNA 协议投屏
5. 开机自动启动
6. 内存占用尽可能小

## 架构设计

### 模块划分

```
tv-cast-launcher/
├── LauncherActivity        # 启动桌面主界面
├── DLNAService             # DLNA 媒体渲染器后台服务
├── VideoPlayerActivity     # 全屏视频播放界面
├── BootReceiver            # 开机自启动广播接收器
└── utils/
    ├── NetworkUtils        # 获取设备 IP 地址
    └── DLNARenderer        # DLNA 渲染器封装
```

### 模块详细设计

#### 1. LauncherActivity（启动桌面）

- 注册为系统默认 Home Launcher（通过 AndroidManifest 的 intent-filter）
- 全屏显示，隐藏状态栏和导航栏
- 背景色：#E6F4FF
- 屏幕中央区域显示：
  - 设备名称（较大字体，深灰色 #333333）
  - IP 地址（中等字体，灰色 #666666）
  - 投屏状态文字（较小字体，灰色 #999999）
- 所有 UI 使用代码创建，不使用 XML 布局文件（减少资源占用）
- 监听 DLNA 服务状态，实时更新投屏状态文字

#### 2. DLNAService（DLNA 后台服务）

- 继承 Android Service，运行为前台服务（防止被系统杀死）
- 使用 Cling 库实现 UPnP MediaRenderer 设备
- 向局域网广播设备信息（SSDP 协议）
- 支持的 DLNA 动作：
  - SetAVTransportURI：接收视频 URL
  - Play：开始播放
  - Pause：暂停播放
  - Stop：停止播放
  - Seek：进度跳转
  - SetVolume / GetVolume：音量控制
  - GetTransportInfo：获取播放状态
  - GetPositionInfo：获取播放进度
- 接收到视频 URL 后，启动 VideoPlayerActivity 进行播放
- 通过 LocalBroadcast 与 Activity 通信

#### 3. VideoPlayerActivity（视频播放）

- 全屏 VideoView 播放视频
- 使用 Android 原生 MediaPlayer 硬件解码
- 支持 HTTP/HTTPS 视频流
- 播放结束后自动返回 LauncherActivity
- 响应 DLNA 服务的控制命令（播放/暂停/停止/跳转）

#### 4. BootReceiver（开机自启动）

- 监听 BOOT_COMPLETED 广播
- 收到广播后启动 LauncherActivity
- 同时启动 DLNAService

### 投屏工作流程

```
电视开机
  ↓
BootReceiver 收到 BOOT_COMPLETED
  ↓
启动 LauncherActivity（显示 #E6F4FF 桌面 + 设备信息）
  ↓
启动 DLNAService（DLNA 渲染器上线，广播设备存在）
  ↓
手机打开 B 站 → 点击投屏 → 发现电视设备
  ↓
选择电视 → B 站推送视频 URL 到 DLNAService
  ↓
DLNAService 启动 VideoPlayerActivity → 全屏播放视频
  ↓
播放结束 / 手机断开 → 返回 LauncherActivity 桌面
```

## 技术选型

| 项目 | 选择 | 理由 |
|------|------|------|
| 语言 | Java | Android 4 对 Kotlin 支持差 |
| 最低 SDK | API 14 (Android 4.0) | 目标设备要求 |
| 目标 SDK | API 19 (Android 4.4) | 保持与 Android 4 兼容 |
| DLNA 库 | Cling 2.1.1 | 轻量级 Java UPnP 库，兼容 Android 4 |
| 视频播放 | Android 原生 VideoView | 内存占用最小，硬件解码 |
| 构建工具 | Gradle | Android 标准构建工具 |
| UI 框架 | 无（纯代码） | 最小化内存占用 |

## 内存优化策略

1. 不使用任何图片资源，纯代码绘制 UI
2. 使用 Android 原生组件，不引入第三方 UI 框架
3. DLNA 服务运行为前台 Service，避免被系统回收
4. VideoView 使用硬件解码（默认行为）
5. 及时释放不使用的资源
6. 预计运行时内存占用：10-20MB

## 开发环境准备

### 需要安装的工具

1. **Android Studio**（最新版，支持 Apple Silicon）
2. **JDK 17**（Android Studio 自带）
3. **Android SDK**：
   - Android 4.0 (API 14) SDK Platform
   - Android 4.4 (API 19) SDK Platform
   - Android SDK Build-Tools
   - Android SDK Platform-Tools

### APK 构建方式

使用命令行 Gradle 构建，不依赖 Android Studio GUI：
```bash
./gradlew assembleDebug
```
生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`

## 安装方式

通过 ADB 安装到电视：
```bash
adb connect <电视IP>
adb install app-debug.apk
```

## 项目文件结构

```
tv-cast-launcher/
├── app/
│   ├── build.gradle
│   ├── src/
│   │   └── main/
│   │       ├── AndroidManifest.xml
│   │       ├── java/com/home/tvlauncher/
│   │       │   ├── LauncherActivity.java
│   │       │   ├── VideoPlayerActivity.java
│   │       │   ├── DLNAService.java
│   │       │   ├── BootReceiver.java
│   │       │   └── utils/
│   │       │       ├── NetworkUtils.java
│   │       │       └── DLNARenderer.java
│   │       └── res/
│   │           └── values/
│   │               └── strings.xml
│   └── proguard-rules.pro
├── build.gradle
├── settings.gradle
├── gradle.properties
└── gradle/
    └── wrapper/
        ├── gradle-wrapper.jar
        └── gradle-wrapper.properties
```
