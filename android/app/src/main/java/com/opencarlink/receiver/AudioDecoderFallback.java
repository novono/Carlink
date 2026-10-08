package com.opencarlink.receiver;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;

/** Audio-only recovery; callers keep video running if every audio decoder fails. */
final class AudioDecoderFallback {
    interface Factory<T> {
        T create(String codecName) throws IOException;
    }

    static <T> T open(List<String> codecNames, Factory<T> factory) throws IOException {
        IOException unavailable = new IOException("没有可启动的音频解码器");
        for (String name : new LinkedHashSet<>(codecNames)) {
            if (name == null || name.isEmpty()) { continue; }
            try {
                return factory.create(name);
            } catch (IOException | RuntimeException error) {
                unavailable.addSuppressed(new IOException("音频解码器 " + name + " 启动失败", error));
            }
        }
        try {
            return factory.create(null); // Android's MIME-based selection is the last fallback.
        } catch (IOException | RuntimeException error) {
            unavailable.addSuppressed(error);
            throw unavailable;
        }
    }

    private AudioDecoderFallback() {
    }
}
