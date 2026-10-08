package com.opencarlink.receiver;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.After;
import org.junit.Test;

public final class VideoStreamHubTest {
    @After
    public void clearSession() {
        VideoStreamHub.reset();
    }

    @Test
    public void oldFeedAndCleanupCannotChangeTheNextPhoneStream() throws Exception {
        long old = VideoStreamHub.beginSession();
        VideoStreamHub.feed(old, new byte[]{1, 2});
        long current = VideoStreamHub.beginSession();
        VideoStreamHub.Reader reader = VideoStreamHub.openReader(current);

        VideoStreamHub.feed(old, new byte[]{9, 9});
        VideoStreamHub.feed(current, new byte[]{3, 4});
        VideoStreamHub.endSession(old);

        byte[] output = new byte[2];
        assertEquals(2, reader.read(output, 0, output.length));
        assertArrayEquals(new byte[]{3, 4}, output);
        VideoStreamHub.endSession(current);
        assertEquals(-1, reader.read(output, 0, output.length));
    }

    @Test
    public void lateReaderFromOldDecoderCannotEvictTheNewDecoder() throws Exception {
        long old = VideoStreamHub.beginSession();
        long current = VideoStreamHub.beginSession();
        VideoStreamHub.Reader active = VideoStreamHub.openReader(current);
        VideoStreamHub.Reader stale = VideoStreamHub.openReader(old);
        VideoStreamHub.feed(current, new byte[]{7});

        byte[] output = new byte[1];
        assertEquals(-1, stale.read(output, 0, 1));
        assertEquals(1, active.read(output, 0, 1));
        assertArrayEquals(new byte[]{7}, output);
    }

    @Test
    public void oldReaderCloseCannotDetachTheNewReader() throws Exception {
        long old = VideoStreamHub.beginSession();
        VideoStreamHub.Reader stale = VideoStreamHub.openReader(old);
        long current = VideoStreamHub.beginSession();
        VideoStreamHub.Reader active = VideoStreamHub.openReader(current);
        stale.close();
        VideoStreamHub.feed(current, new byte[]{8});

        byte[] output = new byte[1];
        assertEquals(-1, stale.read(output, 0, 1));
        assertEquals(1, active.read(output, 0, 1));
        assertArrayEquals(new byte[]{8}, output);
    }

    @Test
    public void resetInvalidatesBufferedDataAndThePreviousToken() throws Exception {
        long old = VideoStreamHub.beginSession();
        VideoStreamHub.Reader stale = VideoStreamHub.openReader(old);
        VideoStreamHub.feed(old, new byte[]{1, 2, 3});
        VideoStreamHub.reset();

        assertNotEquals(old, VideoStreamHub.currentSessionToken());
        byte[] output = new byte[3];
        assertEquals(-1, stale.read(output, 0, output.length));
        VideoStreamHub.feed(old, new byte[]{4, 5, 6});
        assertEquals(-1, VideoStreamHub.openReader(old).read(output, 0, output.length));
    }

    @Test
    public void endedSessionCannotBeReopenedByALateDecoder() throws Exception {
        long current = VideoStreamHub.beginSession();
        VideoStreamHub.endSession(current);

        assertEquals(-1, VideoStreamHub.openReader(current).read(new byte[1], 0, 1));
        assertEquals(-1, VideoStreamHub.openReader().read(new byte[1], 0, 1));
    }
}
