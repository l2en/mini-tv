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
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.home.tvlauncher.utils.NetworkUtils;
import com.home.tvlauncher.utils.QRCodeGenerator;
import com.home.tvlauncher.utils.RemoteServer;

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

    // 二维码相关
    private View qrCard;
    private ImageView qrImageView;

    // 手机遥控服务器
    private RemoteServer remoteServer;

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

        // 启动手机遥控 HTTP 服务器
        remoteServer = new RemoteServer(this);
        remoteServer.startServer();

        // 初始化二维码浮层
        initQROverlay();

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
            // A 正在显示（alpha=1），把下一张设到 B，B 从 0 淡入到 1，然后把 A 设为 0
            imageViewB.setImageBitmap(next);
            imageViewB.setAlpha(0f);
            imageViewB.animate().alpha(1f).setDuration(1500).setListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    imageViewA.setAlpha(0f);
                }
            }).start();
        } else {
            // B 正在显示（alpha=1），把下一张设到 A，A 从 0 淡入到 1，然后把 B 设为 0
            imageViewA.setImageBitmap(next);
            imageViewA.setAlpha(0f);
            imageViewA.animate().alpha(1f).setDuration(1500).setListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    imageViewB.setAlpha(0f);
                }
            }).start();
        }
        showingA = !showingA;
    }

    private void fadeIn(View view) {
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(1000).start();
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (remoteServer != null) {
            remoteServer.stopServer();
        }
    }

    // ========== 二维码 ==========

    private void initQROverlay() {
        // 右下角小卡片：圆角深色背景 + 二维码 + 提示文字
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xCC1C1C1E);
        cardBg.setCornerRadius(dpToPx(16));
        card.setBackground(cardBg);
        card.setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14));

        // 标题
        TextView title = new TextView(this);
        title.setText("手机遥控");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTextColor(0xFFFFFFFF);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.bottomMargin = dpToPx(8);
        card.addView(title, titleParams);

        // 二维码图片（白色圆角背景包裹）
        FrameLayout qrWrapper = new FrameLayout(this);
        GradientDrawable qrBg = new GradientDrawable();
        qrBg.setColor(0xFFFFFFFF);
        qrBg.setCornerRadius(dpToPx(8));
        qrWrapper.setBackground(qrBg);
        int qrPad = dpToPx(8);
        qrWrapper.setPadding(qrPad, qrPad, qrPad, qrPad);

        qrImageView = new ImageView(this);
        qrImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);

        int qrSize = dpToPx(120);
        FrameLayout.LayoutParams qrImgParams = new FrameLayout.LayoutParams(qrSize, qrSize);
        qrWrapper.addView(qrImageView, qrImgParams);

        LinearLayout.LayoutParams wrapperParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        wrapperParams.bottomMargin = dpToPx(6);
        card.addView(qrWrapper, wrapperParams);

        // 提示文字
        TextView hint = new TextView(this);
        hint.setText("扫码投屏");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setTextColor(0x88FFFFFF);
        hint.setGravity(Gravity.CENTER);
        card.addView(hint);

        // 放在右下角
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        cardParams.bottomMargin = dpToPx(40);
        cardParams.rightMargin = dpToPx(40);
        rootLayout.addView(card, cardParams);

        qrCard = card;

        // 生成并显示二维码
        refreshQRCode();
    }

    /** 刷新二维码（IP 变化时调用） */
    private void refreshQRCode() {
        if (qrImageView == null) return;
        String ip = NetworkUtils.getIPAddress(this);
        String url = "http://" + ip + ":" + RemoteServer.getPort() + "/remote";
        Log.d(TAG, "二维码 URL: " + url);

        Bitmap qrBitmap = QRCodeGenerator.generate(url, dpToPx(120));
        if (qrBitmap != null) {
            qrImageView.setImageBitmap(qrBitmap);
            Log.d(TAG, "二维码生成成功，尺寸: " + qrBitmap.getWidth() + "x" + qrBitmap.getHeight());
        } else {
            Log.e(TAG, "二维码生成失败！URL: " + url);
        }
    }

    private void updateDeviceInfo() {
        deviceNameText.setText(getString(R.string.device_name_prefix));
        ipAddressText.setText("IP: " + NetworkUtils.getIPAddress(this));
        // IP 变化时刷新二维码
        refreshQRCode();
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
