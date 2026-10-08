package com.opencarlink.receiver;

import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class WirelessSessionChannels {
    interface Callback {
        void onLog(String message);

        void onState(int stage, String state, String detail);

        void onDisconnected(String reason);
    }

    private static final int CONTROL_PORT = 57219;
    private static final int MEDIA_PORT = 57229;
    private static final int SENSOR_PORT = 57239;
    private static final int CERT_PORT = 57249;
    private static final int RTSP_PORT = 7236;
    private static final int RTP_PORT = 15550;
    private static final int UIBC_PORT = 4321;
    private final IccoaProtocol.Identity identity;
    private final Callback callback;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final ExecutorService uibcWrites = Executors.newSingleThreadExecutor();
    private final Object resourceLock = new Object();
    private final Object controlWriteLock = new Object();
    private final Object rtspWriteLock = new Object();
    private final TransportLiveness liveness = new TransportLiveness();
    private final ScheduledExecutorService lifecycle = Executors.newSingleThreadScheduledExecutor();
    private final List<Runnable> releaseCallbacks = new ArrayList<>();
    private final AtomicInteger controlSequence = new AtomicInteger(1);
    private ScheduledFuture<?> livenessTask;
    private Socket controlSocket;
    private Socket rtspSocket;
    private RtspSinkSession rtspSession;
    private boolean released;
    private final Set<Socket> sockets = new HashSet<>();
    private final Set<ServerSocket> listeners = new HashSet<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean disconnectReported = new AtomicBoolean();
    private final Object uibcQueueLock = new Object();
    private final ArrayDeque<UibcWrite> uibcQueue = new ArrayDeque<>();
    private final TouchInputHub.Sender uibcSender = this::sendUibc;
    private volatile Socket uibcSocket;
    private volatile boolean stopped;
    private byte[] sessionKey = new byte[0];
    private long videoSessionToken = -1L;

    WirelessSessionChannels(IccoaProtocol.Identity identity, Callback callback) {
        this.identity = identity;
        this.callback = callback;
    }

    void start(String phoneIp, byte[] sessionKey) {
        byte[] key;
        synchronized (resourceLock) {
            if (stopped || !started.compareAndSet(false, true)) {
                return;
            }
            key = sessionKey.clone();
            this.sessionKey = key;
        }
        synchronized (resourceLock) {
            if (stopped) { return; }
            livenessTask = lifecycle.scheduleWithFixedDelay(this::confirmLoss,
                250L, 250L, TimeUnit.MILLISECONDS);
        }
        CountDownLatch rtpReady = new CountDownLatch(1);
        submit(workers, () -> runRtp(rtpReady));
        try {
            rtpReady.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return;
        }
        submit(workers, () -> runControl(phoneIp, key));
        submit(workers, () -> runRtsp(phoneIp));
        submit(workers, () -> runUibc(phoneIp));
        submit(uibcWrites, this::runUibcWriter);
        submit(workers, () -> runDrain(phoneIp, MEDIA_PORT, "MEDIA"));
        submit(workers, () -> runDrain(phoneIp, SENSOR_PORT, "SENSOR"));
        submit(workers, () -> runDrain(phoneIp, CERT_PORT, "CERT"));
    }

    void stop() {
        stop(null);
    }

    void stop(Runnable onReleased) {
        Socket[] socketsToClose;
        ServerSocket[] listenersToClose;
        Socket control;
        Socket rtsp;
        RtspSinkSession session;
        byte[] key;
        long token;
        synchronized (resourceLock) {
            if (released) {
                if (onReleased != null) { onReleased.run(); }
                return;
            }
            if (onReleased != null) { releaseCallbacks.add(onReleased); }
            if (stopped) { return; }
            stopped = true;
            liveness.stop();
            if (livenessTask != null) { livenessTask.cancel(false); }
            TouchInputHub.detach(uibcSender);
            uibcSocket = null;
            control = controlSocket;
            rtsp = rtspSocket;
            session = rtspSession;
            key = sessionKey.clone();
            Arrays.fill(sessionKey, (byte) 0);
            sessionKey = new byte[0];
            socketsToClose = sockets.toArray(new Socket[0]);
            sockets.clear();
            listenersToClose = listeners.toArray(new ServerSocket[0]);
            listeners.clear();
            token = videoSessionToken;
            videoSessionToken = -1L;
        }
        synchronized (uibcQueueLock) {
            for (UibcWrite write : uibcQueue) { Arrays.fill(write.payload, (byte) 0); }
            uibcQueue.clear();
            uibcQueueLock.notifyAll();
        }
        for (Socket socket : socketsToClose) {
            if (socket != control && socket != rtsp) { close(socket); }
        }
        for (ServerSocket listener : listenersToClose) { close(listener); }
        if (token >= 0L) { VideoStreamHub.endSession(token); }
        workers.shutdownNow();
        uibcWrites.shutdownNow();
        if (control == null && rtsp == null) {
            Arrays.fill(key, (byte) 0);
            finishRelease(session);
            return;
        }
        // Socket writes have no Java timeout. Closing at the deadline also releases a blocked write.
        lifecycle.schedule(() -> {
            if (control != null) { close(control); }
            if (rtsp != null) { close(rtsp); }
            Arrays.fill(key, (byte) 0);
            finishRelease(session);
        }, 300L, TimeUnit.MILLISECONDS);
        startFarewell("CarLink-Control-Farewell", () -> {
            try {
                if (control != null && !control.isClosed() && key.length > 0) {
                    byte[] message = UCarControlProtocol.encryptMessage(key,
                        UCarControlProtocol.buildSessionDisconnect(controlSequence.getAndIncrement()));
                    try { write(control, controlWriteLock, message); }
                    finally { Arrays.fill(message, (byte) 0); }
                }
            } catch (IOException | GeneralSecurityException | IllegalArgumentException ignored) {
                // A disappearing peer may no longer receive the final notification.
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        });
        startFarewell("CarLink-RTSP-Farewell", () -> {
            try {
                if (rtsp != null && session != null && !rtsp.isClosed()) {
                    byte[] request = session.teardownRequest();
                    if (request.length > 0) { write(rtsp, rtspWriteLock, request); }
                }
            } catch (IOException | IllegalArgumentException ignored) {
                // The hard close deadline still completes local cleanup.
            }
        });
    }

    private static void startFarewell(String name, Runnable action) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        thread.start();
    }

    private void finishRelease(RtspSinkSession session) {
        if (session != null) { session.clear(); }
        List<Runnable> callbacks;
        synchronized (resourceLock) {
            if (released) { return; }
            released = true;
            controlSocket = rtspSocket = null;
            rtspSession = null;
            callbacks = new ArrayList<>(releaseCallbacks);
            releaseCallbacks.clear();
        }
        lifecycle.shutdownNow();
        for (Runnable callback : callbacks) { callback.run(); }
    }

    boolean hasRecentMediaTraffic() {
        return liveness.hasRecentMediaTraffic(nowMs());
    }

    private void runUibc(String phoneIp) {
        Socket connection = connectWithRetry(phoneIp, UIBC_PORT, "UIBC");
        if (connection == null) {
            return;
        }
        byte[] buffer = new byte[1024];
        try {
            synchronized (resourceLock) {
                if (stopped) {
                    return;
                }
                uibcSocket = connection;
                TouchInputHub.attach(uibcSender);
            }
            log("触摸与车机按键控制已就绪");
            while (!stopped) {
                try {
                    if (connection.getInputStream().read(buffer) < 0) {
                        break;
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (IOException ignored) {
        } finally {
            synchronized (resourceLock) {
                TouchInputHub.detach(uibcSender);
                if (uibcSocket == connection) {
                    uibcSocket = null;
                }
            }
            closeRegistered(connection);
        }
    }

    private boolean sendUibc(byte[] payload) {
        synchronized (resourceLock) {
            Socket connection = uibcSocket;
            if (stopped || connection == null || connection.isClosed()) {
                return false;
            }
            synchronized (uibcQueueLock) {
                uibcQueue.addLast(new UibcWrite(
                    payload.clone(),
                    SystemClock.elapsedRealtime()
                ));
                uibcQueueLock.notifyAll();
                return true;
            }
        }
    }

    private void runUibcWriter() {
        prioritizeCurrentThread();
        while (!stopped) {
            UibcWrite write;
            synchronized (uibcQueueLock) {
                while (uibcQueue.isEmpty() && !stopped) {
                    try {
                        uibcQueueLock.wait();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                write = uibcQueue.pollFirst();
            }
            if (write != null) {
                long waitMs = SystemClock.elapsedRealtime() - write.enqueuedAtMs;
                if (waitMs >= 10L) {
                    Log.i(
                        "OpenCarLinkLatency",
                        "uibc queueWait=" + waitMs + "ms"
                    );
                }
                try {
                    writeUibc(write.payload);
                } finally {
                    Arrays.fill(write.payload, (byte) 0);
                }
            }
        }
    }

    private void writeUibc(byte[] payload) {
        Socket connection = uibcSocket;
        if (stopped || connection == null || connection.isClosed()) {
            return;
        }
        try {
            connection.getOutputStream().write(payload);
            connection.getOutputStream().flush();
        } catch (IOException error) {
            synchronized (resourceLock) {
                if (uibcSocket == connection) {
                    uibcSocket = null;
                    TouchInputHub.detach(uibcSender);
                }
            }
            closeRegistered(connection);
        }
    }

    private static final class UibcWrite {
        final byte[] payload;
        final long enqueuedAtMs;

        UibcWrite(byte[] payload, long enqueuedAtMs) {
            this.payload = payload;
            this.enqueuedAtMs = enqueuedAtMs;
        }
    }

    private void runControl(String phoneIp, byte[] sessionKey) {
        Socket connection = connectWithRetry(phoneIp, CONTROL_PORT, "CONTROL");
        if (connection == null) { return; }
        synchronized (resourceLock) {
            if (stopped) { closeRegistered(connection); return; }
            controlSocket = connection;
        }
        IccoaAuthSession.StreamDecoder decoder = new IccoaAuthSession.StreamDecoder();
        byte[] buffer = new byte[64 * 1024];
        long nextHeartbeat = Long.MAX_VALUE;
        int invalidPackets = 0;
        try {
            while (!stopped) {
                long now = nowMs();
                if (now >= nextHeartbeat) {
                    try {
                        byte[] heartbeat = UCarControlProtocol.encryptMessage(sessionKey,
                            UCarControlProtocol.buildHeartbeat(controlSequence.getAndIncrement(),
                                System.currentTimeMillis()));
                        write(connection, controlWriteLock, heartbeat);
                    } catch (GeneralSecurityException | IllegalArgumentException error) {
                        log("CONTROL 心跳编码失败，保留视频连接：" + error.getMessage());
                    }
                    nextHeartbeat = now + 2_000L;
                }
                int count;
                try { count = connection.getInputStream().read(buffer); }
                catch (SocketTimeoutException ignored) { continue; }
                if (count < 0) {
                    suspectLoss("CONTROL 连接已关闭，等待核心通道恢复");
                    break;
                }
                List<byte[]> messages;
                try { messages = decoder.feed(buffer, count); }
                catch (IllegalArgumentException error) {
                    decoder.clear();
                    decoder = new IccoaAuthSession.StreamDecoder();
                    invalidPackets++;
                    if (invalidPackets == 1 || invalidPackets % 32 == 0) {
                        log("忽略损坏的 CONTROL 帧并重置组帧器：" + error.getMessage());
                    }
                    continue;
                }
                for (byte[] encrypted : messages) {
                    if (stopped) { break; }
                    try {
                        byte[] request = encrypted.length > 20
                            ? UCarControlProtocol.decryptMessage(sessionKey, encrypted) : encrypted;
                        if (UCarControlProtocol.isSessionDisconnect(request)) {
                            disconnected("手机主动结束 CONTROL 会话");
                            return;
                        }
                        byte[] response = UCarControlProtocol.buildConfigResponse(request, identity);
                        if (response == null) {
                            IccoaAuthSession.Header header = IccoaAuthSession.parseHeader(request);
                            log("收到 CONTROL 消息：category=" + header.category + " method=" + header.method);
                        } else {
                            write(connection, controlWriteLock,
                                UCarControlProtocol.encryptMessage(sessionKey, response));
                            log("已回复手机 UCar 无线显示配置");
                            if (nextHeartbeat == Long.MAX_VALUE) {
                                nextHeartbeat = nowMs();
                                log("已启动无线 CONTROL 心跳");
                                state(6, "无线配置已接受", phoneIp + " · CONTROL 心跳运行中");
                            }
                        }
                        invalidPackets = 0;
                    } catch (GeneralSecurityException | IllegalArgumentException error) {
                        invalidPackets++;
                        if (invalidPackets == 1 || invalidPackets % 32 == 0) {
                            log("跳过无法解密或解析的 CONTROL 消息，保留投屏：" + error.getMessage());
                        }
                    }
                }
            }
        } catch (IOException error) {
            if (!stopped) { suspectLoss("CONTROL 传输结束：" + error.getMessage()); }
        } finally {
            decoder.clear();
            releaseCore(connection, true);
        }
    }

    private void runRtsp(String phoneIp) {
        Socket connection = connectWithRetry(phoneIp, RTSP_PORT, "RTSP");
        if (connection == null) { return; }
        RtspSinkSession session = new RtspSinkSession();
        synchronized (resourceLock) {
            if (stopped) { closeRegistered(connection); return; }
            rtspSocket = connection;
            rtspSession = session;
        }
        byte[] buffer = new byte[64 * 1024];
        try {
            while (!stopped) {
                int count;
                try { count = connection.getInputStream().read(buffer); }
                catch (SocketTimeoutException ignored) { continue; }
                if (count < 0) {
                    suspectLoss("RTSP 连接已关闭，等待视频通道恢复");
                    break;
                }
                List<RtspSinkSession.Action> actions;
                try { actions = session.feed(buffer, count); }
                catch (IllegalArgumentException error) {
                    suspectLoss("RTSP 消息格式无效：" + error.getMessage());
                    continue;
                }
                if (!actions.isEmpty()) {
                    synchronized (resourceLock) {
                        if (!stopped) { liveness.rtspArrived(); }
                    }
                }
                for (RtspSinkSession.Action action : actions) {
                    if (action.response.length > 0) { write(connection, rtspWriteLock, action.response); }
                    log(action.description);
                    if (action.terminated) {
                        disconnected("手机主动结束 RTSP 会话");
                        return;
                    }
                    if (action.description.startsWith("RTSP PLAY 成功")) {
                        state(7, "投屏信令已建立", phoneIp + " · 等待视频流");
                    }
                }
            }
        } catch (IOException error) {
            if (!stopped) { suspectLoss("RTSP 传输结束：" + error.getMessage()); }
        } finally {
            releaseCore(connection, false);
        }
    }

    private void runRtp(CountDownLatch ready) {
        prioritizeCurrentThread();
        ServerSocket listener = null;
        boolean reported = false;
        long total = 0;
        long nextProgress = nowMs() + 5_000L;
        try {
            listener = new ServerSocket();
            if (!register(listener)) { return; }
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress("0.0.0.0", RTP_PORT));
            listener.setSoTimeout(500);
            log("RTP 视频接收端已监听 TCP " + RTP_PORT);
            ready.countDown();
            while (!stopped) {
                Socket connection;
                try { connection = listener.accept(); }
                catch (SocketTimeoutException ignored) { continue; }
                if (!register(connection)) { return; }
                boolean carriedMedia = false;
                int invalidPackets = 0;
                RtpMpegTsExtractor extractor = new RtpMpegTsExtractor();
                byte[] buffer = new byte[256 * 1024];
                try {
                    connection.setSoTimeout(500);
                    connection.setTcpNoDelay(true);
                    log("手机 RTP TCP 已接入，等待有效视频数据");
                    while (!stopped) {
                        int count;
                        try { count = connection.getInputStream().read(buffer); }
                        catch (SocketTimeoutException ignored) { continue; }
                        if (count < 0) {
                            if (carriedMedia) { log("RTP 视频连接已关闭，等待重连（3 秒宽限）"); }
                            else { log("RTP 空探测连接已关闭，继续等待视频"); }
                            liveness.rtpEnded(carriedMedia, "RTP 视频连接已关闭，等待重连", nowMs());
                            break;
                        }
                        byte[] transportStream;
                        try { transportStream = extractor.feed(buffer, count); }
                        catch (IllegalArgumentException error) {
                            extractor = new RtpMpegTsExtractor();
                            invalidPackets++;
                            if (invalidPackets == 1 || invalidPackets % 32 == 0) {
                                log("忽略损坏的 RTP 包并重新组帧：" + error.getMessage());
                            }
                            if (carriedMedia) { suspectLoss("RTP 视频数据受损，等待有效帧恢复"); }
                            continue;
                        }
                        if (transportStream.length == 0) { continue; }
                        carriedMedia = true;
                        invalidPackets = 0;
                        long token;
                        synchronized (resourceLock) {
                            if (stopped || disconnectReported.get()) { return; }
                            liveness.mediaArrived(nowMs());
                            if (videoSessionToken < 0L) { videoSessionToken = VideoStreamHub.beginSession(); }
                            token = videoSessionToken;
                        }
                        VideoStreamHub.feed(token, transportStream);
                        total += transportStream.length;
                        if (!reported) {
                            reported = true;
                            log("已提取首批 MPEG-TS：" + transportStream.length + " 字节");
                            state(8, "无线视频流已到达", "MPEG-TS 已送入解码器");
                        }
                        long now = nowMs();
                        if (now >= nextProgress) {
                            log("视频流持续接收：" + total + " 字节，RTP 包 " + extractor.packetCount());
                            nextProgress = now + 5_000L;
                        }
                    }
                } catch (IOException error) {
                    if (!stopped) {
                        liveness.rtpEnded(carriedMedia, "RTP 传输结束：" + error.getMessage(), nowMs());
                        if (carriedMedia) { log("RTP 传输结束，等待短暂重连：" + error.getMessage()); }
                    }
                } finally {
                    closeRegistered(connection);
                }
            }
        } catch (IOException error) {
            if (!stopped) { suspectLoss("RTP 监听不可用：" + error.getMessage()); }
        } finally {
            ready.countDown();
            if (listener != null) { closeRegistered(listener); }
        }
    }

    private void runDrain(String phoneIp, int port, String label) {
        Socket connection = connectWithRetry(phoneIp, port, label);
        if (connection == null) {
            return;
        }
        byte[] buffer = new byte[64 * 1024];
        boolean reported = false;
        try {
            while (!stopped) {
                try {
                    int count = connection.getInputStream().read(buffer);
                    if (count < 0) {
                        break;
                    }
                    if (!reported) {
                        reported = true;
                        log(label + " 收到首批数据：" + count + " 字节");
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (IOException error) {
            if (!stopped) {
                log(label + " 通道结束：" + error.getMessage());
            }
        } finally {
            closeRegistered(connection);
        }
    }

    private Socket connectWithRetry(String phoneIp, int port, String label) {
        int attempt = 0;
        while (!stopped) {
            attempt++;
            Socket connection = new Socket();
            if (!register(connection)) {
                return null;
            }
            try {
                connection.connect(new InetSocketAddress(phoneIp, port), 2_000);
                connection.setTcpNoDelay(true);
                connection.setKeepAlive(true);
                connection.setSoTimeout(500);
                synchronized (resourceLock) {
                    if (stopped) {
                        closeRegistered(connection);
                        return null;
                    }
                }
                log(label + " 无线通道已连接：" + phoneIp + ":" + port);
                return connection;
            } catch (IOException error) {
                closeRegistered(connection);
                if (!stopped && attempt == 1) {
                    log("等待手机启动 " + label + " 服务");
                }
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    private boolean register(Socket socket) {
        synchronized (resourceLock) {
            if (!stopped) {
                sockets.add(socket);
                return true;
            }
        }
        close(socket);
        return false;
    }

    private boolean register(ServerSocket listener) {
        synchronized (resourceLock) {
            if (!stopped) {
                listeners.add(listener);
                return true;
            }
        }
        close(listener);
        return false;
    }

    private void closeRegistered(Socket socket) {
        synchronized (resourceLock) {
            sockets.remove(socket);
            close(socket);
        }
    }

    private void closeRegistered(ServerSocket listener) {
        synchronized (resourceLock) {
            listeners.remove(listener);
            close(listener);
        }
    }

    private void submit(ExecutorService executor, Runnable task) {
        synchronized (resourceLock) {
            if (stopped) {
                return;
            }
            try {
                executor.execute(task);
            } catch (RejectedExecutionException ignored) {
                // A concurrent stop has already invalidated this session.
            }
        }
    }

    private void disconnected(String reason) {
        synchronized (resourceLock) {
            if (stopped || !disconnectReported.compareAndSet(false, true)) {
                return;
            }
        }
        stop(() -> callback.onDisconnected(reason));
    }

    private void suspectLoss(String reason) {
        if (!stopped) {
            log(reason + "（3 秒宽限）");
            liveness.suspect(reason, nowMs());
        }
    }

    private void confirmLoss() {
        String reason;
        synchronized (resourceLock) {
            if (stopped) { return; }
            reason = liveness.confirmedLoss(nowMs());
            if (reason == null || !disconnectReported.compareAndSet(false, true)) { return; }
        }
        stop(() -> callback.onDisconnected(reason));
    }

    private void releaseCore(Socket connection, boolean control) {
        synchronized (resourceLock) {
            Socket owned = control ? controlSocket : rtspSocket;
            if (stopped && owned == connection) { return; } // Farewell cleanup owns this socket now.
            if (owned == connection) {
                if (control) { controlSocket = null; }
                else { rtspSocket = null; }
            }
            closeRegistered(connection);
        }
    }

    private static void write(Socket connection, Object lock, byte[] message) throws IOException {
        synchronized (lock) {
            connection.getOutputStream().write(message);
            connection.getOutputStream().flush();
        }
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    private void log(String message) {
        if (!stopped) {
            callback.onLog(message);
        }
    }

    private void state(int stage, String state, String detail) {
        if (!stopped) {
            callback.onState(stage, state, detail);
        }
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void close(ServerSocket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void prioritizeCurrentThread() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        } catch (IllegalArgumentException | SecurityException ignored) {
        }
    }
}
