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
