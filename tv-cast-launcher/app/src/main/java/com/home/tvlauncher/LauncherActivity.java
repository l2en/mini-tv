package com.home.tvlauncher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.home.tvlauncher.utils.NetworkUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LauncherActivity extends Activity {

    private static final String TAG = "Launcher";

    public static final String ACTION_UPDATE_STATUS = "com.home.tvlauncher.UPDATE_STATUS";
    public static final String EXTRA_STATUS = "status";
    public static final String ACTION_START_VIDEO = "com.home.tvlauncher.START_VIDEO";
    public static final String EXTRA_VIDEO_URL = "video_url";
    public static final String EXTRA_VIDEO_TITLE = "video_title";

    private static final String WALLPAPER_DIR = "wallpaper";
    private static final int SLIDE_INTERVAL = 10000; // 10 秒轮播

    private FrameLayout rootLayout;
    private ImageView imageViewA;
    private ImageView imageViewB;
    private TextView deviceNameText;
    private TextView ipAddressText;
    private TextView statusText;

    private Handler handler;
    private Runnable ipUpdateRunnable;
    private Runnable slideRunnable;

    private List<Bitmap> wallpapers = new ArrayList<Bitmap>();
    private int currentIndex = 0;
    private boolean showingA = true;

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

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

        // 根布局
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.parseColor("#E6F4FF"));

        // 两个 ImageView 用于交叉淡入淡出
        imageViewA = new ImageView(this);
        imageViewA.setScaleType(ImageView.ScaleType.CENTER_CROP);
        rootLayout.addView(imageViewA, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        imageViewB = new ImageView(this);
        imageViewB.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageViewB.setAlpha(0f);
        rootLayout.addView(imageViewB, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // 信息浮层
        LinearLayout infoOverlay = new LinearLayout(this);
        infoOverlay.setOrientation(LinearLayout.VERTICAL);
        infoOverlay.setGravity(Gravity.CENTER);
        infoOverlay.setBackgroundColor(0x66000000);
        infoOverlay.setPadding(dpToPx(40), dpToPx(20), dpToPx(40), dpToPx(20));

        deviceNameText = new TextView(this);
        deviceNameText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36);
        deviceNameText.setTextColor(0xFFFFFFFF);
        deviceNameText.setGravity(Gravity.CENTER);
        deviceNameText.setShadowLayer(4, 0, 0, 0xFF000000);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        nameParams.bottomMargin = dpToPx(8);
        infoOverlay.addView(deviceNameText, nameParams);

        ipAddressText = new TextView(this);
        ipAddressText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        ipAddressText.setTextColor(0xDDFFFFFF);
        ipAddressText.setGravity(Gravity.CENTER);
        ipAddressText.setShadowLayer(4, 0, 0, 0xFF000000);
        LinearLayout.LayoutParams ipParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ipParams.bottomMargin = dpToPx(16);
        infoOverlay.addView(ipAddressText, ipParams);

        statusText = new TextView(this);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        statusText.setTextColor(0xAAFFFFFF);
        statusText.setGravity(Gravity.CENTER);
        statusText.setShadowLayer(4, 0, 0, 0xFF000000);
        statusText.setText(getString(R.string.status_waiting));
        infoOverlay.addView(statusText);

        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        overlayParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        overlayParams.bottomMargin = dpToPx(60);
        rootLayout.addView(infoOverlay, overlayParams);

        setContentView(rootLayout);

        handler = new Handler();
        updateDeviceInfo();

        ipUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateDeviceInfo();
                handler.postDelayed(this, 30000);
            }
        };

        slideRunnable = new Runnable() {
            @Override
            public void run() {
                showNextImage();
                handler.postDelayed(this, SLIDE_INTERVAL);
            }
        };

        // 启动 DLNA 服务
        startService(new Intent(this, DLNAService.class));

        // 从 assets 加载壁纸
        loadWallpapersFromAssets();
    }

    // ========== 从 assets 加载图片 ==========

    private void loadWallpapersFromAssets() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    AssetManager am = getAssets();
                    String[] files = am.list(WALLPAPER_DIR);
                    if (files == null || files.length == 0) {
                        Log.w(TAG, "assets/wallpaper/ 目录为空");
                        return;
                    }
                    Arrays.sort(files);
                    Log.d(TAG, "发现 " + files.length + " 张壁纸");

                    for (String file : files) {
                        if (file.startsWith(".")) continue;
                        try {
                            InputStream is = am.open(WALLPAPER_DIR + "/" + file);
                            BitmapFactory.Options opts = new BitmapFactory.Options();
                            opts.inPreferredConfig = Bitmap.Config.RGB_565;
                            Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
                            is.close();
                            if (bmp != null) {
                                wallpapers.add(bmp);
                                // 第一张加载完立即显示
                                if (wallpapers.size() == 1) {
                                    handler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            imageViewA.setImageBitmap(wallpapers.get(0));
                                            fadeIn(imageViewA);
                                        }
                                    });
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "加载图片失败: " + file, e);
                        }
                    }
                    Log.d(TAG, "加载完成，共 " + wallpapers.size() + " 张");
                } catch (Exception e) {
                    Log.e(TAG, "读取 assets 失败", e);
                }
            }
        }).start();
    }

    // ========== 轮播 ==========

    private void showNextImage() {
        if (wallpapers.size() < 2) return;
        currentIndex = (currentIndex + 1) % wallpapers.size();
        Bitmap next = wallpapers.get(currentIndex);

        if (showingA) {
            imageViewB.setImageBitmap(next);
            crossFade(imageViewB, imageViewA);
        } else {
            imageViewA.setImageBitmap(next);
            crossFade(imageViewA, imageViewB);
        }
        showingA = !showingA;
    }

    private void crossFade(View fadeIn, View fadeOut) {
        AlphaAnimation animIn = new AlphaAnimation(0f, 1f);
        animIn.setDuration(1500);
        animIn.setFillAfter(true);
        fadeIn.startAnimation(animIn);

        AlphaAnimation animOut = new AlphaAnimation(1f, 0f);
        animOut.setDuration(1500);
        animOut.setFillAfter(true);
        fadeOut.startAnimation(animOut);
    }

    private void fadeIn(View view) {
        AlphaAnimation anim = new AlphaAnimation(0f, 1f);
        anim.setDuration(1000);
        anim.setFillAfter(true);
        view.startAnimation(anim);
    }

    // ========== 生命周期 ==========

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_UPDATE_STATUS);
        filter.addAction(ACTION_START_VIDEO);
        registerReceiver(statusReceiver, filter);
        handler.post(ipUpdateRunnable);
        handler.postDelayed(slideRunnable, SLIDE_INTERVAL);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(statusReceiver); } catch (Exception e) {}
        handler.removeCallbacks(ipUpdateRunnable);
        handler.removeCallbacks(slideRunnable);
    }

    private void updateDeviceInfo() {
        deviceNameText.setText(getString(R.string.device_name_prefix));
        ipAddressText.setText("IP: " + NetworkUtils.getIPAddress(this));
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
