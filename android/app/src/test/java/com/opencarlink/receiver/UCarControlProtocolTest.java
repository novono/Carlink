package com.opencarlink.receiver;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void detectsVerifiedPhoneDisconnectNotification() {
        byte[] packet = hex(
            "0000003a000000696a72f82c1b01000700007859"
                + "0000000c9a7055e23172b6ad9e0fb52f00000012"
                + "7dcf378b42d6c70996883693783446dd61b7"
        );
        IccoaAuthSession.Header header = IccoaAuthSession.parseHeader(packet);
        assertEquals(105, header.sequenceId);
        assertEquals(1, header.dataFormat);
        assertEquals(1, header.category);
        assertEquals(7, header.method);
        assertEquals(1, packet[12] & 1);
        assertEquals(3, (packet[12] & 0x18) >> 3);
        assertTrue(UCarControlProtocol.isSessionDisconnect(packet));
        assertFalse(UCarControlProtocol.isSessionDisconnect(
            UCarControlProtocol.buildHeartbeat(1, 1)
        ));
    }

    @Test
    public void carDisconnectUsesVerifiedHeaderAndProtobufBody() throws Exception {
        byte[] message = UCarControlProtocol.buildSessionDisconnect(9);
        IccoaAuthSession.Header header = IccoaAuthSession.parseHeader(message);
        assertEquals(22, message.length);
        assertEquals(9, header.sequenceId);
        assertEquals(1, header.dataFormat);
        assertEquals(1, header.category);
        assertEquals(7, header.method);
        assertEquals(0, header.reserved);
        assertEquals(0x1a, message[12] & 0xff);
        assertArrayEquals(hex("0801"), Arrays.copyOfRange(message, 20, message.length));
        assertTrue(UCarControlProtocol.isSessionDisconnect(message));

        byte[] key = hex("000102030405060708090a0b0c0d0e0f");
        byte[] encrypted = UCarControlProtocol.encryptMessage(key, message);
        assertTrue(UCarControlProtocol.isSessionDisconnect(encrypted));
        assertArrayEquals(message, UCarControlProtocol.decryptMessage(key, encrypted));
        assertThrows(IllegalArgumentException.class,
            () -> UCarControlProtocol.buildSessionDisconnect(-1));
    }

    @Test
    public void ignoresOtherMessageTypesCategoriesMethodsAndFormats() {
        assertFalse(UCarControlProtocol.isSessionDisconnect(protocolMessage(1, 7, 1, 1)));
        assertFalse(UCarControlProtocol.isSessionDisconnect(protocolMessage(3, 7, 3, 1)));
        assertFalse(UCarControlProtocol.isSessionDisconnect(protocolMessage(1, 1, 3, 1)));
        assertFalse(UCarControlProtocol.isSessionDisconnect(protocolMessage(1, 7, 3, 0)));
    }

    private static byte[] protocolMessage(int category, int method, int type, int format) {
        byte[] body = hex("0801");
        return IccoaAuthSession.concat(
            IccoaAuthSession.buildHeader(body.length, 1, format, type, category, method, 0),
            body
        );
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }
}
