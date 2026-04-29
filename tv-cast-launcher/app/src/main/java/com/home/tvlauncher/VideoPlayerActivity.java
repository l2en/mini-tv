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
