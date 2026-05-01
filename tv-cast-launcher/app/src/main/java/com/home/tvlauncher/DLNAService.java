package com.home.tvlauncher;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import com.home.tvlauncher.utils.DLNARenderer;
import com.home.tvlauncher.utils.NetworkUtils;

public class DLNAService extends Service {

    private static final String TAG = "DLNAService";
    private static final int NOTIFICATION_ID = 1;

    private DLNARenderer dlnaRenderer;
    private WifiManager.MulticastLock multicastLock;
    private Handler handler = new Handler();
    private boolean dlnaStarted = false;

    // 手机推送 HTTP 服务器
    private com.home.tvlauncher.utils.RemoteServer remoteServer;

    // 监听网络变化
    private BroadcastReceiver networkReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
                if (isWifiConnected()) {
                    String ip = NetworkUtils.getIPAddress(DLNAService.this);
                    Log.d(TAG, "网络已连接, IP: " + ip);
                    if (!dlnaStarted || dlnaRenderer == null) {
                        // 首次启动或之前启动失败，延迟 2 秒等网络稳定后启动
                        handler.removeCallbacksAndMessages(null);
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                restartDLNA();
                            }
                        }, 2000);
                    }
                } else {
                    Log.d(TAG, "网络断开");
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "DLNAService onCreate");

        // 启动为前台服务
        startForegroundService();

        // 注册网络变化监听
        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(networkReceiver, filter);

        // 如果当前已有网络，直接启动
        if (isWifiConnected()) {
            acquireMulticastLock();
            startDLNA();
        } else {
            Log.d(TAG, "当前无网络，等待 Wi-Fi 连接...");
        }

        // 启动手机推送 HTTP 服务器（在 Service 中启动，生命周期稳定）
        remoteServer = new com.home.tvlauncher.utils.RemoteServer(this);
        remoteServer.startServer();
    }

    private boolean isWifiConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        }
        return false;
    }

    private void acquireMulticastLock() {
        if (multicastLock != null && multicastLock.isHeld()) return;
        WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            multicastLock = wifiManager.createMulticastLock("DLNAService");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
            Log.d(TAG, "MulticastLock 已获取");
        }
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

    private void startDLNA() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String deviceName = getString(R.string.device_name_prefix);
                    dlnaRenderer = new DLNARenderer(DLNAService.this, deviceName);
                    dlnaRenderer.start();
                    dlnaStarted = true;
                    Log.d(TAG, "DLNA 渲染器已启动");
                } catch (Exception e) {
                    Log.e(TAG, "启动 DLNA 渲染器失败", e);
                    dlnaStarted = false;
                }
            }
        }).start();
    }

    private void restartDLNA() {
        Log.d(TAG, "重启 DLNA 渲染器...");
        // 停掉旧的
        if (dlnaRenderer != null) {
            dlnaRenderer.stop();
            dlnaRenderer = null;
        }
        dlnaStarted = false;

        // 重新获取 MulticastLock 和启动
        acquireMulticastLock();
        startDLNA();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "DLNAService onDestroy");

        handler.removeCallbacksAndMessages(null);

        try {
            unregisterReceiver(networkReceiver);
        } catch (Exception e) {}

        if (dlnaRenderer != null) {
            dlnaRenderer.stop();
        }

        if (remoteServer != null) {
            remoteServer.stopServer();
        }

        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
    }
}
