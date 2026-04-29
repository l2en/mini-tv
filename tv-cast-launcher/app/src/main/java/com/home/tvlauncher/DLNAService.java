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

public class DLNAService extends Service {

    private static final String TAG = "DLNAService";
    private static final int NOTIFICATION_ID = 1;

    private DLNARenderer dlnaRenderer;
    private WifiManager.MulticastLock multicastLock;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "DLNAService onCreate");

        // 获取 Multicast Lock（DLNA 需要接收多播数据包）
        WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            multicastLock = wifiManager.createMulticastLock("DLNAService");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
            Log.d(TAG, "MulticastLock 已获取");
        }

        // 启动为前台服务（防止被系统杀死）
        startForegroundService();

        // 启动 DLNA 渲染器
        startDLNA();
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
                    Log.d(TAG, "DLNA 渲染器已启动");
                } catch (Exception e) {
                    Log.e(TAG, "启动 DLNA 渲染器失败", e);
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

        if (dlnaRenderer != null) {
            dlnaRenderer.stop();
        }

        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
    }
}
