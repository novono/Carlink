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
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
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
    private SurfaceView videoView;
    private View diagnosticView;
    private View playbackLayer;
    private DirectTsVideoDecoder decoder;
    private boolean firstFrameReported;
    private boolean playbackMode;
    private boolean touchActive;
    private float touchDownViewX;
    private float touchDownViewY;
    private int touchDownUibcX;
    private int touchDownUibcY;
    private long touchDownEventMs;
    private int touchMoveCount;
    private int touchMaxPointerCount;
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
        CarLinkService.Snapshot snapshot = CarLinkService.snapshot();
        if (snapshot.running) {
            startPlayer();
        }
        render(snapshot);
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

        TextView device = label(
            Build.MANUFACTURER + " " + Build.MODEL + "  |  v" + versionName(),
            13,
            muted
        );
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

        FrameLayout videoHost = new FrameLayout(this);
        videoHost.setBackgroundColor(Color.BLACK);
        videoView = new SurfaceView(this);
        videoView.setOnTouchListener(this::handleVideoTouch);
        videoHost.addView(videoView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        videoHost.addOnLayoutChangeListener((view, left, top, right, bottom,
                                              oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = right - left;
            int height = bottom - top;
            float scale = Math.min(
                width / (float) UibcProtocol.WIDTH,
                height / (float) UibcProtocol.HEIGHT
            );
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) videoView.getLayoutParams();
            int videoWidth = Math.round(UibcProtocol.WIDTH * scale);
            int videoHeight = Math.round(UibcProtocol.HEIGHT * scale);
            if (params.width == videoWidth && params.height == videoHeight) {
                return;
            }
            params.width = videoWidth;
            params.height = videoHeight;
            params.gravity = Gravity.CENTER;
            videoView.setLayoutParams(params);
        });
        videoView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                if (CarLinkService.snapshot().stage >= 8) {
                    startPlayer();
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                stopPlayer();
            }
        });
        playback.addView(videoHost, new FrameLayout.LayoutParams(-1, -1));

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

        TextView playbackVersion = label("v" + versionName(), 11, Color.LTGRAY);
        playbackVersion.setPadding(dp(7), dp(3), dp(7), dp(3));
        playbackVersion.setBackgroundColor(Color.argb(150, 20, 24, 22));
        FrameLayout.LayoutParams playbackVersionParams = new FrameLayout.LayoutParams(-2, -2);
        playbackVersionParams.gravity = Gravity.TOP | Gravity.END;
        playbackVersionParams.topMargin = dp(64);
        playbackVersionParams.rightMargin = dp(14);
        playback.addView(playbackVersion, playbackVersionParams);

        screen.addView(root, new FrameLayout.LayoutParams(-1, -1));
        screen.addView(playback, new FrameLayout.LayoutParams(-1, -1));
        return screen;
    }

    private boolean handleVideoTouch(View view, MotionEvent event) {
        int maskedAction = event.getActionMasked();
        if (maskedAction == MotionEvent.ACTION_DOWN) {
            int[][] pointers = mapVideoPointers(view, event);
            touchActive = pointers != null && sendTouchEvent(event.getAction(), pointers);
            if (touchActive) {
                touchDownViewX = event.getX();
                touchDownViewY = event.getY();
                touchDownUibcX = pointers[1][0];
                touchDownUibcY = pointers[2][0];
                touchDownEventMs = event.getEventTime();
                touchMoveCount = 0;
                touchMaxPointerCount = pointers[0].length;
            }
            return touchActive;
        }
        if (!touchActive) {
            return false;
        }
        if (maskedAction == MotionEvent.ACTION_MOVE) {
            int[][] pointers = mapVideoPointers(view, event);
            touchMaxPointerCount = Math.max(touchMaxPointerCount, pointers[0].length);
            sendTouchEvent(event.getAction(), pointers);
            touchMoveCount++;
            return true;
        }
        if (maskedAction == MotionEvent.ACTION_POINTER_DOWN
            || maskedAction == MotionEvent.ACTION_POINTER_UP) {
            int[][] pointers = mapVideoPointers(view, event);
            touchMaxPointerCount = Math.max(touchMaxPointerCount, pointers[0].length);
            sendTouchEvent(event.getAction(), pointers);
            return true;
        }
        if (maskedAction == MotionEvent.ACTION_UP || maskedAction == MotionEvent.ACTION_CANCEL) {
            int[][] pointers = mapVideoPointers(view, event);
            int deltaX = pointers[1][0] - touchDownUibcX;
            int deltaY = pointers[2][0] - touchDownUibcY;
            sendTouchEvent(event.getAction(), pointers);
            if (maskedAction == MotionEvent.ACTION_UP) {
                view.performClick();
            }
            touchActive = false;
            logTouchGesture(event, deltaX, deltaY);
            return true;
        }
        return true;
    }

    private boolean sendTouchEvent(int action, int[][] pointers) {
        return TouchInputHub.sendTouch(action, pointers[0], pointers[1], pointers[2]);
    }

    private void logTouchGesture(
        MotionEvent event,
        int uibcDeltaX,
        int uibcDeltaY
    ) {
        Log.i(
            "OpenCarLinkTouch",
            "viewDelta=" + Math.round(event.getX() - touchDownViewX)
                + "," + Math.round(event.getY() - touchDownViewY)
                + " uibcDelta=" + uibcDeltaX + "," + uibcDeltaY
                + " duration=" + (event.getEventTime() - touchDownEventMs) + "ms"
                + " moves=" + touchMoveCount
                + " pointers=" + touchMaxPointerCount
        );
    }

    private int[][] mapVideoPointers(View view, MotionEvent event) {
        int viewWidth = view.getWidth();
        int viewHeight = view.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) {
            return null;
        }
        int count = event.getPointerCount();
        int[] ids = new int[count];
        int[] xs = new int[count];
        int[] ys = new int[count];
        for (int index = 0; index < count; index++) {
            ids[index] = event.getPointerId(index);
            xs[index] = (int) (event.getX(index) * UibcProtocol.WIDTH / viewWidth);
            ys[index] = (int) (event.getY(index) * UibcProtocol.HEIGHT / viewHeight);
        }
        return new int[][]{ids, xs, ys};
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
        if (streaming) {
            startPlayer();
        } else {
            stopPlayer();
        }
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
        if (decoder != null || videoView == null || !videoView.getHolder().getSurface().isValid()) {
            return;
        }
        firstFrameReported = false;
        DirectTsVideoDecoder value = new DirectTsVideoDecoder(
            videoView.getHolder().getSurface(),
            new DirectTsVideoDecoder.Listener() {
            @Override
            public void onDecoderReady(String codecName, boolean lowLatency) {
                reportVideoStatus(
                    "直通硬件解码器已就绪：" + codecName
                        + (lowLatency ? "（低延迟模式）" : "")
                );
            }

            @Override
            public void onVideoSize(int width, int height) {
                reportVideoStatus("识别视频画面：" + width + "x" + height);
            }

            @Override
            public void onFirstFrame() {
                if (!firstFrameReported) {
                    firstFrameReported = true;
                    reportVideoStatus("首帧已直通渲染，画面开始显示");
                }
            }

            @Override
            public void onError(String message) {
                reportVideoStatus(message);
            }
            }
        );
        decoder = value;
        value.start();
    }

    private void stopPlayer() {
        DirectTsVideoDecoder value = decoder;
        decoder = null;
        if (value != null) {
            value.stop();
        }
    }

    private void reportVideoStatus(String message) {
        startService(
            new Intent(this, CarLinkService.class)
                .setAction(CarLinkService.ACTION_VIDEO_STATUS)
                .putExtra(CarLinkService.EXTRA_VIDEO_STATUS, message)
        );
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException error) {
            return "unknown";
        }
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
