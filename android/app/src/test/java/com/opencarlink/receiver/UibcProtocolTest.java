package com.opencarlink.receiver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public final class UibcProtocolTest {
    @Test
    public void touchBuildsPlainJsonWithoutChangingCoordinates() {
        assertEquals(
            "{\"type\":1,\"action\":0,\"width\":1280,\"height\":720,"
                + "\"count\":1,\"trackID0\":0,\"x0\":2000,\"y0\":-4}",
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
    public void multiTouchIncludesStablePointerIdsAndCoordinates() {
        assertEquals(
            "{\"type\":1,\"action\":261,\"width\":1280,\"height\":720,"
                + "\"count\":2,\"trackID0\":4,\"x0\":100,\"y0\":200,"
                + "\"trackID1\":9,\"x1\":2000,\"y1\":-1}",
            new String(
                UibcProtocol.touch(
                    261,
                    new int[]{4, 9},
                    new int[]{100, 2000},
                    new int[]{200, -1}
                ),
                StandardCharsets.UTF_8
            )
        );
    }

    @Test
    public void touchPassesCancelThrough() {
        assertEquals(
            "{\"type\":1,\"action\":3,\"width\":1280,\"height\":720,"
                + "\"count\":1,\"trackID0\":0,\"x0\":1,\"y0\":2}",
            new String(
                UibcProtocol.touch(UibcProtocol.ACTION_CANCEL, 1, 2),
                StandardCharsets.UTF_8
            )
        );
    }

    @Test
    public void touchRejectsUnknownAction() {
        assertThrows(IllegalArgumentException.class, () -> UibcProtocol.touch(4, 1, 2));
    }
}
