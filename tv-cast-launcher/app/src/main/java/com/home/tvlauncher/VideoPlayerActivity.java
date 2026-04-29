package com.home.tvlauncher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

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

    private SurfaceView surfaceView;
    private MediaPlayer mediaPlayer;
    private String pendingUrl;
    private boolean surfaceReady = false;
    private Handler handler = new Handler();

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

        // 获取视频 URL
        String videoUrl = getIntent().getStringExtra(LauncherActivity.EXTRA_VIDEO_URL);
        if (videoUrl != null) {
            Log.d(TAG, "收到视频 URL: " + videoUrl);
            pendingUrl = videoUrl;
        } else {
            Log.e(TAG, "没有收到视频 URL");
            finish();
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
