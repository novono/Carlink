package com.opencarlink.receiver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import android.content.pm.PackageManager;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public final class CarLinkService extends Service implements WirelessCarLinkEngine.Callback {
    public static final String ACTION_START = "com.opencarlink.receiver.START";
    public static final String ACTION_STOP = "com.opencarlink.receiver.STOP";
    public static final String ACTION_UPDATE = "com.opencarlink.receiver.UPDATE";
    public static final String ACTION_VIDEO_STATUS = "com.opencarlink.receiver.VIDEO_STATUS";
    public static final String EXTRA_VIDEO_STATUS = "video_status";
    private static final String CHANNEL_ID = "carlink_receiver";
    private static final int NOTIFICATION_ID = 57209;
    private static final int MAX_LOG_CHARS = 12_000;
    private static final Object SNAPSHOT_LOCK = new Object();
    private static Snapshot current = Snapshot.idle();

    private WirelessCarLinkEngine engine;
    private DiagnosticLog diagnosticLog;

    public static final class Snapshot {
        public final boolean running;
        public final int stage;
        public final String state;
        public final String detail;
        public final String pin;
        public final String log;

        Snapshot(
            boolean running,
            int stage,
            String state,
            String detail,
            String pin,
            String log
        ) {
            this.running = running;
            this.stage = stage;
            this.state = state;
            this.detail = detail;
            this.pin = pin;
            this.log = log;
        }

        static Snapshot idle() {
            return new Snapshot(false, 0, "等待启动", "无线接收端未运行", "------", "");
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
            if (engine != null && message != null && !message.isEmpty()) {
                onLog(message);
            }
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            stopEngine();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification("正在启动无线车机"));
        if (engine == null) {
            diagnosticLog.reset();
            diagnosticLog.append("应用版本：" + versionName());
            update(true, 0, "正在启动", "准备 Wi-Fi Direct", "------", false);
            engine = new WirelessCarLinkEngine(getApplicationContext(), this);
            engine.start();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopEngine();
        super.onDestroy();
    }

    private void stopEngine() {
        WirelessCarLinkEngine value = engine;
        engine = null;
        if (value != null) {
            value.stop();
        }
        synchronized (SNAPSHOT_LOCK) {
            String existingLog = current.log;
            current = new Snapshot(false, 0, "已停止", "无线资源已释放", "------", existingLog);
        }
        sendBroadcast(new Intent(ACTION_UPDATE).setPackage(getPackageName()));
    }

    @Override
    public void onState(int stage, String state, String detail, String pin) {
        diagnosticLog.append("STATE " + stage + "：" + state + "；" + detail);
        update(true, stage, state, detail, pin, false);
    }

    @Override
    public void onLog(String message) {
        diagnosticLog.append("LOG：" + message);
        update(true, snapshot().stage, snapshot().state, snapshot().detail, snapshot().pin, true, message);
    }

    @Override
    public void onFatal(String message) {
        onLog("错误：" + message);
        update(true, snapshot().stage, "启动失败", message, snapshot().pin, false);
        stopForeground(STOP_FOREGROUND_DETACH);
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
        synchronized (SNAPSHOT_LOCK) {
            String log = current.log;
            if (appendLog) {
                String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                log = log + (log.isEmpty() ? "" : "\n") + time + "  " + message;
                if (log.length() > MAX_LOG_CHARS) {
                    log = log.substring(log.length() - MAX_LOG_CHARS);
                }
            }
            next = new Snapshot(running, stage, state, detail, pin, log);
            current = next;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && running) {
            manager.notify(NOTIFICATION_ID, notification(state));
        }
        sendBroadcast(new Intent(ACTION_UPDATE).setPackage(getPackageName()));
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "CarLink receiver",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Keeps the wireless vehicle receiver active");
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
            .setContentTitle("OpenCarLink Receiver")
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
