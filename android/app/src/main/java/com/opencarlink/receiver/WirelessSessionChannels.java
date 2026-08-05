package com.opencarlink.receiver;

import android.os.SystemClock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class WirelessSessionChannels {
    interface Callback {
        void onLog(String message);

        void onState(int stage, String state, String detail);
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
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Set<ServerSocket> listeners = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean started = new AtomicBoolean();
    private final Object uibcQueueLock = new Object();
    private final ArrayDeque<UibcWrite> uibcQueue = new ArrayDeque<>();
    private final TouchInputHub.Sender uibcSender = this::sendUibc;
    private volatile Socket uibcSocket;
    private volatile boolean stopped;

    WirelessSessionChannels(IccoaProtocol.Identity identity, java.io.File captureDirectory, Callback callback) {
        this.identity = identity;
        this.callback = callback;
    }

    void start(String phoneIp, byte[] sessionKey) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        byte[] key = sessionKey.clone();
        CountDownLatch rtpReady = new CountDownLatch(1);
        workers.execute(() -> runRtp(rtpReady));
        try {
            rtpReady.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        workers.execute(() -> runControl(phoneIp, key));
        workers.execute(() -> runRtsp(phoneIp));
        workers.execute(() -> runUibc(phoneIp));
        uibcWrites.execute(this::runUibcWriter);
        workers.execute(() -> runDrain(phoneIp, MEDIA_PORT, "MEDIA"));
        workers.execute(() -> runDrain(phoneIp, SENSOR_PORT, "SENSOR"));
        workers.execute(() -> runDrain(phoneIp, CERT_PORT, "CERT"));
    }

    void stop() {
        stopped = true;
        TouchInputHub.detach(uibcSender);
        uibcSocket = null;
        synchronized (uibcQueueLock) {
            uibcQueue.clear();
            uibcQueueLock.notifyAll();
        }
        for (Socket socket : sockets) {
            close(socket);
        }
        for (ServerSocket listener : listeners) {
            close(listener);
        }
        workers.shutdownNow();
        uibcWrites.shutdownNow();
    }

    private void runUibc(String phoneIp) {
        Socket connection = connectWithRetry(phoneIp, UIBC_PORT, "UIBC");
        if (connection == null) {
            return;
        }
        uibcSocket = connection;
        TouchInputHub.attach(uibcSender);
        callback.onLog("触摸与车机按键控制已就绪");
        byte[] buffer = new byte[1024];
        try {
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
            TouchInputHub.detach(uibcSender);
            if (uibcSocket == connection) {
                uibcSocket = null;
            }
            closeRegistered(connection);
        }
    }

    private boolean sendUibc(byte[] payload, boolean replaceable) {
        Socket connection = uibcSocket;
        if (stopped || connection == null || connection.isClosed()) {
            return false;
        }
        synchronized (uibcQueueLock) {
            UibcWrite last = uibcQueue.peekLast();
            if (replaceable && last != null && last.replaceable) {
                uibcQueue.removeLast();
            }
            uibcQueue.addLast(new UibcWrite(payload.clone(), replaceable));
            uibcQueueLock.notifyAll();
            return true;
        }
    }

    private void runUibcWriter() {
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
                writeUibc(write.payload);
            }
        }
    }

    private void writeUibc(byte[] payload) {
        Socket connection = uibcSocket;
        if (connection == null || connection.isClosed()) {
            return;
        }
        try {
            connection.getOutputStream().write(payload);
            connection.getOutputStream().flush();
        } catch (IOException error) {
            uibcSocket = null;
        }
    }

    private static final class UibcWrite {
        final byte[] payload;
        final boolean replaceable;

        UibcWrite(byte[] payload, boolean replaceable) {
            this.payload = payload;
            this.replaceable = replaceable;
        }
    }

    private void runControl(String phoneIp, byte[] sessionKey) {
        Socket connection = connectWithRetry(phoneIp, CONTROL_PORT, "CONTROL");
        if (connection == null) {
            return;
        }
        IccoaAuthSession.StreamDecoder decoder = new IccoaAuthSession.StreamDecoder();
        byte[] buffer = new byte[64 * 1024];
        int heartbeatSequence = 1;
        long nextHeartbeat = Long.MAX_VALUE;
        try {
            while (!stopped) {
                long now = SystemClock.elapsedRealtime();
                if (now >= nextHeartbeat) {
                    byte[] heartbeat = UCarControlProtocol.encryptMessage(
                        sessionKey,
                        UCarControlProtocol.buildHeartbeat(
                            heartbeatSequence++,
                            System.currentTimeMillis()
                        )
                    );
                    connection.getOutputStream().write(heartbeat);
                    connection.getOutputStream().flush();
                    nextHeartbeat = now + 2_000L;
                }
                try {
                    int count = connection.getInputStream().read(buffer);
                    if (count < 0) {
                        break;
                    }
                    for (byte[] encrypted : decoder.feed(buffer, count)) {
                        byte[] request = encrypted.length > 20
                            ? UCarControlProtocol.decryptMessage(sessionKey, encrypted)
                            : encrypted;
                        byte[] response = UCarControlProtocol.buildConfigResponse(request, identity);
                        if (response == null) {
                            IccoaAuthSession.Header header = IccoaAuthSession.parseHeader(request);
                            callback.onLog(
                                "收到 CONTROL 消息：category=" + header.category
                                    + " method=" + header.method
                            );
                            continue;
                        }
                        byte[] encryptedResponse = UCarControlProtocol.encryptMessage(sessionKey, response);
                        connection.getOutputStream().write(encryptedResponse);
                        connection.getOutputStream().flush();
                        callback.onLog("已回复手机 UCar 无线显示配置");
                        if (nextHeartbeat == Long.MAX_VALUE) {
                            nextHeartbeat = SystemClock.elapsedRealtime();
                            callback.onLog("已启动无线 CONTROL 心跳");
                            callback.onState(6, "无线配置已接受", phoneIp + " · CONTROL 心跳运行中");
                        }
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (IOException | GeneralSecurityException | IllegalArgumentException error) {
            if (!stopped) {
                callback.onLog("CONTROL 通道结束：" + error.getMessage());
            }
        } finally {
            closeRegistered(connection);
        }
    }

    private void runRtsp(String phoneIp) {
        Socket connection = connectWithRetry(phoneIp, RTSP_PORT, "RTSP");
        if (connection == null) {
            return;
        }
        RtspSinkSession session = new RtspSinkSession();
        byte[] buffer = new byte[64 * 1024];
        try {
            while (!stopped) {
                try {
                    int count = connection.getInputStream().read(buffer);
                    if (count < 0) {
                        break;
                    }
                    for (RtspSinkSession.Action action : session.feed(buffer, count)) {
                        if (action.response.length > 0) {
                            connection.getOutputStream().write(action.response);
                            connection.getOutputStream().flush();
                        }
                        callback.onLog(action.description);
                        if (action.description.startsWith("RTSP PLAY 成功")) {
                            callback.onState(7, "投屏信令已建立", phoneIp + " · 等待视频流");
                        }
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (IOException | IllegalArgumentException error) {
            if (!stopped) {
                callback.onLog("RTSP 通道结束：" + error.getMessage());
            }
        } finally {
            closeRegistered(connection);
        }
    }

    private void runRtp(CountDownLatch ready) {
        ServerSocket listener = null;
        Socket connection = null;
        try {
            listener = new ServerSocket();
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress("0.0.0.0", RTP_PORT));
            listener.setSoTimeout(500);
            listeners.add(listener);
            callback.onLog("RTP 视频接收端已监听 TCP " + RTP_PORT);
            ready.countDown();
            while (!stopped && connection == null) {
                try {
                    connection = listener.accept();
                } catch (SocketTimeoutException ignored) {
                }
            }
            if (connection == null) {
                return;
            }
            register(connection);
            connection.setSoTimeout(500);
            connection.setTcpNoDelay(true);
            callback.onLog("手机 RTP 视频流已接入：" + connection.getInetAddress().getHostAddress());
            VideoStreamHub.beginSession();
            RtpMpegTsExtractor extractor = new RtpMpegTsExtractor();
            byte[] buffer = new byte[256 * 1024];
            long total = 0;
            boolean reported = false;
            long nextProgress = SystemClock.elapsedRealtime() + 5_000L;
            while (!stopped) {
                try {
                    int count = connection.getInputStream().read(buffer);
                    if (count < 0) {
                        break;
                    }
                    byte[] transportStream = extractor.feed(buffer, count);
                    VideoStreamHub.feed(transportStream);
                    total += transportStream.length;
                    if (!reported && transportStream.length > 0) {
                        reported = true;
                        callback.onLog(
                            "已提取首批 MPEG-TS：" + transportStream.length
                                + " 字节，RTP 包 " + extractor.packetCount()
                        );
                        callback.onState(8, "无线视频流已到达", "MPEG-TS 已送入解码器");
                    }
                    long now = SystemClock.elapsedRealtime();
                    if (now >= nextProgress) {
                        callback.onLog(
                            "视频流持续接收：" + total + " 字节，RTP 包 " + extractor.packetCount()
                        );
                        nextProgress = now + 5_000L;
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
            callback.onLog("RTP 视频通道关闭，累计 MPEG-TS " + total + " 字节");
        } catch (IOException | IllegalArgumentException error) {
            ready.countDown();
            if (!stopped) {
                callback.onLog("RTP 视频通道结束：" + error.getMessage());
            }
        } finally {
            VideoStreamHub.endSession();
            ready.countDown();
            if (connection != null) {
                closeRegistered(connection);
            }
            if (listener != null) {
                listeners.remove(listener);
                close(listener);
            }
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
                        callback.onLog(label + " 收到首批数据：" + count + " 字节");
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (IOException error) {
            if (!stopped) {
                callback.onLog(label + " 通道结束：" + error.getMessage());
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
            try {
                connection.connect(new InetSocketAddress(phoneIp, port), 2_000);
                connection.setTcpNoDelay(true);
                connection.setKeepAlive(true);
                connection.setSoTimeout(500);
                register(connection);
                callback.onLog(label + " 无线通道已连接：" + phoneIp + ":" + port);
                return connection;
            } catch (IOException error) {
                close(connection);
                if (attempt == 1) {
                    callback.onLog("等待手机启动 " + label + " 服务");
                }
                SystemClock.sleep(500L);
            }
        }
        return null;
    }

    private void register(Socket socket) {
        sockets.add(socket);
    }

    private void closeRegistered(Socket socket) {
        sockets.remove(socket);
        close(socket);
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
}
