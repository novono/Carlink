package com.opencarlink.receiver;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;

import org.junit.Test;

import java.util.Arrays;

public final class IccoaIdentityTest {
    @Test
    public void everyReceiverSessionGetsAnIndependentRandomIdentity() {
        IccoaProtocol.Identity first = IccoaProtocol.createIdentity();
        IccoaProtocol.Identity next = IccoaProtocol.createIdentity();

        assertEquals(6, first.carId.length);
        assertEquals(6, next.carId.length);
        assertFalse(Arrays.equals(first.carId, next.carId));
        assertNotSame(first.carId, next.carId);
        assertNotSame(first.modelId, next.modelId);
        assertNotSame(first.vendorData, next.vendorData);
    }

    @Test
    public void advertisedIdentityUsesOnlyTheCurrentSessionCarId() {
        IccoaProtocol.Identity first = IccoaProtocol.createIdentity();
        IccoaProtocol.Identity next = IccoaProtocol.createIdentity();
        byte[] firstAdvertising = IccoaProtocol.primaryData(first);
        byte[] nextAdvertising = IccoaProtocol.primaryData(next);

        assertEquals(15, nextAdvertising.length);
        assertArrayEquals(next.carId, Arrays.copyOfRange(nextAdvertising, 3, 9));
        assertFalse(Arrays.equals(
            Arrays.copyOfRange(firstAdvertising, 3, 9),
            Arrays.copyOfRange(nextAdvertising, 3, 9)
        ));
    }
}
