package com.opencarlink.receiver;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.DiscardingTrackOutput;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.extractor.ts.TsExtractor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Demuxes MPEG-TS and feeds H.264 access units straight into a surface decoder. */
@UnstableApi
final class DirectTsVideoDecoder {
    interface Listener {
        void onDecoderReady(String codecName, boolean lowLatency);

        void onVideoSize(int width, int height);

        void onFirstFrame();

        void onError(String message);
    }

    private static final String TAG = "OpenCarLinkDirectVideo";
    private static final int DEFAULT_WIDTH = 1280;
    private static final int DEFAULT_HEIGHT = 720;
    private static final int INPUT_TIMEOUT_US = 2_000;

    private final Surface surface;
    private final Listener listener;
    private final Object codecLock = new Object();
    private final VideoTrackOutput videoTrack = new VideoTrackOutput();
    private volatile boolean stopped;
    private volatile boolean firstFrameReported;
    private volatile MediaCodec codec;
    private volatile VideoStreamHub.Reader reader;
    private Thread extractorThread;
    private Thread outputThread;
    private long queuedFrames;
    private long droppedFrames;
    private long renderedFrames;
    private long lastStatsMs;

    DirectTsVideoDecoder(Surface surface, Listener listener) {
        this.surface = surface;
        this.listener = listener;
    }

    void start() {
        if (extractorThread != null) {
            return;
        }
        extractorThread = new Thread(this::runExtractor, "CarLink-TS-Demux");
        extractorThread.start();
    }

    void stop() {
        stopped = true;
        VideoStreamHub.Reader activeReader = reader;
        if (activeReader != null) {
            activeReader.close();
        }
        Thread extractor = extractorThread;
        if (extractor != null) {
            extractor.interrupt();
        }
        Thread output = outputThread;
        if (output != null) {
            output.interrupt();
        }
        releaseCodec();
    }

    private void runExtractor() {
        prioritizeCurrentThread();
        int tsFlags = DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            | DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
            | DefaultTsPayloadReaderFactory.FLAG_IGNORE_AAC_STREAM;
        Extractor extractor = new TsExtractor(
            TsExtractor.MODE_SINGLE_PMT,
            new TimestampAdjuster(0),
            new DefaultTsPayloadReaderFactory(tsFlags)
        );
        try {
            reader = VideoStreamHub.openReader();
            DataReader dataReader = (target, offset, length) -> readStream(target, offset, length);
            DefaultExtractorInput input = new DefaultExtractorInput(dataReader, 0, C.LENGTH_UNSET);
            extractor.init(new ExtractorOutput() {
                @Override
                public TrackOutput track(int id, int type) {
                    return type == C.TRACK_TYPE_VIDEO
                        ? videoTrack
                        : new DiscardingTrackOutput();
                }

                @Override
                public void endTracks() {
                }

                @Override
                public void seekMap(SeekMap seekMap) {
                }
            });
            PositionHolder seekPosition = new PositionHolder();
            while (!stopped) {
                int result = extractor.read(input, seekPosition);
                if (result == Extractor.RESULT_END_OF_INPUT) {
                    break;
                }
                if (result == Extractor.RESULT_SEEK) {
                    throw new IOException("实时 MPEG-TS 不支持 seek");
                }
            }
        } catch (IOException | RuntimeException error) {
            if (!stopped) {
                listener.onError("实时视频解码失败：" + error.getMessage());
            }
        } finally {
            extractor.release();
            VideoStreamHub.Reader activeReader = reader;
            reader = null;
            if (activeReader != null) {
                activeReader.close();
            }
            releaseCodec();
        }
    }

    private int readStream(byte[] target, int offset, int length) throws IOException {
        VideoStreamHub.Reader activeReader = reader;
        if (activeReader == null) {
            return C.RESULT_END_OF_INPUT;
        }
        try {
            return activeReader.read(target, offset, length);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("视频读取被中断", error);
        }
    }

    private void configureCodec(Format format) {
        if (codec != null || stopped) {
            return;
        }
        String mimeType = format.sampleMimeType;
        if (mimeType == null || !mimeType.startsWith("video/")) {
            return;
        }
        int width = format.width > 0 ? format.width : DEFAULT_WIDTH;
        int height = format.height > 0 ? format.height : DEFAULT_HEIGHT;
        MediaFormat mediaFormat = MediaFormat.createVideoFormat(mimeType, width, height);
        mediaFormat.setInteger(MediaFormat.KEY_PRIORITY, 0);
        mediaFormat.setInteger(MediaFormat.KEY_OPERATING_RATE, 60);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
        mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024);
        for (int index = 0; index < format.initializationData.size(); index++) {
            mediaFormat.setByteBuffer(
                "csd-" + index,
                ByteBuffer.wrap(format.initializationData.get(index))
            );
        }

        CodecChoice choice = chooseCodec(mimeType);
        if (choice == null) {
            listener.onError("设备没有可用的 " + mimeType + " 解码器");
            stopped = true;
            return;
        }
        if (Build.VERSION.SDK_INT >= 30 && choice.lowLatency) {
            mediaFormat.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        }

        try {
            MediaCodec value = MediaCodec.createByCodecName(choice.name);
            value.configure(mediaFormat, surface, null, 0);
            value.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            value.start();
            synchronized (codecLock) {
                if (stopped) {
                    value.stop();
                    value.release();
                    return;
                }
                codec = value;
            }
            listener.onDecoderReady(choice.name, choice.lowLatency);
            listener.onVideoSize(width, height);
            outputThread = new Thread(this::runOutput, "CarLink-Codec-Output");
            outputThread.setPriority(Thread.MAX_PRIORITY);
            outputThread.start();
        } catch (IOException | IllegalArgumentException | IllegalStateException error) {
            listener.onError("硬件解码器启动失败：" + error.getMessage());
            stopped = true;
            releaseCodec();
        }
    }

    private CodecChoice chooseCodec(String mimeType) {
        CodecChoice hardware = null;
        CodecChoice fallback = null;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
            if (info.isEncoder()) {
                continue;
            }
            boolean supportsType = false;
            for (String type : info.getSupportedTypes()) {
                if (mimeType.equalsIgnoreCase(type)) {
                    supportsType = true;
                    break;
                }
            }
            if (!supportsType) {
                continue;
            }
            boolean lowLatency = false;
            try {
                lowLatency = Build.VERSION.SDK_INT >= 30
                    && info.getCapabilitiesForType(mimeType).isFeatureSupported(
                        MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency
                    );
            } catch (IllegalArgumentException ignored) {
            }
            CodecChoice candidate = new CodecChoice(info.getName(), lowLatency);
            if (info.isHardwareAccelerated() && lowLatency) {
                return candidate;
            }
            if (info.isHardwareAccelerated() && hardware == null) {
                hardware = candidate;
            }
            if (fallback == null) {
                fallback = candidate;
            }
        }
        return hardware != null ? hardware : fallback;
    }

    private void queueSample(byte[] data, int offset, int size, long timeUs, int flags) {
        MediaCodec value = codec;
        if (value == null || stopped) {
            return;
        }
        try {
            int inputIndex = value.dequeueInputBuffer(INPUT_TIMEOUT_US);
            if (inputIndex < 0) {
                droppedFrames++;
                return;
            }
            ByteBuffer input = value.getInputBuffer(inputIndex);
            if (input == null || input.capacity() < size) {
                droppedFrames++;
                value.queueInputBuffer(inputIndex, 0, 0, timeUs, 0);
                return;
            }
            input.clear();
            input.put(data, offset, size);
            int codecFlags = (flags & C.BUFFER_FLAG_KEY_FRAME) != 0
                ? MediaCodec.BUFFER_FLAG_KEY_FRAME
                : 0;
            value.queueInputBuffer(inputIndex, 0, size, Math.max(0, timeUs), codecFlags);
            queuedFrames++;
        } catch (IllegalStateException error) {
            if (!stopped) {
                listener.onError("视频帧送入解码器失败：" + error.getMessage());
            }
        }
    }

    private void runOutput() {
        prioritizeCurrentThread();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (!stopped) {
            MediaCodec value = codec;
            if (value == null) {
                return;
            }
            try {
                int outputIndex = value.dequeueOutputBuffer(info, 10_000L);
                if (outputIndex >= 0) {
                    value.releaseOutputBuffer(outputIndex, true);
                    renderedFrames++;
                    if (!firstFrameReported) {
                        firstFrameReported = true;
                        listener.onFirstFrame();
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = value.getOutputFormat();
                    listener.onVideoSize(
                        outputFormat.getInteger(MediaFormat.KEY_WIDTH),
                        outputFormat.getInteger(MediaFormat.KEY_HEIGHT)
                    );
                }
                logStats();
            } catch (IllegalStateException error) {
                if (!stopped) {
                    listener.onError("视频输出失败：" + error.getMessage());
                }
                return;
            }
        }
    }

    private void logStats() {
        long now = SystemClock.elapsedRealtime();
        if (lastStatsMs == 0) {
            lastStatsMs = now;
        } else if (now - lastStatsMs >= 5_000L) {
            Log.i(
                TAG,
                "queued=" + queuedFrames + " rendered=" + renderedFrames
                    + " dropped=" + droppedFrames
            );
            lastStatsMs = now;
        }
    }

    private void releaseCodec() {
        MediaCodec value;
        synchronized (codecLock) {
            value = codec;
            codec = null;
        }
        if (value != null) {
            try {
                value.stop();
            } catch (IllegalStateException ignored) {
            }
            value.release();
        }
    }

    private static void prioritizeCurrentThread() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        } catch (IllegalArgumentException | SecurityException ignored) {
        }
    }

    private final class VideoTrackOutput implements TrackOutput {
        private byte[] pending = new byte[256 * 1024];
        private int pendingSize;

        @Override
        public void format(Format format) {
            configureCodec(format);
        }

        @Override
        public int sampleData(DataReader input, int length, boolean allowEndOfInput, int sampleDataPart)
            throws IOException {
            ensureCapacity(pendingSize + length);
            int count = input.read(pending, pendingSize, length);
            if (count == C.RESULT_END_OF_INPUT && !allowEndOfInput) {
                throw new IOException("H.264 sample 提前结束");
            }
            if (count > 0) {
                pendingSize += count;
            }
            return count;
        }

        @Override
        public void sampleData(ParsableByteArray data, int length, int sampleDataPart) {
            ensureCapacity(pendingSize + length);
            data.readBytes(pending, pendingSize, length);
            pendingSize += length;
        }

        @Override
        public void sampleMetadata(long timeUs, int flags, int size, int offset, CryptoData cryptoData) {
            int sampleEnd = pendingSize - offset;
            int sampleStart = sampleEnd - size;
            if (sampleStart < 0 || sampleEnd < sampleStart || sampleEnd > pendingSize) {
                Log.w(TAG, "Invalid sample bounds size=" + size + " offset=" + offset);
                pendingSize = 0;
                return;
            }
            queueSample(pending, sampleStart, size, timeUs, flags);
            int retained = pendingSize - sampleEnd;
            if (retained > 0) {
                System.arraycopy(pending, sampleEnd, pending, 0, retained);
            }
            pendingSize = retained;
        }

        private void ensureCapacity(int required) {
            if (required <= pending.length) {
                return;
            }
            int capacity = pending.length;
            while (capacity < required) {
                capacity *= 2;
            }
            pending = Arrays.copyOf(pending, capacity);
        }
    }

    private static final class CodecChoice {
        final String name;
        final boolean lowLatency;

        CodecChoice(String name, boolean lowLatency) {
            this.name = name;
            this.lowLatency = lowLatency;
        }
    }
}
