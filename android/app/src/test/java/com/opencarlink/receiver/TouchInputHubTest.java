package com.opencarlink.receiver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class TouchInputHubTest {
    @Test
    public void forwardsEveryMoveWithoutCoalescing() {
        List<byte[]> payloads = new ArrayList<>();
        TouchInputHub.Sender sender = payload -> {
            payloads.add(payload);
            return true;
        };
        TouchInputHub.attach(sender);
        try {
            assertTrue(TouchInputHub.sendTouch(UibcProtocol.ACTION_MOVE, 100, 100));
            assertTrue(TouchInputHub.sendTouch(UibcProtocol.ACTION_MOVE, 101, 100));
            assertTrue(TouchInputHub.sendTouch(UibcProtocol.ACTION_MOVE, 102, 100));
        } finally {
            TouchInputHub.detach(sender);
        }

        assertEquals(3, payloads.size());
    }
}
