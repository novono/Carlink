package com.opencarlink.receiver;

import java.util.ArrayDeque;

final class VideoStreamHub {
    private static final int MAX_BUFFER_BYTES = 4 * 1024 * 1024;
    private static final Object LOCK = new Object();
    private static final ArrayDeque<byte[]> QUEUE = new ArrayDeque<>();
    private static int headOffset;
    private static int bufferedBytes;
    private static boolean ended;
    private static Reader activeReader;

    static void beginSession() {
        synchronized (LOCK) {
            closeActiveReaderLocked();
            QUEUE.clear();
            headOffset = 0;
            bufferedBytes = 0;
            ended = false;
            LOCK.notifyAll();
        }
    }

    static void feed(byte[] data) {
        if (data.length == 0) {
            return;
        }
        synchronized (LOCK) {
            if (ended) {
                return;
            }
            QUEUE.addLast(data.clone());
            bufferedBytes += data.length;
            while (bufferedBytes > MAX_BUFFER_BYTES && QUEUE.size() > 1) {
                byte[] dropped = QUEUE.removeFirst();
                bufferedBytes -= dropped.length - headOffset;
                headOffset = 0;
            }
            LOCK.notifyAll();
        }
    }

    static void endSession() {
        synchronized (LOCK) {
            closeActiveReaderLocked();
            QUEUE.clear();
            headOffset = 0;
            bufferedBytes = 0;
            ended = true;
            LOCK.notifyAll();
        }
    }

    static void reset() {
        endSession();
    }

    static Reader openReader() {
        synchronized (LOCK) {
            closeActiveReaderLocked();
            activeReader = new Reader();
            LOCK.notifyAll();
            return activeReader;
        }
    }

    private static void closeActiveReaderLocked() {
        if (activeReader != null) {
            activeReader.closed = true;
            activeReader = null;
        }
    }

    static final class Reader {
        private volatile boolean closed;

        int read(byte[] target, int offset, int length) throws InterruptedException {
            synchronized (LOCK) {
                while (QUEUE.isEmpty() && !ended && !closed) {
                    LOCK.wait(500L);
                }
                if (closed || (QUEUE.isEmpty() && ended)) {
                    return -1;
                }
                byte[] head = QUEUE.peekFirst();
                if (head == null) {
                    return 0;
                }
                int count = Math.min(length, head.length - headOffset);
                System.arraycopy(head, headOffset, target, offset, count);
                headOffset += count;
                bufferedBytes -= count;
                if (headOffset == head.length) {
                    QUEUE.removeFirst();
                    headOffset = 0;
                }
                return count;
            }
        }

        void close() {
            synchronized (LOCK) {
                closed = true;
                if (activeReader == this) {
                    activeReader = null;
                }
                LOCK.notifyAll();
            }
        }
    }

    private VideoStreamHub() {
    }
}
