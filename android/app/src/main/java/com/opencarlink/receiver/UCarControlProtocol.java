package com.opencarlink.receiver;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

final class UCarControlProtocol {
    private static final int HEADER_SIZE = 20;
    private static final int FORMAT_PB3 = 1;
    private static final int MESSAGE_SEND = 0;
    private static final int MESSAGE_REQ = 1;
    private static final int MESSAGE_RES = 2;
    private static final int CATEGORY_CONTROL = 1;
    private static final int METHOD_HEARTBEAT = 1;
    private static final int METHOD_GET_CONFIG_REQUEST = 24;
    private static final int METHOD_GET_CONFIG_RESPONSE = 25;

    static byte[] encryptMessage(byte[] sessionKey, byte[] message)
        throws GeneralSecurityException {
        IccoaAuthSession.parseHeader(message);
        return replaceBody(
            message,
            IccoaAuthSession.encryptSessionPayload(
                sessionKey,
                Arrays.copyOfRange(message, HEADER_SIZE, message.length)
            )
        );
    }

    static byte[] decryptMessage(byte[] sessionKey, byte[] message)
        throws GeneralSecurityException {
        IccoaAuthSession.parseHeader(message);
        return replaceBody(
            message,
            IccoaAuthSession.decryptSessionPayload(
                sessionKey,
                Arrays.copyOfRange(message, HEADER_SIZE, message.length)
            )
        );
    }

    static byte[] buildConfigResponse(byte[] request, IccoaProtocol.Identity identity) {
        IccoaAuthSession.Header header = IccoaAuthSession.parseHeader(request);
        int messageType = (request[12] & 0x18) >> 3;
        if (header.category != CATEGORY_CONTROL
            || header.method != METHOD_GET_CONFIG_REQUEST
            || header.dataFormat != FORMAT_PB3
            || messageType != MESSAGE_REQ) {
            return null;
        }
        byte[] sdkVersion = "v1.2.12-202301290958-5a25c0f"
            .getBytes(StandardCharsets.US_ASCII);
        byte[] body = IccoaAuthSession.concat(
            IccoaAuthSession.fieldBytes(1, identity.carId),
            IccoaAuthSession.fieldVarint(2, 1280),
            IccoaAuthSession.fieldVarint(3, 720),
            IccoaAuthSession.fieldVarint(4, 320),
            IccoaAuthSession.fieldVarint(5, 1280),
            IccoaAuthSession.fieldVarint(6, 720),
            IccoaAuthSession.fieldVarint(7, 60),
            IccoaAuthSession.fieldVarint(8, 1),
            IccoaAuthSession.fieldVarint(11, 149),
            IccoaAuthSession.fieldVarint(12, 1),
            IccoaAuthSession.fieldVarint(13, 1),
            IccoaAuthSession.fieldBytes(16, identity.vendorData),
            IccoaAuthSession.fieldBytes(17, sdkVersion)
        );
        return IccoaAuthSession.concat(
            IccoaAuthSession.buildHeader(
                body.length,
                header.sequenceId,
                FORMAT_PB3,
                MESSAGE_RES,
                CATEGORY_CONTROL,
                METHOD_GET_CONFIG_RESPONSE,
                header.reserved
            ),
            body
        );
    }

    static byte[] buildHeartbeat(int sequenceId, long timestampMs) {
        byte[] body = IccoaAuthSession.fieldVarint(1, timestampMs);
        return IccoaAuthSession.concat(
            IccoaAuthSession.buildHeader(
                body.length,
                sequenceId,
                FORMAT_PB3,
                MESSAGE_SEND,
                CATEGORY_CONTROL,
                METHOD_HEARTBEAT,
                0
            ),
            body
        );
    }

    private static byte[] replaceBody(byte[] message, byte[] body) {
        byte[] result = new byte[HEADER_SIZE + body.length];
        System.arraycopy(message, 0, result, 0, HEADER_SIZE);
        System.arraycopy(body, 0, result, HEADER_SIZE, body.length);
        ByteBuffer header = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN);
        header.putInt(0, result.length);
        header.putShort(18, (short) IccoaAuthSession.crc16Modbus(Arrays.copyOf(result, 18)));
        return result;
    }

    private UCarControlProtocol() {
    }
}
