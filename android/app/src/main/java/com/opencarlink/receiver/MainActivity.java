package com.opencarlink.receiver;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.C;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.extractor.ts.TsExtractor;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.List;

@SuppressLint("UnsafeOptInUsageError")
public final class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 41;

    private TextView stateText;
    private TextView detailText;
    private TextView pinText;
    private TextView logText;
    private Button startButton;
    private Button stopButton;
    private PlayerView videoView;
    private View diagnosticView;
    private View playbackLayer;
    private ExoPlayer player;
    private boolean decoderReadyReported;
    private boolean firstFrameReported;
    private boolean playbackMode;
    private boolean touchActive;
    private long lastTouchMoveMs;
    private final TextView[] stageViews = new TextView[4];

    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            render(CarLinkService.snapshot());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(16, 20, 18));
        window.setNavigationBarColor(Color.rgb(16, 20, 18));
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildContent());
        render(CarLinkService.snapshot());
    }

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(CarLinkService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(updates, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updates, filter);
        }
        startPlayer();
        render(CarLinkService.snapshot());
    }

    @Override
    protected void onStop() {
        stopPlayer();
        setPlaybackMode(false);
        unregisterReceiver(updates);
        super.onStop();
    }

    private View buildContent() {
        int background = Color.rgb(16, 20, 18);
        int surface = Color.rgb(28, 34, 31);
        int text = Color.rgb(241, 245, 243);
        int muted = Color.rgb(145, 158, 151);
        int green = Color.rgb(37, 184, 121);

        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(Color.BLACK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(18));
        root.setBackgroundColor(background);
        diagnosticView = root;

        TextView title = label("OpenCarLink Receiver", 24, text);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, matchWrap());

        TextView device = label(Build.MANUFACTURER + " " + Build.MODEL, 13, muted);
        LinearLayout.LayoutParams deviceParams = matchWrap();
        deviceParams.bottomMargin = dp(22);
        root.addView(device, deviceParams);

        stateText = label("等待启动", 21, text);
        stateText.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(stateText, matchWrap());

        detailText = label("无线接收端未运行", 14, muted);
        LinearLayout.LayoutParams detailParams = matchWrap();
        detailParams.topMargin = dp(5);
        detailParams.bottomMargin = dp(18);
        root.addView(detailText, detailParams);

        LinearLayout stages = new LinearLayout(this);
        stages.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"P2P GO", "BLE", "手机", "AUTH"};
        for (int index = 0; index < names.length; index++) {
            TextView stage = label(names[index], 12, muted);
            stage.setGravity(Gravity.CENTER);
            stage.setPadding(dp(4), dp(10), dp(4), dp(10));
            stage.setBackgroundColor(surface);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1f);
            if (index > 0) {
                params.leftMargin = dp(5);
            }
            stages.addView(stage, params);
            stageViews[index] = stage;
        }
        root.addView(stages, new LinearLayout.LayoutParams(-1, dp(42)));

        pinText = label("PIN  ------", 30, green);
        pinText.setGravity(Gravity.CENTER);
        pinText.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams pinParams = matchWrap();
        pinParams.topMargin = dp(20);
        pinParams.bottomMargin = dp(16);
        root.addView(pinText, pinParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        startButton = new Button(this);
        startButton.setText("启动接收");
        startButton.setTextColor(Color.WHITE);
        startButton.setBackgroundColor(green);
        startButton.setOnClickListener(view -> requestAndStart());
        controls.addView(startButton, new LinearLayout.LayoutParams(0, dp(50), 1f));

        stopButton = new Button(this);
        stopButton.setText("停止");
        stopButton.setOnClickListener(view -> stopReceiver());
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, dp(50), 1f);
        stopParams.leftMargin = dp(8);
        controls.addView(stopButton, stopParams);
        root.addView(controls, new LinearLayout.LayoutParams(-1, dp(50)));

        TextView logTitle = label("现场日志", 14, muted);
        logTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams logTitleParams = matchWrap();
        logTitleParams.topMargin = dp(22);
        logTitleParams.bottomMargin = dp(7);
        root.addView(logTitle, logTitleParams);

        ScrollView logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logScroll.setBackgroundColor(Color.rgb(11, 14, 13));
        logText = label("尚无日志", 12, Color.rgb(184, 194, 188));
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        logText.setPadding(dp(12), dp(10), dp(12), dp(10));
        logScroll.addView(logText, new ScrollView.LayoutParams(-1, -2));
        root.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        FrameLayout playback = new FrameLayout(this);
        playback.setBackgroundColor(Color.BLACK);
        playback.setVisibility(View.GONE);
        playbackLayer = playback;

        videoView = new PlayerView(this);
        videoView.setUseController(false);
        videoView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        videoView.setShutterBackgroundColor(Color.BLACK);
        videoView.setBackgroundColor(Color.BLACK);
        videoView.setOnTouchListener(this::handleVideoTouch);
        playback.addView(videoView, new FrameLayout.LayoutParams(-1, -1));

        Button playbackStop = new Button(this);
        playbackStop.setText("停止");
        playbackStop.setTextColor(Color.WHITE);
        playbackStop.setTextSize(13);
        playbackStop.setBackgroundColor(Color.argb(190, 20, 24, 22));
        playbackStop.setOnClickListener(view -> stopReceiver());
        FrameLayout.LayoutParams playbackStopParams = new FrameLayout.LayoutParams(dp(88), dp(44));
        playbackStopParams.gravity = Gravity.TOP | Gravity.END;
        playbackStopParams.topMargin = dp(14);
        playbackStopParams.rightMargin = dp(14);
        playback.addView(playbackStop, playbackStopParams);

        screen.addView(root, new FrameLayout.LayoutParams(-1, -1));
        screen.addView(playback, new FrameLayout.LayoutParams(-1, -1));
        return screen;
    }

    private boolean handleVideoTouch(View view, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            int[] point = mapVideoPoint(view, event, false);
            touchActive = point != null
                && TouchInputHub.sendTouch(UibcProtocol.ACTION_DOWN, point[0], point[1]);
            return touchActive;
        }
        if (!touchActive) {
            return false;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastTouchMoveMs >= 16L) {
                lastTouchMoveMs = now;
                int[] point = mapVideoPoint(view, event, true);
                TouchInputHub.sendTouch(UibcProtocol.ACTION_MOVE, point[0], point[1]);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            int[] point = mapVideoPoint(view, event, true);
            TouchInputHub.sendTouch(UibcProtocol.ACTION_UP, point[0], point[1]);
            touchActive = false;
            return true;
        }
        return true;
    }

    private int[] mapVideoPoint(View view, MotionEvent event, boolean clamp) {
        float scale = Math.min(
            view.getWidth() / (float) UibcProtocol.WIDTH,
            view.getHeight() / (float) UibcProtocol.HEIGHT
        );
        float left = (view.getWidth() - UibcProtocol.WIDTH * scale) / 2f;
        float top = (view.getHeight() - UibcProtocol.HEIGHT * scale) / 2f;
        float x = (event.getX() - left) / scale;
        float y = (event.getY() - top) / scale;
        if (!clamp && (x < 0 || y < 0 || x >= UibcProtocol.WIDTH || y >= UibcProtocol.HEIGHT)) {
            return null;
        }
        return new int[]{
            Math.max(0, Math.min(UibcProtocol.WIDTH - 1, (int) x)),
            Math.max(0, Math.min(UibcProtocol.HEIGHT - 1, (int) y))
        };
    }

    @Override
    public void onBackPressed() {
        if (playbackMode && TouchInputHub.sendKey(UibcProtocol.KEY_CODE_BACK)) {
            return;
        }
        super.onBackPressed();
    }

    private void requestAndStart() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            addMissing(missing, Manifest.permission.BLUETOOTH_ADVERTISE);
            addMissing(missing, Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            addMissing(missing, Manifest.permission.NEARBY_WIFI_DEVICES);
            addMissing(missing, Manifest.permission.POST_NOTIFICATIONS);
        } else {
            addMissing(missing, Manifest.permission.ACCESS_COARSE_LOCATION);
            addMissing(missing, Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST);
            return;
        }
        startReceiver();
    }

    private void addMissing(List<String> missing, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            missing.add(permission);
        }
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode,
        String[] permissions,
        int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST) {
            return;
        }
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "需要附近设备权限才能模拟车机", Toast.LENGTH_LONG).show();
                return;
            }
        }
        startReceiver();
    }

    private void startReceiver() {
        Intent intent = new Intent(this, CarLinkService.class)
            .setAction(CarLinkService.ACTION_START);
        startForegroundService(intent);
    }

    private void stopReceiver() {
        startService(new Intent(this, CarLinkService.class).setAction(CarLinkService.ACTION_STOP));
    }

    private void render(CarLinkService.Snapshot snapshot) {
        stateText.setText(snapshot.state);
        detailText.setText(snapshot.detail);
        pinText.setText("PIN  " + snapshot.pin);
        logText.setText(snapshot.log.isEmpty() ? "尚无日志" : snapshot.log);
        startButton.setEnabled(!snapshot.running);
        stopButton.setEnabled(snapshot.running);
        boolean streaming = snapshot.stage >= 8;
        playbackLayer.setVisibility(streaming ? View.VISIBLE : View.GONE);
        diagnosticView.setVisibility(streaming ? View.GONE : View.VISIBLE);
        setPlaybackMode(streaming);
        for (int index = 0; index < stageViews.length; index++) {
            boolean reached = snapshot.stage > index;
            stageViews[index].setTextColor(
                reached ? Color.rgb(16, 20, 18) : Color.rgb(145, 158, 151)
            );
            stageViews[index].setBackgroundColor(
                reached ? Color.rgb(37, 184, 121) : Color.rgb(28, 34, 31)
            );
        }
    }

    @SuppressWarnings("deprecation")
    private void setPlaybackMode(boolean enabled) {
        if (playbackMode == enabled) {
            return;
        }
        playbackMode = enabled;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller == null) {
                return;
            }
            int bars = WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars();
            if (enabled) {
                controller.hide(bars);
                controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            } else {
                controller.show(bars);
            }
            return;
        }
        getWindow().getDecorView().setSystemUiVisibility(
            enabled
                ? View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                : View.SYSTEM_UI_FLAG_VISIBLE
        );
    }

    private void startPlayer() {
        if (player != null) {
            return;
        }
        decoderReadyReported = false;
        firstFrameReported = false;
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(100, 300, 25, 75)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build();
        ExoPlayer value = new ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build();
        player = value;
        videoView.setPlayer(value);
        value.setTrackSelectionParameters(
            value.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        );
        value.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY && !decoderReadyReported) {
                    decoderReadyReported = true;
                    reportVideoStatus("Android 视频解码器已就绪，等待首帧");
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                reportVideoStatus(
                    "识别视频画面：" + videoSize.width + "x" + videoSize.height
                );
            }

            @Override
            public void onRenderedFirstFrame() {
                if (!firstFrameReported) {
                    firstFrameReported = true;
                    reportVideoStatus("首帧已渲染，画面开始显示");
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                reportVideoStatus("视频解码失败：" + error.getErrorCodeName());
            }
        });
        int tsFlags = DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            | DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
            | DefaultTsPayloadReaderFactory.FLAG_IGNORE_AAC_STREAM;
        ExtractorsFactory extractorsFactory = () -> new Extractor[]{
            new TsExtractor(
                TsExtractor.MODE_SINGLE_PMT,
                new TimestampAdjuster(0),
                new DefaultTsPayloadReaderFactory(tsFlags)
            )
        };
        ProgressiveMediaSource source = new ProgressiveMediaSource.Factory(
            CarLinkVideoDataSource::new,
            extractorsFactory
        ).createMediaSource(
            new MediaItem.Builder()
                .setUri(Uri.parse("carlink://live/stream.ts"))
                .setMimeType(MimeTypes.VIDEO_MP2T)
                .build()
        );
        value.setMediaSource(source);
        value.setPlayWhenReady(true);
        value.prepare();
    }

    private void stopPlayer() {
        ExoPlayer value = player;
        player = null;
        videoView.setPlayer(null);
        if (value != null) {
            value.release();
        }
    }

    private void reportVideoStatus(String message) {
        startService(
            new Intent(this, CarLinkService.class)
                .setAction(CarLinkService.ACTION_VIDEO_STATUS)
                .putExtra(CarLinkService.EXTRA_VIDEO_STATUS, message)
        );
    }

    private TextView label(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLetterSpacing(0f);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
