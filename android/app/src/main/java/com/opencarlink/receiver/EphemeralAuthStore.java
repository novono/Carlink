package com.opencarlink.receiver;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.security.auth.DestroyFailedException;

/** Authentication data for one connection, never shared with a later connection. */
final class EphemeralAuthStore implements IccoaAuthSession.Store {
    private final Map<ByteBuffer, byte[]> peers = new HashMap<>();
    private KeyPair carAuthKey;
    private boolean cleared;

    @Override
    public synchronized KeyPair loadOrCreateCarAuthKey() throws GeneralSecurityException {
        if (cleared) {
            throw new GeneralSecurityException("Authentication session has ended");
        }
        if (carAuthKey == null) {
            carAuthKey = IccoaAuthSession.generateEcKeyPair();
        }
        return carAuthKey;
    }

    @Override
    public synchronized byte[] loadPeer(byte[] deviceId) {
        if (cleared) {
            return null;
        }
        byte[] value = peers.get(ByteBuffer.wrap(deviceId));
        return value == null ? null : value.clone();
    }

    @Override
    public synchronized void savePeer(byte[] deviceId, byte[] publicKey) {
        // A handshake that finishes after disconnect cannot repopulate this store.
        if (cleared) {
            return;
        }
        byte[] previous = peers.put(ByteBuffer.wrap(deviceId.clone()), publicKey.clone());
        if (previous != null) {
            Arrays.fill(previous, (byte) 0);
        }
    }

    synchronized void clear() {
        if (cleared) {
            return;
        }
        cleared = true;
        for (Map.Entry<ByteBuffer, byte[]> entry : peers.entrySet()) {
            Arrays.fill(entry.getKey().array(), (byte) 0);
            Arrays.fill(entry.getValue(), (byte) 0);
        }
        peers.clear();
        KeyPair previousKey = carAuthKey;
        carAuthKey = null;
        if (previousKey != null) {
            try {
                previousKey.getPrivate().destroy();
            } catch (DestroyFailedException ignored) {
                // Some JCA providers cannot destroy keys; discard our reference regardless.
            }
        }
    }
}
