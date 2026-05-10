# 电视投屏启动桌面 APK 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个轻量级 Android 电视启动桌面 APK，集成 DLNA 投屏功能，支持安卓和 iPhone 投屏。

**Architecture:** Launcher Activity 作为系统桌面 + DLNA Service 后台运行接收投屏 + VideoPlayer Activity 全屏播放。模块间通过 LocalBroadcast 通信。

**Tech Stack:** Java, Android SDK API 14-19, Cling 2.1.1 (UPnP/DLNA), Gradle, Android 原生 VideoView

---

## 文件结构

```
tv-cast-launcher/
├── app/
│   ├── build.gradle                                    # 应用级构建配置
│   ├── proguard-rules.pro                              # ProGuard 混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml                         # 应用清单（权限、组件注册）
│       ├── java/com/home/tvlauncher/
│       │   ├── LauncherActivity.java                   # 启动桌面主界面
│       │   ├── VideoPlayerActivity.java                # 全屏视频播放
│       │   ├── DLNAService.java                        # DLNA 媒体渲染器服务
│       │   ├── BootReceiver.java                       # 开机自启动接收器
│       │   └── utils/
│       │       ├── NetworkUtils.java                   # 网络工具（获取 IP）
│       │       └── DLNARenderer.java                   # DLNA 渲染器封装
│       └── res/values/
│           └── strings.xml                             # 字符串资源
├── build.gradle                                        # 项目级构建配置
├── settings.gradle                                     # 项目设置
├── gradle.properties                                   # Gradle 属性
└── gradle/wrapper/
    ├── gradle-wrapper.jar                              # Gradle Wrapper JAR
    └── gradle-wrapper.properties                       # Gradle Wrapper 配置
```

---

## 前置准备：开发环境安装

> 以下步骤需要你在 macOS 上手动执行，完成后再回来继续。

### 步骤 P1：安装 Android Studio

1. 打开浏览器，访问 https://developer.android.com/studio
2. 点击 "Download Android Studio" 按钮
3. 下载 macOS (Apple Silicon) 版本的 `.dmg` 文件
4. 双击下载的 `.dmg` 文件，将 Android Studio 拖入 Applications 文件夹
5. 从 Applications 打开 Android Studio
6. 首次启动会弹出设置向导，选择 "Standard" 安装类型，一路点 Next/Finish
7. 等待 Android Studio 下载必要的 SDK 组件（约 10-15 分钟）

### 步骤 P2：配置 Android SDK

1. 打开 Android Studio
2. 点击顶部菜单 **Android Studio → Settings**（或按 `Cmd + ,`）
3. 左侧导航到 **Languages & Frameworks → Android SDK**
4. 在 **SDK Platforms** 标签页：
   - 勾选 **Android 4.0 (IceCreamSandwich) API 14**
   - 勾选 **Android 4.4 (KitKat) API 19**
   - 点击 **Apply**，等待下载完成
5. 在 **SDK Tools** 标签页，确认以下已勾选：
   - Android SDK Build-Tools
   - Android SDK Platform-Tools
   - Android SDK Command-line Tools
   - 点击 **Apply**

### 步骤 P3：配置环境变量

打开终端，执行以下命令（将 Android SDK 路径加入环境变量）：

```bash
echo 'export ANDROID_HOME=$HOME/Library/Android/sdk' >> ~/.zshrc
echo 'export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/tools' >> ~/.zshrc
source ~/.zshrc
```

验证安装：
```bash
adb version
```
应该输出类似 `Android Debug Bridge version 1.0.xx`

---

## Task 1: 项目脚手架 — Gradle 构建配置

**Files:**
- Create: `tv-cast-launcher/build.gradle`
- Create: `tv-cast-launcher/settings.gradle`
- Create: `tv-cast-launcher/gradle.properties`
- Create: `tv-cast-launcher/app/build.gradle`
- Create: `tv-cast-launcher/app/proguard-rules.pro`
- Create: `tv-cast-launcher/app/src/main/res/values/strings.xml`

- [ ] **Step 1: 创建项目根目录和 settings.gradle**

```gradle
// tv-cast-launcher/settings.gradle
include ':app'
```

- [ ] **Step 2: 创建项目级 build.gradle**

```gradle
// tv-cast-launcher/build.gradle
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:4.2.2'
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://4thline.org/m2' }
    }
}

task clean(type: Delete) {
    delete rootProject.buildDir
}
```

- [ ] **Step 3: 创建 gradle.properties**

```properties
# tv-cast-launcher/gradle.properties
org.gradle.jvmargs=-Xmx1536m
android.useAndroidX=false
```

- [ ] **Step 4: 创建 app/build.gradle**

```gradle
// tv-cast-launcher/app/build.gradle
apply plugin: 'com.android.application'

android {
    compileSdkVersion 19
    buildToolsVersion "30.0.3"

    defaultConfig {
        applicationId "com.home.tvlauncher"
        minSdkVersion 14
        targetSdkVersion 19
        versionCode 1
        versionName "1.0"
    }

    buildTypes {
        release {
            minifyEnabled true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_7
        targetCompatibility JavaVersion.VERSION_1_7
    }

    lintOptions {
        abortOnError false
    }
}

dependencies {
    implementation 'org.fourthline.cling:cling-core:2.1.1'
    implementation 'org.fourthline.cling:cling-support:2.1.1'
    implementation 'org.eclipse.jetty:jetty-server:8.1.22.v20160922'
    implementation 'org.eclipse.jetty:jetty-servlet:8.1.22.v20160922'
    implementation 'org.eclipse.jetty:jetty-client:8.1.22.v20160922'
}
```

- [ ] **Step 5: 创建 proguard-rules.pro**

```proguard
# tv-cast-launcher/app/proguard-rules.pro
-keep class org.fourthline.cling.** { *; }
-keep class org.eclipse.jetty.** { *; }
-keep class org.seamless.** { *; }
-dontwarn org.fourthline.cling.**
-dontwarn org.eclipse.jetty.**
-dontwarn org.seamless.**
-dontwarn javax.**
```

- [ ] **Step 6: 创建 strings.xml**

```xml
<!-- tv-cast-launcher/app/src/main/res/values/strings.xml -->
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">投屏桌面</string>
    <string name="status_waiting">等待投屏...</string>
    <string name="status_playing">正在播放</string>
    <string name="status_paused">已暂停</string>
    <string name="device_name_prefix">家庭电视</string>
</resources>
```

- [ ] **Step 7: 下载 Gradle Wrapper**

在 `tv-cast-launcher/` 目录下执行：
```bash
gradle wrapper --gradle-version 6.9.4
```

如果没有安装 gradle，先执行：
```bash
brew install gradle
```

然后再执行 wrapper 命令。

- [ ] **Step 8: 验证项目结构**

```bash
cd tv-cast-launcher
./gradlew tasks
```

应该能看到 Android 构建任务列表，无报错。

- [ ] **Step 9: 提交**

```bash
git add tv-cast-launcher/
git commit -m "feat: 初始化项目脚手架和 Gradle 构建配置"
```

---

## Task 2: AndroidManifest.xml — 权限和组件注册

**Files:**
- Create: `tv-cast-launcher/app/src/main/AndroidManifest.xml`

- [ ] **Step 1: 创建 AndroidManifest.xml**

```xml
<!-- tv-cast-launcher/app/src/main/AndroidManifest.xml -->
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.home.tvlauncher"
    android:versionCode="1"
    android:versionName="1.0">

    <uses-sdk
        android:minSdkVersion="14"
        android:targetSdkVersion="19" />

    <!-- 网络权限（DLNA 需要） -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />

    <!-- 开机自启动权限 -->
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <!-- 前台服务权限 -->
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <application
        android:label="@string/app_name"
        android:theme="@android:style/Theme.NoTitleBar.Fullscreen"
        android:icon="@android:drawable/sym_def_app_icon">

        <!-- 启动桌面 Activity -->
        <activity
            android:name=".LauncherActivity"
            android:launchMode="singleTask"
            android:stateNotNeeded="true"
            android:screenOrientation="landscape"
            android:configChanges="orientation|keyboardHidden|screenSize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.HOME" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- 视频播放 Activity -->
        <activity
            android:name=".VideoPlayerActivity"
            android:launchMode="singleTask"
            android:screenOrientation="landscape"
            android:configChanges="orientation|keyboardHidden|screenSize"
            android:theme="@android:style/Theme.NoTitleBar.Fullscreen" />

        <!-- DLNA 后台服务 -->
        <service
            android:name=".DLNAService"
            android:exported="false" />

        <!-- 开机自启动接收器 -->
        <receiver
            android:name=".BootReceiver"
            android:enabled="true"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>

    </application>
</manifest>
```

- [ ] **Step 2: 提交**

```bash
git add tv-cast-launcher/app/src/main/AndroidManifest.xml
git commit -m "feat: 添加 AndroidManifest 权限和组件注册"
```

---

## Task 3: NetworkUtils — 网络工具类

**Files:**
- Create: `tv-cast-launcher/app/src/main/java/com/home/tvlauncher/utils/NetworkUtils.java`

- [ ] **Step 1: 创建 NetworkUtils.java**

```java
// tv-cast-launcher/app/src/main/java/com/home/tvlauncher/utils/NetworkUtils.java
package com.home.tvlauncher.utils;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class NetworkUtils {

    /**
     * 获取设备 IP 地址
     */
    public static String getIPAddress(Context context) {
        try {
            WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                int ipInt = wifiInfo.getIpAddress();
                if (ipInt != 0) {
                    return String.format("%d.%d.%d.%d",
                            (ipInt & 0xff),
                            (ipInt >> 8 & 0xff),
                            (ipInt >> 16 & 0xff),
                            (ipInt >> 24 & 0xff));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 备用方案：遍历网络接口
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return "未连接网络";
    }

    /**
     * 获取设备名称
     */
    public static String getDeviceName() {
        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;
        if (model.startsWith(manufacturer)) {
            return capitalize(model);
        }
        return capitalize(manufacturer) + " " + model;
    }

    private static String capitalize(String s) {
        if (s == null || s.length() == 0) {
            return "";
        }
        char first = s.charAt(0);
        if (Character.isUpperCase(first)) {
            return s;
        }
        return Character.toUpperCase(first) + s.substring(1);
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add tv-cast-launcher/app/src/main/java/com/home/tvlauncher/utils/NetworkUtils.java
git commit -m "feat: 添加网络工具类 NetworkUtils"
```

---

## Task 4: LauncherActivity — 启动桌面主界面

**Files:**
- Create: `tv-cast-launcher/app/src/main/java/com/home/tvlauncher/LauncherActivity.java`

- [ ] **Step 1: 创建 LauncherActivity.java**

```java
// tv-cast-launcher/app/src/main/java/com/home/tvlauncher/LauncherActivity.java
package com.home.tvlauncher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.home.tvlauncher.utils.NetworkUtils;

public class LauncherActivity extends Activity {

    public static final String ACTION_UPDATE_STATUS = "com.home.tvlauncher.UPDATE_STATUS";
    public static final String EXTRA_STATUS = "status";
    public static final String ACTION_START_VIDEO = "com.home.tvlauncher.START_VIDEO";
    public static final String EXTRA_VIDEO_URL = "video_url";
    public static final String EXTRA_VIDEO_TITLE = "video_title";

    private TextView deviceNameText;
    private TextView ipAddressText;
    private TextView statusText;
    private Handler handler;
    private Runnable ipUpdateRunnable;

    private BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_UPDATE_STATUS.equals(intent.getAction())) {
                String status = intent.getStringExtra(EXTRA_STATUS);
                if (status != null && statusText != null) {
                    statusText.setText(status);
                }
            } else if (ACTION_START_VIDEO.equals(intent.getAction())) {
                String url = intent.getStringExtra(EXTRA_VIDEO_URL);
                String title = intent.getStringExtra(EXTRA_VIDEO_TITLE);
                if (url != null) {
                    Intent videoIntent = new Intent(LauncherActivity.this, VideoPlayerActivity.class);
                    videoIntent.putExtra(EXTRA_VIDEO_URL, url);
                    if (title != null) {
                        videoIntent.putExtra(EXTRA_VIDEO_TITLE, title);
                    }
                    startActivity(videoIntent);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 全屏
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 隐藏导航栏（API 14+）
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

        // 创建 UI（纯代码，不使用 XML）
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setGravity(Gravity.CENTER);
        rootLayout.setBackgroundColor(Color.parseColor("#E6F4FF"));

        // 设备名称
        deviceNameText = new TextView(this);
        deviceNameText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36);
        deviceNameText.setTextColor(Color.parseColor("#333333"));
        deviceNameText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        nameParams.bottomMargin = dpToPx(16);
        rootLayout.addView(deviceNameText, nameParams);

        // IP 地址
        ipAddressText = new TextView(this);
        ipAddressText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        ipAddressText.setTextColor(Color.parseColor("#666666"));
        ipAddressText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ipParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ipParams.bottomMargin = dpToPx(32);
        rootLayout.addView(ipAddressText, ipParams);

        // 投屏状态
        statusText = new TextView(this);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        statusText.setTextColor(Color.parseColor("#999999"));
        statusText.setGravity(Gravity.CENTER);
        statusText.setText(getString(R.string.status_waiting));
        rootLayout.addView(statusText);

        setContentView(rootLayout);

        // 更新设备信息
        updateDeviceInfo();

        // 定时更新 IP（每 30 秒）
        handler = new Handler();
        ipUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateDeviceInfo();
                handler.postDelayed(this, 30000);
            }
        };

        // 启动 DLNA 服务
        Intent serviceIntent = new Intent(this, DLNAService.class);
        startService(serviceIntent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 注册广播接收器
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_UPDATE_STATUS);
        filter.addAction(ACTION_START_VIDEO);
        registerReceiver(statusReceiver, filter);

        // 开始定时更新
        handler.post(ipUpdateRunnable);

        // 恢复等待状态
        statusText.setText(getString(R.string.status_waiting));

        // 隐藏导航栏
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(statusReceiver);
        } catch (Exception e) {
            // 忽略未注册的异常
        }
        handler.removeCallbacks(ipUpdateRunnable);
    }

    private void updateDeviceInfo() {
        String deviceName = getString(R.string.device_name_prefix);
        String ip = NetworkUtils.getIPAddress(this);
        deviceNameText.setText(deviceName);
        ipAddressText.setText("IP: " + ip);
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add tv-cast-launcher/app/src/main/java/com/home/tvlauncher/LauncherActivity.java
git commit -m "feat: 添加启动桌面主界面 LauncherActivity"
```

---

## Task 5: VideoPlayerActivity — 视频播放界面

**Files:**
- Create: `tv-cast-launcher/app/src/main/java/com/home/tvlauncher/VideoPlayerActivity.java`

- [ ] **Step 1: 创建 VideoPlayerActivity.java**

```java
// tv-cast-launcher/app/src/main/java/com/home/tvlauncher/VideoPlayerActivity.java
package com.home.tvlauncher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.MediaController;
import android.widget.VideoView;

public class VideoPlayerActivity extends Activity {

    private static final String TAG = "VideoPlayer";

    public static final String ACTION_CONTROL = "com.home.tvlauncher.VIDEO_CONTROL";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_SEEK_POSITION = "seek_position";

    public static final String CMD_PLAY = "play";
    public static final String CMD_PAUSE = "pause";
    public static final String CMD_STOP = "stop";
    public static final String CMD_SEEK = "seek";

    private VideoView videoView;
    private static VideoPlayerActivity instance;

    private BroadcastReceiver controlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_CONTROL.equals(intent.getAction())) {
                String command = intent.getStringExtra(EXTRA_COMMAND);
                if (command == null) return;

                switch (command) {
                    case CMD_PLAY:
                        if (videoView != null && !videoView.isPlaying()) {
                            videoView.start();
                        }
                        break;
                    case CMD_PAUSE:
                        if (videoView != null && videoView.isPlaying()) {
                            videoView.pause();
                        }
                        break;
                    case CMD_STOP:
                        if (videoView != null) {
                            videoView.stopPlayback();
                        }
                        finish();
                        break;
                    case CMD_SEEK:
                        long position = intent.getLongExtra(EXTRA_SEEK_POSITION, 0);
                        if (videoView != null) {
                            videoView.seekTo((int) position);
                        }
                        break;
                }
            }
        }
    };

    public static VideoPlayerActivity getInstance() {
        return instance;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;

        // 全屏
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

        // 创建 VideoView
        videoView = new VideoView(this);
        setContentView(videoView);

        // 添加媒体控制器（遥控器可用）
        MediaController mediaController = new MediaController(this);
        mediaController.setAnchorView(videoView);
        videoView.setMediaController(mediaController);

        // 播放完成回调
        videoView.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                Log.d(TAG, "播放完成");
                // 通知 LauncherActivity 更新状态
                Intent statusIntent = new Intent(LauncherActivity.ACTION_UPDATE_STATUS);
                statusIntent.putExtra(LauncherActivity.EXTRA_STATUS,
                        getString(R.string.status_waiting));
                sendBroadcast(statusIntent);
                finish();
            }
        });

        // 错误回调
        videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                Log.e(TAG, "播放错误: what=" + what + " extra=" + extra);
                finish();
                return true;
            }
        });

        // 获取视频 URL 并播放
        String videoUrl = getIntent().getStringExtra(LauncherActivity.EXTRA_VIDEO_URL);
        if (videoUrl != null) {
            Log.d(TAG, "播放视频: " + videoUrl);
            videoView.setVideoURI(Uri.parse(videoUrl));
            videoView.start();
        } else {
            finish();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // 收到新的视频 URL，切换播放
        String videoUrl = intent.getStringExtra(LauncherActivity.EXTRA_VIDEO_URL);
        if (videoUrl != null && videoView != null) {
            Log.d(TAG, "切换视频: " + videoUrl);
            videoView.setVideoURI(Uri.parse(videoUrl));
            videoView.start();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(ACTION_CONTROL);
        registerReceiver(controlReceiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(controlReceiver);
        } catch (Exception e) {
            // 忽略
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        instance = null;
        if (videoView != null) {
            videoView.stopPlayback();
        }
    }

    /**
     * 供 DLNAService 查询播放状态
     */
    public boolean isPlaying() {
        return videoView != null && videoView.isPlaying();
    }

    public int getCurrentPosition() {
        return videoView != null ? videoView.getCurrentPosition() : 0;
    }

    public int getDuration() {
        return videoView != null ? videoView.getDuration() : 0;
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add tv-cast-launcher/app/src/main/java/com/home/tvlauncher/VideoPlayerActivity.java
git commit -m "feat: 添加视频播放界面 VideoPlayerActivity"
```

---

## Task 6: DLNARenderer — DLNA 渲染器封装

**Files:**
- Create: `tv-cast-launcher/app/src/main/java/com/home/tvlauncher/utils/DLNARenderer.java`

- [ ] **Step 1: 创建 DLNARenderer.java**

```java
// tv-cast-launcher/app/src/main/java/com/home/tvlauncher/utils/DLNARenderer.java
package com.home.tvlauncher.utils;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.home.tvlauncher.LauncherActivity;
import com.home.tvlauncher.VideoPlayerActivity;

import org.fourthline.cling.binding.annotations.AnnotationLocalServiceBinder;
import org.fourthline.cling.model.DefaultServiceManager;
import org.fourthline.cling.model.meta.DeviceDetails;
import org.fourthline.cling.model.meta.DeviceIdentity;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.LocalService;
import org.fourthline.cling.model.meta.ManufacturerDetails;
import org.fourthline.cling.model.meta.ModelDetails;
import org.fourthline.cling.model.types.DeviceType;
import org.fourthline.cling.model.types.UDADeviceType;
import org.fourthline.cling.model.types.UDN;
import org.fourthline.cling.support.avtransport.AbstractAVTransportService;
import org.fourthline.cling.support.avtransport.lastchange.AVTransportVariable;
import org.fourthline.cling.support.lastchange.LastChange;
import org.fourthline.cling.support.lastchange.LastChangeAwareServiceManager;
import org.fourthline.cling.support.model.DeviceCapabilities;
import org.fourthline.cling.support.model.MediaInfo;
import org.fourthline.cling.support.model.PositionInfo;
import org.fourthline.cling.support.model.TransportAction;
import org.fourthline.cling.support.model.TransportInfo;
import org.fourthline.cling.support.model.TransportState;
import org.fourthline.cling.support.model.TransportStatus;
import org.fourthline.cling.support.renderingcontrol.AbstractAudioRenderingControl;
import org.fourthline.cling.support.renderingcontrol.RenderingControlErrorCode;
import org.fourthline.cling.support.renderingcontrol.RenderingControlException;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

public class DLNARenderer {

    private static final String TAG = "DLNARenderer";
    private static Context appContext;
    private static String currentURI = "";
    private static String currentTitle = "";
    private static TransportState currentState = TransportState.NO_MEDIA_PRESENT;
    private static int volume = 50;

    public static void setContext(Context context) {
        appContext = context.getApplicationContext();
    }

    /**
     * 创建 DLNA MediaRenderer 设备
     */
    public static LocalDevice createDevice(String deviceName) throws Exception {
        DeviceIdentity identity = new DeviceIdentity(
                UDN.uniqueSystemIdentifier("HomeTVLauncher"));

        DeviceType type = new UDADeviceType("MediaRenderer", 1);

        DeviceDetails details = new DeviceDetails(
                deviceName,
                new ManufacturerDetails("HomeTVLauncher"),
                new ModelDetails("HomeTVLauncher", "家庭电视投屏", "1.0"));

        // AVTransport 服务
        LocalService<AVTransportServiceImpl> avTransportService =
                new AnnotationLocalServiceBinder().read(AVTransportServiceImpl.class);
        LastChange avTransportLastChange = new LastChange(
                new org.fourthline.cling.support.avtransport.lastchange.AVTransportLastChangeParser());
        avTransportService.setManager(
                new LastChangeAwareServiceManager<AVTransportServiceImpl>(
                        avTransportService, avTransportLastChange) {
                    @Override
                    protected AVTransportServiceImpl createServiceInstance() {
                        return new AVTransportServiceImpl(getLastChange());
                    }
                });

        // RenderingControl 服务
        LocalService<AudioRenderingControlImpl> renderingControlService =
                new AnnotationLocalServiceBinder().read(AudioRenderingControlImpl.class);
        LastChange renderingControlLastChange = new LastChange(
                new org.fourthline.cling.support.renderingcontrol.lastchange.RenderingControlLastChangeParser());
        renderingControlService.setManager(
                new LastChangeAwareServiceManager<AudioRenderingControlImpl>(
                        renderingControlService, renderingControlLastChange) {
                    @Override
                    protected AudioRenderingControlImpl createServiceInstance() {
                        return new AudioRenderingControlImpl(getLastChange());
                    }
                });

        return new LocalDevice(identity, type, details,
                new LocalService[]{avTransportService, renderingControlService});
    }

    /**
     * AVTransport 服务实现
     */
    public static class AVTransportServiceImpl extends AbstractAVTransportService {

        private LastChange lastChange;

        public AVTransportServiceImpl(LastChange lastChange) {
            super(lastChange);
            this.lastChange = lastChange;
        }

        @Override
        public void setAVTransportURI(long instanceId, String currentURI,
                                       String currentURIMetaData) {
            Log.d(TAG, "setAVTransportURI: " + currentURI);
            DLNARenderer.currentURI = currentURI;

            // 尝试从元数据中提取标题
            String title = "投屏视频";
            if (currentURIMetaData != null && currentURIMetaData.contains("<dc:title>")) {
                int start = currentURIMetaData.indexOf("<dc:title>") + 10;
                int end = currentURIMetaData.indexOf("</dc:title>");
                if (end > start) {
                    title = currentURIMetaData.substring(start, end);
                }
            }
            DLNARenderer.currentTitle = title;
            DLNARenderer.currentState = TransportState.STOPPED;

            // 通知 LauncherActivity 更新状态
            if (appContext != null) {
                Intent statusIntent = new Intent(LauncherActivity.ACTION_UPDATE_STATUS);
                statusIntent.putExtra(LauncherActivity.EXTRA_STATUS, "准备播放: " + title);
                appContext.sendBroadcast(statusIntent);
            }
        }

        @Override
        public void setNextAVTransportURI(long instanceId, String nextURI,
                                           String nextURIMetaData) {
            // 不支持下一个 URI
        }

        @Override
        public void play(long instanceId, String speed) {
            Log.d(TAG, "play: " + DLNARenderer.currentURI);
            DLNARenderer.currentState = TransportState.PLAYING;

            if (appContext != null) {
                // 启动视频播放
                Intent videoIntent = new Intent(LauncherActivity.ACTION_START_VIDEO);
                videoIntent.putExtra(LauncherActivity.EXTRA_VIDEO_URL, DLNARenderer.currentURI);
                videoIntent.putExtra(LauncherActivity.EXTRA_VIDEO_TITLE, DLNARenderer.currentTitle);
                appContext.sendBroadcast(videoIntent);

                // 更新状态
                Intent statusIntent = new Intent(LauncherActivity.ACTION_UPDATE_STATUS);
                statusIntent.putExtra(LauncherActivity.EXTRA_STATUS, "正在播放: " + DLNARenderer.currentTitle);
                appContext.sendBroadcast(statusIntent);
            }
        }

        @Override
        public void pause(long instanceId) {
            Log.d(TAG, "pause");
            DLNARenderer.currentState = TransportState.PAUSED_PLAYBACK;

            if (appContext != null) {
                Intent controlIntent = new Intent(VideoPlayerActivity.ACTION_CONTROL);
                controlIntent.putExtra(VideoPlayerActivity.EXTRA_COMMAND, VideoPlayerActivity.CMD_PAUSE);
                appContext.sendBroadcast(controlIntent);

                Intent statusIntent = new Intent(LauncherActivity.ACTION_UPDATE_STATUS);
                statusIntent.putExtra(LauncherActivity.EXTRA_STATUS, "已暂停");
                appContext.sendBroadcast(statusIntent);
            }
        }

        @Override
        public void stop(long instanceId) {
            Log.d(TAG, "stop");
            DLNARenderer.currentState = TransportState.STOPPED;

            if (appContext != null) {
                Intent controlIntent = new Intent(VideoPlayerActivity.ACTION_CONTROL);
                controlIntent.putExtra(VideoPlayerActivity.EXTRA_COMMAND, VideoPlayerActivity.CMD_STOP);
                appContext.sendBroadcast(controlIntent);

                Intent statusIntent = new Intent(LauncherActivity.ACTION_UPDATE_STATUS);
                statusIntent.putExtra(LauncherActivity.EXTRA_STATUS, "等待投屏...");
                appContext.sendBroadcast(statusIntent);
            }
        }

        @Override
        public void seek(long instanceId, String unit, String target) {
            Log.d(TAG, "seek: " + target);
            // 解析时间格式 HH:MM:SS 为毫秒
            long position = parseTimeToMillis(target);
            if (appContext != null) {
                Intent controlIntent = new Intent(VideoPlayerActivity.ACTION_CONTROL);
                controlIntent.putExtra(VideoPlayerActivity.EXTRA_COMMAND, VideoPlayerActivity.CMD_SEEK);
                controlIntent.putExtra(VideoPlayerActivity.EXTRA_SEEK_POSITION, position);
                appContext.sendBroadcast(controlIntent);
            }
        }

        @Override
        public void next(long instanceId) {
            // 不支持
        }

        @Override
        public void previous(long instanceId) {
            // 不支持
        }

        @Override
        public void record(long instanceId) {
            // 不支持
        }

        @Override
        public void setPlayMode(long instanceId, String newPlayMode) {
            // 不支持
        }

        @Override
        public void setRecordQualityMode(long instanceId, String newRecordQualityMode) {
            // 不支持
        }

        @Override
        public MediaInfo getMediaInfo(long instanceId) {
            return new MediaInfo(DLNARenderer.currentURI, "");
        }

        @Override
        public TransportInfo getTransportInfo(long instanceId) {
            return new TransportInfo(DLNARenderer.currentState,
                    TransportStatus.OK, "1");
        }

        @Override
        public PositionInfo getPositionInfo(long instanceId) {
            VideoPlayerActivity player = VideoPlayerActivity.getInstance();
            if (player != null) {
                long current = player.getCurrentPosition();
                long duration = player.getDuration();
                return new PositionInfo(1, "",
                        formatMillisToTime(duration),
                        DLNARenderer.currentURI, "",
                        formatMillisToTime(current),
                        formatMillisToTime(current),
                        Integer.MAX_VALUE, Integer.MAX_VALUE);
            }
            return new PositionInfo(1, "", "00:00:00",
                    DLNARenderer.currentURI, "", "00:00:00", "00:00:00",
                    Integer.MAX_VALUE, Integer.MAX_VALUE);
        }

        @Override
        public DeviceCapabilities getDeviceCapabilities(long instanceId) {
            return new DeviceCapabilities(new String[]{"NOT_IMPLEMENTED"});
        }

        @Override
        public TransportAction[] getCurrentTransportActions(long instanceId) {
            return new TransportAction[]{
                    TransportAction.Play,
                    TransportAction.Pause,
                    TransportAction.Stop,
                    TransportAction.Seek
            };
        }

        @Override
        public long getStateVariableValue(long instanceId) {
            return 0;
        }

        private long parseTimeToMillis(String time) {
            try {
                String[] parts = time.split(":");
                long hours = Long.parseLong(parts[0]);
                long minutes = Long.parseLong(parts[1]);
                long seconds = Long.parseLong(parts[2]);
                return (hours * 3600 + minutes * 60 + seconds) * 1000;
            } catch (Exception e) {
                return 0;
            }
        }

        private String formatMillisToTime(long millis) {
            long seconds = millis / 1000;
            long hours = seconds / 3600;
            long minutes = (seconds % 3600) / 60;
            long secs = seconds % 60;
            return String.format("%02d:%02d:%02d", hours, minutes, secs);
        }
    }

    /**
     * RenderingControl 服务实现（音量控制）
     */
    public static class AudioRenderingControlImpl extends AbstractAudioRenderingControl {

        public AudioRenderingControlImpl(LastChange lastChange) {
            super(lastChange);
        }

        @Override
        public boolean getMute(long instanceId, String channelName) throws RenderingControlException {
            return false;
        }

        @Override
        public void setMute(long instanceId, String channelName, boolean desiredMute) throws RenderingControlException {
            Log.d(TAG, "setMute: " + desiredMute);
        }

        @Override
        public int getVolume(long instanceId, String channelName) throws RenderingControlException {
            return DLNARenderer.volume;
        }

        @Override
        public void setVolume(long instanceId, String channelName, int desiredVolume) throws RenderingControlException {
            Log.d(TAG, "setVolume: " + desiredVolume);
            DLNARenderer.volume = desiredVolume;
        }

        @Override
        protected Channel[] getCurrentChannels() {
            return new Channel[]{Channel.Master};
        }

        @Override
        public long getStateVariableValue(long instanceId) {
            return 0;
        }
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add tv-cast-launcher/app/src/main/java/com/home/tvlauncher/utils/DLNARenderer.java
git commit -m "feat: 添加 DLNA 渲染器封装 DLNARenderer"
```

---

## Task 7: DLNAService — DLNA 后台服务

**Files:**
- Create: `tv-cast-launcher/app/src/main/java/com/home/tvlauncher/DLNAService.java`

- [ ] **Step 1: 创建 DLNAService.java**

```java
// tv-cast-launcher/app/src/main/java/com/home/tvlauncher/DLNAService.java
package com.home.tvlauncher;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.util.Log;

import com.home.tvlauncher.utils.DLNARenderer;
import com.home.tvlauncher.utils.NetworkUtils;

import org.fourthline.cling.UpnpService;
import org.fourthline.cling.UpnpServiceImpl;
import org.fourthline.cling.android.AndroidUpnpServiceConfiguration;
import org.fourthline.cling.model.meta.LocalDevice;

public class DLNAService extends Service {

    private static final String TAG = "DLNAService";
    private static final int NOTIFICATION_ID = 1;

    private UpnpService upnpService;
    private WifiManager.MulticastLock multicastLock;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "DLNAService onCreate");

        // 设置 Context
        DLNARenderer.setContext(this);

        // 获取 Multicast Lock（DLNA 需要接收多播数据包）
        WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            multicastLock = wifiManager.createMulticastLock("DLNAService");
            multicastLock.setReferenceCounted(true);
            multicastLock.acquire();
        }

        // 启动为前台服务（防止被系统杀死）
        startForegroundService();

        // 启动 UPnP 服务
        startUPnP();
    }

    private void startForegroundService() {
        Intent notificationIntent = new Intent(this, LauncherActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, 0);

        Notification notification = new Notification.Builder(this)
                .setContentTitle("投屏桌面")
                .setContentText("DLNA 投屏服务运行中")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    private void startUPnP() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    upnpService = new UpnpServiceImpl(
                            new AndroidUpnpServiceConfiguration());

                    String deviceName = getString(R.string.device_name_prefix) + " - " +
                            NetworkUtils.getIPAddress(DLNAService.this);

                    LocalDevice device = DLNARenderer.createDevice(deviceName);
                    upnpService.getRegistry().addDevice(device);

                    Log.d(TAG, "DLNA 设备已注册: " + deviceName);
                } catch (Exception e) {
                    Log.e(TAG, "启动 UPnP 失败", e);
                }
            }
        }).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // 被杀死后自动重启
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "DLNAService onDestroy");

        if (upnpService != null) {
            upnpService.shutdown();
        }

        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add tv-cast-launcher/app/src/main/java/com/home/tvlauncher/DLNAService.java
git commit -m "feat: 添加 DLNA 后台服务 DLNAService"
```

---

## Task 8: BootReceiver — 开机自启动

**Files:**
- Create: `tv-cast-launcher/app/src/main/java/com/home/tvlauncher/BootReceiver.java`

- [ ] **Step 1: 创建 BootReceiver.java**

```java
// tv-cast-launcher/app/src/main/java/com/home/tvlauncher/BootReceiver.java
package com.home.tvlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d(TAG, "开机完成，启动投屏桌面");

            // 启动 LauncherActivity
            Intent launcherIntent = new Intent(context, LauncherActivity.class);
            launcherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launcherIntent);

            // 启动 DLNA 服务
            Intent serviceIntent = new Intent(context, DLNAService.class);
            context.startService(serviceIntent);
        }
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add tv-cast-launcher/app/src/main/java/com/home/tvlauncher/BootReceiver.java
git commit -m "feat: 添加开机自启动接收器 BootReceiver"
```

---

## Task 9: 构建和安装 APK

- [ ] **Step 1: 构建 Debug APK**

在 `tv-cast-launcher/` 目录下执行：

```bash
./gradlew assembleDebug
```

构建成功后，APK 文件位于：
`tv-cast-launcher/app/build/outputs/apk/debug/app-debug.apk`

预期输出包含：`BUILD SUCCESSFUL`

- [ ] **Step 2: 连接电视并安装**

确保电视和电脑在同一局域网，电视已开启 ADB 调试：

```bash
# 连接电视（替换为你电视的 IP 地址）
adb connect 192.168.x.x:5555

# 安装 APK
adb install tv-cast-launcher/app/build/outputs/apk/debug/app-debug.apk
```

预期输出：`Success`

- [ ] **Step 3: 设置为默认桌面**

安装后按电视遥控器的 Home 键，系统会弹出选择默认桌面的对话框，选择"投屏桌面"并勾选"始终使用"。

- [ ] **Step 4: 验证投屏功能**

1. 确认电视显示 #E6F4FF 浅蓝色背景 + 设备名称 + IP 地址
2. 手机打开 B 站，播放一个视频，点击投屏按钮
3. 在投屏设备列表中应该能看到"家庭电视"
4. 选择后视频应在电视上全屏播放
5. 播放结束后应自动回到浅蓝色桌面

- [ ] **Step 5: 最终提交**

```bash
git add -A
git commit -m "feat: 完成电视投屏启动桌面 APK v1.0"
```

---

## 故障排查指南

### 手机找不到电视设备
1. 确认手机和电视在同一 Wi-Fi 网络
2. 检查电视 IP 是否正确显示在桌面上
3. 重启 APK：`adb shell am force-stop com.home.tvlauncher`，然后按 Home 键

### 视频无法播放
1. 部分视频格式可能不被 Android 4 原生支持
2. 检查 logcat 日志：`adb logcat -s VideoPlayer DLNARenderer DLNAService`

### APK 安装失败
1. 确认 ADB 连接正常：`adb devices`
2. 如果提示签名冲突，先卸载旧版本：`adb uninstall com.home.tvlauncher`
