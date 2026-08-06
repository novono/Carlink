package com.opencarlink.receiver;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.Arrays;

public final class RtpMpegTsExtractorTest {
    @Test
    public void extractsSplitLengthPrefixedMpegTsPackets() {
        byte[] transport = new byte[188 * 2];
        transport[0] = 0x47;
        transport[188] = 0x47;
        byte[] framed = framedRtp(33, transport);
        RtpMpegTsExtractor extractor = new RtpMpegTsExtractor();
        assertEquals(0, extractor.feed(framed, 9).length);
        byte[] remainder = Arrays.copyOfRange(framed, 9, framed.length);
        assertArrayEquals(transport, extractor.feed(remainder, remainder.length));
        assertEquals(1, extractor.packetCount());
    }

    @Test
    public void ignoresNonMpegTsPayloadAndRejectsMisalignment() {
        RtpMpegTsExtractor extractor = new RtpMpegTsExtractor();
        byte[] other = framedRtp(96, new byte[]{1, 2, 3});
        assertEquals(0, extractor.feed(other, other.length).length);

        byte[] invalid = framedRtp(33, new byte[]{0x47, 1, 2});
        assertThrows(
            IllegalArgumentException.class,
            () -> extractor.feed(invalid, invalid.length)
        );
    }

    @Test
    public void extractsPayloadWithCsrcExtensionAndPaddingWithoutPacketCopies() {
        byte[] transport = new byte[188];
        transport[0] = 0x47;
        byte[] packet = new byte[2 + 12 + 4 + 8 + transport.length + 4];
        int packetLength = packet.length - 2;
        packet[0] = (byte) (packetLength >> 8);
        packet[1] = (byte) packetLength;
        packet[2] = (byte) 0xb1;
        packet[3] = 33;
        int extension = 2 + 12 + 4;
        packet[extension + 2] = 0;
        packet[extension + 3] = 1;
        int payload = extension + 8;
        System.arraycopy(transport, 0, packet, payload, transport.length);
        packet[packet.length - 1] = 4;

        RtpMpegTsExtractor extractor = new RtpMpegTsExtractor();

        assertArrayEquals(transport, extractor.feed(packet, packet.length));
        assertEquals(1, extractor.packetCount());
    }

    private static byte[] framedRtp(int payloadType, byte[] payload) {
        byte[] result = new byte[2 + 12 + payload.length];
        int packetLength = result.length - 2;
        result[0] = (byte) (packetLength >> 8);
        result[1] = (byte) packetLength;
        result[2] = (byte) 0x80;
        result[3] = (byte) payloadType;
        System.arraycopy(payload, 0, result, 14, payload.length);
        return result;
    }
}
