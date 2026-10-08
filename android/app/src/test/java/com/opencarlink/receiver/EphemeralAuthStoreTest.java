package com.opencarlink.receiver;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class EphemeralAuthStoreTest {
    @Test
    public void authenticationDataIsReusedOnlyInsideOneConnection() throws Exception {
        EphemeralAuthStore first = new EphemeralAuthStore();
        byte[] deviceId = {1, 2, 3};
        byte[] peerKey = {4, 5, 6};
        KeyPair firstKey = first.loadOrCreateCarAuthKey();
        first.savePeer(deviceId, peerKey);

        assertSame(firstKey, first.loadOrCreateCarAuthKey());
        assertArrayEquals(peerKey, first.loadPeer(deviceId));

        EphemeralAuthStore next = new EphemeralAuthStore();
        assertNull(next.loadPeer(deviceId));
        assertFalse(Arrays.equals(
            firstKey.getPublic().getEncoded(),
            next.loadOrCreateCarAuthKey().getPublic().getEncoded()
        ));
    }

    @Test
    public void cachedPeerDataCannotBeChangedThroughCallerArrays() {
        EphemeralAuthStore store = new EphemeralAuthStore();
        byte[] deviceId = {1, 2, 3};
        byte[] peerKey = {4, 5, 6};
        store.savePeer(deviceId, peerKey);
        deviceId[0] = 9;
        peerKey[0] = 9;

        byte[] lookupId = {1, 2, 3};
        byte[] loaded = store.loadPeer(lookupId);
        assertArrayEquals(new byte[]{4, 5, 6}, loaded);
        loaded[0] = 9;
        assertArrayEquals(new byte[]{4, 5, 6}, store.loadPeer(lookupId));
    }

    @Test
    public void disconnectDiscardsKeysAndRejectsLateHandshakeWrites() throws Exception {
        EphemeralAuthStore ended = new EphemeralAuthStore();
        byte[] phoneId = {1, 2, 3};
        KeyPair oldKey = ended.loadOrCreateCarAuthKey();
        ended.savePeer(phoneId, new byte[]{4, 5, 6});
        ended.clear();
        ended.clear();

        ended.savePeer(phoneId, new byte[]{7, 8, 9});
        assertNull(ended.loadPeer(phoneId));
        assertThrows(GeneralSecurityException.class, ended::loadOrCreateCarAuthKey);

        EphemeralAuthStore next = new EphemeralAuthStore();
        assertNull(next.loadPeer(phoneId));
        assertFalse(Arrays.equals(
            oldKey.getPublic().getEncoded(),
            next.loadOrCreateCarAuthKey().getPublic().getEncoded()
        ));
    }

    @Test
    public void handshakeAlreadyInFlightCannotRestoreDisconnectedPeer() throws Exception {
        EphemeralAuthStore store = new EphemeralAuthStore();
        CountDownLatch allowSave = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> lateHandshake = worker.submit(() -> {
                try {
                    if (!allowSave.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Save was never released");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
                store.savePeer(new byte[]{1, 2, 3}, new byte[]{4, 5, 6});
            });
            store.clear();
            allowSave.countDown();
            lateHandshake.get(5, TimeUnit.SECONDS);

            assertNull(store.loadPeer(new byte[]{1, 2, 3}));
            assertThrows(GeneralSecurityException.class, store::loadOrCreateCarAuthKey);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void previouslyPairedPhoneMustAuthenticateAsNewAfterDisconnect() throws Exception {
        byte[] phoneId = {1, 2, 3};
        KeyPair phoneAuth = IccoaAuthSession.generateEcKeyPair();
        EphemeralAuthStore ended = new EphemeralAuthStore();
        ended.savePeer(phoneId, IccoaAuthSession.rawPublicKey(phoneAuth.getPublic()));
        ended.clear();

        KeyPair phoneAgreement = IccoaAuthSession.generateEcKeyPair();
        byte[] agreement = IccoaAuthSession.rawPublicKey(phoneAgreement.getPublic());
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        byte[] requestBody = IccoaAuthSession.concat(
            IccoaAuthSession.fieldVarint(1, 1),
            IccoaAuthSession.fieldBytes(4, agreement),
            IccoaAuthSession.fieldBytes(5, IccoaAuthSession.sign(
                phoneAuth.getPrivate(),
                IccoaAuthSession.concat(agreement, nonce)
            )),
            IccoaAuthSession.fieldBytes(6, nonce),
            IccoaAuthSession.fieldBytes(7, phoneId)
        );
        byte[] request = IccoaAuthSession.concat(
            IccoaAuthSession.buildHeader(requestBody.length, 1, 1, 1, 3, 1, 0),
            requestBody
        );
        IccoaAuthSession next = new IccoaAuthSession("123456", new EphemeralAuthStore());
        IccoaAuthSession.Result result = next.handle(request);

        assertNotNull(result.response);
        Map<Integer, List<Object>> fields = IccoaAuthSession.parseProtobuf(
            Arrays.copyOfRange(result.response, 20, result.response.length)
        );
        assertEquals(1L, fields.get(7).get(0));
        assertNull(next.sessionKey());
        assertFalse(next.isConfirmed());
    }
}
