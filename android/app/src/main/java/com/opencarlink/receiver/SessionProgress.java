package com.opencarlink.receiver;

/** One receiver session advances independently of the order its transport callbacks arrive. */
final class SessionProgress {
    private int stage;

    boolean advance(int incoming) {
        if (incoming < stage) { return false; }
        stage = incoming;
        return true;
    }

    int stage() { return stage; }

    boolean streaming() { return stage >= 8; }
}
