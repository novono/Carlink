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
    private volatile long playerGeneration;
    private long playerSessionId = -1;
    private boolean firstFrameReported;
    private boolean playbackMode;
    private float carUiScale = 3f;
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
        // Clear legacy records even if the receiver is never started after an upgrade.
        getSharedPreferences("carlink_auth", MODE_PRIVATE).edit().clear().commit();
        getSharedPreferences("carlink", MODE_PRIVATE).edit().clear().commit();
        new DiagnosticLog(getApplicationContext());
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
        carUiScale = resolveCarUiScale();

        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(Color.BLACK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(14), dp(24), dp(18));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int leftInset = insets.getSystemWindowInsetLeft();
            int topInset = insets.getSystemWindowInsetTop();
            int rightInset = insets.getSystemWindowInsetRight();
            int bottomInset = insets.getSystemWindowInsetBottom();
            view.setPadding(
                Math.max(dp(24), leftInset + dp(8)),
                Math.max(dp(14), topInset + dp(8)),
                Math.max(dp(24), rightInset + dp(8)),
                Math.max(dp(18), bottomInset + dp(8))
            );
            return insets;
        });
        root.setBackgroundColor(COLOR_BACKGROUND);
        diagnosticView = root;

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView brandIcon = label("车", 18, Color.rgb(7, 24, 15));
        brandIcon.setGravity(Gravity.CENTER);
        brandIcon.setTypeface(null, android.graphics.Typeface.BOLD);
        brandIcon.setBackground(roundedBackground(COLOR_GREEN, 12));
        LinearLayout.LayoutParams brandIconParams = new LinearLayout.LayoutParams(dp(46), dp(46));
        brandIconParams.rightMargin = dp(14);
        topBar.addView(brandIcon, brandIconParams);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView title = label("CarLink 车机管家", 23, COLOR_TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        brand.addView(title, matchWrap());
        TextView product = label("手机互联 · 行车服务", 11, COLOR_MUTED);
        LinearLayout.LayoutParams productParams = matchWrap();
        productParams.topMargin = dp(2);
        brand.addView(product, productParams);
        topBar.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("HH:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(COLOR_TEXT);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(30));
        clock.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        topBar.addView(clock, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout buildInfo = new LinearLayout(this);
        buildInfo.setOrientation(LinearLayout.VERTICAL);
        buildInfo.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView device = label(Build.MANUFACTURER + " " + Build.MODEL, 11, COLOR_MUTED);
        device.setGravity(Gravity.END);
        buildInfo.addView(device, matchWrap());
        TextView version = label("v" + versionName(), 10, COLOR_BLUE);
        version.setGravity(Gravity.END);
        LinearLayout.LayoutParams versionParams = matchWrap();
        versionParams.topMargin = dp(2);
        buildInfo.addView(version, versionParams);
        LinearLayout.LayoutParams buildInfoParams = new LinearLayout.LayoutParams(dp(174), -1);
        buildInfoParams.leftMargin = dp(20);
        topBar.addView(buildInfo, buildInfoParams);
        root.addView(topBar, new LinearLayout.LayoutParams(-1, dp(58)));

        View topDivider = new View(this);
        topDivider.setBackgroundColor(COLOR_DIVIDER);
        LinearLayout.LayoutParams topDividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        topDividerParams.topMargin = dp(8);
        root.addView(topDivider, topDividerParams);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        contentParams.topMargin = dp(14);
        root.addView(content, contentParams);

        LinearLayout connection = new LinearLayout(this);
        connection.setOrientation(LinearLayout.VERTICAL);
        connection.setPadding(dp(24), dp(20), dp(24), dp(20));
        connection.setBackground(roundedBackground(COLOR_SURFACE, 12));

        LinearLayout featureHeader = new LinearLayout(this);
        featureHeader.setOrientation(LinearLayout.HORIZONTAL);
        featureHeader.setGravity(Gravity.CENTER_VERTICAL);

        TextView featureIcon = label("互", 17, Color.rgb(7, 24, 15));
        featureIcon.setGravity(Gravity.CENTER);
        featureIcon.setTypeface(null, android.graphics.Typeface.BOLD);
        featureIcon.setBackground(roundedBackground(COLOR_GREEN, 10));
        LinearLayout.LayoutParams featureIconParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        featureIconParams.rightMargin = dp(12);
        featureHeader.addView(featureIcon, featureIconParams);

        LinearLayout featureName = new LinearLayout(this);
        featureName.setOrientation(LinearLayout.VERTICAL);
        TextView featureTitle = label("手机互联", 19, COLOR_TEXT);
        featureTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        featureName.addView(featureTitle, matchWrap());
        TextView featureSubtitle = label("无线投屏 · 触控回传", 10, COLOR_MUTED);
        featureName.addView(featureSubtitle, matchWrap());
        featureHeader.addView(featureName, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout statusPill = new LinearLayout(this);
        statusPill.setOrientation(LinearLayout.HORIZONTAL);
        statusPill.setGravity(Gravity.CENTER_VERTICAL);
        statusPill.setPadding(dp(11), dp(7), dp(11), dp(7));
        statusPill.setBackground(roundedBackground(COLOR_SURFACE_STRONG, 10));
        statusDot = new View(this);
        statusDot.setBackground(roundedBackground(COLOR_MUTED, 8));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotParams.rightMargin = dp(7);
        statusPill.addView(statusDot, dotParams);
        statusModeText = label("待机", 11, COLOR_MUTED);
        statusModeText.setTypeface(null, android.graphics.Typeface.BOLD);
        statusPill.addView(statusModeText, new LinearLayout.LayoutParams(-2, -2));
        featureHeader.addView(statusPill, new LinearLayout.LayoutParams(-2, -2));
        connection.addView(featureHeader, matchWrap());

        stateText = label("等待连接", 34, COLOR_TEXT);
        stateText.setTypeface(null, android.graphics.Typeface.BOLD);
        stateText.setSingleLine(true);
        stateText.setEllipsize(TextUtils.TruncateAt.END);
        stateText.setAutoSizeTextTypeUniformWithConfiguration(
            dp(24),
            dp(34),
            dp(1),
            TypedValue.COMPLEX_UNIT_PX
        );
        LinearLayout.LayoutParams stateParams = matchWrap();
        stateParams.topMargin = dp(20);
        connection.addView(stateText, stateParams);

        detailText = label("无线接收端未运行", 14, COLOR_MUTED);
        detailText.setMaxLines(2);
        detailText.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams detailParams = matchWrap();
        detailParams.topMargin = dp(5);
        connection.addView(detailText, detailParams);

        View connectionSpacer = new View(this);
        connection.addView(connectionSpacer, new LinearLayout.LayoutParams(1, 0, 1f));

        LinearLayout pairing = new LinearLayout(this);
        pairing.setOrientation(LinearLayout.HORIZONTAL);
        pairing.setGravity(Gravity.CENTER_VERTICAL);
        pairing.setPadding(dp(18), dp(12), dp(18), dp(12));
        pairing.setBackground(outlinedBackground(COLOR_SURFACE_STRONG, COLOR_DIVIDER, 10));
        LinearLayout pairingLabel = new LinearLayout(this);
        pairingLabel.setOrientation(LinearLayout.VERTICAL);
        TextView pairingTitle = label("手机配对码", 13, COLOR_TEXT);
        pairingTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        pairingLabel.addView(pairingTitle, matchWrap());
        TextView pairingType = label("连接时在手机端输入", 10, COLOR_MUTED);
        pairingLabel.addView(pairingType, matchWrap());
        pairing.addView(pairingLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        pinText = label("------", 31, COLOR_AMBER);
        pinText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        pinText.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        pairing.addView(pinText, new LinearLayout.LayoutParams(-2, -2));
        connection.addView(pairing, new LinearLayout.LayoutParams(-1, dp(76)));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        startButton = commandButton("开始连接", COLOR_GREEN, Color.rgb(7, 24, 15));
        startButton.setOnClickListener(view -> requestAndStart());
        controls.addView(startButton, new LinearLayout.LayoutParams(0, dp(58), 1f));
        stopButton = commandButton("停止服务", COLOR_SURFACE_STRONG, COLOR_TEXT);
        stopButton.setBackground(outlinedBackground(COLOR_SURFACE_STRONG, COLOR_DIVIDER, 9));
        stopButton.setOnClickListener(view -> stopReceiver());
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(dp(120), dp(58));
        stopParams.leftMargin = dp(12);
        controls.addView(stopButton, stopParams);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(-1, dp(58));
        controlsParams.topMargin = dp(12);
        connection.addView(controls, controlsParams);

        LinearLayout.LayoutParams connectionParams = new LinearLayout.LayoutParams(0, -1, 1.22f);
        connectionParams.rightMargin = dp(14);
        content.addView(connection, connectionParams);

        LinearLayout activity = new LinearLayout(this);
        activity.setOrientation(LinearLayout.VERTICAL);
        activity.setPadding(dp(20), dp(18), dp(20), dp(18));
        activity.setBackground(roundedBackground(COLOR_SURFACE, 12));

        TextView progressTitle = label("连接向导", 17, COLOR_TEXT);
        progressTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        activity.addView(progressTitle, matchWrap());
        TextView progressHint = label("自动完成无线握手与安全认证", 10, COLOR_MUTED);
        LinearLayout.LayoutParams progressHintParams = matchWrap();
        progressHintParams.topMargin = dp(2);
        activity.addView(progressHint, progressHintParams);

        LinearLayout stages = new LinearLayout(this);
        stages.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"网络", "蓝牙", "手机", "认证"};
        for (int index = 0; index < names.length; index++) {
            TextView stage = label(String.format("%02d\n%s", index + 1, names[index]), 12, COLOR_MUTED);
            stage.setGravity(Gravity.CENTER);
            stage.setLineSpacing(0f, 0.94f);
            stage.setTypeface(null, android.graphics.Typeface.BOLD);
            stage.setBackground(outlinedBackground(COLOR_SURFACE_STRONG, COLOR_DIVIDER, 9));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(70), 1f);
            if (index > 0) {
                params.leftMargin = dp(7);
            }
            stages.addView(stage, params);
            stageViews[index] = stage;
        }
        LinearLayout.LayoutParams stagesParams = new LinearLayout.LayoutParams(-1, dp(70));
        stagesParams.topMargin = dp(12);
        activity.addView(stages, stagesParams);

        TextView logTitle = label("最近连接", 14, COLOR_TEXT);
        logTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams logTitleParams = matchWrap();
        logTitleParams.topMargin = dp(18);
        logTitleParams.bottomMargin = dp(8);
        activity.addView(logTitle, logTitleParams);

        logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logScroll.setBackground(outlinedBackground(Color.rgb(15, 18, 17), COLOR_DIVIDER, 9));
        logText = label("尚无连接记录\n\n启动手机互联后，连接状态会显示在这里。", 11, Color.rgb(190, 200, 195));
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        logText.setLineSpacing(dp(2), 1f);
        logText.setPadding(dp(14), dp(12), dp(14), dp(12));
        logScroll.addView(logText, new ScrollView.LayoutParams(-1, -2));
        activity.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        content.addView(activity, new LinearLayout.LayoutParams(0, -1, 0.88f));

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

        Button playbackStop = commandButton("结束投屏", Color.argb(220, 20, 24, 22), Color.WHITE);
        playbackStop.setBackground(outlinedBackground(Color.argb(220, 20, 24, 22), Color.argb(120, 255, 255, 255), 10));
        playbackStop.setOnClickListener(view -> stopReceiver());
        FrameLayout.LayoutParams playbackStopParams = new FrameLayout.LayoutParams(dp(128), dp(50));
        playbackStopParams.gravity = Gravity.TOP | Gravity.END;
        playbackStopParams.topMargin = dp(16);
        playbackStopParams.rightMargin = dp(16);
        playback.addView(playbackStop, playbackStopParams);

        TextView playbackVersion = label("v" + versionName(), 11, Color.LTGRAY);
        playbackVersion.setPadding(dp(8), dp(4), dp(8), dp(4));
        playbackVersion.setBackground(roundedBackground(Color.argb(185, 20, 24, 22), 6));
        FrameLayout.LayoutParams playbackVersionParams = new FrameLayout.LayoutParams(-2, -2);
        playbackVersionParams.gravity = Gravity.TOP | Gravity.END;
        playbackVersionParams.topMargin = dp(72);
        playbackVersionParams.rightMargin = dp(16);
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
        boolean streaming = snapshot.running && snapshot.stage >= 8;
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
        if (decoder != null && playerSessionId != snapshot.sessionId) { stopPlayer(); }
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
        CarLinkService.Snapshot current = CarLinkService.snapshot();
        if (!current.running || current.stage < 8) { return; }
        if (decoder != null || videoView == null || !videoView.getHolder().getSurface().isValid()) {
            return;
        }
        final long token = ++playerGeneration;
        final long sessionId = current.sessionId;
        playerSessionId = sessionId;
        firstFrameReported = false;
        DirectTsVideoDecoder value = new DirectTsVideoDecoder(
            this,
            videoView.getHolder().getSurface(),
            new DirectTsVideoDecoder.Listener() {
            @Override
            public void onDecoderReady(String codecName, boolean lowLatency) {
                reportVideoStatus(token, sessionId,
                    "直通硬件解码器已就绪：" + codecName
                        + (lowLatency ? "（低延迟模式）" : "")
                );
            }

            @Override
            public void onVideoSize(int width, int height) {
                reportVideoStatus(token, sessionId, "识别视频画面：" + width + "x" + height);
            }

            @Override
            public void onFirstFrame() {
                runOnUiThread(() -> {
                    if (playerGeneration == token && !firstFrameReported) {
                        firstFrameReported = true;
                        reportVideoStatus(token, sessionId, "首帧已直通渲染，画面开始显示");
                    }
                });
            }

            @Override
            public void onError(String message) {
                reportVideoStatus(token, sessionId, message);
            }
            }
        );
        decoder = value;
        value.start();
    }

    private void stopPlayer() {
        ++playerGeneration;
        playerSessionId = -1;
        DirectTsVideoDecoder value = decoder;
        decoder = null;
        if (value != null) {
            value.stop();
        }
    }

    private void reportVideoStatus(long token, long sessionId, String message) {
        runOnUiThread(() -> {
            if (token != playerGeneration || decoder == null) { return; }
            startService(
            new Intent(this, CarLinkService.class)
                .setAction(CarLinkService.ACTION_VIDEO_STATUS)
                .putExtra(CarLinkService.EXTRA_VIDEO_STATUS, message)
                .putExtra(CarLinkService.EXTRA_SESSION_ID, sessionId)
            );
        });
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
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(sp));
        view.setTextColor(color);
        view.setLetterSpacing(0f);
        return view;
    }

    private Button commandButton(String text, int backgroundColor, int textColor) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(16));
        button.setTextColor(textColor);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setStateListAnimator(null);
        button.setBackground(roundedBackground(backgroundColor, 9));
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
        drawable.setStroke(Math.max(1, dp(1)), strokeColor);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private float resolveCarUiScale() {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        int longSide = Math.max(width, height);
        int shortSide = Math.min(width, height);
        float viewportScale = Math.min(longSide / 2560f, shortSide / 1440f);
        return Math.max(0.75f, viewportScale * 3f);
    }

    private int dp(int value) {
        return Math.round(value * carUiScale);
    }
}
