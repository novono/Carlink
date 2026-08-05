package com.opencarlink.receiver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public final class UibcProtocolTest {
    @Test
    public void touchBuildsPlainJsonAndClampsCoordinates() {
        assertEquals(
            "{\"type\":1,\"action\":0,\"width\":1280,\"height\":720,"
                + "\"count\":1,\"trackID0\":0,\"x0\":1279,\"y0\":0}",
            new String(
                UibcProtocol.touch(UibcProtocol.ACTION_DOWN, 2000, -4),
                StandardCharsets.UTF_8
            )
        );
    }

    @Test
    public void keyMatchesColorOsLayout() {
        assertEquals(
            "{\"type\":2,\"action\":2,\"keycode\":4,\"metaState\":0}",
            new String(UibcProtocol.key(UibcProtocol.KEY_CODE_BACK), StandardCharsets.UTF_8)
        );
    }

    @Test
    public void touchRejectsUnknownAction() {
        assertThrows(IllegalArgumentException.class, () -> UibcProtocol.touch(3, 1, 2));
    }
}
