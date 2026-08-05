package com.opencarlink.receiver;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

final class AndroidAuthStore implements IccoaAuthSession.Store {
    private static final String PRIVATE_KEY = "auth_private_key";
    private static final String PUBLIC_KEY = "auth_public_key";
    private static final String PEER_PREFIX = "peer_";

    private final SharedPreferences preferences;

    AndroidAuthStore(Context context) {
        preferences = context.getSharedPreferences("carlink_auth", Context.MODE_PRIVATE);
    }

    @Override
    public synchronized KeyPair loadOrCreateCarAuthKey() throws GeneralSecurityException {
        String privateEncoded = preferences.getString(PRIVATE_KEY, "");
        String publicEncoded = preferences.getString(PUBLIC_KEY, "");
        if (!privateEncoded.isEmpty() && !publicEncoded.isEmpty()) {
            try {
                KeyFactory factory = KeyFactory.getInstance("EC");
                PrivateKey privateKey = factory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.decode(privateEncoded, Base64.NO_WRAP))
                );
                PublicKey publicKey = factory.generatePublic(
                    new X509EncodedKeySpec(Base64.decode(publicEncoded, Base64.NO_WRAP))
                );
                return new KeyPair(publicKey, privateKey);
            } catch (IllegalArgumentException | GeneralSecurityException ignored) {
                preferences.edit().remove(PRIVATE_KEY).remove(PUBLIC_KEY).apply();
            }
        }
        KeyPair keyPair = IccoaAuthSession.generateEcKeyPair();
        preferences.edit()
            .putString(PRIVATE_KEY, Base64.encodeToString(keyPair.getPrivate().getEncoded(), Base64.NO_WRAP))
            .putString(PUBLIC_KEY, Base64.encodeToString(keyPair.getPublic().getEncoded(), Base64.NO_WRAP))
            .apply();
        return keyPair;
    }

    @Override
    public byte[] loadPeer(byte[] deviceId) {
        String encoded = preferences.getString(peerKey(deviceId), "");
        try {
            return encoded.isEmpty() ? null : Base64.decode(encoded, Base64.NO_WRAP);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    @Override
    public void savePeer(byte[] deviceId, byte[] publicKey) {
        preferences.edit()
            .putString(peerKey(deviceId), Base64.encodeToString(publicKey, Base64.NO_WRAP))
            .apply();
    }

    private String peerKey(byte[] deviceId) {
        return PEER_PREFIX + Base64.encodeToString(
            deviceId,
            Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING
        );
    }
}
