package com.opencarlink.receiver;

import java.util.ArrayDeque;

final class VideoStreamHub {
    private static final int MAX_BUFFER_BYTES = 4 * 1024 * 1024;
    private static final Object LOCK = new Object();
    private static final ArrayDeque<byte[]> QUEUE = new ArrayDeque<>();
    private static int headOffset;
    private static int bufferedBytes;
    private static boolean ended = true;
    private static long sessionToken;
    private static Reader activeReader;

    static long beginSession() {
        synchronized (LOCK) {
            sessionToken++;
            closeActiveReaderLocked();
            QUEUE.clear();
            headOffset = 0;
            bufferedBytes = 0;
            ended = false;
            LOCK.notifyAll();
            return sessionToken;
        }
    }

    static void feed(long token, byte[] data) {
        if (data.length == 0) {
            return;
        }
        synchronized (LOCK) {
            if (ended || token != sessionToken) {
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

    static void endSession(long token) {
        synchronized (LOCK) {
            if (token == sessionToken) {
                clearSessionLocked();
            }
        }
    }

    static void reset() {
        synchronized (LOCK) {
            sessionToken++;
            clearSessionLocked();
        }
    }

    static long currentSessionToken() {
        synchronized (LOCK) {
            return sessionToken;
        }
    }

    static Reader openReader() {
        synchronized (LOCK) {
            return openReaderLocked(sessionToken);
        }
    }

    static Reader openReader(long token) {
        synchronized (LOCK) {
            return openReaderLocked(token);
        }
    }

    private static Reader openReaderLocked(long token) {
        Reader reader = new Reader(token);
        if (ended || token != sessionToken) {
            reader.closed = true;
        } else {
            closeActiveReaderLocked();
            activeReader = reader;
            LOCK.notifyAll();
        }
        return reader;
    }

    private static void clearSessionLocked() {
        closeActiveReaderLocked();
        QUEUE.clear();
        headOffset = 0;
        bufferedBytes = 0;
        ended = true;
        LOCK.notifyAll();
    }

    private static void closeActiveReaderLocked() {
        if (activeReader != null) {
            activeReader.closed = true;
            activeReader = null;
        }
    }

    static final class Reader {
        private final long token;
        private volatile boolean closed;

        private Reader(long token) {
            this.token = token;
        }

        int read(byte[] target, int offset, int length) throws InterruptedException {
            synchronized (LOCK) {
                while (QUEUE.isEmpty() && !ended && !closed && token == sessionToken) {
                    LOCK.wait(500L);
                }
                if (closed || token != sessionToken || (QUEUE.isEmpty() && ended)) {
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
