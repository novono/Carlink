package com.opencarlink.receiver;

import android.content.Context;

import java.security.GeneralSecurityException;
import java.security.KeyPair;

final class AndroidAuthStore implements IccoaAuthSession.Store {
    private final EphemeralAuthStore session = new EphemeralAuthStore();

    AndroidAuthStore(Context context) {
        // Remove keys and paired phones saved by older builds before starting a session.
        context.getSharedPreferences("carlink_auth", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit();
    }

    @Override
    public KeyPair loadOrCreateCarAuthKey() throws GeneralSecurityException {
        return session.loadOrCreateCarAuthKey();
    }

    @Override
    public byte[] loadPeer(byte[] deviceId) {
        return session.loadPeer(deviceId);
    }

    @Override
    public void savePeer(byte[] deviceId, byte[] publicKey) {
        session.savePeer(deviceId, publicKey);
    }

    void clear() {
        session.clear();
    }
}
