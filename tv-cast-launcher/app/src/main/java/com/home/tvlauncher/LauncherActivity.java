package com.home.tvlauncher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.home.tvlauncher.utils.NetworkUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class LauncherActivity extends Activity {

    private static final String TAG = "Launcher";

    public static final String ACTION_UPDATE_STATUS = "com.home.tvlauncher.UPDATE_STATUS";
    public static final String EXTRA_STATUS = "status";
    public static final String ACTION_START_VIDEO = "com.home.tvlauncher.START_VIDEO";
    public static final String EXTRA_VIDEO_URL = "video_url";
    public static final String EXTRA_VIDEO_TITLE = "video_title";

    private static final String WALLPAPER_API = "http://192.168.1.4:3456/api/list";
    private static final int SLIDE_INTERVAL = 10000; // 10 秒轮播

    private FrameLayout rootLayout;
    private ImageView imageViewA;
    private ImageView imageViewB;
    private LinearLayout infoOverlay;
    private TextView deviceNameText;
    private TextView ipAddressText;
    private TextView statusText;

    private Handler handler;
    private Runnable ipUpdateRunnable;
    private Runnable slideRunnable;

    private List<String> imageUrls = new ArrayList<String>();
    private List<Bitmap> cachedBitmaps = new ArrayList<Bitmap>();
    private int currentIndex = 0;
    private boolean showingA = true; // 当前显示的是 imageViewA

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
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

        // 根布局：FrameLayout 叠加两个 ImageView + 信息浮层
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

        // 信息浮层（半透明背景 + 文字）
        infoOverlay = new LinearLayout(this);
        infoOverlay.setOrientation(LinearLayout.VERTICAL);
        infoOverlay.setGravity(Gravity.CENTER);
        infoOverlay.setBackgroundColor(0x66000000); // 40% 黑色半透明
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

        // 信息浮层放在底部居中
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        overlayParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        overlayParams.bottomMargin = dpToPx(60);
        rootLayout.addView(infoOverlay, overlayParams);

        setContentView(rootLayout);

        handler = new Handler();

        // 更新设备信息
        updateDeviceInfo();

        // 定时更新 IP（每 30 秒）
        ipUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateDeviceInfo();
                handler.postDelayed(this, 30000);
            }
        };

        // 图片轮播
        slideRunnable = new Runnable() {
            @Override
            public void run() {
                showNextImage();
                handler.postDelayed(this, SLIDE_INTERVAL);
            }
        };

        // 启动 DLNA 服务
        Intent serviceIntent = new Intent(this, DLNAService.class);
        startService(serviceIntent);

        // 后台加载壁纸
        loadWallpapers();
    }

    // ========== 图片加载 ==========

    private void loadWallpapers() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 从本地 Node 服务获取图片列表
                    HttpURLConnection conn = (HttpURLConnection) new URL(WALLPAPER_API).openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    InputStream is = conn.getInputStream();
                    byte[] buf = new byte[4096];
                    StringBuilder sb = new StringBuilder();
                    int len;
                    while ((len = is.read(buf)) != -1) {
                        sb.append(new String(buf, 0, len));
                    }
                    is.close();
                    conn.disconnect();

                    JSONObject json = new JSONObject(sb.toString());
                    JSONArray images = json.getJSONArray("images");
                    for (int i = 0; i < images.length(); i++) {
                        imageUrls.add(images.getString(i));
                    }
                    Log.d(TAG, "获取到 " + imageUrls.size() + " 张壁纸");

                    // 预加载前两张
                    if (imageUrls.size() > 0) {
                        Bitmap first = downloadBitmap(imageUrls.get(0));
                        if (first != null) {
                            cachedBitmaps.add(first);
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (cachedBitmaps.size() > 0) {
                                        imageViewA.setImageBitmap(cachedBitmaps.get(0));
                                        fadeIn(imageViewA);
                                    }
                                }
                            });
                        }
                    }
                    if (imageUrls.size() > 1) {
                        Bitmap second = downloadBitmap(imageUrls.get(1));
                        if (second != null) {
                            cachedBitmaps.add(second);
                        }
                    }

                    // 后台继续加载剩余图片
                    for (int i = 2; i < imageUrls.size(); i++) {
                        Bitmap bmp = downloadBitmap(imageUrls.get(i));
                        if (bmp != null) {
                            cachedBitmaps.add(bmp);
                        }
                    }
                    Log.d(TAG, "缓存了 " + cachedBitmaps.size() + " 张壁纸");

                } catch (Exception e) {
                    Log.e(TAG, "加载壁纸失败", e);
                }
            }
        }).start();
    }

    private Bitmap downloadBitmap(String urlStr) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            InputStream is = conn.getInputStream();

            // 降低采样率以节省内存（电视分辨率通常 1920x1080）
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1; // Bing 给的就是 1920x1080，不需要缩放
            opts.inPreferredConfig = Bitmap.Config.RGB_565; // 省一半内存
            Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);

            is.close();
            conn.disconnect();
            return bmp;
        } catch (Exception e) {
            Log.e(TAG, "下载图片失败: " + urlStr, e);
            return null;
        }
    }

    // ========== 轮播切换 ==========

    private void showNextImage() {
        if (cachedBitmaps.size() < 2) return;

        currentIndex = (currentIndex + 1) % cachedBitmaps.size();
        Bitmap nextBitmap = cachedBitmaps.get(currentIndex);

        if (showingA) {
            // A 正在显示，把 B 设为下一张，淡入 B 淡出 A
            imageViewB.setImageBitmap(nextBitmap);
            crossFade(imageViewB, imageViewA);
        } else {
            // B 正在显示，把 A 设为下一张，淡入 A 淡出 B
            imageViewA.setImageBitmap(nextBitmap);
            crossFade(imageViewA, imageViewB);
        }
        showingA = !showingA;
    }

    private void crossFade(final View fadeIn, final View fadeOut) {
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
        try {
            unregisterReceiver(statusReceiver);
        } catch (Exception e) {
            // 忽略
        }
        handler.removeCallbacks(ipUpdateRunnable);
        handler.removeCallbacks(slideRunnable);
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
