package com.opencarlink.receiver;

import java.nio.charset.StandardCharsets;

final class UibcProtocol {
    static final int ACTION_DOWN = 0;
    static final int ACTION_UP = 1;
    static final int ACTION_MOVE = 2;
    static final int ACTION_CANCEL = 3;
    static final int ACTION_POINTER_DOWN = 5;
    static final int ACTION_POINTER_UP = 6;
    static final int KEY_ACTION_PRESS = 2;
    static final int KEY_CODE_BACK = 4;
    static final int KEY_CODE_MAIN = 8103;
    static final int WIDTH = 1280;
    static final int HEIGHT = 720;

    static byte[] touch(int action, int x, int y) {
        return touch(action, new int[]{0}, new int[]{x}, new int[]{y});
    }

    static byte[] touch(int action, int[] trackIds, int[] xs, int[] ys) {
        int maskedAction = action & 0xff;
        if (maskedAction != ACTION_DOWN
            && maskedAction != ACTION_UP
            && maskedAction != ACTION_MOVE
            && maskedAction != ACTION_CANCEL
            && maskedAction != ACTION_POINTER_DOWN
            && maskedAction != ACTION_POINTER_UP) {
            throw new IllegalArgumentException("不支持的触控 action：" + action);
        }
        if (trackIds.length == 0 || trackIds.length != xs.length || xs.length != ys.length) {
            throw new IllegalArgumentException("触控 pointer 参数不完整");
        }
        StringBuilder json = new StringBuilder("{\"type\":1,\"action\":")
            .append(action)
            .append(",\"width\":").append(WIDTH)
            .append(",\"height\":").append(HEIGHT)
            .append(",\"count\":").append(trackIds.length);
        for (int index = 0; index < trackIds.length; index++) {
            json.append(",\"trackID").append(index).append("\":").append(trackIds[index])
                .append(",\"x").append(index).append("\":").append(xs[index])
                .append(",\"y").append(index).append("\":").append(ys[index]);
        }
        return json.append('}').toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] key(int keyCode) {
        String json = "{\"type\":2,\"action\":" + KEY_ACTION_PRESS
            + ",\"keycode\":" + keyCode + ",\"metaState\":0}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private UibcProtocol() {
    }
}
