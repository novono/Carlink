package com.opencarlink.receiver;

import java.util.concurrent.atomic.AtomicReference;

final class TouchInputHub {
    interface Sender {
        boolean send(byte[] payload);
    }

    private static final AtomicReference<Sender> ACTIVE = new AtomicReference<>();

    static void attach(Sender sender) {
        ACTIVE.set(sender);
    }

    static void detach(Sender sender) {
        ACTIVE.compareAndSet(sender, null);
    }

    static boolean sendTouch(int action, int x, int y) {
        return sendTouch(action, new int[]{0}, new int[]{x}, new int[]{y});
    }

    static boolean sendTouch(int action, int[] trackIds, int[] xs, int[] ys) {
        Sender sender = ACTIVE.get();
        return sender != null && sender.send(UibcProtocol.touch(action, trackIds, xs, ys));
    }

    static boolean sendKey(int keyCode) {
        Sender sender = ACTIVE.get();
        return sender != null && sender.send(UibcProtocol.key(keyCode));
    }

    private TouchInputHub() {
    }
}
