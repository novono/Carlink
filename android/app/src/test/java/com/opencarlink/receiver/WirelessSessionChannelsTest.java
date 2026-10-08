package com.opencarlink.receiver;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class WirelessSessionChannelsTest {
    @Test
    public void stopClosesConnectingSocketsAndRejectsLateRegistration() throws Exception {
        WirelessSessionChannels channels = channels(new AtomicInteger());
        Socket connecting = new Socket();
        ServerSocket listening = new ServerSocket();
        try {
            assertTrue(register(channels, Socket.class, connecting));
            assertTrue(register(channels, ServerSocket.class, listening));
            channels.stop();

            assertTrue(connecting.isClosed());
            assertTrue(listening.isClosed());
            Socket lateSocket = new Socket();
            ServerSocket lateListener = new ServerSocket();
            assertFalse(register(channels, Socket.class, lateSocket));
            assertFalse(register(channels, ServerSocket.class, lateListener));
            assertTrue(lateSocket.isClosed());
            assertTrue(lateListener.isClosed());
        } finally {
            channels.stop();
            connecting.close();
            listening.close();
        }
    }

    @Test
    public void stopErasesTheSessionKeyAndRejectsTouchWrites() throws Exception {
        WirelessSessionChannels channels = channels(new AtomicInteger());
        byte[] secret = {1, 2, 3, 4};
        Field key = WirelessSessionChannels.class.getDeclaredField("sessionKey");
        key.setAccessible(true);
        key.set(channels, secret);
        channels.stop();

        assertArrayEquals(new byte[4], secret);
        assertEquals(0, ((byte[]) key.get(channels)).length);
        Method send = WirelessSessionChannels.class.getDeclaredMethod("sendUibc", byte[].class);
        send.setAccessible(true);
        assertFalse((Boolean) send.invoke(channels, (Object) new byte[]{7}));
    }

    @Test
    public void concurrentCriticalChannelClosuresNotifyOnlyOnce() throws Exception {
        AtomicInteger notifications = new AtomicInteger();
        WirelessSessionChannels channels = channels(notifications);
        Method disconnected = WirelessSessionChannels.class.getDeclaredMethod("disconnected", String.class);
        disconnected.setAccessible(true);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable close = () -> {
            try {
                release.await();
                disconnected.invoke(channels, "手机离线");
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        };
        Thread control = new Thread(close);
        Thread rtp = new Thread(close);
        control.start();
        rtp.start();
        release.countDown();
        control.join(2_000L);
        rtp.join(2_000L);
        channels.stop();

        assertFalse(control.isAlive());
        assertFalse(rtp.isAlive());
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        assertEquals(1, notifications.get());
    }

    private static boolean register(WirelessSessionChannels channels, Class<?> type, Object socket)
        throws Exception {
        Method register = WirelessSessionChannels.class.getDeclaredMethod("register", type);
        register.setAccessible(true);
        return (Boolean) register.invoke(channels, socket);
    }

    private static WirelessSessionChannels channels(AtomicInteger notifications) {
        IccoaProtocol.Identity identity = new IccoaProtocol.Identity(new byte[6], new byte[4], new byte[2]);
        return new WirelessSessionChannels(identity, new WirelessSessionChannels.Callback() {
            @Override
            public void onLog(String message) {
            }

            @Override
            public void onState(int stage, String state, String detail) {
            }

            @Override
            public void onDisconnected(String reason) {
                notifications.incrementAndGet();
            }
        });
    }
}
