package com.opencarlink.receiver;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class IccoaAuthSession {
    interface Store {
        KeyPair loadOrCreateCarAuthKey() throws GeneralSecurityException;

        byte[] loadPeer(byte[] deviceId);

        void savePeer(byte[] deviceId, byte[] publicKey);
    }

    static final class Result {
        final String description;
        final byte[] response;

        Result(String description, byte[] response) {
            this.description = description;
            this.response = response;
        }
    }

    static final class StreamDecoder {
        private byte[] buffer = new byte[0];
        private boolean cleared;

        synchronized List<byte[]> feed(byte[] data, int length) {
            if (cleared) {
                throw new IllegalStateException("Authentication stream has ended");
            }
            int oldLength = buffer.length;
            byte[] previous = buffer;
            buffer = Arrays.copyOf(previous, oldLength + length);
            Arrays.fill(previous, (byte) 0);
            System.arraycopy(data, 0, buffer, oldLength, length);
            List<byte[]> messages = new ArrayList<>();
            int offset = 0;
            while (buffer.length - offset >= HEADER_SIZE) {
                int messageLength = ByteBuffer.wrap(buffer, offset, 4).getInt();
                if (messageLength < HEADER_SIZE || messageLength > MAX_MESSAGE_LENGTH) {
                    Arrays.fill(buffer, (byte) 0);
                    buffer = new byte[0];
                    throw new IllegalArgumentException("UCar 消息长度无效：" + messageLength);
                }
                if (buffer.length - offset < messageLength) {
                    break;
                }
                byte[] message = Arrays.copyOfRange(buffer, offset, offset + messageLength);
                parseHeader(message);
                messages.add(message);
                offset += messageLength;
            }
            if (offset > 0) {
                previous = buffer;
                buffer = Arrays.copyOfRange(previous, offset, previous.length);
                Arrays.fill(previous, (byte) 0);
            }
            return messages;
        }

        synchronized void clear() {
            cleared = true;
            Arrays.fill(buffer, (byte) 0);
            buffer = new byte[0];
        }
    }

    static final class Header {
        final int sequenceId;
        final int dataFormat;
        final int category;
        final int method;
        final int reserved;

        Header(int sequenceId, int dataFormat, int category, int method, int reserved) {
            this.sequenceId = sequenceId;
            this.dataFormat = dataFormat;
            this.category = category;
            this.method = method;
            this.reserved = reserved;
        }
    }

    private static final int HEADER_SIZE = 20;
    private static final int MAX_MESSAGE_LENGTH = 8 * 1024 * 1024;
    private static final int FORMAT_PB3 = 1;
    private static final int MESSAGE_RES = 2;
    private static final int CATEGORY_AUTH = 3;
    private static final int METHOD_AUTH_REQUEST = 1;
    private static final int METHOD_AUTH_RESPONSE = 2;
    private static final int METHOD_AUTH_CONFIRM = 3;
    private static final SecureRandom RANDOM = new SecureRandom();

    private byte[] pin;
    private Store store;
    private byte[] sessionKey;
    private String phoneId = "";
    private String connectionInfo = "";
    private boolean confirmed;
    private boolean cleared;

    IccoaAuthSession(String pin, Store store) {
        this.pin = pin.getBytes(StandardCharsets.UTF_8);
        this.store = store;
    }

    synchronized boolean isConfirmed() {
        return confirmed;
    }

    synchronized byte[] sessionKey() {
        return sessionKey == null ? null : sessionKey.clone();
    }

    synchronized String phoneId() {
        return phoneId;
    }

    synchronized String connectionInfo() {
        return connectionInfo;
    }

    synchronized void clear() {
        cleared = true;
        if (sessionKey != null) {
            Arrays.fill(sessionKey, (byte) 0);
            sessionKey = null;
        }
        if (pin != null) {
            Arrays.fill(pin, (byte) 0);
            pin = null;
        }
        phoneId = null;
        connectionInfo = null;
        confirmed = false;
        store = null;
    }

    synchronized Result handle(byte[] message) throws GeneralSecurityException {
        if (cleared) {
            throw new GeneralSecurityException("Authentication session has ended");
        }
        Header header = parseHeader(message);
        if (header.category != CATEGORY_AUTH || header.dataFormat != FORMAT_PB3) {
            throw new IllegalArgumentException("AUTH 通道收到非认证消息");
        }
        byte[] body = Arrays.copyOfRange(message, HEADER_SIZE, message.length);
        if (header.method == METHOD_AUTH_REQUEST) {
            return handleRequest(header, body);
        }
        if (header.method == METHOD_AUTH_CONFIRM) {
            return handleConfirm(body);
        }
        return new Result("收到未知认证方法 " + header.method, null);
    }

    private Result handleRequest(Header header, byte[] body) throws GeneralSecurityException {
        Map<Integer, List<Object>> fields = parseProtobuf(body);
        byte[] phoneAuthRaw = lastBytes(fields, 2);
        byte[] phoneAuthHmac = lastBytes(fields, 3);
        byte[] phoneAgreementRaw = lastBytes(fields, 4);
        byte[] phoneSignature = lastBytes(fields, 5);
        byte[] phoneNonce = lastBytes(fields, 6);
        byte[] deviceId = lastBytes(fields, 7);
        String model = new String(lastBytes(fields, 8), StandardCharsets.UTF_8);

        if (phoneNonce.length != 32 || deviceId.length == 0 || phoneAgreementRaw.length != 64) {
            return new Result("手机认证请求缺少必要字段", resultResponse(header.sequenceId, 3));
        }

        byte[] pinBytes = pin;
        boolean normalConnection = phoneAuthRaw.length > 0 && phoneAuthHmac.length > 0;
        if (normalConnection) {
            byte[] expected = pinHmac(pinBytes, phoneNonce, phoneAuthRaw);
            if (!MessageDigest.isEqual(expected, phoneAuthHmac)) {
                return new Result("手机认证 PIN 校验失败", resultResponse(header.sequenceId, 3));
            }
        } else {
            phoneAuthRaw = store.loadPeer(deviceId);
            if (phoneAuthRaw == null || phoneAuthRaw.length == 0) {
                return new Result("手机请求快速认证，要求转为首次认证", resultResponse(header.sequenceId, 1));
            }
        }

        PublicKey phoneAuthKey = loadRawPublicKey(phoneAuthRaw);
        PublicKey phoneAgreementKey = loadRawPublicKey(phoneAgreementRaw);
        if (!verify(phoneAuthKey, concat(phoneAgreementRaw, phoneNonce), phoneSignature)) {
            throw new GeneralSecurityException("手机认证签名校验失败");
        }

        KeyPair carAuthKey = store.loadOrCreateCarAuthKey();
        byte[] carAuthRaw = rawPublicKey(carAuthKey.getPublic());
        KeyPair carAgreementKey = generateEcKeyPair();
        byte[] carAgreementRaw = rawPublicKey(carAgreementKey.getPublic());
        byte[] carNonce = new byte[32];
        RANDOM.nextBytes(carNonce);
        byte[] negotiatedKey = deriveSessionKey(
            carAgreementKey.getPrivate(),
            phoneAgreementKey,
            phoneNonce,
            carNonce
        );
        if (sessionKey != null) {
            Arrays.fill(sessionKey, (byte) 0);
        }
        sessionKey = negotiatedKey;
        confirmed = false;
        connectionInfo = "";
        phoneId = new String(deviceId, StandardCharsets.UTF_8);

        byte[] responseAuthRaw;
        byte[] responseAuthHmac;
        if (normalConnection) {
            store.savePeer(deviceId, phoneAuthRaw);
            responseAuthRaw = carAuthRaw;
            responseAuthHmac = pinHmac(
                pinBytes,
                concat(carNonce, phoneNonce),
                concat(carAuthRaw, phoneAuthRaw)
            );
        } else {
            responseAuthRaw = new byte[0];
            responseAuthHmac = new byte[0];
        }

        byte[] agreementSignature = sign(
            carAuthKey.getPrivate(),
            concat(carAgreementRaw, phoneAgreementRaw, carNonce, phoneNonce)
        );
        byte[] responseBody = concat(
            fieldVarint(1, 1),
            fieldBytes(2, responseAuthRaw),
            fieldBytes(3, responseAuthHmac),
            fieldBytes(4, carAgreementRaw),
            fieldBytes(5, agreementSignature),
            fieldBytes(6, carNonce)
        );
        byte[] response = concat(
            buildHeader(
                responseBody.length,
                header.sequenceId,
                FORMAT_PB3,
                MESSAGE_RES,
                CATEGORY_AUTH,
                METHOD_AUTH_RESPONSE,
                header.reserved
            ),
            responseBody
        );
        String mode = normalConnection ? "首次" : "快速";
        return new Result("已完成 " + (model.isEmpty() ? "OPPO 手机" : model) + "的" + mode + "密钥协商", response);
    }

    private Result handleConfirm(byte[] body) throws GeneralSecurityException {
        if (sessionKey == null) {
            throw new IllegalArgumentException("尚未生成会话密钥却收到认证确认");
        }
        byte[] cipher = lastBytes(parseProtobuf(body), 1);
        byte[] plaintext = decryptSessionPayload(sessionKey, cipher);
        connectionInfo = new String(plaintext, StandardCharsets.UTF_8);
        confirmed = true;
        String address = connectionInfo.split(":", 2)[0];
        return new Result("手机认证成功：" + address, null);
    }

    private static byte[] resultResponse(int sequenceId, int result) {
        byte[] body = concat(fieldVarint(1, 1), fieldVarint(7, result));
        return concat(
            buildHeader(body.length, sequenceId, FORMAT_PB3, MESSAGE_RES, CATEGORY_AUTH, METHOD_AUTH_RESPONSE, 0),
            body
        );
    }

    static Header parseHeader(byte[] message) {
        if (message.length < HEADER_SIZE) {
            throw new IllegalArgumentException("UCar 消息不足 20 字节");
        }
        ByteBuffer input = ByteBuffer.wrap(message).order(ByteOrder.BIG_ENDIAN);
        int length = input.getInt();
        int sequenceId = input.getInt();
        input.getInt();
        int flags = input.get() & 0xff;
        int category = input.get() & 0xff;
        int method = input.getShort() & 0xffff;
        int reserved = input.getShort() & 0xffff;
        int expectedCrc = input.getShort() & 0xffff;
        if (length != message.length) {
            throw new IllegalArgumentException("UCar 长度字段与实际长度不符");
        }
        int actualCrc = crc16Modbus(Arrays.copyOf(message, 18));
        if (expectedCrc != actualCrc) {
            throw new IllegalArgumentException("UCar 头 CRC 错误");
        }
        return new Header(sequenceId, (flags & 0x06) >> 1, category, method, reserved);
    }

    static byte[] buildHeader(
        int bodyLength,
        int sequenceId,
        int dataFormat,
        int messageType,
        int category,
        int method,
        int reserved
    ) {
        ByteBuffer output = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        int flags = (dataFormat << 1) | (messageType << 3);
        output.putInt(HEADER_SIZE + bodyLength);
        output.putInt(sequenceId);
        output.putInt((int) (System.currentTimeMillis() / 1000L));
        output.put((byte) flags);
        output.put((byte) category);
        output.putShort((short) method);
        output.putShort((short) reserved);
        byte[] header = output.array();
        output.putShort(18, (short) crc16Modbus(Arrays.copyOf(header, 18)));
        return header;
    }

    static int crc16Modbus(byte[] data) {
        int crc = 0;
        for (byte item : data) {
            crc ^= item & 0xff;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xa001 : crc >> 1;
            }
        }
        return crc & 0xffff;
    }

    static Map<Integer, List<Object>> parseProtobuf(byte[] data) {
        Map<Integer, List<Object>> fields = new HashMap<>();
        int[] offset = {0};
        while (offset[0] < data.length) {
            long tag = readVarint(data, offset);
            int number = (int) (tag >> 3);
            int wireType = (int) (tag & 7);
            if (number == 0) {
                throw new IllegalArgumentException("protobuf 字段号不能为 0");
            }
            Object value;
            if (wireType == 0) {
                value = readVarint(data, offset);
            } else if (wireType == 1) {
                value = readFixed(data, offset, 8);
            } else if (wireType == 2) {
                long length = readVarint(data, offset);
                if (length < 0 || length > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("protobuf bytes 长度无效");
                }
                value = readFixed(data, offset, (int) length);
            } else if (wireType == 5) {
                value = readFixed(data, offset, 4);
            } else {
                throw new IllegalArgumentException("不支持的 protobuf wire type：" + wireType);
            }
            fields.computeIfAbsent(number, ignored -> new ArrayList<>()).add(value);
        }
        return fields;
    }

    private static long readVarint(byte[] data, int[] offset) {
        long value = 0;
        int shift = 0;
        while (offset[0] < data.length && shift < 70) {
            int item = data[offset[0]++] & 0xff;
            value |= (long) (item & 0x7f) << shift;
            if ((item & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IllegalArgumentException("无效 protobuf varint");
    }

    private static byte[] readFixed(byte[] data, int[] offset, int length) {
        if (length < 0 || offset[0] + length > data.length) {
            throw new IllegalArgumentException("protobuf 字段越界");
        }
        byte[] result = Arrays.copyOfRange(data, offset[0], offset[0] + length);
        offset[0] += length;
        return result;
    }

    static byte[] lastBytes(Map<Integer, List<Object>> fields, int number) {
        List<Object> values = fields.get(number);
        if (values == null || values.isEmpty()) {
            return new byte[0];
        }
        Object value = values.get(values.size() - 1);
        if (!(value instanceof byte[])) {
            throw new IllegalArgumentException("protobuf 字段类型错误：" + number);
        }
        return (byte[]) value;
    }

    static byte[] fieldVarint(int number, long value) {
        if (value == 0) {
            return new byte[0];
        }
        return concat(encodeVarint((long) number << 3), encodeVarint(value));
    }

    static byte[] fieldBytes(int number, byte[] value) {
        if (value.length == 0) {
            return new byte[0];
        }
        return concat(
            encodeVarint(((long) number << 3) | 2),
            encodeVarint(value.length),
            value
        );
    }

    private static byte[] encodeVarint(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("protobuf varint 不能为负数");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (value > 0x7f) {
            output.write((int) (value & 0x7f) | 0x80);
            value >>= 7;
        }
        output.write((int) value);
        return output.toByteArray();
    }

    static KeyPair generateEcKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        return generator.generateKeyPair();
    }

    static byte[] rawPublicKey(PublicKey key) {
        ECPublicKey ecKey = (ECPublicKey) key;
        return concat(
            fixedUnsigned(ecKey.getW().getAffineX(), 32),
            fixedUnsigned(ecKey.getW().getAffineY(), 32)
        );
    }

    static PublicKey loadRawPublicKey(byte[] data) throws GeneralSecurityException {
        if (data.length != 64) {
            throw new GeneralSecurityException("P-256 公钥长度应为 64，实际为 " + data.length);
        }
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec ecParameters = parameters.getParameterSpec(ECParameterSpec.class);
        ECPoint point = new ECPoint(
            new BigInteger(1, Arrays.copyOfRange(data, 0, 32)),
            new BigInteger(1, Arrays.copyOfRange(data, 32, 64))
        );
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, ecParameters));
    }

    private static byte[] fixedUnsigned(BigInteger value, int length) {
        byte[] encoded = value.toByteArray();
        if (encoded.length == length) {
            return encoded;
        }
        byte[] result = new byte[length];
        int sourceOffset = Math.max(0, encoded.length - length);
        int count = Math.min(encoded.length, length);
        System.arraycopy(encoded, sourceOffset, result, length - count, count);
        return result;
    }

    static byte[] sign(PrivateKey key, byte[] data) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(key, RANDOM);
        signature.update(data);
        return signature.sign();
    }

    static boolean verify(PublicKey key, byte[] data, byte[] encodedSignature)
        throws GeneralSecurityException {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initVerify(key);
        signature.update(data);
        return signature.verify(encodedSignature);
    }

    static byte[] deriveSessionKey(
        PrivateKey privateKey,
        PublicKey peerPublicKey,
        byte[] clientNonce,
        byte[] serverNonce
    ) throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(privateKey);
        agreement.doPhase(peerPublicKey, true);
        byte[] secret = agreement.generateSecret();
        byte[] salt = sha256(concat(clientNonce, serverNonce));
        return hkdfSha256(secret, salt, "session_key".getBytes(StandardCharsets.UTF_8), 16);
    }

    private static byte[] pinHmac(byte[] pin, byte[] nonceContext, byte[] data)
        throws GeneralSecurityException {
        return hmacSha256(sha256(concat(nonceContext, pin)), data);
    }

    static byte[] sha256(byte[] data) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    static byte[] hmacSha256(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] hkdfSha256(byte[] input, byte[] salt, byte[] info, int length)
        throws GeneralSecurityException {
        byte[] prk = hmacSha256(salt, input);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] previous = new byte[0];
        int counter = 1;
        while (output.size() < length) {
            previous = hmacSha256(prk, concat(previous, info, new byte[]{(byte) counter}));
            int count = Math.min(previous.length, length - output.size());
            output.write(previous, 0, count);
            counter++;
        }
        return output.toByteArray();
    }

    static byte[] encryptSessionPayload(byte[] key, byte[] payload)
        throws GeneralSecurityException {
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
            Cipher.ENCRYPT_MODE,
            new SecretKeySpec(key, "AES"),
            new GCMParameterSpec(128, iv)
        );
        byte[] encrypted = cipher.doFinal(payload);
        ByteBuffer output = ByteBuffer.allocate(8 + iv.length + encrypted.length)
            .order(ByteOrder.BIG_ENDIAN);
        output.putInt(iv.length);
        output.put(iv);
        output.putInt(encrypted.length);
        output.put(encrypted);
        return output.array();
    }

    static byte[] decryptSessionPayload(byte[] key, byte[] payload)
        throws GeneralSecurityException {
        ByteBuffer input = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (input.hasRemaining()) {
            if (input.remaining() < 4) {
                throw new IllegalArgumentException("AES-GCM IV 长度字段不完整");
            }
            int ivLength = input.getInt();
            if (ivLength <= 0 || input.remaining() < ivLength + 4) {
                throw new IllegalArgumentException("AES-GCM IV 长度无效");
            }
            byte[] iv = new byte[ivLength];
            input.get(iv);
            int encryptedLength = input.getInt();
            if (encryptedLength <= 16 || input.remaining() < encryptedLength) {
                throw new IllegalArgumentException("AES-GCM 密文长度无效");
            }
            byte[] encrypted = new byte[encryptedLength];
            input.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, iv)
            );
            byte[] plaintext = cipher.doFinal(encrypted);
            output.write(plaintext, 0, plaintext.length);
        }
        return output.toByteArray();
    }

    static byte[] concat(byte[]... values) {
        int length = 0;
        for (byte[] value : values) {
            length += value.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }
}
