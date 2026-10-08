package com.opencarlink.receiver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TransportLivenessTest {
    @Test
    public void controlClosureCannotEndAContinuingVideoStream() {
        TransportLiveness guard = new TransportLiveness();
        guard.mediaArrived(100L);
        guard.suspect("CONTROL EOF", 200L);
        guard.mediaArrived(500L);
        guard.mediaArrived(2_000L);
        guard.mediaArrived(4_000L);

        assertNull(guard.confirmedLoss(5_000L));
        assertTrue(guard.hasRecentMediaTraffic(5_000L));
    }

    @Test
    public void disappearingCoreChannelsAreConfirmedAfterBoundedGrace() {
        TransportLiveness guard = new TransportLiveness();
        guard.mediaArrived(100L);
        guard.suspect("RTP EOF", 200L);

        assertNull(guard.confirmedLoss(3_199L));
        assertEquals("RTP EOF", guard.confirmedLoss(3_200L));
        assertNull(guard.confirmedLoss(4_000L));
    }

    @Test
    public void repeatedErrorsCannotExtendTheDisconnectDeadline() {
        TransportLiveness guard = new TransportLiveness();
        guard.suspect("RTP corrupted", 100L);
        guard.suspect("CONTROL EOF", 2_000L);
        guard.suspect("RTP corrupted again", 3_099L);

        assertEquals("RTP corrupted", guard.confirmedLoss(3_100L));
    }

    @Test
    public void briefRtpReconnectRecoversThePendingDisconnect() {
        TransportLiveness guard = new TransportLiveness();
        guard.mediaArrived(100L);
        guard.suspect("RTP EOF", 200L);
        guard.mediaArrived(3_000L);

        assertNull(guard.confirmedLoss(3_201L));
        assertNull(guard.confirmedLoss(10_000L));
    }

    @Test
    public void emptyTcpProbeCannotCreateAPhoneDisconnect() {
        TransportLiveness guard = new TransportLiveness();
        guard.rtpEnded(false, "empty probe", 100L);

        assertNull(guard.confirmedLoss(10_000L));
        guard.mediaArrived(11_000L);
        guard.rtpEnded(true, "real RTP EOF", 11_100L);
        assertEquals("real RTP EOF", guard.confirmedLoss(14_100L));
    }

    @Test
    public void validRtspKeepaliveConfirmsThePhoneStillParticipates() {
        TransportLiveness guard = new TransportLiveness();
        guard.suspect("CONTROL EOF", 100L);
        guard.rtspArrived();

        assertNull(guard.confirmedLoss(3_100L));
        guard.suspect("RTSP EOF", 4_000L);
        assertEquals("RTSP EOF", guard.confirmedLoss(7_000L));
    }

    @Test
    public void recentMediaEvidenceExpiresAndCannotSurviveStop() {
        TransportLiveness guard = new TransportLiveness();
        guard.mediaArrived(100L);
        assertTrue(guard.hasRecentMediaTraffic(3_100L));
        assertFalse(guard.hasRecentMediaTraffic(3_101L));
        guard.stop();
        guard.mediaArrived(4_000L);
        guard.suspect("late callback", 4_000L);

        assertFalse(guard.hasRecentMediaTraffic(4_000L));
        assertNull(guard.confirmedLoss(8_000L));
    }
}
