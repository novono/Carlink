package com.opencarlink.receiver;

import java.nio.charset.StandardCharsets;

final class UibcProtocol {
    static final int ACTION_DOWN = 0;
    static final int ACTION_UP = 1;
    static final int ACTION_MOVE = 2;
    static final int KEY_ACTION_PRESS = 2;
    static final int KEY_CODE_BACK = 4;
    static final int KEY_CODE_MAIN = 8103;
    static final int WIDTH = 1280;
    static final int HEIGHT = 720;

    static byte[] touch(int action, int x, int y) {
        if (action < ACTION_DOWN || action > ACTION_MOVE) {
            throw new IllegalArgumentException("不支持的触控 action：" + action);
        }
        int clampedX = Math.max(0, Math.min(WIDTH - 1, x));
        int clampedY = Math.max(0, Math.min(HEIGHT - 1, y));
        String json = "{\"type\":1,\"action\":" + action
            + ",\"width\":" + WIDTH
            + ",\"height\":" + HEIGHT
            + ",\"count\":1,\"trackID0\":0,\"x0\":" + clampedX
            + ",\"y0\":" + clampedY + "}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] key(int keyCode) {
        String json = "{\"type\":2,\"action\":" + KEY_ACTION_PRESS
            + ",\"keycode\":" + keyCode + ",\"metaState\":0}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private UibcProtocol() {
    }
}
