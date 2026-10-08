package com.opencarlink.receiver;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
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
import java.util.ArrayList;
import java.util.List;

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
    private static final int FIRST_KEY_FRAME_INPUT_TIMEOUT_US = 50_000;
    private static final int AUDIO_BUFFER_DURATION_MS = 100;
    private static final AudioAttributes AUDIO_ATTRIBUTES = new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build();

    private final Surface surface;
    private final Listener listener;
    private final AudioManager audioManager;
    private final AudioFocusRequest audioFocusRequest;
    private final Object lifecycleLock = new Object();
    private final Object codecLock = new Object();
    private final Object audioCodecLock = new Object();
    private final VideoTrackOutput videoTrack = new VideoTrackOutput();
    private final AudioTrackOutput audioTrack = new AudioTrackOutput();
    private volatile boolean stopped;
    private volatile boolean firstFrameReported;
    private volatile boolean waitingForKeyFrame = true;
    private volatile boolean audioDisabled;
    private volatile boolean audioFocusGranted;
    private volatile boolean audioFocusRequested;
    private volatile MediaCodec codec;
    private volatile MediaCodec audioCodec;
    private volatile AudioTrack audioPlayer;
    private volatile VideoStreamHub.Reader reader;
    private long videoSessionToken;
    private Thread extractorThread;
    private Thread outputThread;
    private Thread audioOutputThread;
    private long queuedFrames;
    private long droppedFrames;
    private long renderedFrames;
    private long queuedAudioFrames;
    private long droppedAudioFrames;
    private long playedAudioBytes;
    private long lastStatsMs;

    DirectTsVideoDecoder(Context context, Surface surface, Listener listener) {
        this.surface = surface;
        this.listener = listener;
        audioManager = (AudioManager) context.getApplicationContext()
            .getSystemService(Context.AUDIO_SERVICE);
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AUDIO_ATTRIBUTES)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(this::handleAudioFocusChange)
            .build();
    }

    void start() {
        Thread extractor;
        synchronized (lifecycleLock) {
            if (stopped || extractorThread != null) {
                return;
            }
            videoSessionToken = VideoStreamHub.currentSessionToken();
            extractor = new Thread(this::runExtractor, "CarLink-TS-Demux");
            extractorThread = extractor;
        }
        extractor.start();
    }

    void stop() {
        VideoStreamHub.Reader activeReader;
        Thread extractor;
        synchronized (lifecycleLock) {
            stopped = true;
            activeReader = reader;
            reader = null;
            extractor = extractorThread;
        }
        if (activeReader != null) {
            activeReader.close();
        }
        if (extractor != null) {
            extractor.interrupt();
        }
        Thread output = outputThread;
        if (output != null) {
            output.interrupt();
        }
        Thread audioOutput = audioOutputThread;
        if (audioOutput != null) {
            audioOutput.interrupt();
        }
        releaseCodec();
        releaseAudio();
    }

    private void runExtractor() {
        prioritizeCurrentThread();
        int tsFlags = DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS;
        Extractor extractor = new TsExtractor(
            TsExtractor.MODE_SINGLE_PMT,
            new TimestampAdjuster(0),
            new DefaultTsPayloadReaderFactory(tsFlags)
        );
        try {
            synchronized (lifecycleLock) {
                if (stopped) {
                    return;
                }
                reader = VideoStreamHub.openReader(videoSessionToken);
            }
            DataReader dataReader = (target, offset, length) -> readStream(target, offset, length);
            DefaultExtractorInput input = new DefaultExtractorInput(dataReader, 0, C.LENGTH_UNSET);
            extractor.init(new ExtractorOutput() {
                @Override
                public TrackOutput track(int id, int type) {
                    if (type == C.TRACK_TYPE_VIDEO) {
                        return videoTrack;
                    }
                    if (type == C.TRACK_TYPE_AUDIO) {
                        return audioTrack;
                    }
                    return new DiscardingTrackOutput();
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
            VideoStreamHub.Reader activeReader;
            synchronized (lifecycleLock) {
                activeReader = reader;
                reader = null;
            }
            if (activeReader != null) {
                activeReader.close();
            }
            releaseCodec();
            releaseAudio();
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
            waitingForKeyFrame = true;
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

    private void configureAudioCodec(Format format) {
        if (audioCodec != null || audioDisabled || stopped) { return; }
        MediaCodec value = null;
        try {
            String mimeType = format.sampleMimeType;
            if (mimeType == null || !mimeType.startsWith("audio/")) { return; }
            int sampleRate = format.sampleRate;
            int channelCount = format.channelCount;
            if (sampleRate <= 0 || channelCount <= 0) {
                throw new IllegalArgumentException("音频参数不完整：" + sampleRate + "Hz/" + channelCount + "ch");
            }
            MediaFormat mediaFormat = MediaFormat.createAudioFormat(mimeType, sampleRate, channelCount);
            mediaFormat.setInteger(MediaFormat.KEY_PRIORITY, 0);
            mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024);
            mediaFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            for (int index = 0; index < format.initializationData.size(); index++) {
                mediaFormat.setByteBuffer("csd-" + index,
                    ByteBuffer.wrap(format.initializationData.get(index)));
            }
            List<String> names = audioDecoderNames(mimeType);
            value = AudioDecoderFallback.open(names, name -> {
                MediaCodec candidate = name == null
                    ? MediaCodec.createDecoderByType(mimeType) : MediaCodec.createByCodecName(name);
                boolean configured = false;
                try {
                    candidate.configure(mediaFormat, null, null, 0);
                    candidate.start();
                    configured = true;
                    return candidate;
                } finally {
                    if (!configured) { stopAndReleaseAudioCodec(candidate); }
                }
            });
            String codecName = value.getName();
            synchronized (audioCodecLock) {
                if (stopped || audioDisabled) { return; }
                audioCodec = value;
                value = null;
            }
            Log.i(TAG, "AAC decoder started: " + codecName + " " + sampleRate + "Hz/" + channelCount + "ch");
            audioOutputThread = new Thread(this::runAudioOutput, "CarLink-Audio-Output");
            audioOutputThread.setPriority(Thread.MAX_PRIORITY);
            audioOutputThread.start();
        } catch (IOException | RuntimeException error) {
            disableAudio("AAC 解码器启动失败", error);
        } finally {
            if (value != null) { stopAndReleaseAudioCodec(value); }
        }
    }

    private List<String> audioDecoderNames(String mimeType) {
        List<String> software = new ArrayList<>();
        List<String> others = new ArrayList<>();
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if (info.isEncoder()) { continue; }
                for (String type : info.getSupportedTypes()) {
                    if (mimeType.equalsIgnoreCase(type)) {
                        (info.isSoftwareOnly() ? software : others).add(info.getName());
                        break;
                    }
                }
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Audio decoder inventory unavailable; trying explicit names and MIME lookup", error);
        }
        software.addAll(others);
        if ("audio/mp4a-latm".equalsIgnoreCase(mimeType)) {
            // This head unit declares these names even when createDecoderByType cannot resolve AAC.
            software.add("c2.android.aac.decoder");
            software.add("OMX.google.aac.decoder");
        }
        return software;
    }

    private static void stopAndReleaseAudioCodec(MediaCodec value) {
        try { value.stop(); } catch (RuntimeException ignored) { }
        try { value.release(); } catch (RuntimeException ignored) { }
    }

    private static void stopAndReleaseCodec(MediaCodec value) {
        try {
            value.stop();
        } catch (IllegalStateException ignored) {
        }
        try {
            value.release();
        } catch (RuntimeException ignored) {
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
        boolean hasAnnexBStartCode = hasAnnexBStartCode(data, offset, size);
        boolean keyFrame = containsH264NalType(data, offset, size, 5)
            || (!hasAnnexBStartCode && (flags & C.BUFFER_FLAG_KEY_FRAME) != 0);
        if (waitingForKeyFrame && !keyFrame) {
            droppedFrames++;
            return;
        }
        try {
            int inputIndex = value.dequeueInputBuffer(
                waitingForKeyFrame ? FIRST_KEY_FRAME_INPUT_TIMEOUT_US : INPUT_TIMEOUT_US
            );
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
            int codecFlags = keyFrame
                ? MediaCodec.BUFFER_FLAG_KEY_FRAME
                : 0;
            value.queueInputBuffer(inputIndex, 0, size, Math.max(0, timeUs), codecFlags);
            queuedFrames++;
            if (waitingForKeyFrame) {
                waitingForKeyFrame = false;
                Log.i(TAG, "First complete IDR queued; predictive frames are now enabled");
            }
        } catch (IllegalStateException error) {
            if (!stopped) {
                listener.onError("视频帧送入解码器失败：" + error.getMessage());
            }
        }
    }

    private void queueAudioSample(byte[] data, int offset, int size, long timeUs) {
        MediaCodec value = audioCodec;
        if (value == null || audioDisabled || stopped) {
            return;
        }
        try {
            int inputIndex = value.dequeueInputBuffer(INPUT_TIMEOUT_US);
            if (inputIndex < 0) {
                droppedAudioFrames++;
                return;
            }
            ByteBuffer input = value.getInputBuffer(inputIndex);
            if (input == null || input.capacity() < size) {
                droppedAudioFrames++;
                value.queueInputBuffer(inputIndex, 0, 0, Math.max(0, timeUs), 0);
                return;
            }
            input.clear();
            input.put(data, offset, size);
            value.queueInputBuffer(inputIndex, 0, size, Math.max(0, timeUs), 0);
            queuedAudioFrames++;
        } catch (RuntimeException error) {
            if (!stopped) {
                disableAudio("AAC 输入失败", error);
            }
        }
    }
    private static boolean hasAnnexBStartCode(byte[] data, int offset, int size) {
        int end = Math.min(data.length, offset + size);
        for (int index = Math.max(0, offset); index + 2 < end; index++) {
            if (data[index] == 0 && data[index + 1] == 0 && data[index + 2] == 1) {
                return true;
            }
            if (index + 3 < end
                && data[index] == 0
                && data[index + 1] == 0
                && data[index + 2] == 0
                && data[index + 3] == 1) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsH264NalType(
        byte[] data,
        int offset,
        int size,
        int expectedType
    ) {
        int end = Math.min(data.length, offset + size);
        for (int index = Math.max(0, offset); index + 3 < end; index++) {
            int headerOffset;
            if (data[index] == 0 && data[index + 1] == 0 && data[index + 2] == 1) {
                headerOffset = index + 3;
            } else if (index + 4 < end
                && data[index] == 0
                && data[index + 1] == 0
                && data[index + 2] == 0
                && data[index + 3] == 1) {
                headerOffset = index + 4;
            } else {
                continue;
            }
            if (headerOffset < end && (data[headerOffset] & 0x1f) == expectedType) {
                return true;
            }
            index = headerOffset - 1;
        }
        return false;
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

    private void runAudioOutput() {
        prioritizeAudioThread();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (!stopped && !audioDisabled) {
            MediaCodec value = audioCodec;
            if (value == null) {
                return;
            }
            try {
                int outputIndex = value.dequeueOutputBuffer(info, 10_000L);
                if (outputIndex >= 0) {
                    boolean endOfStream = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    try {
                        writeAudioOutput(value, outputIndex, info);
                    } finally {
                        value.releaseOutputBuffer(outputIndex, false);
                    }
                    if (endOfStream) {
                        return;
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    configureAudioPlayer(value.getOutputFormat());
                }
            } catch (RuntimeException error) {
                if (!stopped) {
                    disableAudio("AAC 输出失败", error);
                }
                return;
            }
        }
    }

    private void writeAudioOutput(
        MediaCodec value,
        int outputIndex,
        MediaCodec.BufferInfo info
    ) {
        if (info.size <= 0) {
            return;
        }
        ByteBuffer output = value.getOutputBuffer(outputIndex);
        AudioTrack player = audioPlayer;
        if (output == null
            || player == null
            || !audioFocusGranted
            || player.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            droppedAudioFrames++;
            return;
        }
        int start = Math.max(0, info.offset);
        int end = Math.min(output.capacity(), start + info.size);
        if (end <= start) {
            droppedAudioFrames++;
            return;
        }
        output.position(start);
        output.limit(end);
        while (output.hasRemaining() && !stopped && !audioDisabled) {
            int written = player.write(output, output.remaining(), AudioTrack.WRITE_BLOCKING);
            if (written < 0) {
                throw new IllegalStateException("AudioTrack.write failed: " + written);
            }
            if (written == 0) {
                droppedAudioFrames++;
                return;
            }
            playedAudioBytes += written;
        }
    }

    private void configureAudioPlayer(MediaFormat outputFormat) {
        int sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        if (channelCount != 1 && channelCount != 2) {
            throw new IllegalArgumentException("Unsupported PCM channel count: " + channelCount);
        }
        int pcmEncoding = outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
            ? outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
            : AudioFormat.ENCODING_PCM_16BIT;
        int bytesPerSample;
        if (pcmEncoding == AudioFormat.ENCODING_PCM_8BIT) {
            bytesPerSample = 1;
        } else if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            bytesPerSample = 4;
        } else {
            pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
            bytesPerSample = 2;
        }
        int channelMask = channelCount == 1
            ? AudioFormat.CHANNEL_OUT_MONO
            : AudioFormat.CHANNEL_OUT_STEREO;
        int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, pcmEncoding);
        if (minBufferSize <= 0) {
            throw new IllegalStateException("Invalid AudioTrack buffer size: " + minBufferSize);
        }
        int bytesPerFrame = channelCount * bytesPerSample;
        int targetBufferSize = sampleRate * bytesPerFrame * AUDIO_BUFFER_DURATION_MS / 1_000;
        int bufferSize = Math.max(minBufferSize, targetBufferSize);

        AudioTrack player = buildAudioTrack(
            sampleRate,
            channelMask,
            pcmEncoding,
            bufferSize,
            true
        );
        if (player.getState() != AudioTrack.STATE_INITIALIZED) {
            player.release();
            player = buildAudioTrack(
                sampleRate,
                channelMask,
                pcmEncoding,
                bufferSize,
                false
            );
        }
        if (player.getState() != AudioTrack.STATE_INITIALIZED) {
            player.release();
            throw new IllegalStateException("AudioTrack initialization failed");
        }

        AudioTrack previous;
        synchronized (audioCodecLock) {
            if (stopped || audioDisabled) {
                player.release();
                return;
            }
            previous = audioPlayer;
            audioPlayer = player;
        }
        releaseAudioTrack(previous);
        if (requestAudioFocus()) {
            player.play();
        }
        Log.i(
            TAG,
            "AudioTrack ready: " + sampleRate + "Hz/" + channelCount
                + "ch encoding=" + pcmEncoding + " buffer=" + bufferSize
        );
    }

    private static AudioTrack buildAudioTrack(
        int sampleRate,
        int channelMask,
        int pcmEncoding,
        int bufferSize,
        boolean lowLatency
    ) {
        AudioFormat audioFormat = new AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .setEncoding(pcmEncoding)
            .build();
        AudioTrack.Builder builder = new AudioTrack.Builder()
            .setAudioAttributes(AUDIO_ATTRIBUTES)
            .setAudioFormat(audioFormat)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize);
        if (lowLatency) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        }
        try {
            return builder.build();
        } catch (IllegalArgumentException | UnsupportedOperationException error) {
            if (!lowLatency) {
                throw error;
            }
            return buildAudioTrack(sampleRate, channelMask, pcmEncoding, bufferSize, false);
        }
    }

    private boolean requestAudioFocus() {
        if (audioManager == null) {
            audioFocusGranted = true;
            return true;
        }
        synchronized (audioCodecLock) {
            if (stopped || audioDisabled) {
                return false;
            }
            if (audioFocusRequested) {
                return audioFocusGranted;
            }
            int result = audioManager.requestAudioFocus(audioFocusRequest);
            audioFocusRequested = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                || result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED;
            audioFocusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!audioFocusRequested) {
                Log.w(TAG, "Audio focus request was rejected: " + result);
            }
            return audioFocusGranted;
        }
    }

    private void handleAudioFocusChange(int focusChange) {
        if (stopped || audioDisabled) { return; }
        try {
            AudioTrack player = audioPlayer;
            if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                audioFocusGranted = true;
                if (player != null && player.getState() == AudioTrack.STATE_INITIALIZED) {
                    player.setVolume(1f);
                    player.play();
                }
            } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                if (player != null) { player.setVolume(0.25f); }
            } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                audioFocusGranted = false;
                if (player != null && player.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    player.pause();
                }
            }
        } catch (RuntimeException error) {
            if (!stopped) { disableAudio("音频焦点或播放状态异常", error); }
        }
    }
    private void logStats() {
        long now = SystemClock.elapsedRealtime();
        if (lastStatsMs == 0) {
            lastStatsMs = now;
        } else if (now - lastStatsMs >= 5_000L) {
            Log.i(
                TAG,
                "videoQueued=" + queuedFrames + " videoRendered=" + renderedFrames
                    + " videoDropped=" + droppedFrames + " audioQueued=" + queuedAudioFrames
                    + " audioDropped=" + droppedAudioFrames
                    + " audioBytes=" + playedAudioBytes
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
            stopAndReleaseCodec(value);
        }
    }

    private void disableAudio(String message, Throwable error) {
        synchronized (audioCodecLock) {
            if (audioDisabled) {
                return;
            }
            audioDisabled = true;
        }
        if (error == null) {
            Log.w(TAG, message);
        } else {
            Log.w(TAG, message, error);
        }
        Thread output = audioOutputThread;
        if (output != null && output != Thread.currentThread()) {
            output.interrupt();
        }
        releaseAudio();
        if (!stopped) {
            try { listener.onError("音频不可用：" + message + "；视频与触摸继续运行"); }
            catch (RuntimeException callbackError) { Log.w(TAG, "Audio status callback failed", callbackError); }
        }
    }

    private void releaseAudio() {
        MediaCodec codecValue;
        AudioTrack player;
        boolean abandonFocus;
        synchronized (audioCodecLock) {
            codecValue = audioCodec;
            audioCodec = null;
            player = audioPlayer;
            audioPlayer = null;
            abandonFocus = audioFocusRequested;
            audioFocusRequested = false;
            audioFocusGranted = false;
        }
        releaseAudioTrack(player);
        if (codecValue != null) {
            stopAndReleaseAudioCodec(codecValue);
        }
        if (abandonFocus && audioManager != null) {
            try {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not abandon audio focus", error);
            }
        }
    }

    private static void releaseAudioTrack(AudioTrack player) {
        if (player == null) {
            return;
        }
        try {
            player.pause();
        } catch (RuntimeException ignored) {
        }
        try {
            player.flush();
        } catch (RuntimeException ignored) {
        }
        try {
            player.stop();
        } catch (RuntimeException ignored) {
        }
        try { player.release(); }
        catch (RuntimeException error) { Log.w(TAG, "AudioTrack release failed", error); }
    }

    private static void prioritizeAudioThread() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        } catch (IllegalArgumentException | SecurityException ignored) {
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

    private final class AudioTrackOutput implements TrackOutput {
        private static final int MAX_SAMPLE_BYTES = 1024 * 1024;
        private byte[] pending = new byte[64 * 1024];
        private final byte[] discarded = new byte[8 * 1024];
        private int pendingSize;

        @Override
        public void format(Format format) {
            if (audioDisabled || stopped) { return; }
            try {
                Log.i(TAG, "Audio format: mime=" + format.sampleMimeType + " sampleRate=" + format.sampleRate
                    + " channelCount=" + format.channelCount);
                configureAudioCodec(format);
            } catch (RuntimeException error) {
                disableAudio("音频格式处理失败", error);
            }
        }

        @Override
        public int sampleData(DataReader input, int length, boolean allowEndOfInput, int sampleDataPart)
            throws IOException {
            if (length <= 0) { return 0; }
            if (audioDisabled || stopped) {
                discardPending();
                return input.read(discarded, 0, Math.min(length, discarded.length));
            }
            try { ensureCapacity(pendingSize + length); }
            catch (IllegalArgumentException error) {
                disableAudio("音频样本过大", error);
                discardPending();
                return input.read(discarded, 0, Math.min(length, discarded.length));
            }
            // An IOException from this shared TS input is a transport error and still propagates.
            int count = input.read(pending, pendingSize, length);
            if (count == C.RESULT_END_OF_INPUT && !allowEndOfInput) {
                disableAudio("音频样本提前结束", null);
                discardPending();
            }
            if (count > 0) { pendingSize += count; }
            return count;
        }

        @Override
        public void sampleData(ParsableByteArray data, int length, int sampleDataPart) {
            if (audioDisabled || stopped) {
                discardPending();
                data.skipBytes(Math.min(Math.max(0, length), data.bytesLeft()));
                return;
            }
            try {
                ensureCapacity(pendingSize + length);
                data.readBytes(pending, pendingSize, length);
                pendingSize += length;
            } catch (RuntimeException error) {
                disableAudio("音频样本解析失败", error);
                discardPending();
                data.skipBytes(Math.min(Math.max(0, length), data.bytesLeft()));
            }
        }

        @Override
        public void sampleMetadata(long timeUs, int flags, int size, int offset, CryptoData cryptoData) {
            if (audioDisabled || stopped) { discardPending(); return; }
            int sampleEnd = pendingSize - offset;
            int sampleStart = sampleEnd - size;
            if (sampleStart < 0 || sampleEnd < sampleStart || sampleEnd > pendingSize) {
                disableAudio("音频样本边界无效", null);
                discardPending();
                return;
            }
            queueAudioSample(pending, sampleStart, size, timeUs);
            if (audioDisabled) { discardPending(); return; }
            int retained = pendingSize - sampleEnd;
            if (retained > 0) { System.arraycopy(pending, sampleEnd, pending, 0, retained); }
            pendingSize = retained;
        }

        private void discardPending() {
            Arrays.fill(pending, 0, pendingSize, (byte) 0);
            pendingSize = 0;
        }

        private void ensureCapacity(int required) {
            if (required < 0 || required > MAX_SAMPLE_BYTES) {
                throw new IllegalArgumentException("AAC 样本超过容量限制");
            }
            if (required <= pending.length) { return; }
            int capacity = pending.length;
            while (capacity < required) { capacity *= 2; }
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
