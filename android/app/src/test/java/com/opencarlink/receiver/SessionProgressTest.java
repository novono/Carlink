package com.opencarlink.receiver;

import static org.junit.Assert.*;
import org.junit.Test;

public final class SessionProgressTest {
    @Test public void rtpBeforeRtspPlayResponseMustKeepThePlayerRunning() {
        SessionProgress progress = new SessionProgress();
        progress.advance(5); // AUTH confirmed.
        assertTrue(progress.advance(8)); // First TS packet arrives before PLAY response.
        assertTrue(progress.streaming());
        assertFalse(progress.advance(7)); // Late RTSP PLAY callback.
        assertEquals(8, progress.stage());
        assertTrue(progress.streaming());
    }

    @Test public void lateBootstrapCallbacksCannotHideAnActiveProjection() {
        SessionProgress progress = new SessionProgress();
        progress.advance(8);
        assertFalse(progress.advance(2)); // Advertising success callback.
        assertFalse(progress.advance(3)); // Client Info callback.
        assertFalse(progress.advance(4)); // AUTH progress callback.
        assertTrue(progress.advance(8));
        assertTrue(progress.streaming());
    }

    @Test public void theNextPhoneStartsWithFreshProgressAfterSessionReset() {
        SessionProgress first = new SessionProgress();
        first.advance(8);
        SessionProgress second = new SessionProgress();
        assertEquals(0, second.stage());
        assertFalse(second.streaming());
        assertTrue(second.advance(2));
        assertTrue(second.advance(5));
        assertTrue(second.advance(7));
        assertFalse(second.streaming());
        assertTrue(second.advance(8));
        assertTrue(second.streaming());
    }
}
