package com.opencarlink.receiver;

import java.util.concurrent.atomic.AtomicReference;

final class TouchInputHub {
    interface Sender {
        boolean send(byte[] payload, boolean replaceable);
    }

    private static final AtomicReference<Sender> ACTIVE = new AtomicReference<>();

    static void attach(Sender sender) {
        ACTIVE.set(sender);
    }

    static void detach(Sender sender) {
        ACTIVE.compareAndSet(sender, null);
    }

    static boolean sendTouch(int action, int x, int y) {
        Sender sender = ACTIVE.get();
        return sender != null && sender.send(
            UibcProtocol.touch(action, x, y),
            action == UibcProtocol.ACTION_MOVE
        );
    }

    static boolean sendKey(int keyCode) {
        Sender sender = ACTIVE.get();
        return sender != null && sender.send(UibcProtocol.key(keyCode), false);
    }

    private TouchInputHub() {
    }
}
