package com.opencarlink.receiver;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class IccoaAuthSessionTest {
    @Test
    public void normalNegotiationAndConfirmMatchReferenceAlgorithm() throws Exception {
        String pin = "123456";
        MemoryStore store = new MemoryStore();
        IccoaAuthSession session = new IccoaAuthSession(pin, store);
        KeyPair phoneAuth = IccoaAuthSession.generateEcKeyPair();
        KeyPair phoneAgreement = IccoaAuthSession.generateEcKeyPair();
        byte[] phoneAuthRaw = IccoaAuthSession.rawPublicKey(phoneAuth.getPublic());
        byte[] phoneAgreementRaw = IccoaAuthSession.rawPublicKey(phoneAgreement.getPublic());
        byte[] phoneNonce = new byte[32];
        new SecureRandom().nextBytes(phoneNonce);
        byte[] phoneHmac = IccoaAuthSession.hmacSha256(
            IccoaAuthSession.sha256(
                IccoaAuthSession.concat(phoneNonce, pin.getBytes(StandardCharsets.UTF_8))
            ),
            phoneAuthRaw
        );
        byte[] phoneSignature = IccoaAuthSession.sign(
            phoneAuth.getPrivate(),
            IccoaAuthSession.concat(phoneAgreementRaw, phoneNonce)
        );
        byte[] requestBody = IccoaAuthSession.concat(
            IccoaAuthSession.fieldVarint(1, 1),
            IccoaAuthSession.fieldBytes(2, phoneAuthRaw),
            IccoaAuthSession.fieldBytes(3, phoneHmac),
            IccoaAuthSession.fieldBytes(4, phoneAgreementRaw),
            IccoaAuthSession.fieldBytes(5, phoneSignature),
            IccoaAuthSession.fieldBytes(6, phoneNonce),
            IccoaAuthSession.fieldBytes(7, "phone-test-id".getBytes(StandardCharsets.UTF_8)),
            IccoaAuthSession.fieldBytes(8, "PKT110".getBytes(StandardCharsets.UTF_8))
        );
        byte[] request = IccoaAuthSession.concat(
            IccoaAuthSession.buildHeader(requestBody.length, 42, 1, 1, 3, 1, 0),
            requestBody
        );

        IccoaAuthSession.StreamDecoder decoder = new IccoaAuthSession.StreamDecoder();
        assertTrue(decoder.feed(request, 11).isEmpty());
        byte[] remainder = Arrays.copyOfRange(request, 11, request.length);
        List<byte[]> decoded = decoder.feed(remainder, remainder.length);
        assertEquals(1, decoded.size());

        IccoaAuthSession.Result result = session.handle(decoded.get(0));
        assertTrue(result.description.contains("首次密钥协商"));
        assertNotNull(result.response);
        IccoaAuthSession.Header responseHeader = IccoaAuthSession.parseHeader(result.response);
        assertEquals(42, responseHeader.sequenceId);
        assertEquals(3, responseHeader.category);
        assertEquals(2, responseHeader.method);

        Map<Integer, List<Object>> fields = IccoaAuthSession.parseProtobuf(
            Arrays.copyOfRange(result.response, 20, result.response.length)
        );
        byte[] serverAuthRaw = IccoaAuthSession.lastBytes(fields, 2);
        byte[] serverAuthHmac = IccoaAuthSession.lastBytes(fields, 3);
        byte[] serverAgreementRaw = IccoaAuthSession.lastBytes(fields, 4);
        byte[] serverSignature = IccoaAuthSession.lastBytes(fields, 5);
        byte[] serverNonce = IccoaAuthSession.lastBytes(fields, 6);
        byte[] expectedHmac = IccoaAuthSession.hmacSha256(
            IccoaAuthSession.sha256(
                IccoaAuthSession.concat(
                    serverNonce,
                    phoneNonce,
                    pin.getBytes(StandardCharsets.UTF_8)
                )
            ),
            IccoaAuthSession.concat(serverAuthRaw, phoneAuthRaw)
        );
        assertArrayEquals(expectedHmac, serverAuthHmac);

        PublicKey serverAuthKey = IccoaAuthSession.loadRawPublicKey(serverAuthRaw);
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(serverAuthKey);
        verifier.update(
            IccoaAuthSession.concat(
                serverAgreementRaw,
                phoneAgreementRaw,
                serverNonce,
                phoneNonce
            )
        );
        assertTrue(verifier.verify(serverSignature));

        byte[] phoneSessionKey = IccoaAuthSession.deriveSessionKey(
            phoneAgreement.getPrivate(),
            IccoaAuthSession.loadRawPublicKey(serverAgreementRaw),
            phoneNonce,
            serverNonce
        );
        assertArrayEquals(phoneSessionKey, session.sessionKey());

        String connectionInfo = "192.168.49.2:7236";
        byte[] confirmBody = IccoaAuthSession.fieldBytes(
            1,
            IccoaAuthSession.encryptSessionPayload(
                phoneSessionKey,
                connectionInfo.getBytes(StandardCharsets.UTF_8)
            )
        );
        byte[] confirm = IccoaAuthSession.concat(
            IccoaAuthSession.buildHeader(confirmBody.length, 43, 1, 1, 3, 3, 0),
            confirmBody
        );
        IccoaAuthSession.Result confirmResult = session.handle(confirm);
        assertTrue(confirmResult.description.contains("手机认证成功"));
        assertTrue(session.isConfirmed());
        assertEquals(connectionInfo, session.connectionInfo());
    }

    private static final class MemoryStore implements IccoaAuthSession.Store {
        private final KeyPair keyPair;
        private final Map<String, byte[]> peers = new HashMap<>();

        MemoryStore() throws Exception {
            keyPair = IccoaAuthSession.generateEcKeyPair();
        }

        @Override
        public KeyPair loadOrCreateCarAuthKey() {
            return keyPair;
        }

        @Override
        public byte[] loadPeer(byte[] deviceId) {
            return peers.get(new String(deviceId, StandardCharsets.UTF_8));
        }

        @Override
        public void savePeer(byte[] deviceId, byte[] publicKey) {
            peers.put(new String(deviceId, StandardCharsets.UTF_8), publicKey.clone());
        }
    }
}
