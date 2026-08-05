package com.opencarlink.receiver;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

final class RtpMpegTsExtractor {
    private static final int RTP_PAYLOAD_TYPE_MPEG_TS = 33;
    private static final int MPEG_TS_PACKET_SIZE = 188;
    private byte[] buffer = new byte[0];
    private long packetCount;

    byte[] feed(byte[] data, int length) {
        int oldLength = buffer.length;
        buffer = Arrays.copyOf(buffer, oldLength + length);
        System.arraycopy(data, 0, buffer, oldLength, length);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int consumed = 0;
        while (buffer.length - consumed >= 2) {
            int packetLength = ((buffer[consumed] & 0xff) << 8) | (buffer[consumed + 1] & 0xff);
            if (packetLength < 12) {
                buffer = new byte[0];
                throw new IllegalArgumentException("RTP 包长度无效：" + packetLength);
            }
            int total = 2 + packetLength;
            if (buffer.length - consumed < total) {
                break;
            }
            byte[] packet = Arrays.copyOfRange(buffer, consumed + 2, consumed + total);
            byte[] payload = payload(packet);
            packetCount++;
            output.write(payload, 0, payload.length);
            consumed += total;
        }
        if (consumed > 0) {
            buffer = Arrays.copyOfRange(buffer, consumed, buffer.length);
        }
        return output.toByteArray();
    }

    long packetCount() {
        return packetCount;
    }

    private static byte[] payload(byte[] packet) {
        if ((packet[0] & 0xc0) != 0x80) {
            throw new IllegalArgumentException("RTP 版本不是 2");
        }
        int payloadType = packet[1] & 0x7f;
        int offset = 12 + (packet[0] & 0x0f) * 4;
        if (offset > packet.length) {
            throw new IllegalArgumentException("RTP CSRC 头越界");
        }
        if ((packet[0] & 0x10) != 0) {
            if (offset + 4 > packet.length) {
                throw new IllegalArgumentException("RTP 扩展头不完整");
            }
            int words = ((packet[offset + 2] & 0xff) << 8) | (packet[offset + 3] & 0xff);
            offset += 4 + words * 4;
        }
        if (offset > packet.length) {
            throw new IllegalArgumentException("RTP 扩展数据越界");
        }
        int end = packet.length;
        if ((packet[0] & 0x20) != 0) {
            int padding = packet[packet.length - 1] & 0xff;
            if (padding == 0 || padding > end - offset) {
                throw new IllegalArgumentException("RTP padding 无效");
            }
            end -= padding;
        }
        if (payloadType != RTP_PAYLOAD_TYPE_MPEG_TS) {
            return new byte[0];
        }
        int length = end - offset;
        if (length != 0
            && (length % MPEG_TS_PACKET_SIZE != 0 || (packet[offset] & 0xff) != 0x47)) {
            throw new IllegalArgumentException("RTP 负载不是对齐的 MPEG-TS");
        }
        return Arrays.copyOfRange(packet, offset, end);
    }
}
