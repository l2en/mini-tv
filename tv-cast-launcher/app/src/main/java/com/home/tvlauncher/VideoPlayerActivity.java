package com.home.tvlauncher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

public class VideoPlayerActivity extends Activity implements
        SurfaceHolder.Callback,
        MediaPlayer.OnPreparedListener,
        MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener {

    private static final String TAG = "VideoPlayer";

    public static final String ACTION_CONTROL = "com.home.tvlauncher.VIDEO_CONTROL";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_SEEK_POSITION = "seek_position";

    public static final String CMD_PLAY = "play";
    public static final String CMD_PAUSE = "pause";
    public static final String CMD_STOP = "stop";
    public static final String CMD_SEEK = "seek";

    // 快进/快退步长（毫秒）
    private static final int SEEK_STEP_MS = 10000;
    // 进度条自动隐藏延迟（毫秒）
    private static final int PROGRESS_HIDE_DELAY_SEEK = 1500;  // 快进快退后 1.5 秒隐藏
    private static final int PROGRESS_HIDE_DELAY_DOWN = 1500;  // 按下键后 1.5 秒隐藏
    // 滑入滑出动画距离（dp）
    private static final int SLIDE_DISTANCE_DP = 30;

    private SurfaceView surfaceView;
    private MediaPlayer mediaPlayer;
    private String pendingUrl;
    private boolean surfaceReady = false;
    private Handler handler = new Handler();

    // 进度条 UI
    private View progressOverlay;
    private ProgressBar progressBar;
    private TextView timeCurrentText;
    private TextView timeDurationText;
    private Runnable hideProgressRunnable;
    private Runnable progressBarUpdater;

    // 左上角标题 UI
    private TextView titleOverlay;
    private String videoTitle = "";

    private static VideoPlayerActivity instance;

    // 定期向 DLNARenderer 报告播放进度
    private Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null) {
                try {
                    com.home.tvlauncher.utils.DLNARenderer renderer =
                            com.home.tvlauncher.utils.DLNARenderer.getInstance();
                    if (renderer != null && mediaPlayer.isPlaying()) {
                        renderer.updatePosition(
                                mediaPlayer.getCurrentPosition(),
                                mediaPlayer.getDuration());
                    }
                } catch (Exception e) {
                    // 忽略
                }
            }
            handler.postDelayed(this, 500);
        }
    };

    private BroadcastReceiver controlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_CONTROL.equals(intent.getAction())) {
                String command = intent.getStringExtra(EXTRA_COMMAND);
                if (command == null) return;

                switch (command) {
                    case CMD_PLAY:
                        if (mediaPlayer != null) {
                            try { mediaPlayer.start(); } catch (Exception e) {
                                Log.e(TAG, "play error", e);
                            }
                        }
                        break;
                    case CMD_PAUSE:
                        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                            mediaPlayer.pause();
                        }
                        break;
                    case CMD_STOP:
                        stopAndFinish();
                        break;
                    case CMD_SEEK:
                        long position = intent.getLongExtra(EXTRA_SEEK_POSITION, 0);
                        if (mediaPlayer != null) {
                            mediaPlayer.seekTo((int) position);
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

        // 创建 SurfaceView
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        surfaceView.getHolder().addCallback(this);

        // 初始化进度条浮层
        initProgressOverlay(root);

        // 初始化左上角标题浮层
        initTitleOverlay(root);

        // 获取视频 URL
        String videoUrl = getIntent().getStringExtra(LauncherActivity.EXTRA_VIDEO_URL);
        if (videoUrl != null) {
            Log.d(TAG, "收到视频 URL: " + videoUrl);
            pendingUrl = videoUrl;
        } else {
            Log.e(TAG, "没有收到视频 URL");
            finish();
        }

        // 获取视频标题
        String title = getIntent().getStringExtra(LauncherActivity.EXTRA_VIDEO_TITLE);
        if (title != null && !title.isEmpty()) {
            videoTitle = title;
        }
    }

    // ========== SurfaceHolder.Callback ==========

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "Surface 已创建");
        surfaceReady = true;
        if (pendingUrl != null) {
            startPlayback(pendingUrl);
            pendingUrl = null;
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
    }

    // ========== 进度条 UI ==========

    /** 进度条是否正在显示 */
    private boolean progressVisible = false;

    private void initProgressOverlay(FrameLayout root) {
        // 底部半透明磨砂风格容器（macOS 风格，圆角）
        // 布局：一行 → [当前时间] [进度条] [总时长]
        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.HORIZONTAL);
        overlay.setGravity(Gravity.CENTER_VERTICAL);

        // 圆角背景（macOS 风格深色半透明，胶囊形圆角）
        GradientDrawable overlayBg = new GradientDrawable();
        overlayBg.setColor(0xCC1C1C1E);
        overlayBg.setCornerRadius(dpToPx(100)); // 足够大的圆角 → 胶囊形
        overlay.setBackground(overlayBg);

        int padH = dpToPx(24);
        int padV = dpToPx(14);
        overlay.setPadding(padH, padV, padH, padV);
        overlay.setVisibility(View.INVISIBLE);
        overlay.setAlpha(0f);

        // 当前时间（左侧）
        timeCurrentText = new TextView(this);
        timeCurrentText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        timeCurrentText.setTextColor(0xFFFFFFFF);
        timeCurrentText.setText("00:00");
        LinearLayout.LayoutParams currentParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        currentParams.rightMargin = dpToPx(14);
        overlay.addView(timeCurrentText, currentParams);

        // 中间进度条（占满剩余空间，圆角轨道）
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressBar.setProgress(0);

        // 圆角进度条背景（浅灰）
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(0x4DFFFFFF);
        trackBg.setCornerRadius(dpToPx(2));

        // 圆角进度条前景（白色）
        GradientDrawable progressFg = new GradientDrawable();
        progressFg.setColor(0xFFFFFFFF);
        progressFg.setCornerRadius(dpToPx(2));
        ClipDrawable progressClip = new ClipDrawable(progressFg, Gravity.LEFT, ClipDrawable.HORIZONTAL);

        LayerDrawable layerDrawable = new LayerDrawable(new Drawable[]{trackBg, progressClip});
        layerDrawable.setId(0, android.R.id.background);
        layerDrawable.setId(1, android.R.id.progress);
        progressBar.setProgressDrawable(layerDrawable);

        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                0, dpToPx(4), 1f);
        overlay.addView(progressBar, barParams);

        // 总时长（右侧）
        timeDurationText = new TextView(this);
        timeDurationText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        timeDurationText.setTextColor(0x99FFFFFF);
        timeDurationText.setText("00:00");
        LinearLayout.LayoutParams durationParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        durationParams.leftMargin = dpToPx(14);
        overlay.addView(timeDurationText, durationParams);

        // 添加到根布局底部，左右留边距
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        overlayParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        overlayParams.bottomMargin = dpToPx(40);
        overlayParams.leftMargin = dpToPx(48);
        overlayParams.rightMargin = dpToPx(48);
        root.addView(overlay, overlayParams);

        progressOverlay = overlay;

        // 隐藏回调
        hideProgressRunnable = new Runnable() {
            @Override
            public void run() {
                fadeOutProgress();
            }
        };

        // 进度条刷新回调（可见时每 500ms 更新）
        progressBarUpdater = new Runnable() {
            @Override
            public void run() {
                updateProgressBar();
                if (progressVisible) {
                    handler.postDelayed(this, 500);
                }
            }
        };
    }

    // ========== 左上角标题 UI ==========

    private void initTitleOverlay(FrameLayout root) {
        titleOverlay = new TextView(this);
        titleOverlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleOverlay.setTextColor(0xFFFFFFFF);
        titleOverlay.setShadowLayer(4, 0, 0, 0xAA000000);
        titleOverlay.setSingleLine(true);
        titleOverlay.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleOverlay.setMaxWidth(dpToPx(500));
        titleOverlay.setVisibility(View.INVISIBLE);
        titleOverlay.setAlpha(0f);

        // 圆角半透明背景（macOS 风格）
        GradientDrawable titleBg = new GradientDrawable();
        titleBg.setColor(0xAA1C1C1E);
        titleBg.setCornerRadius(dpToPx(10));
        titleOverlay.setBackground(titleBg);
        titleOverlay.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));

        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        titleParams.gravity = Gravity.TOP | Gravity.LEFT;
        titleParams.topMargin = dpToPx(32);
        titleParams.leftMargin = dpToPx(40);
        root.addView(titleOverlay, titleParams);
    }

    /** 显示进度条并在指定延迟后自动隐藏 */
    private void showProgressOverlay(int hideDelayMs) {
        if (progressOverlay == null || mediaPlayer == null) return;

        // 取消之前的隐藏计划和正在进行的动画
        handler.removeCallbacks(hideProgressRunnable);
        progressOverlay.animate().cancel();
        if (titleOverlay != null) titleOverlay.animate().cancel();

        // 更新进度数据
        updateProgressBar();

        float slideDist = dpToPx(SLIDE_DISTANCE_DP);

        if (!progressVisible) {
            progressVisible = true;

            // 进度条：从底部滑入 + 淡入
            progressOverlay.setVisibility(View.VISIBLE);
            progressOverlay.setAlpha(0f);
            progressOverlay.setTranslationY(slideDist);
            progressOverlay.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(350)
                    .setInterpolator(new DecelerateInterpolator(2.0f))
                    .setListener(null)
                    .start();

            // 标题：从顶部滑入 + 淡入
            if (titleOverlay != null && videoTitle != null && !videoTitle.isEmpty()) {
                titleOverlay.setText(videoTitle);
                titleOverlay.setVisibility(View.VISIBLE);
                titleOverlay.setAlpha(0f);
                titleOverlay.setTranslationY(-slideDist);
                titleOverlay.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(350)
                        .setInterpolator(new DecelerateInterpolator(2.0f))
                        .setListener(null)
                        .start();
            }

            // 启动进度条定时刷新
            handler.removeCallbacks(progressBarUpdater);
            handler.post(progressBarUpdater);
        } else {
            // 已经在显示，确保完全可见
            progressOverlay.setAlpha(1f);
            progressOverlay.setTranslationY(0f);
            if (titleOverlay != null && titleOverlay.getVisibility() == View.VISIBLE) {
                titleOverlay.setAlpha(1f);
                titleOverlay.setTranslationY(0f);
            }
        }

        // 设定自动隐藏
        handler.postDelayed(hideProgressRunnable, hideDelayMs);
    }

    /** 淡出隐藏进度条和标题（滑出 + 淡出） */
    private void fadeOutProgress() {
        if (progressOverlay == null || !progressVisible) return;

        float slideDist = dpToPx(SLIDE_DISTANCE_DP);

        // 进度条：向底部滑出 + 淡出
        progressOverlay.animate()
                .alpha(0f)
                .translationY(slideDist)
                .setDuration(300)
                .setInterpolator(new AccelerateInterpolator(2.0f))
                .setListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator animation) {
                        progressVisible = false;
                        progressOverlay.setVisibility(View.INVISIBLE);
                        handler.removeCallbacks(progressBarUpdater);
                        progressOverlay.animate().setListener(null);
                    }
                })
                .start();

        // 标题：向顶部滑出 + 淡出
        if (titleOverlay != null && titleOverlay.getVisibility() == View.VISIBLE) {
            titleOverlay.animate()
                    .alpha(0f)
                    .translationY(-slideDist)
                    .setDuration(300)
                    .setInterpolator(new AccelerateInterpolator(2.0f))
                    .setListener(new android.animation.AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(android.animation.Animator animation) {
                            titleOverlay.setVisibility(View.INVISIBLE);
                            titleOverlay.animate().setListener(null);
                        }
                    })
                    .start();
        }
    }

    /** 刷新进度条和时间文字 */
    private void updateProgressBar() {
        if (mediaPlayer == null) return;
        try {
            int current = mediaPlayer.getCurrentPosition();
            int duration = mediaPlayer.getDuration();
            if (duration > 0) {
                progressBar.setProgress((int) (1000L * current / duration));
            }
            timeCurrentText.setText(formatTime(current));
            timeDurationText.setText(formatTime(duration));
        } catch (Exception e) {
            // 忽略
        }
    }

    /** 毫秒转时间字符串，短视频显示 MM:SS，长视频显示 H:MM:SS */
    private String formatTime(int millis) {
        int totalSec = millis / 1000;
        int h = totalSec / 3600;
        int m = (totalSec % 3600) / 60;
        int s = totalSec % 60;
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, s);
        }
        return String.format("%02d:%02d", m, s);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    // ========== 播放控制 ==========

    private void startPlayback(String url) {
        Log.d(TAG, "开始播放: " + url);

        releasePlayer();

        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDisplay(surfaceView.getHolder());
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mediaPlayer.setOnPreparedListener(this);
            mediaPlayer.setOnCompletionListener(this);
            mediaPlayer.setOnErrorListener(this);

            // 设置视频尺寸回调，用于调整 SurfaceView 比例
            mediaPlayer.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                @Override
                public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
                    Log.d(TAG, "视频尺寸: " + width + "x" + height);
                }
            });

            // 使用带 headers 的方式设置数据源
            // B 站等 App 投屏的 URL 可能需要特定的 User-Agent 和 Referer
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 4.4) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/33.0.0.0 Safari/537.36");
            headers.put("Referer", "https://www.bilibili.com/");

            Log.d(TAG, "setDataSource URL: " + url);
            mediaPlayer.setDataSource(this, Uri.parse(url), headers);

            Log.d(TAG, "开始异步准备...");
            mediaPlayer.prepareAsync();

        } catch (Exception e) {
            Log.e(TAG, "设置播放源失败: " + e.getMessage(), e);
            showErrorAndFinish("设置播放源失败: " + e.getMessage());
        }
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        Log.d(TAG, "视频准备完成，时长: " + mp.getDuration() + "ms，开始播放");
        try {
            mp.start();
            // 通知 DLNARenderer 状态为 PLAYING
            com.home.tvlauncher.utils.DLNARenderer renderer =
                    com.home.tvlauncher.utils.DLNARenderer.getInstance();
            if (renderer != null) {
                renderer.setTransportState("PLAYING");
                renderer.updatePosition(0, mp.getDuration());
            }
            // 启动进度定期更新
            handler.post(progressUpdater);
        } catch (Exception e) {
            Log.e(TAG, "start 失败", e);
            showErrorAndFinish("播放启动失败");
        }
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        Log.d(TAG, "播放完成");
        Intent statusIntent = new Intent(LauncherActivity.ACTION_UPDATE_STATUS);
        statusIntent.putExtra(LauncherActivity.EXTRA_STATUS, getString(R.string.status_waiting));
        sendBroadcast(statusIntent);
        finish();
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        String errorMsg = "播放错误 what=" + what + " extra=" + extra;
        Log.e(TAG, errorMsg);

        // 解码错误详情
        String detail;
        switch (what) {
            case MediaPlayer.MEDIA_ERROR_UNKNOWN:
                detail = "未知错误";
                break;
            case MediaPlayer.MEDIA_ERROR_SERVER_DIED:
                detail = "媒体服务崩溃";
                break;
            default:
                detail = "错误码:" + what;
        }
        switch (extra) {
            case MediaPlayer.MEDIA_ERROR_IO:
                detail += " (IO错误/网络不可达)";
                break;
            case MediaPlayer.MEDIA_ERROR_MALFORMED:
                detail += " (格式错误)";
                break;
            case MediaPlayer.MEDIA_ERROR_UNSUPPORTED:
                detail += " (不支持的格式)";
                break;
            case MediaPlayer.MEDIA_ERROR_TIMED_OUT:
                detail += " (超时)";
                break;
            default:
                detail += " (extra:" + extra + ")";
        }

        Log.e(TAG, "错误详情: " + detail);
        showErrorAndFinish(detail);
        return true;
    }

    private void showErrorAndFinish(final String msg) {
        Intent statusIntent = new Intent(LauncherActivity.ACTION_UPDATE_STATUS);
        statusIntent.putExtra(LauncherActivity.EXTRA_STATUS, "播放失败: " + msg);
        sendBroadcast(statusIntent);

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Intent waitIntent = new Intent(LauncherActivity.ACTION_UPDATE_STATUS);
                waitIntent.putExtra(LauncherActivity.EXTRA_STATUS, getString(R.string.status_waiting));
                sendBroadcast(waitIntent);
                finish();
            }
        }, 5000);
    }

    private void releasePlayer() {
        handler.removeCallbacks(progressUpdater);
        handler.removeCallbacks(hideProgressRunnable);
        handler.removeCallbacks(progressBarUpdater);
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (Exception ignored) {}
            try {
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
    }

    private void stopAndFinish() {
        releasePlayer();
        Intent statusIntent = new Intent(LauncherActivity.ACTION_UPDATE_STATUS);
        statusIntent.putExtra(LauncherActivity.EXTRA_STATUS, getString(R.string.status_waiting));
        sendBroadcast(statusIntent);
        finish();
    }

    // ========== 生命周期 ==========

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String videoUrl = intent.getStringExtra(LauncherActivity.EXTRA_VIDEO_URL);
        if (videoUrl != null) {
            Log.d(TAG, "切换视频: " + videoUrl);

            // 更新标题
            String title = intent.getStringExtra(LauncherActivity.EXTRA_VIDEO_TITLE);
            if (title != null && !title.isEmpty()) {
                videoTitle = title;
            }

            if (surfaceReady) {
                startPlayback(videoUrl);
            } else {
                pendingUrl = videoUrl;
            }
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
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        instance = null;
        releasePlayer();
    }

    // ========== 遥控器按键处理 ==========

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mediaPlayer == null) {
            return super.onKeyDown(keyCode, event);
        }

        switch (keyCode) {
            // OK 键 / 播放暂停键 → 切换播放/暂停
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePlayPause();
                return true;

            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (!mediaPlayer.isPlaying()) {
                    try {
                        mediaPlayer.start();
                        updateTransportState("PLAYING");
                    } catch (Exception e) {
                        Log.e(TAG, "play error", e);
                    }
                }
                return true;

            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    updateTransportState("PAUSED_PLAYBACK");
                }
                return true;

            // 左键 → 快退 10 秒
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekRelative(-SEEK_STEP_MS);
                showProgressOverlay(PROGRESS_HIDE_DELAY_SEEK);
                return true;

            // 右键 → 快进 10 秒
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekRelative(SEEK_STEP_MS);
                showProgressOverlay(PROGRESS_HIDE_DELAY_SEEK);
                return true;

            // 下键 → 显示进度条
            case KeyEvent.KEYCODE_DPAD_DOWN:
                showProgressOverlay(PROGRESS_HIDE_DELAY_DOWN);
                return true;

            // 返回键 / 停止键 → 停止播放
            case KeyEvent.KEYCODE_MEDIA_STOP:
                stopAndFinish();
                return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    /** 切换播放/暂停 */
    private void togglePlayPause() {
        try {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                updateTransportState("PAUSED_PLAYBACK");
            } else {
                mediaPlayer.start();
                updateTransportState("PLAYING");
            }
        } catch (Exception e) {
            Log.e(TAG, "togglePlayPause error", e);
        }
    }

    /** 相对快进/快退 */
    private void seekRelative(int offsetMs) {
        try {
            int current = mediaPlayer.getCurrentPosition();
            int duration = mediaPlayer.getDuration();
            int target = current + offsetMs;
            if (target < 0) target = 0;
            if (target > duration) target = duration;
            mediaPlayer.seekTo(target);
            Log.d(TAG, "Seek: " + current + " -> " + target + " (offset=" + offsetMs + ")");
        } catch (Exception e) {
            Log.e(TAG, "seekRelative error", e);
        }
    }

    /** 更新 DLNA 传输状态并通知桌面 */
    private void updateTransportState(String state) {
        com.home.tvlauncher.utils.DLNARenderer renderer =
                com.home.tvlauncher.utils.DLNARenderer.getInstance();
        if (renderer != null) {
            renderer.setTransportState(state);
        }

        Intent statusIntent = new Intent(LauncherActivity.ACTION_UPDATE_STATUS);
        if ("PLAYING".equals(state)) {
            statusIntent.putExtra(LauncherActivity.EXTRA_STATUS, getString(R.string.status_playing));
        } else if ("PAUSED_PLAYBACK".equals(state)) {
            statusIntent.putExtra(LauncherActivity.EXTRA_STATUS, getString(R.string.status_paused));
        }
        sendBroadcast(statusIntent);
    }

    // ========== 供外部查询 ==========

    public boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    public int getCurrentPosition() {
        try {
            return mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public int getDuration() {
        try {
            return mediaPlayer != null ? mediaPlayer.getDuration() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
