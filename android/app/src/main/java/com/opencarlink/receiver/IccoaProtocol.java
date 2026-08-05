package com.opencarlink.receiver;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

final class IccoaProtocol {
    static final UUID ADVERTISEMENT_UUID = UUID.fromString("0000fcfb-0000-1000-8000-00805f9b34fb");
    static final UUID PRIMARY_DATA_UUID = UUID.fromString("00000001-0000-1000-8000-00805f9b34fb");
    static final UUID CAR_NAME_DATA_UUID = UUID.fromString("00000002-0000-1000-8000-00805f9b34fb");
    static final UUID SHARE_SERVICE_UUID = UUID.fromString("2abcc850-9935-4f8a-ba84-123456789100");
    static final UUID CLIENT_INFO_UUID = UUID.fromString("2abcc850-9935-4f8a-ba84-123456789101");
    static final UUID SERVER_INFO_UUID = UUID.fromString("2abcc850-9935-4f8a-ba84-123456789102");
    static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final int WIRELESS_TYPE_WIFI_DIRECT = 1001;
    static final String CAR_NAME = "Meizu CarLink";

    private static final SecureRandom RANDOM = new SecureRandom();

    static final class Identity {
        final byte[] carId;
        final byte[] modelId;
        final byte[] vendorData;

        Identity(byte[] carId, byte[] modelId, byte[] vendorData) {
            this.carId = carId;
            this.modelId = modelId;
            this.vendorData = vendorData;
        }
    }

    static Identity loadIdentity(Context context) {
        String encoded = context.getSharedPreferences("carlink", Context.MODE_PRIVATE)
            .getString("car_id", "");
        byte[] carId;
        try {
            carId = fromHex(encoded);
        } catch (IllegalArgumentException error) {
            carId = new byte[0];
        }
        if (carId.length != 6) {
            carId = new byte[6];
            RANDOM.nextBytes(carId);
            context.getSharedPreferences("carlink", Context.MODE_PRIVATE)
                .edit()
                .putString("car_id", toHex(carId))
                .apply();
        }
        return new Identity(carId, new byte[4], new byte[2]);
    }

    static byte[] primaryData(Identity identity) {
        byte[] result = new byte[15];
        result[0] = 1;
        result[1] = 2;
        result[2] = (byte) RANDOM.nextInt(256);
        System.arraycopy(identity.carId, 0, result, 3, 6);
        System.arraycopy(identity.modelId, 0, result, 9, 4);
        System.arraycopy(identity.vendorData, 0, result, 13, 2);
        return result;
    }

    static byte[] carNameData() {
        byte[] encoded = CAR_NAME.getBytes(StandardCharsets.UTF_8);
        return Arrays.copyOf(encoded, 16);
    }

    static byte[] serverInfo(String ssid, String passphrase, int frequency) {
        try {
            return new JSONObject()
                .put("name", CAR_NAME)
                .put("ssid", ssid)
                .put("psk", passphrase)
                .put("mac", "02:00:00:00:00:00")
                .put("freq", frequency)
                .put("port", 0)
                .put("type", WIRELESS_TYPE_WIFI_DIRECT)
                .toString()
                .getBytes(StandardCharsets.UTF_8);
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    static String randomSsid() {
        return String.format(Locale.US, "DIRECT-%02X-%s", RANDOM.nextInt(256), "MeizuCarLink");
    }

    static String randomPassphrase() {
        byte[] value = new byte[8];
        RANDOM.nextBytes(value);
        return "cl" + toHex(value);
    }

    static String randomPin() {
        return String.format(Locale.US, "%06d", RANDOM.nextInt(1_000_000));
    }

    static String clientLabel(byte[] payload) {
        try {
            JSONObject value = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            String name = value.optString("name");
            String model = value.optString("model");
            String label = name.isEmpty() ? model : name;
            return label.isEmpty() ? "OPPO 手机" : label;
        } catch (JSONException error) {
            return "OPPO 手机（Client Info JSON 无效）";
        }
    }

    static String clientPin(byte[] payload) {
        try {
            JSONObject value = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            return value.optString("pinCodeOrAuthentication", "").trim();
        } catch (JSONException error) {
            return "";
        }
    }

    private static String toHex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(String.format(Locale.US, "%02x", item & 0xff));
        }
        return result.toString();
    }

    private static byte[] fromHex(String value) {
        if ((value.length() & 1) != 0) {
            throw new IllegalArgumentException("odd hex length");
        }
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++) {
            int high = Character.digit(value.charAt(index * 2), 16);
            int low = Character.digit(value.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("invalid hex");
            }
            result[index] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private IccoaProtocol() {
    }
}
