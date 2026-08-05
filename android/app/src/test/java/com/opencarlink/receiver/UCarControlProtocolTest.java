package com.opencarlink.receiver;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

public final class UCarControlProtocolTest {
    @Test
    public void encryptedConfigResponseMatchesVerifiedShape() throws Exception {
        byte[] request = hex("00000014000000036a719d250b01001800000c50");
        IccoaProtocol.Identity identity = new IccoaProtocol.Identity(
            hex("010203040506"),
            new byte[4],
            new byte[2]
        );
        byte[] response = UCarControlProtocol.buildConfigResponse(request, identity);
        assertNotNull(response);
        IccoaAuthSession.Header header = IccoaAuthSession.parseHeader(response);
        assertEquals(3, header.sequenceId);
        assertEquals(1, header.category);
        assertEquals(25, header.method);
        assertArrayEquals(
            hex("0a06010203040506"),
            Arrays.copyOfRange(response, 20, 28)
        );

        byte[] key = new byte[16];
        new SecureRandom().nextBytes(key);
        byte[] encrypted = UCarControlProtocol.encryptMessage(key, response);
        assertArrayEquals(response, UCarControlProtocol.decryptMessage(key, encrypted));
    }

    @Test
    public void streamDecoderHandlesSplitEncryptedMessages() throws Exception {
        byte[] key = new byte[16];
        new SecureRandom().nextBytes(key);
        byte[] first = UCarControlProtocol.encryptMessage(
            key,
            UCarControlProtocol.buildHeartbeat(1, 1_700_000_000_001L)
        );
        byte[] second = UCarControlProtocol.encryptMessage(
            key,
            UCarControlProtocol.buildHeartbeat(2, 1_700_000_000_002L)
        );
        IccoaAuthSession.StreamDecoder decoder = new IccoaAuthSession.StreamDecoder();
        assertEquals(0, decoder.feed(first, 13).size());
        byte[] remainder = IccoaAuthSession.concat(
            Arrays.copyOfRange(first, 13, first.length),
            second
        );
        List<byte[]> messages = decoder.feed(remainder, remainder.length);
        assertEquals(2, messages.size());
        assertArrayEquals(first, messages.get(0));
        assertArrayEquals(second, messages.get(1));
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }
}
