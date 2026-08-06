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
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
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
import android.widget.TextClock;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

@SuppressLint("UnsafeOptInUsageError")
public final class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 41;
    private static final int COLOR_BACKGROUND = Color.rgb(10, 13, 12);
    private static final int COLOR_SURFACE = Color.rgb(24, 29, 27);
    private static final int COLOR_SURFACE_STRONG = Color.rgb(31, 37, 34);
    private static final int COLOR_DIVIDER = Color.rgb(48, 56, 52);
    private static final int COLOR_TEXT = Color.rgb(244, 247, 245);
    private static final int COLOR_MUTED = Color.rgb(157, 169, 163);
    private static final int COLOR_GREEN = Color.rgb(55, 204, 128);
    private static final int COLOR_BLUE = Color.rgb(91, 165, 245);
    private static final int COLOR_AMBER = Color.rgb(242, 184, 75);

    private TextView stateText;
    private TextView detailText;
    private TextView pinText;
    private TextView logText;
    private TextView statusModeText;
    private Button startButton;
    private Button stopButton;
    private ScrollView logScroll;
    private SurfaceView videoView;
    private View statusDot;
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
        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(Color.BLACK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(14), dp(24), dp(18));
        root.setBackgroundColor(COLOR_BACKGROUND);
        diagnosticView = root;

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        View brandBar = new View(this);
        brandBar.setBackground(roundedBackground(COLOR_GREEN, 3));
        LinearLayout.LayoutParams brandBarParams = new LinearLayout.LayoutParams(dp(5), dp(36));
        brandBarParams.rightMargin = dp(12);
        topBar.addView(brandBar, brandBarParams);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView title = label("OpenCarLink", 24, COLOR_TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        brand.addView(title, matchWrap());
        TextView product = label("无线车机助手", 12, COLOR_MUTED);
        brand.addView(product, matchWrap());
        topBar.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("HH:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(COLOR_TEXT);
        clock.setTextSize(26);
        clock.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        topBar.addView(clock, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout buildInfo = new LinearLayout(this);
        buildInfo.setOrientation(LinearLayout.VERTICAL);
        buildInfo.setGravity(Gravity.END);
        TextView device = label(Build.MANUFACTURER + " " + Build.MODEL, 12, COLOR_MUTED);
        device.setGravity(Gravity.END);
        buildInfo.addView(device, matchWrap());
        TextView version = label("v" + versionName(), 11, COLOR_BLUE);
        version.setGravity(Gravity.END);
        buildInfo.addView(version, matchWrap());
        LinearLayout.LayoutParams buildInfoParams = new LinearLayout.LayoutParams(dp(170), -2);
        buildInfoParams.leftMargin = dp(18);
        topBar.addView(buildInfo, buildInfoParams);
        root.addView(topBar, new LinearLayout.LayoutParams(-1, dp(52)));

        View topDivider = new View(this);
        topDivider.setBackgroundColor(COLOR_DIVIDER);
        LinearLayout.LayoutParams topDividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        topDividerParams.topMargin = dp(10);
        root.addView(topDivider, topDividerParams);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout connection = new LinearLayout(this);
        connection.setOrientation(LinearLayout.VERTICAL);
        connection.setPadding(0, dp(20), dp(28), 0);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this);
        statusDot.setBackground(roundedBackground(COLOR_MUTED, 8));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotParams.rightMargin = dp(8);
        statusRow.addView(statusDot, dotParams);
        statusModeText = label("待机", 12, COLOR_MUTED);
        statusModeText.setTypeface(null, android.graphics.Typeface.BOLD);
        statusRow.addView(statusModeText, new LinearLayout.LayoutParams(-2, -2));
        connection.addView(statusRow, matchWrap());

        stateText = label("等待连接", 31, COLOR_TEXT);
        stateText.setTypeface(null, android.graphics.Typeface.BOLD);
        stateText.setSingleLine(true);
        stateText.setEllipsize(TextUtils.TruncateAt.END);
        stateText.setAutoSizeTextTypeUniformWithConfiguration(
            21,
            31,
            1,
            TypedValue.COMPLEX_UNIT_SP
        );
        LinearLayout.LayoutParams stateParams = matchWrap();
        stateParams.topMargin = dp(8);
        connection.addView(stateText, stateParams);

        detailText = label("无线接收端未运行", 14, COLOR_MUTED);
        detailText.setMaxLines(2);
        detailText.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams detailParams = matchWrap();
        detailParams.topMargin = dp(4);
        connection.addView(detailText, detailParams);

        View connectionSpacer = new View(this);
        connection.addView(connectionSpacer, new LinearLayout.LayoutParams(1, 0, 1f));

        LinearLayout pairing = new LinearLayout(this);
        pairing.setOrientation(LinearLayout.HORIZONTAL);
        pairing.setGravity(Gravity.CENTER_VERTICAL);
        pairing.setPadding(dp(18), dp(11), dp(18), dp(11));
        pairing.setBackground(outlinedBackground(COLOR_SURFACE, COLOR_DIVIDER, 8));
        LinearLayout pairingLabel = new LinearLayout(this);
        pairingLabel.setOrientation(LinearLayout.VERTICAL);
        TextView pairingTitle = label("配对码", 13, COLOR_TEXT);
        pairingTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        pairingLabel.addView(pairingTitle, matchWrap());
        TextView pairingType = label("PIN", 10, COLOR_MUTED);
        pairingLabel.addView(pairingType, matchWrap());
        pairing.addView(pairingLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        pinText = label("------", 31, COLOR_AMBER);
        pinText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        pinText.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        pairing.addView(pinText, new LinearLayout.LayoutParams(-2, -2));
        connection.addView(pairing, new LinearLayout.LayoutParams(-1, dp(68)));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        startButton = commandButton("开始连接", COLOR_GREEN, Color.rgb(7, 24, 15));
        startButton.setOnClickListener(view -> requestAndStart());
        controls.addView(startButton, new LinearLayout.LayoutParams(0, dp(52), 1f));
        stopButton = commandButton("停止", COLOR_SURFACE_STRONG, COLOR_TEXT);
        stopButton.setBackground(outlinedBackground(COLOR_SURFACE_STRONG, COLOR_DIVIDER, 7));
        stopButton.setOnClickListener(view -> stopReceiver());
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(dp(108), dp(52));
        stopParams.leftMargin = dp(10);
        controls.addView(stopButton, stopParams);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(-1, dp(52));
        controlsParams.topMargin = dp(12);
        connection.addView(controls, controlsParams);

        content.addView(connection, new LinearLayout.LayoutParams(0, -1, 1.08f));

        View columnDivider = new View(this);
        columnDivider.setBackgroundColor(COLOR_DIVIDER);
        LinearLayout.LayoutParams columnDividerParams = new LinearLayout.LayoutParams(dp(1), -1);
        columnDividerParams.topMargin = dp(20);
        content.addView(columnDivider, columnDividerParams);

        LinearLayout activity = new LinearLayout(this);
        activity.setOrientation(LinearLayout.VERTICAL);
        activity.setPadding(dp(28), dp(20), 0, 0);
        TextView progressTitle = label("连接进度", 15, COLOR_TEXT);
        progressTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        activity.addView(progressTitle, matchWrap());

        LinearLayout stages = new LinearLayout(this);
        stages.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"网络", "蓝牙", "手机", "认证"};
        for (int index = 0; index < names.length; index++) {
            TextView stage = label((index + 1) + "\n" + names[index], 12, COLOR_MUTED);
            stage.setGravity(Gravity.CENTER);
            stage.setLineSpacing(0f, 0.92f);
            stage.setBackground(outlinedBackground(COLOR_SURFACE, COLOR_DIVIDER, 7));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(58), 1f);
            if (index > 0) {
                params.leftMargin = dp(7);
            }
            stages.addView(stage, params);
            stageViews[index] = stage;
        }
        LinearLayout.LayoutParams stagesParams = new LinearLayout.LayoutParams(-1, dp(58));
        stagesParams.topMargin = dp(10);
        activity.addView(stages, stagesParams);

        TextView logTitle = label("连接记录", 13, COLOR_MUTED);
        logTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams logTitleParams = matchWrap();
        logTitleParams.topMargin = dp(16);
        logTitleParams.bottomMargin = dp(7);
        activity.addView(logTitle, logTitleParams);

        logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logScroll.setBackground(outlinedBackground(Color.rgb(15, 18, 17), COLOR_DIVIDER, 7));
        logText = label("尚无连接记录", 11, Color.rgb(190, 200, 195));
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        logText.setPadding(dp(12), dp(10), dp(12), dp(10));
        logScroll.addView(logText, new ScrollView.LayoutParams(-1, -2));
        activity.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        content.addView(activity, new LinearLayout.LayoutParams(0, -1, 0.92f));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

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
        playbackStop.setText("结束投屏");
        playbackStop.setTextColor(Color.WHITE);
        playbackStop.setTextSize(13);
        playbackStop.setAllCaps(false);
        playbackStop.setBackground(roundedBackground(Color.argb(210, 20, 24, 22), 7));
        playbackStop.setOnClickListener(view -> stopReceiver());
        FrameLayout.LayoutParams playbackStopParams = new FrameLayout.LayoutParams(dp(106), dp(44));
        playbackStopParams.gravity = Gravity.TOP | Gravity.END;
        playbackStopParams.topMargin = dp(14);
        playbackStopParams.rightMargin = dp(14);
        playback.addView(playbackStop, playbackStopParams);

        TextView playbackVersion = label("v" + versionName(), 11, Color.LTGRAY);
        playbackVersion.setPadding(dp(7), dp(3), dp(7), dp(3));
        playbackVersion.setBackground(roundedBackground(Color.argb(170, 20, 24, 22), 5));
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
        boolean streaming = snapshot.stage >= 8;
        boolean failed = snapshot.state.contains("失败") || snapshot.state.contains("错误");
        int statusColor = failed
            ? Color.rgb(242, 102, 102)
            : streaming ? COLOR_GREEN : snapshot.running ? COLOR_BLUE : COLOR_MUTED;
        statusDot.setBackground(roundedBackground(statusColor, 8));
        statusModeText.setText(failed ? "异常" : streaming ? "投屏中" : snapshot.running ? "连接中" : "待机");
        statusModeText.setTextColor(statusColor);
        stateText.setText(snapshot.state);
        detailText.setText(snapshot.detail);
        pinText.setText(snapshot.pin.isEmpty() ? "------" : snapshot.pin);
        logText.setText(snapshot.log.isEmpty() ? "尚无连接记录" : snapshot.log);
        startButton.setEnabled(!snapshot.running);
        stopButton.setEnabled(snapshot.running);
        startButton.setText(snapshot.running ? "连接中" : "开始连接");
        startButton.setAlpha(snapshot.running ? 0.45f : 1f);
        stopButton.setAlpha(snapshot.running ? 1f : 0.45f);
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
                reached ? Color.rgb(7, 24, 15) : COLOR_MUTED
            );
            stageViews[index].setBackground(
                reached
                    ? roundedBackground(COLOR_GREEN, 7)
                    : outlinedBackground(COLOR_SURFACE, COLOR_DIVIDER, 7)
            );
        }
        if (logScroll != null) {
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
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

    private Button commandButton(String text, int backgroundColor, int textColor) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(15);
        button.setTextColor(textColor);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setStateListAnimator(null);
        button.setBackground(roundedBackground(backgroundColor, 7));
        return button;
    }

    private GradientDrawable roundedBackground(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable outlinedBackground(int color, int strokeColor, int radiusDp) {
        GradientDrawable drawable = roundedBackground(color, radiusDp);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
