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
