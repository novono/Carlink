package com.opencarlink.receiver;

/** Confirms a transport loss only after giving healthy core traffic time to recover it. */
final class TransportLiveness {
    static final long GRACE_MS = 3_000L;
    private long lastMediaMs = -1L;
    private long deadlineMs = Long.MAX_VALUE;
    private String pendingReason;
    private boolean stopped;

    synchronized void suspect(String reason, long nowMs) {
        if (!stopped && pendingReason == null) {
            pendingReason = reason;
            deadlineMs = nowMs + GRACE_MS;
        }
    }

    synchronized void mediaArrived(long nowMs) {
        if (!stopped) {
            lastMediaMs = nowMs;
            recover();
        }
    }

    synchronized void rtpEnded(boolean carriedMedia, String reason, long nowMs) {
        if (carriedMedia) { suspect(reason, nowMs); }
    }

    synchronized void rtspArrived() {
        if (!stopped) {
            recover();
        }
    }

    synchronized boolean hasRecentMediaTraffic(long nowMs) {
        return !stopped && lastMediaMs >= 0L
            && nowMs >= lastMediaMs && nowMs - lastMediaMs <= GRACE_MS;
    }

    synchronized String confirmedLoss(long nowMs) {
        if (stopped || pendingReason == null || nowMs < deadlineMs) {
            return null;
        }
        String reason = pendingReason;
        recover();
        return reason;
    }

    synchronized void stop() {
        stopped = true;
        lastMediaMs = -1L;
        recover();
    }

    private void recover() {
        pendingReason = null;
        deadlineMs = Long.MAX_VALUE;
    }
}
