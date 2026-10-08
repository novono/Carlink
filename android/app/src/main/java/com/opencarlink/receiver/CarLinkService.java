package com.opencarlink.receiver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import android.content.pm.PackageManager;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public final class CarLinkService extends Service {
    public static final String ACTION_START = "com.opencarlink.receiver.START";
    public static final String ACTION_STOP = "com.opencarlink.receiver.STOP";
    public static final String ACTION_UPDATE = "com.opencarlink.receiver.UPDATE";
    public static final String ACTION_VIDEO_STATUS = "com.opencarlink.receiver.VIDEO_STATUS";
    public static final String EXTRA_VIDEO_STATUS = "video_status";
    public static final String EXTRA_SESSION_ID = "session_id";
    private static final String CHANNEL_ID = "carlink_receiver";
    private static final int NOTIFICATION_ID = 57209;
    private static final int MAX_LOG_CHARS = 12_000;
    private static final Object SNAPSHOT_LOCK = new Object();
    private static Snapshot current = Snapshot.idle();
    private static final AtomicLong NEXT_GENERATION = new AtomicLong();

    private WirelessCarLinkEngine engine;
    private DiagnosticLog diagnosticLog;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long generation;
    private boolean wantRunning;
    private boolean releasing;
    private boolean destroyed;

    public static final class Snapshot {
        public final long sessionId;
        public final boolean running;
        public final int stage;
        public final String state;
        public final String detail;
        public final String pin;
        public final String log;

        Snapshot(
            long sessionId,
            boolean running,
            int stage,
            String state,
            String detail,
            String pin,
            String log
        ) {
            this.sessionId = sessionId;
            this.running = running;
            this.stage = stage;
            this.state = state;
            this.detail = detail;
            this.pin = pin;
            this.log = log;
        }

        static Snapshot idle() {
            return new Snapshot(0, false, 0, "等待启动", "无线接收端未运行", "------", "");
        }
    }

    public static Snapshot snapshot() {
        synchronized (SNAPSHOT_LOCK) {
            return current;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        diagnosticLog = new DiagnosticLog(getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_VIDEO_STATUS.equals(action)) {
            String message = intent.getStringExtra(EXTRA_VIDEO_STATUS);
            if (engine != null && intent.getLongExtra(EXTRA_SESSION_ID, -1) == generation
                && message != null && !message.isEmpty()) {
                log(message);
            }
            if (!wantRunning && !releasing) { stopSelf(); }
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            Log.i("OpenCarLinkState", "manual stop requested");
            wantRunning = false;
            closeEngine("已停止", "设备记忆已清除，无线资源已释放");
            return START_NOT_STICKY;
        }
        Log.i("OpenCarLinkState", "start requested");
        wantRunning = true;
        startForeground(NOTIFICATION_ID, notification("正在启动无线车机"));
        if (engine == null && !releasing) { startEngine(); }
        return START_NOT_STICKY;
    }

    private void startEngine() {
        if (destroyed || !wantRunning || releasing || engine != null) { return; }
        long token = NEXT_GENERATION.incrementAndGet();
        generation = token;
        clearSnapshot(true, "正在启动", "全新配对 · 不保存设备记忆");
        WirelessCarLinkEngine.Callback scopedCallback = new WirelessCarLinkEngine.Callback() {
            private final SessionProgress progress = new SessionProgress();
            private void deliver(Runnable action) {
                main.post(() -> {
                    if (!destroyed && engine != null && generation == token) { action.run(); }
                });
            }

            @Override public void onState(int stage, String state, String detail, String pin) {
                deliver(() -> {
                    if (!progress.advance(stage)) {
                        Log.i("OpenCarLinkState", "ignored late stage=" + stage
                            + " current=" + progress.stage());
                        return;
                    }
                    Log.i("OpenCarLinkState", "stage=" + stage + " state=" + state);
                    update(true, stage, state, detail, pin, false);
                });
            }
            @Override public void onLog(String message) { deliver(() -> log(message)); }
            @Override public void onFatal(String message) {
                deliver(() -> {
                    Log.i("OpenCarLinkState", "engine fatal");
                    wantRunning = false;
                    closeEngine("启动失败", message);
                });
            }
            @Override public void onDisconnected(String reason) {
                deliver(() -> {
                    String source = reason.startsWith("CONTROL") ? "CONTROL"
                        : reason.startsWith("RTP") ? "RTP"
                        : reason.startsWith("RTSP") ? "RTSP" : "WiFi/session";
                    Log.i("OpenCarLinkState", "disconnect source=" + source);
                    closeEngine("正在清理连接", "结束上一场投屏并清空设备记忆");
                });
            }
        };
        engine = new WirelessCarLinkEngine(getApplicationContext(), scopedCallback);
        engine.start();
    }

    private void closeEngine(String state, String detail) {
        generation = NEXT_GENERATION.incrementAndGet(); // Invalidate callbacks before closing any old resource.
        WirelessCarLinkEngine value = engine;
        engine = null;
        VideoStreamHub.reset();
        diagnosticLog.reset();
        clearSnapshot(wantRunning, state, detail);
        if (releasing) { return; }
        releasing = true;
        Consumer<Boolean> released = cleared -> main.post(() -> {
            releasing = false;
            if (destroyed) { return; }
            if (!cleared) {
                wantRunning = false;
                clearSnapshot(false, "清理失败", "Wi-Fi Direct 群组未释放，请重试");
            }
            if (wantRunning) {
                startEngine(); // Old removeGroup has completed before creating the next GO.
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        });
        if (value != null) { value.stop(released); } else { released.accept(true); }
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        wantRunning = false;
        if (engine != null || snapshot().running) {
            closeEngine("已停止", "设备记忆已清除，无线资源已释放");
        }
        super.onDestroy();
    }

    private void clearSnapshot(boolean running, String state, String detail) {
        synchronized (SNAPSHOT_LOCK) {
            current = new Snapshot(generation, running, 0, state, detail, "------", "");
        }
        if (running) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(state));
        }
        sendBroadcast(new Intent(ACTION_UPDATE).setPackage(getPackageName()));
    }

    private void log(String message) {
        Snapshot previous = snapshot();
        update(true, previous.stage, previous.state, previous.detail, previous.pin, true, message);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException error) {
            return "unknown";
        }
    }

    private void update(
        boolean running,
        int stage,
        String state,
        String detail,
        String pin,
        boolean appendLog
    ) {
        update(running, stage, state, detail, pin, appendLog, "");
    }

    private void update(
        boolean running,
        int stage,
        String state,
        String detail,
        String pin,
        boolean appendLog,
        String message
    ) {
        Snapshot next;
        boolean notificationChanged;
        synchronized (SNAPSHOT_LOCK) {
            notificationChanged = current.running != running
                || current.stage != stage
                || !current.state.equals(state);
            String log = current.log;
            if (appendLog) {
                String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                log = log + (log.isEmpty() ? "" : "\n") + time + "  " + message;
                if (log.length() > MAX_LOG_CHARS) {
                    log = log.substring(log.length() - MAX_LOG_CHARS);
                }
            }
            next = new Snapshot(generation, running, stage, state, detail, pin, log);
            current = next;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && running && notificationChanged) {
            manager.notify(NOTIFICATION_ID, notification(state));
        }
        sendBroadcast(new Intent(ACTION_UPDATE).setPackage(getPackageName()));
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "OpenCarLink 车机助手",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("保持无线车机助手连接");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification notification(String state) {
        PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Intent stopIntent = new Intent(this, CarLinkService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("OpenCarLink 车机助手")
            .setContentText(state)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "停止", stop).build())
            .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
